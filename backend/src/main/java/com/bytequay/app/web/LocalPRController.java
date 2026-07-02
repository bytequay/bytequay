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

import com.bytequay.app.beans.localpr.AddLocalPRCommentRequest;
import com.bytequay.app.beans.localpr.CreateLocalPRRequest;
import com.bytequay.app.beans.localpr.LocalPRCheckDto;
import com.bytequay.app.beans.localpr.LocalPRCommentDto;
import com.bytequay.app.beans.localpr.LocalPRCommitDto;
import com.bytequay.app.beans.localpr.LocalPRDto;
import com.bytequay.app.beans.localpr.LocalPRTimelineEventDto;
import com.bytequay.app.beans.localpr.UpdateLocalPRRequest;
import com.bytequay.app.domain.LocalPRComment;
import com.bytequay.app.domain.LocalPRTimelineEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.LocalPRService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
public class LocalPRController
{
    private static final String USER_AUTHOR = LocalPRTimelineEvent.ACTOR_USER;
    private static final String DEFAULT_BASE_BRANCH = "main";

    private final LocalPRService localPr;
    private final TaskStore taskStore;
    private final ObjectMapper mapper;

    public LocalPRController(LocalPRService localPr, TaskStore taskStore, ObjectMapper mapper)
    {
        this.localPr = requireNonNull(localPr, "localPr is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @GetMapping("/api/tasks/{taskId}/local-pr")
    public LocalPRDto getForTask(@PathVariable String taskId)
    {
        return LocalPRDto.from(localPr.findByTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR for task " + taskId)));
    }

    @PostMapping("/api/tasks/{taskId}/local-pr")
    public LocalPRDto create(@PathVariable String taskId, @RequestBody(required = false) CreateLocalPRRequest body)
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
        return LocalPRDto.from(
                localPr.createForTask(taskId, task.branchName(), baseBranch, title, description));
    }

    @PatchMapping("/api/local-pr/{prId}")
    public LocalPRDto update(@PathVariable String prId, @RequestBody(required = false) UpdateLocalPRRequest body)
    {
        String title = body == null ? null : body.title();
        String description = body == null ? null : body.description();
        return LocalPRDto.from(localPr.updateDetails(prId, title, description));
    }

    @GetMapping("/api/local-pr/{prId}/timeline")
    public List<LocalPRTimelineEventDto> timeline(@PathVariable String prId)
    {
        return localPr.timeline(prId).stream().map(e -> LocalPRTimelineEventDto.from(e, mapper)).toList();
    }

    @GetMapping("/api/local-pr/{prId}/commits")
    public List<LocalPRCommitDto> commits(@PathVariable String prId)
    {
        return localPr.commits(prId).stream().map(LocalPRCommitDto::from).toList();
    }

    @GetMapping("/api/local-pr/{prId}/checks")
    public List<LocalPRCheckDto> checks(@PathVariable String prId)
    {
        return localPr.checks(prId).stream().map(LocalPRCheckDto::from).toList();
    }

    @GetMapping("/api/local-pr/{prId}/comments")
    public List<LocalPRCommentDto> comments(@PathVariable String prId)
    {
        return localPr.comments(prId).stream().map(LocalPRCommentDto::from).toList();
    }

    @PostMapping("/api/local-pr/{prId}/comments")
    public LocalPRCommentDto addComment(@PathVariable String prId, @RequestBody AddLocalPRCommentRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        // A comment posted through this controller is the user's own — always
        // local origin; the "remote" origin is populated only by the review
        // ingestor, never by a user request.
        return LocalPRCommentDto.from(localPr.addComment(
                prId,
                LocalPRComment.ORIGIN_LOCAL,
                body.scope(),
                body.filePath(),
                body.lineNumber(),
                USER_AUTHOR,
                body.body(),
                body.parentCommentId()));
    }

    @PatchMapping("/api/local-pr/comments/{commentId}")
    public LocalPRCommentDto resolveComment(@PathVariable String commentId)
    {
        return LocalPRCommentDto.from(localPr.resolveComment(commentId));
    }
}
