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
import com.bytequay.app.review.LocalRunService.OutputChunk;
import com.bytequay.app.review.ReviewCodeService;
import com.bytequay.app.review.ReviewCodeService.CodeFiles;
import com.bytequay.app.review.TaskCodeHost;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Task-owned Code and Tests reads, and the existing development-only edits, local runs and terminal, without Discard.
 */
@RestController
@RequestMapping({"/agent/tasks/{taskId}/code", "/api/agent-review/{reviewId}/code"})
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
            @PathVariable Map<String, String> target,
            @RequestParam Optional<String> workspaceId,
            @RequestParam(defaultValue = "ALL") ReviewCodeService.ChangeScope scope,
            @RequestParam Optional<String> commit,
            @RequestParam Optional<String> base,
            @RequestParam Optional<String> head)
    {
        return code.changes(host(target, workspaceId), scope, commit, base, head);
    }

    @GetMapping("/files")
    public CodeFiles files(@PathVariable Map<String, String> target, @RequestParam Optional<String> workspaceId)
    {
        return code.files(host(target, workspaceId));
    }

    @GetMapping("/file")
    public ReviewCodeHttpModels.FileResponse file(
            @PathVariable Map<String, String> target,
            @RequestParam Optional<String> workspaceId,
            @RequestParam String path,
            @RequestParam Optional<String> expectedRevision)
    {
        return ReviewCodeHttpModels.file(code.file(host(target, workspaceId), path, expectedRevision));
    }

    @PostMapping("/terminal")
    public ReviewCodeHttpModels.TerminalResponse terminal(@PathVariable Map<String, String> target, @RequestParam Optional<String> workspaceId)
    {
        var launch = code.terminal(host(target, workspaceId));
        return new ReviewCodeHttpModels.TerminalResponse(launch.workingDirectory().toString(), launch.claudeConfigDirectory().map(Path::toString));
    }

    @GetMapping("/runs")
    public ReviewCodeHttpModels.RunsResponse runs(@PathVariable Map<String, String> target, @RequestParam Optional<String> workspaceId)
    {
        return new ReviewCodeHttpModels.RunsResponse(localRuns.list(host(target, workspaceId)).stream()
                .map(ReviewCodeHttpModels::run)
                .collect(toImmutableList()));
    }

    @PostMapping("/runs/{runId}/stop")
    public ReviewCodeHttpModels.RunResponse stopRun(@PathVariable Map<String, String> target, @PathVariable String runId, @RequestParam Optional<String> workspaceId)
    {
        return ReviewCodeHttpModels.run(localRuns.stop(host(target, workspaceId), runId));
    }

    @GetMapping("/runs/{runId}/output")
    public OutputChunk runOutput(
            @PathVariable Map<String, String> target,
            @PathVariable String runId,
            @RequestParam Optional<String> workspaceId,
            @RequestParam(defaultValue = "0") long after)
    {
        return localRuns.output(host(target, workspaceId), runId, after);
    }

    private TaskCodeHost host(Map<String, String> target, Optional<String> workspaceId)
    {
        return Optional.ofNullable(target.get("reviewId")).map(code::reviewHost)
                .orElseGet(() -> new TaskCodeHost(workspaceId.orElseThrow(() -> new IllegalArgumentException("workspaceId is required")), target.get("taskId")));
    }
}
