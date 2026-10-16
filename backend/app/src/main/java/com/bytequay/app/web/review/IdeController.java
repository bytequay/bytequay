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
import com.bytequay.app.review.IdeExecutions;
import com.bytequay.app.review.IdeRuntimeService;
import com.bytequay.app.review.ReviewCodeService;
import com.bytequay.app.review.ReviewCodeService.CodeFile;
import com.bytequay.app.review.ReviewFileConflictException;
import com.bytequay.app.review.TaskCodeHost;
import com.bytequay.app.web.review.ReviewCodeHttpModels.RunResponse;
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

import java.util.Map;
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
    private final IdeExecutions executions;

    public IdeController(ReviewCodeService code, IdeRuntimeService runtime, IdeCodeQueries queries, IdeExecutions executions)
    {
        this.code = requireNonNull(code, "code is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.queries = requireNonNull(queries, "queries is null");
        this.executions = requireNonNull(executions, "executions is null");
    }

    @PostMapping({"/tasks/{workspaceId}/{taskId}/executions", "/reviews/{reviewId}/executions"})
    public RunResponse run(@PathVariable Map<String, String> target, @RequestBody IdeExecutions.Start request)
    {
        return ReviewCodeHttpModels.run(executions.start(host(target), request));
    }

    @PostMapping("/{taskId}/{sessionId}/executions/{id}/output")
    public void runOutput(@PathVariable String taskId, @PathVariable String sessionId, @PathVariable String id, @RequestBody IdeExecutions.Output request)
    {
        executions.output(taskId, sessionId, id, request);
    }

    @PostMapping("/{taskId}/{sessionId}/executions/{id}/finish")
    public RunResponse finishRun(@PathVariable String taskId, @PathVariable String sessionId, @PathVariable String id, @RequestBody IdeExecutions.Result result)
    {
        return ReviewCodeHttpModels.run(executions.finish(taskId, sessionId, id, result));
    }

    @DeleteMapping("/{taskId}/{sessionId}/executions/{id}")
    public RunResponse stopRun(@PathVariable String taskId, @PathVariable String sessionId, @PathVariable String id)
    {
        return ReviewCodeHttpModels.run(executions.stop(taskId, sessionId, id));
    }

    @PostMapping("/tasks/{workspaceId}/{taskId}/query")
    public IdeCodeQueries.Answer query(@PathVariable Map<String, String> target, @RequestBody CodeQuery query)
    {
        return queries.execute(code.ideQueryWorktree(host(target)), query);
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

    @GetMapping({"/tasks/{workspaceId}/{taskId}", "/reviews/{reviewId}"})
    public Optional<IdeConnection> existing(@PathVariable Map<String, String> target)
    {
        return runtime.existing(code.ideWorktree(host(target)));
    }

    @PostMapping({"/tasks/{workspaceId}/{taskId}", "/reviews/{reviewId}"})
    public IdeConnection open(@PathVariable Map<String, String> target)
    {
        return runtime.open(code.ideWorktree(host(target)));
    }

    @GetMapping({"/tasks/{workspaceId}/{taskId}/history", "/reviews/{reviewId}/history"})
    public CodeHistory history(@PathVariable Map<String, String> target, @RequestParam Optional<String> revision,
            @RequestParam Optional<String> path, @RequestParam(defaultValue = "0") int offset)
    {
        return code.history(host(target), revision, path, offset);
    }

    @GetMapping({"/tasks/{workspaceId}/{taskId}/commit", "/reviews/{reviewId}/commit"})
    public CodeHistory.Comparison commit(@PathVariable Map<String, String> target, @RequestParam String revision, @RequestParam Optional<String> parent)
    {
        return code.historyCommit(host(target), revision, parent);
    }

    @GetMapping({"/tasks/{workspaceId}/{taskId}/snapshot", "/reviews/{reviewId}/snapshot"})
    public CodeFile snapshot(@PathVariable Map<String, String> target, @RequestParam String revision, @RequestParam String path)
    {
        return code.snapshot(host(target), revision, path);
    }

    @GetMapping({"/tasks/{workspaceId}/{taskId}/blame", "/reviews/{reviewId}/blame"})
    public CodeBlame blame(@PathVariable Map<String, String> target, @RequestParam String path)
    {
        return code.blame(host(target), path);
    }

    @PutMapping({"/tasks/{workspaceId}/{taskId}/file", "/reviews/{reviewId}/file"})
    public void save(@PathVariable Map<String, String> target, @RequestBody SaveBytesRequest request)
    {
        code.saveBytes(host(target), request.path(), request.content(), request.expectedHash());
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

    private TaskCodeHost host(Map<String, String> target)
    {
        return Optional.ofNullable(target.get("reviewId")).map(code::reviewHost)
                .orElseGet(() -> new TaskCodeHost(target.get("workspaceId"), target.get("taskId")));
    }
}
