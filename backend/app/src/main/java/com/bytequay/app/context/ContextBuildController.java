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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.app.agent.AgentStartupRecovery;
import com.bytequay.app.web.agent.AgentHttpModels;
import com.bytequay.core.context.ContextLimits;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Purpose-scoped controls for a noninteractive learning task; no generic interactive-task coercion. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/repository-context/build")
public class ContextBuildController
{
    private final ContextBuildService builds;
    private final AgentTurnService turns;
    private final AgentCompletionService completion;
    private final AgentStartupRecovery recovery;

    public ContextBuildController(ContextBuildService builds, AgentTurnService turns, AgentCompletionService completion, AgentStartupRecovery recovery)
    {
        this.builds = requireNonNull(builds, "builds is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.completion = requireNonNull(completion, "completion is null");
        this.recovery = requireNonNull(recovery, "recovery is null");
    }

    @GetMapping
    public ContextBuildService.View get(@PathVariable String workspaceId)
    {
        return builds.get(workspaceId);
    }

    @PostMapping
    public ContextBuildService.View start(@PathVariable String workspaceId, @RequestBody StartRequest request)
    {
        return builds.start(workspaceId, new ContextBuildService.Request(request.requestKey(), request.expectedRevision(), request.expectedBuildSequence(),
                request.scopeIds(), request.history()));
    }

    @PostMapping("/stop")
    public ContextBuildService.View stop(@PathVariable String workspaceId, @RequestBody TaskRequest request)
    {
        return builds.stop(workspaceId, request.taskId());
    }

    @GetMapping("/runtime/stop-impact")
    public AgentHttpModels.RuntimeStopImpactResponse stopImpact(@PathVariable String workspaceId, @RequestParam String taskId)
    {
        AgentTask task = builds.task(workspaceId, taskId);
        return AgentHttpModels.stopImpact(turns.stopImpact(task.id()));
    }

    @PostMapping("/runtime/stop")
    public ContextBuildService.View stopRuntime(@PathVariable String workspaceId, @RequestBody RuntimeStopRequest request)
    {
        AgentTask task = builds.task(workspaceId, request.taskId());
        if (task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY).isPresent()) {
            recovery.stopRecoveredRuntime(task.id(), request.generation());
        }
        else {
            turns.stopRuntime(task.id(), request.generation());
        }
        return builds.get(workspaceId);
    }

    @PostMapping("/mark-failed")
    public ContextBuildService.View markFailed(@PathVariable String workspaceId, @RequestBody MarkFailedRequest request)
    {
        AgentTask task = builds.task(workspaceId, request.taskId());
        completion.abandon(task.id(), request.reason());
        return builds.get(workspaceId);
    }

    @PostMapping("/cleanup")
    public ContextBuildService.View cleanup(@PathVariable String workspaceId, @RequestBody TaskRequest request)
    {
        AgentTask task = builds.task(workspaceId, request.taskId());
        completion.cleanupWorktree(task.id());
        return builds.get(workspaceId);
    }

    public record StartRequest(String requestKey, Long expectedRevision, Long expectedBuildSequence, List<String> scopeIds, List<ContextBuild.History> history)
    {
        public StartRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0 && expectedBuildSequence != null && expectedBuildSequence >= 0,
                    "Both expectedRevision and expectedBuildSequence are required nonnegative integers");
            ContextBuildService.Request checked = new ContextBuildService.Request(requestKey, expectedRevision, expectedBuildSequence, scopeIds, history);
            scopeIds = checked.scopeIds();
            history = checked.history();
        }
    }

    public record TaskRequest(String taskId)
    {
        public TaskRequest
        {
            ContextLimits.text(taskId, "taskId", 200);
        }
    }

    public record RuntimeStopRequest(String taskId, String generation)
    {
        public RuntimeStopRequest
        {
            ContextLimits.text(taskId, "taskId", 200);
            ContextLimits.text(generation, "generation", 500);
        }
    }

    public record MarkFailedRequest(String taskId, String reason)
    {
        public MarkFailedRequest
        {
            ContextLimits.text(taskId, "taskId", 200);
            ContextLimits.text(reason, "reason", 2000);
        }
    }
}
