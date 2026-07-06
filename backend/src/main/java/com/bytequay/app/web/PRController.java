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
import com.bytequay.app.beans.localpr.MergePRRequest;
import com.bytequay.app.beans.localpr.PRBundleDto;
import com.bytequay.app.beans.localpr.PRCheckDto;
import com.bytequay.app.beans.localpr.PRCommentDto;
import com.bytequay.app.beans.localpr.PRCommitDto;
import com.bytequay.app.beans.localpr.PRDto;
import com.bytequay.app.beans.localpr.PRTimelineEntryDto;
import com.bytequay.app.beans.localpr.UpdatePRRequest;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.checks.RepoTestValidationCheck;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PRSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

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
    private static final String DEFAULT_BASE_BRANCH = "main";

    private final PRService prService;
    private final PRPublishService publish;
    private final PRSyncService sync;
    private final TaskStore taskStore;
    private final ObjectMapper mapper;
    private final RepoTestValidationCheck testRunner;

    public PRController(
            PRService prService,
            PRPublishService publish,
            PRSyncService sync,
            TaskStore taskStore,
            ObjectMapper mapper,
            RepoTestValidationCheck testRunner)
    {
        this.prService = requireNonNull(prService, "prService is null");
        this.publish = requireNonNull(publish, "publish is null");
        this.sync = requireNonNull(sync, "sync is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.testRunner = requireNonNull(testRunner, "testRunner is null");
    }

    @GetMapping("/api/tasks/{taskId}/local-pr")
    public PRDto getForTask(@PathVariable String taskId)
    {
        return PRDto.from(prService.findByTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR for task " + taskId)));
    }

    /** The whole local PR in one payload — the frontend PR view / push dialog
     *  fetch this rather than five separate reads. */
    @GetMapping("/api/tasks/{taskId}/local-pr/bundle")
    public PRBundleDto bundle(@PathVariable String taskId)
    {
        // Materialise/refresh the local PR from the task's branch on read, so
        // the view shows the real commits even before an agent records them.
        PR pr = sync.syncFromTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR for task " + taskId));
        return new PRBundleDto(
                PRDto.from(pr),
                prService.commits(pr.id()).stream().map(PRCommitDto::from).toList(),
                prService.timeline(pr.id()).stream().map(e -> PRTimelineEntryDto.from(e, mapper)).toList(),
                prService.checks(pr.id()).stream().map(PRCheckDto::from).toList(),
                prService.comments(pr.id()).stream().map(PRCommentDto::from).toList(),
                prService.pendingStripCount(pr.id()));
    }

    /** Explicit user-triggered refresh — always probes GitHub (no maxAge
     *  short-circuit), unlike the passive sync a PR-bundle fetch performs. */
    @PostMapping("/api/prs/{prId}/sync")
    public PRDto syncPr(@PathVariable String prId)
    {
        return PRDto.from(sync.syncPR(prId, 0)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId)));
    }

    /**
     * On-demand local test run (design doc slice 4 — "runs at VALIDATING and
     * on demand"): the same {@link RepoTestValidationCheck} the VALIDATING
     * phase runs automatically, triggered manually from the Tests card.
     * Synchronous — a local desktop sidecar with one user, so a "run tests,
     * wait for it" click is the same shape as running it in a terminal; the
     * frontend shows a busy state for the call's duration.
     */
    @PostMapping("/api/local-pr/{prId}/run-tests")
    public List<PRCheckDto> runTests(@PathVariable String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        Task task = taskStore.findTaskById(pr.taskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no task " + pr.taskId()));
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task " + pr.taskId() + " has no worktree");
        }
        testRunner.run(task.id(), Path.of(task.worktreePath()));
        return prService.checks(prId).stream().map(PRCheckDto::from).toList();
    }

    /**
     * User-gated push: pushes the branch, opens a Draft PR on GitHub, strips
     * the private local record, and flips the PR to {@code remote-drafted}.
     * This is not an agent path — only the user's Approve &amp; push triggers it.
     */
    @PostMapping("/api/local-pr/{prId}/push")
    public PRDto push(@PathVariable String prId)
    {
        return PRDto.from(publish.push(prId));
    }

    /** User-gated merge of a pushed PR, then flip the local PR to {@code merged}. */
    @PostMapping("/api/local-pr/{prId}/merge")
    public PRDto merge(@PathVariable String prId, @RequestBody(required = false) MergePRRequest body)
    {
        return PRDto.from(publish.merge(prId, body == null ? null : body.method()));
    }

    @PostMapping("/api/tasks/{taskId}/local-pr")
    public PRDto create(@PathVariable String taskId, @RequestBody(required = false) CreatePRRequest body)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no task " + taskId));
        if (task.branchName() == null || task.branchName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task " + taskId + " has no branch yet");
        }
        String baseBranch = task.baseBranch() == null || task.baseBranch().isBlank()
                ? DEFAULT_BASE_BRANCH : task.baseBranch();
        String title = body != null && body.title() != null && !body.title().isBlank()
                ? body.title()
                : task.name() != null && !task.name().isBlank() ? task.name() : task.branchName();
        String description = body == null ? "" : body.description();
        return PRDto.from(
                prService.createForTask(taskId, task.branchName(), baseBranch, title, description));
    }

    @PatchMapping("/api/local-pr/{prId}")
    public PRDto update(@PathVariable String prId, @RequestBody(required = false) UpdatePRRequest body)
    {
        String title = body == null ? null : body.title();
        String description = body == null ? null : body.description();
        return PRDto.from(prService.updateDetails(prId, title, description));
    }

    @GetMapping("/api/local-pr/{prId}/timeline")
    public List<PRTimelineEntryDto> timeline(@PathVariable String prId)
    {
        return prService.timeline(prId).stream().map(e -> PRTimelineEntryDto.from(e, mapper)).toList();
    }

    @GetMapping("/api/local-pr/{prId}/commits")
    public List<PRCommitDto> commits(@PathVariable String prId)
    {
        return prService.commits(prId).stream().map(PRCommitDto::from).toList();
    }

    @GetMapping("/api/local-pr/{prId}/checks")
    public List<PRCheckDto> checks(@PathVariable String prId)
    {
        return prService.checks(prId).stream().map(PRCheckDto::from).toList();
    }

    @GetMapping("/api/local-pr/{prId}/comments")
    public List<PRCommentDto> comments(@PathVariable String prId)
    {
        return prService.comments(prId).stream().map(PRCommentDto::from).toList();
    }

    @PostMapping("/api/local-pr/{prId}/comments")
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
                USER_AUTHOR,
                body.body(),
                body.parentCommentId()));
    }

    @PatchMapping("/api/local-pr/comments/{commentId}")
    public PRCommentDto resolveComment(@PathVariable String commentId)
    {
        return PRCommentDto.from(prService.resolveComment(commentId));
    }

    @PatchMapping("/api/local-pr/comments/{commentId}/dismiss")
    public PRCommentDto dismissComment(@PathVariable String commentId)
    {
        return PRCommentDto.from(prService.dismissComment(commentId));
    }
}
