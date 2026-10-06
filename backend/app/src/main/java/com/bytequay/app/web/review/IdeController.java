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
package com.bytequay.app.web.review;

import com.bytequay.agent.core.CodeQueryResult;
import com.bytequay.agent.spi.CodeQuery;
import com.bytequay.app.review.CodeBlame;
import com.bytequay.app.review.CodeHistory;
import com.bytequay.app.review.IdeCodeQueries;
import com.bytequay.app.review.IdeConnection;
import com.bytequay.app.review.IdeRuntimeService;
import com.bytequay.app.review.ReviewCodeService;
import com.bytequay.app.review.ReviewCodeService.CodeFile;
import com.bytequay.app.review.ReviewFileConflictException;
import com.bytequay.app.review.TaskCodeHost;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Authenticated desktop-only runtime transport; workspace paths are resolved from durable task ownership. */
@RestController
@RequestMapping("/agent/ide")
public class IdeController
{
    private final ReviewCodeService code;
    private final IdeRuntimeService runtime;
    private final IdeCodeQueries queries;

    public IdeController(ReviewCodeService code, IdeRuntimeService runtime, IdeCodeQueries queries)
    {
        this.code = requireNonNull(code, "code is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.queries = requireNonNull(queries, "queries is null");
    }

    @PostMapping("/tasks/{workspaceId}/{taskId}/query")
    public IdeCodeQueries.Answer query(@PathVariable String workspaceId, @PathVariable String taskId, @RequestBody CodeQuery query)
    {
        return queries.execute(code.ideQueryWorktree(new TaskCodeHost(workspaceId, taskId)), query);
    }

    @GetMapping("/queries")
    public IdeCodeQueries.Snapshot queries(@RequestParam(defaultValue = "-1") long after)
            throws InterruptedException
    {
        return queries.poll(after);
    }

    @PostMapping("/queries/{id}")
    public void queryReply(@PathVariable String id, @RequestBody QueryReply reply)
    {
        if (reply.sessionId().isPresent()) {
            runtime.touch(reply.taskId(), reply.sessionId().orElseThrow());
        }
        else {
            checkArgument(reply.result().status() == CodeQueryResult.Status.UNAVAILABLE, "a live IDE session is required for query results");
        }
        queries.complete(id, reply.taskId(), reply.result());
    }

    record QueryReply(String taskId, Optional<String> sessionId, CodeQueryResult result) {}

    @PostMapping("/reviews/{reviewId}")
    public IdeConnection openReview(@PathVariable String reviewId)
    {
        return runtime.open(code.ideWorktree(reviewId));
    }

    @PostMapping("/tasks/{workspaceId}/{taskId}")
    public IdeConnection openTask(@PathVariable String workspaceId, @PathVariable String taskId)
    {
        return runtime.open(code.ideWorktree(new TaskCodeHost(workspaceId, taskId)));
    }

    @GetMapping("/reviews/{reviewId}/history")
    public CodeHistory historyReview(@PathVariable String reviewId, @RequestParam Optional<String> revision, @RequestParam Optional<String> path,
            @RequestParam(defaultValue = "0") int offset)
    {
        return code.history(reviewId, revision, path, offset);
    }

    @GetMapping("/tasks/{workspaceId}/{taskId}/history")
    public CodeHistory historyTask(@PathVariable String workspaceId, @PathVariable String taskId, @RequestParam Optional<String> revision,
            @RequestParam Optional<String> path, @RequestParam(defaultValue = "0") int offset)
    {
        return code.history(new TaskCodeHost(workspaceId, taskId), revision, path, offset);
    }

    @GetMapping("/reviews/{reviewId}/commit")
    public CodeHistory.Comparison commitReview(@PathVariable String reviewId, @RequestParam String revision, @RequestParam Optional<String> parent)
    {
        return code.historyCommit(reviewId, revision, parent);
    }

    @GetMapping("/tasks/{workspaceId}/{taskId}/commit")
    public CodeHistory.Comparison commitTask(@PathVariable String workspaceId, @PathVariable String taskId, @RequestParam String revision, @RequestParam Optional<String> parent)
    {
        return code.historyCommit(new TaskCodeHost(workspaceId, taskId), revision, parent);
    }

    @GetMapping("/reviews/{reviewId}/snapshot")
    public CodeFile snapshotReview(@PathVariable String reviewId, @RequestParam String revision, @RequestParam String path)
    {
        return code.snapshot(reviewId, revision, path);
    }

    @GetMapping("/tasks/{workspaceId}/{taskId}/snapshot")
    public CodeFile snapshotTask(@PathVariable String workspaceId, @PathVariable String taskId, @RequestParam String revision, @RequestParam String path)
    {
        return code.snapshot(new TaskCodeHost(workspaceId, taskId), revision, path);
    }

    @GetMapping("/reviews/{reviewId}/blame")
    public CodeBlame blameReview(@PathVariable String reviewId, @RequestParam String path)
    {
        return code.blame(reviewId, path);
    }

    @GetMapping("/tasks/{workspaceId}/{taskId}/blame")
    public CodeBlame blameTask(@PathVariable String workspaceId, @PathVariable String taskId, @RequestParam String path)
    {
        return code.blame(new TaskCodeHost(workspaceId, taskId), path);
    }

    @PutMapping("/reviews/{reviewId}/file")
    public void saveReview(@PathVariable String reviewId, @RequestBody SaveBytesRequest request)
    {
        code.saveBytes(reviewId, request.path(), request.content(), request.expectedHash());
    }

    @PutMapping("/tasks/{workspaceId}/{taskId}/file")
    public void saveTask(@PathVariable String workspaceId, @PathVariable String taskId, @RequestBody SaveBytesRequest request)
    {
        code.saveBytes(new TaskCodeHost(workspaceId, taskId), request.path(), request.content(), request.expectedHash());
    }

    @ExceptionHandler(ReviewFileConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public SaveConflict changed(ReviewFileConflictException conflict)
    {
        return new SaveConflict("IDE_FILE_CHANGED", conflict.getMessage());
    }

    record SaveConflict(String code, String message) {}

    record SaveBytesRequest(String path, byte[] content, String expectedHash) {}

    @PostMapping("/{taskId}/{sessionId}/touch")
    public void touch(@PathVariable String taskId, @PathVariable String sessionId)
    {
        runtime.touch(taskId, sessionId);
    }

    @DeleteMapping("/{taskId}/{sessionId}")
    public void stop(@PathVariable String taskId, @PathVariable String sessionId)
    {
        runtime.stop(taskId, sessionId);
    }
}
