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

import com.bytequay.app.review.LocalRunRefusedException;
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

import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * The Code and Tests tab of a development task's pull request: the review routes' reads, edits of any file, local test runs and the
 * terminal's directory, without Discard. Every request names the pull request, so the task's ownership of it is checked each time.
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

    @GetMapping("/files")
    public ReviewCodeHttpModels.FilesResponse files(@PathVariable String taskId, @RequestParam String workspaceId, @RequestParam String repo, @RequestParam int number)
    {
        return ReviewCodeHttpModels.files(code.files(new TaskCodeHost(workspaceId, taskId, repo, number)));
    }

    @GetMapping("/file")
    public ReviewCodeHttpModels.FileResponse file(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestParam String repo,
            @RequestParam int number,
            @RequestParam String path)
    {
        return ReviewCodeHttpModels.file(code.file(new TaskCodeHost(workspaceId, taskId, repo, number), path));
    }

    @PutMapping("/file")
    public ReviewCodeHttpModels.FileResponse save(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestParam String repo,
            @RequestParam int number,
            @RequestBody ReviewCodeHttpModels.SaveFileRequest request)
    {
        return ReviewCodeHttpModels.file(code.save(new TaskCodeHost(workspaceId, taskId, repo, number), request.path(), request.content(), request.expectedHash()));
    }

    @PostMapping("/terminal")
    public ReviewCodeHttpModels.TerminalResponse terminal(@PathVariable String taskId, @RequestParam String workspaceId, @RequestParam String repo, @RequestParam int number)
    {
        return new ReviewCodeHttpModels.TerminalResponse(code.terminalDirectory(new TaskCodeHost(workspaceId, taskId, repo, number)).toString());
    }

    @GetMapping("/symbols")
    public ReviewCodeHttpModels.SymbolsResponse symbols(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestParam String repo,
            @RequestParam int number,
            @RequestParam String query,
            @RequestParam(defaultValue = "50") int limit)
    {
        return ReviewCodeHttpModels.symbols(code.symbols(new TaskCodeHost(workspaceId, taskId, repo, number), query, limit));
    }

    @GetMapping("/outline")
    public ReviewCodeHttpModels.SymbolsResponse outline(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestParam String repo,
            @RequestParam int number,
            @RequestParam String path)
    {
        return ReviewCodeHttpModels.outline(code.outline(new TaskCodeHost(workspaceId, taskId, repo, number), path));
    }

    @GetMapping("/runs")
    public ReviewCodeHttpModels.RunsResponse runs(@PathVariable String taskId, @RequestParam String workspaceId, @RequestParam String repo, @RequestParam int number)
    {
        return new ReviewCodeHttpModels.RunsResponse(localRuns.list(new TaskCodeHost(workspaceId, taskId, repo, number)).stream()
                .map(ReviewCodeHttpModels::run)
                .collect(toImmutableList()));
    }

    @GetMapping("/runs/{runId}")
    public ReviewCodeHttpModels.RunResponse run(
            @PathVariable String taskId,
            @PathVariable String runId,
            @RequestParam String workspaceId,
            @RequestParam String repo,
            @RequestParam int number)
    {
        return ReviewCodeHttpModels.run(localRuns.get(new TaskCodeHost(workspaceId, taskId, repo, number), runId));
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewCodeHttpModels.RunResponse startRun(
            @PathVariable String taskId,
            @RequestParam String workspaceId,
            @RequestParam String repo,
            @RequestParam int number,
            @RequestBody ReviewCodeHttpModels.StartRunRequest request)
    {
        return ReviewCodeHttpModels.run(localRuns.start(new TaskCodeHost(workspaceId, taskId, repo, number), request.path(), Optional.ofNullable(request.method())));
    }

    @PostMapping("/runs/{runId}/stop")
    public ReviewCodeHttpModels.RunResponse stopRun(
            @PathVariable String taskId,
            @PathVariable String runId,
            @RequestParam String workspaceId,
            @RequestParam String repo,
            @RequestParam int number)
    {
        return ReviewCodeHttpModels.run(localRuns.stop(new TaskCodeHost(workspaceId, taskId, repo, number), runId));
    }

    @GetMapping("/runs/{runId}/output")
    public ReviewCodeHttpModels.RunOutputResponse runOutput(
            @PathVariable String taskId,
            @PathVariable String runId,
            @RequestParam String workspaceId,
            @RequestParam String repo,
            @RequestParam int number,
            @RequestParam(defaultValue = "0") long after)
    {
        LocalRunService.OutputChunk chunk = localRuns.output(new TaskCodeHost(workspaceId, taskId, repo, number), runId, after);
        return new ReviewCodeHttpModels.RunOutputResponse(chunk.text(), chunk.nextOffset(), chunk.skippedBytes(), chunk.complete());
    }

    @ExceptionHandler(LocalRunRefusedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ReviewCodeHttpModels.RunRefusalResponse refused(LocalRunRefusedException refusal)
    {
        return new ReviewCodeHttpModels.RunRefusalResponse(refusal.getMessage(), refusal.openTerminal() ? Optional.of("OPEN_TERMINAL") : Optional.empty());
    }

    @ExceptionHandler(ReviewFileConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ReviewCodeHttpModels.SaveConflictResponse fileChanged(ReviewFileConflictException conflict)
    {
        return new ReviewCodeHttpModels.SaveConflictResponse(conflict.currentHash(), conflict.currentContent());
    }
}
