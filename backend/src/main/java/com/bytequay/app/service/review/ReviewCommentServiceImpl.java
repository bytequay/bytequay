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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

@Service
public class ReviewCommentServiceImpl
        implements ReviewCommentService
{
    private final StageStore stageStore;
    private final ReviewRoundService reviewRounds;
    private final PRService prService;
    private final TaskStore taskStore;

    public ReviewCommentServiceImpl(
            StageStore stageStore,
            ReviewRoundService reviewRounds,
            PRService prService,
            TaskStore taskStore)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    @Override
    public PRComment add(
            String taskId, String file, int line, String side, Integer startLine, String startSide, String body)
    {
        String taskIdValue = nullToEmpty(taskId).strip();
        String fileValue = nullToEmpty(file).strip();
        String bodyValue = nullToEmpty(body).strip();
        if (taskIdValue.isEmpty()) {
            throw status(400, "taskId is required");
        }
        if (fileValue.isEmpty()) {
            throw status(400, "file is required");
        }
        if (line <= 0) {
            throw status(400, "line must be positive");
        }
        if (bodyValue.isEmpty()) {
            throw status(400, "body is required");
        }
        PR pr = localPrForTask(taskIdValue);
        return prService.addComment(
                pr.id(),
                PRComment.ORIGIN_LOCAL,
                PRComment.SCOPE_FILE_LINE,
                fileValue,
                line,
                side,
                startLine,
                startSide,
                PRTimelineEntry.ACTOR_USER,
                bodyValue,
                /* parentCommentId */ null);
    }

    @Override
    public List<PRComment> list(String taskId)
    {
        return prService.findByTask(nullToEmpty(taskId).strip())
                .map(pr -> prService.comments(pr.id()).stream()
                        .filter(c -> PRComment.SCOPE_FILE_LINE.equals(c.scope()))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public void resolve(UUID commentId)
    {
        requireNonNull(commentId, "commentId is null");
        if (resolveLegacyReviewComment(commentId, true)) {
            return;
        }
        try {
            prService.resolveComment(commentId.toString());
        }
        catch (IllegalArgumentException ignored) {
            // Preserve the legacy review_comment behaviour: resolving an
            // unknown id is a no-op, so stale UI/tool calls do not fail a turn.
        }
    }

    @Override
    public void reopen(UUID commentId)
    {
        requireNonNull(commentId, "commentId is null");
        if (resolveLegacyReviewComment(commentId, false)) {
            return;
        }
        try {
            prService.reopenComment(commentId.toString());
        }
        catch (IllegalArgumentException ignored) {
            // See resolve(UUID).
        }
    }

    /** A resolve/reopen on a legacy round-attached comment (the remote-reviewer
     *  batch a review_round is addressing) moves it between the round's
     *  open/fixed/replied buckets — keep the round's stored stats in step. */
    private boolean resolveLegacyReviewComment(UUID commentId, boolean resolved)
    {
        Optional<ReviewComment> existing = stageStore.findReviewCommentById(commentId);
        if (existing.isEmpty()) {
            return false;
        }
        stageStore.setReviewCommentResolved(commentId, resolved);
        Optional.ofNullable(existing.get().roundId())
                .ifPresent(roundId -> reviewRounds.recomputeStats(roundId.toString()));
        return true;
    }

    @Override
    public SubmitResult submitReview(String taskId, String body, String verdict, List<String> commentIds)
    {
        String taskIdValue = nullToEmpty(taskId).strip();
        if (taskIdValue.isEmpty()) {
            throw status(400, "taskId is required");
        }
        String bodyValue = nullToEmpty(body).strip();
        return TaskPhaseMachine.withTaskLock(taskIdValue,
                () -> submitReviewLocked(taskIdValue, bodyValue, verdict, commentIds));
    }

    private SubmitResult submitReviewLocked(
            String taskId, String body, String verdict, List<String> commentIds)
    {
        PR pr = prService.findByTask(taskId)
                .orElseThrow(() -> status(409, "task " + taskId + " has no pull request to review"));
        Task task = taskStore.findTaskById(taskId).orElse(null);
        boolean acceptingLocalReview = task != null
                && task.status() != TaskStatus.NEEDS_ATTENTION
                && (task.phase() == TaskPhase.AWAITING_PUSH
                        || task.phase() == TaskPhase.ADDRESSING_LOCAL_COMMENTS
                        || task.phase() == TaskPhase.INTERNAL_REVIEW);
        if (!acceptingLocalReview) {
            throw status(409, "task " + taskId + " is not accepting Local Review submissions");
        }
        if (!PR.ORIGIN_TASK.equals(pr.origin()) || !PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            throw status(409, "task " + taskId + " is not in Local Review");
        }
        Map<String, PRComment> eligible = prService.comments(pr.id()).stream()
                .filter(ReviewCommentServiceImpl::isOpenSubmittedRoot)
                .collect(LinkedHashMap::new, (comments, comment) -> comments.put(comment.id(), comment), Map::putAll);
        Map<String, PRComment> unresolved = eligible.values().stream()
                .filter(comment -> PRTimelineEntry.ACTOR_USER.equals(comment.author()))
                .collect(LinkedHashMap::new, (comments, comment) -> comments.put(comment.id(), comment), Map::putAll);
        Set<String> submitted = prService.localReviewSubmissions(pr.id()).stream()
                .flatMap(review -> review.commentIds().stream())
                .collect(Collectors.toSet());
        List<String> selected;
        if (commentIds == null) {
            selected = unresolved.keySet().stream()
                    .filter(id -> !submitted.contains(id))
                    .toList();
        }
        else {
            LinkedHashSet<String> requested = new LinkedHashSet<>();
            for (String commentId : commentIds) {
                String id = nullToEmpty(commentId).strip();
                if (!id.isEmpty()) {
                    requested.add(id);
                }
            }
            requested.stream()
                    .filter(id -> !eligible.containsKey(id))
                    .findFirst()
                    .ifPresent(id -> {
                        throw status(409, "comment " + id + " is not an open actionable root on this review");
                    });
            // An explicit Send is also how a reopened/already-submitted root
            // gets a fresh dispatch timestamp without another DB state bit.
            selected = List.copyOf(requested);
        }
        String event = switch (nullToEmpty(verdict).strip()) {
            case "APPROVE" -> "APPROVE";
            case "REQUEST_CHANGES" -> "REQUEST_CHANGES";
            default -> "COMMENT";
        };
        if (selected.isEmpty() && body.isEmpty() && !"APPROVE".equals(event)) {
            return new SubmitResult(0, null);
        }
        selected = new ArrayList<>(selected);
        String bodyCommentId = null;
        if (!body.isEmpty()) {
            PRComment summary = prService.addComment(
                    pr.id(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                    null, null, null, null, null,
                    PRTimelineEntry.ACTOR_USER, body, null);
            selected.add(summary.id());
            bodyCommentId = summary.id();
        }
        prService.recordLocalReviewSubmission(pr.id(), selected, body, event, bodyCommentId);
        return new SubmitResult(selected.size(), null);
    }

    private PR localPrForTask(String taskId)
    {
        Optional<PR> existing = prService.findByTask(taskId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> status(404, "no task " + taskId));
        if (task.branchName() == null || task.branchName().isBlank()) {
            throw status(422, "task " + taskId + " has no branch yet");
        }
        String base = task.baseBranch() == null || task.baseBranch().isBlank() ? "main" : task.baseBranch();
        String title = task.name() == null || task.name().isBlank() ? task.branchName() : task.name();
        return prService.createForTask(task.id(), task.branchName(), base, title, "");
    }

    private static boolean isOpenSubmittedRoot(PRComment comment)
    {
        return PRComment.ORIGIN_LOCAL.equals(comment.origin())
                && (PRTimelineEntry.ACTOR_USER.equals(comment.author())
                        || "agent".equals(comment.author()) && comment.findingId() != null)
                && comment.parentCommentId() == null
                && comment.resolvedAt() == null
                && comment.dismissedAt() == null;
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
