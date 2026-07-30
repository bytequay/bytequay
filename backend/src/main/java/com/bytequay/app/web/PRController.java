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
package com.bytequay.app.web;

import com.bytequay.app.beans.localpr.AddPRCommentRequest;
import com.bytequay.app.beans.localpr.CreatePRRequest;
import com.bytequay.app.beans.localpr.MarkHandledRequest;
import com.bytequay.app.beans.localpr.MergePRRequest;
import com.bytequay.app.beans.localpr.PRBundleDto;
import com.bytequay.app.beans.localpr.PRCheckDto;
import com.bytequay.app.beans.localpr.PRCommentDto;
import com.bytequay.app.beans.localpr.PRCommitDto;
import com.bytequay.app.beans.localpr.PRDashboardEntryDto;
import com.bytequay.app.beans.localpr.PRDto;
import com.bytequay.app.beans.localpr.PRTimelineEntryDto;
import com.bytequay.app.beans.localpr.SnoozePRRequest;
import com.bytequay.app.beans.localpr.UpdatePRRequest;
import com.bytequay.app.developmentflow.execution.remote.SqliteExternalPrActionStore.Projection;
import com.bytequay.app.developmentflow.stage.ManualPrValidationRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.Operation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.Status;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PRSyncService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the local PR — the PR artifact that lives in ByteQuay
 * before it reaches GitHub. Reads and the create / update / comment mutations
 * are here; the user-gated {@code push} and {@code merge} transitions land in
 * a later phase (they perform GitHub I/O). Agents mutate the local PR through
 * the {@code record_pr_*} MCP tools inside a scheduler-dispatched turn, not
 * through this controller.
 */
@RestController
public class PRController
{
    private static final String USER_AUTHOR = PRTimelineEntry.ACTOR_USER;
    private final PRService prService;
    private final PRPublishService publish;
    private final PRSyncService sync;
    private final TaskStore taskStore;
    private final ObjectMapper mapper;
    private final ManualPrValidationRuntime manualValidation;
    private final InvestigationReviewService investigationReviews;

    public PRController(
            PRService prService,
            PRPublishService publish,
            PRSyncService sync,
            TaskStore taskStore,
            ObjectMapper mapper,
            ManualPrValidationRuntime manualValidation,
            PullRequestService pullRequests,
            InvestigationReviewService investigationReviews)
    {
        this.prService = requireNonNull(prService, "prService is null");
        this.publish = requireNonNull(publish, "publish is null");
        this.sync = requireNonNull(sync, "sync is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        requireNonNull(pullRequests, "pullRequests is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.manualValidation = requireNonNull(
                manualValidation, "manualValidation is null");
        this.investigationReviews = requireNonNull(investigationReviews, "investigationReviews is null");
    }

    /** Pure projection of the PR already owned by the Task runtime. */
    @GetMapping("/api/tasks/{taskId}/pr")
    public PRDto getForTask(@PathVariable String taskId)
    {
        return PRDto.from(prService.findByTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR for task " + taskId)));
    }

    @PostMapping("/api/tasks/{taskId}/pr")
    public PRDto create(@PathVariable String taskId, @RequestBody(required = false) CreatePRRequest body)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no task " + taskId));
        rejectTaskOwnedCompatibilityMutation(task.id(), "PR creation");
        throw new IllegalStateException("unreachable Task-owned PR creation");
    }

    @GetMapping("/api/prs/{prId}")
    public PRDto getById(@PathVariable String prId)
    {
        return PRDto.from(prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId)));
    }

    /** Resolver for an external PR — the dashboard/details-page entry point
     *  for a GitHub PR that isn't tied to a ByteQuay task, and what a PR pane
     *  blocks on before it can mount. Creates the row (origin=external) on
     *  first sight; after that it answers from the store and refreshes in the
     *  background, since the caller is only here for the id. */
    @GetMapping("/api/repos/{owner}/{repo}/prs/{number}")
    public PRDto getExternalPr(@PathVariable String owner, @PathVariable String repo, @PathVariable int number)
    {
        String slug = owner + "/" + repo;
        return PRDto.from(sync.resolveExternalPR(slug, number)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + slug + "#" + number)));
    }

    /** The dashboard list — every PR the last {@code syncList} pass watched
     *  (authored or review-requested), flattened with its triage state. */
    @GetMapping("/api/prs")
    public List<PRDashboardEntryDto> dashboard()
    {
        return dashboardEntries();
    }

    /** Explicit user-triggered dashboard refresh (the list's manual-refresh
     *  button) — always sweeps GitHub, unlike the scheduled tick. */
    @PostMapping("/api/prs/sync-list")
    public List<PRDashboardEntryDto> syncDashboard()
    {
        sync.syncList();
        return dashboardEntries();
    }

    private List<PRDashboardEntryDto> dashboardEntries()
    {
        Map<String, String> reviewStates = investigationReviews.dashboardStates();
        return prService.dashboardEntries().stream()
                .map(entry -> PRDashboardEntryDto.from(
                        entry, reviewStates.getOrDefault(entry.pr().id(), "none")))
                .toList();
    }

    /** Records that the user opened this PR in the app. Idempotent. */
    @PostMapping("/api/prs/{prId}/viewed")
    public void markViewed(@PathVariable String prId)
    {
        prService.markViewed(prId);
    }

    /** Marks a PR handled with the given action, without any GitHub call —
     *  the dashboard's hover "Handled" affordance. */
    @PostMapping("/api/prs/{prId}/handle")
    public void markHandled(@PathVariable String prId, @RequestBody MarkHandledRequest body)
    {
        prService.markHandled(prId, HandledAction.valueOf(body.action()));
    }

    /** Clears the local reviewed timestamp so the PR returns to the Inbox. */
    @PostMapping("/api/prs/{prId}/reopen")
    public void reopen(@PathVariable String prId)
    {
        prService.reopen(prId);
    }

    /** Parks a PR until the given instant. */
    @PostMapping("/api/prs/{prId}/snooze")
    public void snooze(@PathVariable String prId, @RequestBody SnoozePRRequest body)
    {
        Instant until = Instant.parse(body.until());
        if (!until.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snooze target must be in the future.");
        }
        prService.snooze(prId, until);
    }

    /** User-initiated wake — no wake-reason banner. */
    @PostMapping("/api/prs/{prId}/unsnooze")
    public void unsnooze(@PathVariable String prId)
    {
        prService.unsnooze(prId);
    }

    /** Drops the wake-reason flag once the user has seen the "PR woke up" banner. */
    @PostMapping("/api/prs/{prId}/clear-snooze-wake-reason")
    public void clearSnoozeWakeReason(@PathVariable String prId)
    {
        prService.clearSnoozeWakeReason(prId);
    }

    /** Submits a GitHub approval review through the durable V2 action
     *  protocol for Task-owned PRs. Standalone PRs keep the direct path. */
    @PostMapping("/api/prs/{prId}/approve")
    public void approve(
            @PathVariable String prId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId));
        if (pr.taskId() != null) {
            String workflowVersion = taskStore.findWorkflowVersion(pr.taskId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Task " + pr.taskId() + " has no immutable workflow route"));
            if ("V2".equals(workflowVersion)) {
                if (commandId == null || commandId.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Idempotency-Key is required for a V2 remote action");
                }
                publish.publishReview(
                        commandId, prId, "APPROVE", List.of(), List.of(), "");
                return;
            }
            if (!"LEGACY".equals(workflowVersion)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "unsupported Task workflow version " + workflowVersion);
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "LEGACY Task-owned PR approval is read-only; use a V2 Task");
        }
        publish.publishReview(
                requireCommandId(commandId), prId, "APPROVE", List.of(),
                List.of(), "");
    }

    /** The whole stored PR projection in one payload. Task-owned reads never
     *  perform Git or GitHub I/O or advance lifecycle. A standalone dashboard
     *  PR still refreshes, but off the request thread — refreshing it inline
     *  put seconds of GitHub and git latency on every PR-pane paint, for data
     *  the store already holds in milliseconds. {@code syncing} tells the
     *  caller that refresh is still running so it can poll for the result. */
    @GetMapping("/api/prs/{prId}/bundle")
    public PRBundleDto bundle(@PathVariable String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId));
        if (pr.taskId() == null) {
            sync.syncInBackground(prId);
        }
        return new PRBundleDto(
                PRDto.from(pr),
                prService.commits(pr.id()).stream().map(PRCommitDto::from).toList(),
                prService.timeline(pr.id()).stream().map(e -> PRTimelineEntryDto.from(e, mapper)).toList(),
                prService.checks(pr.id()).stream().map(PRCheckDto::from).toList(),
                prService.comments(pr.id()).stream().map(PRCommentDto::from).toList(),
                prService.pendingStripCount(pr.id()),
                sync.isSyncing(pr.id()));
    }

    /** Explicit user-triggered refresh — always probes GitHub (no maxAge
     *  short-circuit), unlike the passive sync a PR-bundle fetch performs. */
    @PostMapping("/api/prs/{prId}/sync")
    public PRDto syncPr(@PathVariable String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no PR " + prId));
        if (pr.taskId() != null) {
            rejectTaskOwnedCompatibilityMutation(
                    pr.taskId(), "direct PR synchronization");
        }
        return PRDto.from(sync.syncPR(prId, 0)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId)));
    }

    /**
     * On-demand local test run. The HTTP call waits for the UI contract, but
     * the process itself is a durable, capacity-admitted V2 Validation
     * Operation; the servlet thread never launches repository work directly.
     */
    @PostMapping("/api/prs/{prId}/run-tests")
    public ResponseEntity<List<PRCheckDto>> runTests(
            @PathVariable String prId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId)
    {
        if (commandId == null || commandId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required for Manual PR validation");
        }
        Operation operation;
        try {
            operation = manualValidation.runAndWait(commandId, prId);
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
        if (!operation.terminal()) {
            return ResponseEntity.accepted()
                    .header("Retry-After", "1")
                    .build();
        }
        if (operation.status() != Status.COMPLETED) {
            HttpStatus status = operation.status() == Status.FAILED
                    ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.CONFLICT;
            String message = operation.error() == null
                    ? "Manual PR validation "
                            + operation.status().name().toLowerCase(Locale.ROOT)
                    : operation.error();
            throw new ResponseStatusException(status, message);
        }
        return ResponseEntity.ok(
                prService.checks(prId).stream().map(PRCheckDto::from).toList());
    }

    /**
     * User-gated push: pushes the branch, opens a Draft PR on GitHub, strips
     * the private local record, and flips the PR to {@code remote-drafted}.
     * This is not an agent path — only the user's Approve &amp; push triggers it.
     */
    @PostMapping("/api/prs/{prId}/push")
    public PRDto push(
            @PathVariable String prId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId)
    {
        // User-gated Approve & ship: the human is the final authority, so this
        // path may override the review-quality gate (open comments, failing
        // local checks). Auto-merge still keeps the gate via push(prId).
        return PRDto.from(publish.push(commandId, prId, true));
    }

    /** User-gated merge of a pushed task-origin PR, then flip it to {@code merged}. */
    @PostMapping("/api/prs/{prId}/merge")
    public PRDto merge(
            @PathVariable String prId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId,
            @RequestBody(required = false) MergePRRequest body)
    {
        return PRDto.from(publish.merge(
                commandId, prId, body == null ? null : body.method()));
    }

    /** User-gated removal of a pushed PR from its repo's merge queue. */
    @DeleteMapping("/api/prs/{prId}/merge-queue")
    public PRDto dequeue(
            @PathVariable String prId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId)
    {
        return PRDto.from(publish.dequeue(commandId, prId));
    }

    /** User-gated deletion of a merged PR's head branch on GitHub. */
    @DeleteMapping("/api/prs/{prId}/branch")
    public PRDto deleteBranch(
            @PathVariable String prId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId)
    {
        return PRDto.from(publish.deleteBranch(commandId, prId));
    }

    public record PostRemoteCommentRequest(String body) {}

    /** Explicitly posts a top-level comment to GitHub, including after the
     * PR has merged, then refreshes the conversation timeline. */
    @PostMapping("/api/prs/{prId}/remote-comments")
    public PRDto postRemoteComment(
            @PathVariable String prId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId,
            @RequestBody PostRemoteCommentRequest body)
    {
        PR published = publish.postComment(commandId, prId, body.body());
        if (isV2TaskPr(published)) {
            return PRDto.from(published);
        }
        return PRDto.from(sync.syncPR(prId, 0)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId)));
    }

    /** Batch every draft comment on a remote PR into one GitHub review, then
     *  re-sync so the review appears on the timeline. Both external PRs and
     *  task PRs that have reached the remote stage publish here. */
    @PostMapping("/api/prs/{prId}/publish-review")
    public Object publishReview(
            @PathVariable String prId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId,
            @RequestBody(required = false) PublishReviewRequest body)
    {
        String verdict = body == null ? "COMMENT" : body.verdict();
        List<String> findingIds = body == null ? null : body.findingIds();
        List<String> commentIds = body == null ? null : body.comments();
        String reviewBody = body == null ? null : body.body();
        PR published = publish.publishReview(
                commandId, prId, verdict, findingIds, commentIds, reviewBody);
        if (isV2TaskPr(published)) {
            return PRDto.from(published);
        }
        return publish.findExternalReviewPublication(prId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "external review publication was not authorized"));
    }

    @GetMapping("/api/prs/{prId}/review-publication")
    public Projection reviewPublication(@PathVariable String prId)
    {
        return publish.findExternalReviewPublication(prId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "no external review publication for PR " + prId));
    }

    public record PublishReviewRequest(
            String verdict, List<String> findingIds, List<String> comments, String body) {}

    private static String requireCommandId(String commandId)
    {
        if (commandId == null || commandId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required for a V2 remote action");
        }
        return commandId;
    }

    private boolean isV2TaskPr(PR pr)
    {
        return pr.taskId() != null
                && "V2".equals(taskStore.findWorkflowVersion(
                        pr.taskId()).orElse(null));
    }

    private void rejectTaskOwnedCompatibilityMutation(
            String taskId, String action)
    {
        String workflow = taskStore.findWorkflowVersion(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Task " + taskId + " has no immutable workflow route"));
        if ("V2".equals(workflow)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "V2 Task " + taskId + " " + action
                            + " is owned by its exact Local/Remote Development command");
        }
        if ("LEGACY".equals(workflow)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Historical LEGACY Task " + taskId
                            + " is read-only; " + action + " is retired");
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "unsupported Task workflow version " + workflow);
    }

    @PatchMapping("/api/prs/{prId}")
    public PRDto update(@PathVariable String prId, @RequestBody(required = false) UpdatePRRequest body)
    {
        String title = body == null ? null : body.title();
        String description = body == null ? null : body.description();
        return PRDto.from(prService.updateDetails(prId, title, description));
    }

    @GetMapping("/api/prs/{prId}/timeline")
    public List<PRTimelineEntryDto> timeline(@PathVariable String prId)
    {
        return prService.timeline(prId).stream().map(e -> PRTimelineEntryDto.from(e, mapper)).toList();
    }

    @GetMapping("/api/prs/{prId}/commits")
    public List<PRCommitDto> commits(@PathVariable String prId)
    {
        return prService.commits(prId).stream().map(PRCommitDto::from).toList();
    }

    @GetMapping("/api/prs/{prId}/checks")
    public List<PRCheckDto> checks(@PathVariable String prId)
    {
        return prService.checks(prId).stream().map(PRCheckDto::from).toList();
    }

    @GetMapping("/api/prs/{prId}/comments")
    public List<PRCommentDto> comments(@PathVariable String prId)
    {
        return prService.comments(prId).stream().map(PRCommentDto::from).toList();
    }

    @PostMapping("/api/prs/{prId}/comments")
    public PRCommentDto addComment(@PathVariable String prId, @RequestBody AddPRCommentRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        // A comment posted through this controller is the user's own — always
        // local origin; the "remote" origin is populated only by the review
        // ingestor, never by a user request.
        return PRCommentDto.from(prService.addComment(
                prId,
                PRComment.ORIGIN_LOCAL,
                body.scope(),
                body.filePath(),
                body.lineNumber(),
                body.side(),
                body.startLine(),
                body.startSide(),
                USER_AUTHOR,
                body.body(),
                body.parentCommentId()));
    }

    @PatchMapping("/api/prs/comments/{commentId}")
    public PRCommentDto resolveComment(@PathVariable String commentId)
    {
        return PRCommentDto.from(prService.resolveComment(commentId));
    }

    @DeleteMapping("/api/prs/comments/{commentId}")
    public void deleteComment(@PathVariable String commentId)
    {
        prService.deleteDraftComment(commentId);
    }

    @PatchMapping("/api/prs/comments/{commentId}/dismiss")
    public PRCommentDto dismissComment(@PathVariable String commentId)
    {
        return PRCommentDto.from(prService.dismissComment(commentId));
    }

    @PatchMapping("/api/prs/comments/{commentId}/reopen")
    public PRCommentDto reopenComment(@PathVariable String commentId)
    {
        return PRCommentDto.from(prService.reopenComment(commentId));
    }
}
