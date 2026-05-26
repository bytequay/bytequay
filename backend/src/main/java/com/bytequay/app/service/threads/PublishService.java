/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.web.PatResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Resolves an AWAITING_REVIEW notification: either approve (run the
 * deferred push / createIssueComment, flip the task to COMPLETED, and
 * write an audit row) or discard (skip the side effect, dismiss the
 * row, audit the discard). Side effects only fire here — McpController
 * just parks; this is where the publish-gate contract actually lands.
 *
 * <p>Audit rows are AUTO_FIX_DONE notifications carrying the
 * resolution (approved / discarded / failed), the action, the parked
 * notification's id, and a human-readable summary. Failures don't
 * mutate the parked row, so the user can retry from the same
 * AWAITING_REVIEW entry.
 */
@Service
public class PublishService
{
    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    private final NotificationService notifications;
    private final TaskStore taskStore;
    private final GitRunner git;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;
    private final ObjectMapper mapper;

    public PublishService(
            NotificationService notifications,
            TaskStore taskStore,
            GitRunner git,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            ObjectMapper mapper)
    {
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Run the parked publish action. {@code editedBody} only applies
     * to {@code post_comment} — push has no editable surface, so any
     * editedBody value is ignored for that action. Returns the
     * resolution shape the controller hands back to the frontend.
     */
    public PublishResult approve(String notificationId, String editedBody)
    {
        Notification original = requireParked(notificationId);
        JsonNode payload = parsePayload(original);
        String action = payload.path("action").asText("");

        PublishResult result;
        try {
            result = switch (action) {
                case "push" -> doPush(payload);
                case "post_comment" -> doPostComment(payload, editedBody);
                default -> throw new ResponseStatusException(
                        HttpStatusCode.valueOf(400), "unsupported action: " + action);
            };
        }
        catch (ResponseStatusException e) {
            // Bad request shape (no worktreePath, no PR ref, etc.) —
            // surface as 4xx without writing an audit row, the parked
            // notification is the audit trail for "user tried but it
            // wasn't actionable".
            throw e;
        }
        catch (RuntimeException e) {
            // The side effect blew up (network, GitHub API, git failed
            // remote). Don't dismiss the parked row — the user might
            // retry — but do log the failure so the user can see why.
            log.warn("publish approve {} ({}) failed: {}",
                    notificationId, action, e.getMessage());
            writeAuditRow(original, "failed", action,
                    "publish failed: " + e.getMessage());
            return new PublishResult(false, "failed",
                    "publish failed: " + e.getMessage(), action);
        }

        completeTaskIfStillParked(original.taskId());
        notifications.dismiss(notificationId);
        writeAuditRow(original, "approved", action, result.message());
        return result;
    }

    /**
     * Drop the parked publish without running its side effect. The
     * task still transitions to COMPLETED — discard means "this work
     * is finished, just not shipped" rather than "go back to RUNNING".
     */
    public PublishResult discard(String notificationId)
    {
        Notification original = requireParked(notificationId);
        JsonNode payload = parsePayload(original);
        String action = payload.path("action").asText("");

        completeTaskIfStillParked(original.taskId());
        notifications.dismiss(notificationId);
        writeAuditRow(original, "discarded", action,
                "user discarded the proposed " + action);
        return new PublishResult(true, "discarded",
                "Discarded.", action);
    }

    private Notification requireParked(String notificationId)
    {
        Notification original = notifications.find(notificationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "no notification: " + notificationId));
        if (original.kind() != NotificationKind.AWAITING_REVIEW) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "only AWAITING_REVIEW notifications can be approved or discarded");
        }
        return original;
    }

    private JsonNode parsePayload(Notification original)
    {
        try {
            String json = original.payloadJson() == null ? "{}" : original.payloadJson();
            return mapper.readTree(json);
        }
        catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "notification payload is not valid JSON: " + e.getMessage());
        }
    }

    private PublishResult doPush(JsonNode payload)
    {
        String worktreePath = payload.path("worktreePath").asText("");
        if (worktreePath.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked push notification has no worktreePath");
        }
        Path worktree = Path.of(worktreePath);
        String branch = payload.path("branch").asText("the branch");
        try {
            git.push(worktree);
        }
        catch (IOException e) {
            throw new RuntimeException("git push failed: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git push interrupted", e);
        }
        return new PublishResult(true, "approved",
                "Pushed " + branch + " from " + worktree + ".", "push");
    }

    private PublishResult doPostComment(JsonNode payload, String editedBody)
    {
        JsonNode pr = payload.path("pr");
        if (pr.isMissingNode() || pr.isNull()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked post_comment notification has no pr ref");
        }
        String owner = pr.path("owner").asText("");
        String repo = pr.path("repo").asText("");
        int number = pr.path("number").asInt(0);
        if (owner.isBlank() || repo.isBlank() || number <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked post_comment notification has incomplete pr ref");
        }
        String parkedBody = payload.path("body").asText("");
        String effectiveBody = (editedBody == null || editedBody.isBlank())
                ? parkedBody
                : editedBody;
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "comment body is blank — nothing to post");
        }
        PullRequestRef ref = new PullRequestRef(owner, repo, number);
        String pat = patResolver.resolve(owner + "/" + repo);
        pullRequests.createIssueComment(pat, ref, effectiveBody);
        return new PublishResult(true, "approved",
                "Posted comment on " + owner + "/" + repo + "#" + number + ".",
                "post_comment");
    }

    /** Move the task off AWAITING_REVIEW to COMPLETED. No-op when the
     *  task is missing (notification with no taskId, or a stale row)
     *  or has already moved off the parked state — the second
     *  approve/discard against the same task is idempotent. */
    private void completeTaskIfStillParked(String taskId)
    {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskStore.findTaskById(taskId).ifPresent(t -> {
            if (t.status() != TaskStatus.AWAITING_REVIEW) {
                return;
            }
            taskStore.saveTask(new Task(
                    t.id(), t.threadId(), t.seq(), TaskStatus.COMPLETED,
                    t.branchName(), t.worktreePath(), t.baseBranch(), t.workingDir(),
                    t.processPid(), t.logPath(),
                    t.prNumber(), t.prState(), t.ciState(),
                    t.taskType(), t.linkedPrNumber(), t.linkedIssueNumber(),
                    t.costUsdMilli(), t.tokensIn(), t.tokensOut(),
                    t.agentSessionId(),
                    t.createdAt(), t.endedAt(), t.errorMessage(),
                    t.name(), t.roleSkill()));
        });
    }

    private void writeAuditRow(
            Notification original, String resolution, String action, String message)
    {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("publishResolution", resolution);
            payload.put("action", action);
            payload.put("originalNotificationId", original.id());
            payload.put("message", message);
            String json = mapper.writeValueAsString(payload);
            notifications.notifyAutoFixDone(original.threadId(), original.taskId(), json);
        }
        catch (JsonProcessingException | RuntimeException e) {
            // Audit failures shouldn't surface to the user — the
            // resolution already happened, and we'd rather lose the
            // audit row than the side effect's success acknowledgement.
            log.warn("audit row write failed for notification {} (resolution={}): {}",
                    original.id(), resolution, e.getMessage());
        }
    }

    /**
     * Resolution shape the controller hands back to the frontend.
     * {@code ok} is true on success (approved or discarded), false on
     * a side-effect failure. {@code resolution} is one of "approved",
     * "discarded", "failed" — the frontend dispatches on it for the
     * toast / inline copy.
     */
    public record PublishResult(boolean ok, String resolution, String message, String action) {}
}
