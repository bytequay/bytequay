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
import com.bytequay.app.beans.localpr.LocalPRBundleDto;
import com.bytequay.app.beans.localpr.LocalPRCheckDto;
import com.bytequay.app.beans.localpr.LocalPRCommentDto;
import com.bytequay.app.beans.localpr.LocalPRCommitDto;
import com.bytequay.app.beans.localpr.LocalPRDto;
import com.bytequay.app.beans.localpr.LocalPRTimelineEventDto;
import com.bytequay.app.beans.localpr.MergeLocalPRRequest;
import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRComment;
import com.bytequay.app.domain.LocalPRTimelineEvent;
import com.bytequay.app.service.localpr.LocalPRPublishService;
import com.bytequay.app.service.localpr.LocalPRService;
import com.bytequay.app.service.localpr.LocalPRSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the local PR — the PR artifact that lives in ByteQuay
 * before it reaches GitHub. The read bundle and comment mutations are here;
 * the user-gated {@code push} and {@code merge} transitions perform GitHub
 * I/O. Agents mutate the local PR through the {@code record_pr_*} MCP tools
 * inside a scheduler-dispatched turn, not through this controller.
 */
@RestController
public class LocalPRController
{
    private static final String USER_AUTHOR = LocalPRTimelineEvent.ACTOR_USER;

    private final LocalPRService localPr;
    private final LocalPRPublishService publish;
    private final LocalPRSyncService sync;
    private final ObjectMapper mapper;

    public LocalPRController(
            LocalPRService localPr,
            LocalPRPublishService publish,
            LocalPRSyncService sync,
            ObjectMapper mapper)
    {
        this.localPr = requireNonNull(localPr, "localPr is null");
        this.publish = requireNonNull(publish, "publish is null");
        this.sync = requireNonNull(sync, "sync is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** The whole local PR in one payload — the frontend PR view / push dialog
     *  fetch this rather than five separate reads. */
    @GetMapping("/api/tasks/{taskId}/local-pr/bundle")
    public LocalPRBundleDto bundle(@PathVariable String taskId)
    {
        // Materialise/refresh the local PR from the task's branch on read, so
        // the view shows the real commits even before an agent records them.
        LocalPR pr = sync.syncFromTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR for task " + taskId));
        return new LocalPRBundleDto(
                LocalPRDto.from(pr),
                localPr.commits(pr.id()).stream().map(LocalPRCommitDto::from).toList(),
                localPr.timeline(pr.id()).stream().map(e -> LocalPRTimelineEventDto.from(e, mapper)).toList(),
                localPr.checks(pr.id()).stream().map(LocalPRCheckDto::from).toList(),
                localPr.comments(pr.id()).stream().map(LocalPRCommentDto::from).toList(),
                localPr.pendingStripCount(pr.id()));
    }

    /**
     * User-gated push: pushes the branch, opens a Draft PR on GitHub, strips
     * the private local record, and flips the PR to {@code remote-drafted}.
     * This is not an agent path — only the user's Approve &amp; push triggers it.
     */
    @PostMapping("/api/local-pr/{prId}/push")
    public LocalPRDto push(@PathVariable String prId)
    {
        return LocalPRDto.from(publish.push(prId));
    }

    /** User-gated merge of a pushed PR, then flip the local PR to {@code merged}. */
    @PostMapping("/api/local-pr/{prId}/merge")
    public LocalPRDto merge(@PathVariable String prId, @RequestBody(required = false) MergeLocalPRRequest body)
    {
        return LocalPRDto.from(publish.merge(prId, body == null ? null : body.method()));
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

    @PatchMapping("/api/local-pr/comments/{commentId}/dismiss")
    public LocalPRCommentDto dismissComment(@PathVariable String commentId)
    {
        return LocalPRCommentDto.from(localPr.dismissComment(commentId));
    }
}
