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

import com.bytequay.app.review.LocalRunService;
import com.bytequay.app.review.ReviewCodeService;
import com.bytequay.app.review.ReviewFileConflictException;
import com.bytequay.app.review.TaskCodeHost;
import org.springframework.http.HttpStatus;
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

import java.nio.file.Path;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Task-owned Code and Tests reads, and the existing development-only edits, local runs and terminal, without Discard.
 */
@RestController
@RequestMapping("/agent/tasks/{taskId}/code")
public class TaskCodeController
{
    private final ReviewCodeService code;
    private final LocalRunService localRuns;

    public TaskCodeController(ReviewCodeService code, LocalRunService localRuns)
    {
        this.code = requireNonNull(code, "code is null");
        this.localRuns = requireNonNull(localRuns, "localRuns is null");
    }

    @GetMapping("/changes")
    public ReviewCodeService.Changes changes(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestParam(defaultValue = "ALL") ReviewCodeService.ChangeScope scope,
            @RequestParam Optional<String> commit,
            @RequestParam Optional<String> base,
            @RequestParam Optional<String> head)
    {
        return code.changes(new TaskCodeHost(workspaceId, taskId), scope, commit, base, head);
    }

    @GetMapping("/files")
    public ReviewCodeHttpModels.FilesResponse files(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        return ReviewCodeHttpModels.files(code.files(new TaskCodeHost(workspaceId, taskId)));
    }

    @GetMapping("/file")
    public ReviewCodeHttpModels.FileResponse file(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestParam String path,
            @RequestParam Optional<String> expectedRevision)
    {
        return ReviewCodeHttpModels.file(code.file(new TaskCodeHost(workspaceId, taskId), path, expectedRevision));
    }

    @PutMapping("/file")
    public ReviewCodeHttpModels.FileResponse save(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestBody ReviewCodeHttpModels.SaveFileRequest request)
    {
        return ReviewCodeHttpModels.file(code.save(new TaskCodeHost(workspaceId, taskId), request.path(), request.content(), request.expectedHash()));
    }

    @PostMapping("/terminal")
    public ReviewCodeHttpModels.TerminalResponse terminal(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        var launch = code.terminal(new TaskCodeHost(workspaceId, taskId));
        return new ReviewCodeHttpModels.TerminalResponse(launch.workingDirectory().toString(), launch.claudeConfigDirectory().map(Path::toString));
    }

    @GetMapping("/runs")
    public ReviewCodeHttpModels.RunsResponse runs(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        return new ReviewCodeHttpModels.RunsResponse(localRuns.list(new TaskCodeHost(workspaceId, taskId)).stream()
                .map(ReviewCodeHttpModels::run)
                .collect(toImmutableList()));
    }

    @PostMapping("/runs/{runId}/stop")
    public ReviewCodeHttpModels.RunResponse stopRun(
            @PathVariable String taskId,
            @PathVariable String runId,
            @RequestParam String workspaceId)
    {
        return ReviewCodeHttpModels.run(localRuns.stop(new TaskCodeHost(workspaceId, taskId), runId));
    }

    @GetMapping("/runs/{runId}/output")
    public ReviewCodeHttpModels.RunOutputResponse runOutput(
            @PathVariable String taskId,
            @PathVariable String runId,
            @RequestParam String workspaceId,
            @RequestParam(defaultValue = "0") long after)
    {
        LocalRunService.OutputChunk chunk = localRuns.output(new TaskCodeHost(workspaceId, taskId), runId, after);
        return new ReviewCodeHttpModels.RunOutputResponse(chunk.text(), chunk.nextOffset(), chunk.skippedBytes(), chunk.complete());
    }

    @ExceptionHandler(ReviewFileConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ReviewCodeHttpModels.SaveConflictResponse fileChanged(ReviewFileConflictException conflict)
    {
        return new ReviewCodeHttpModels.SaveConflictResponse(conflict.currentHash(), conflict.currentContent());
    }
}
