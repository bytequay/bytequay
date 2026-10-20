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
package com.bytequay.app.web.ci;

import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.SendCiAutofixMessageCommand;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/ci-autofix/runs")
public class CiAutofixController
{
    private final CiAutofixService service;
    private final AgentMetricsService metricsService;
    private final AgentTaskService taskService;

    public CiAutofixController(CiAutofixService service, AgentMetricsService metricsService, AgentTaskService taskService)
    {
        this.service = requireNonNull(service, "service is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
    }

    @GetMapping
    public List<CiAutofixHttpModels.RunResponse> list(@RequestParam(defaultValue = "100") int limit)
    {
        return service.list(limit).stream().map(this::response).collect(toImmutableList());
    }

    @GetMapping("/{runId}")
    public CiAutofixHttpModels.RunResponse get(@PathVariable String runId)
    {
        return response(service.get(CiAutofixRunId.valueOf(runId)));
    }

    @PostMapping("/{runId}/messages")
    public ResponseEntity<CiAutofixHttpModels.RunResponse> message(
            @PathVariable String runId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MessageRequest request)
    {
        checkText(idempotencyKey, "Idempotency-Key");
        checkText(request.message(), "message");
        CiAutofixRun run = service.sendMessage(new SendCiAutofixMessageCommand(
                CiAutofixRunId.valueOf(runId),
                request.message(),
                idempotencyKey,
                request.expectedTurnId().map(TurnId::valueOf)));
        return ResponseEntity.accepted().body(response(run));
    }

    /** Stops a run and its repair; a development task's next push starts another. */
    @PostMapping("/{runId}/cancel")
    public CiAutofixHttpModels.RunResponse cancel(@PathVariable String runId)
    {
        return response(service.cancel(CiAutofixRunId.valueOf(runId)));
    }

    private CiAutofixHttpModels.RunResponse response(CiAutofixRun run)
    {
        Optional<AgentTask> task = run.agentTaskId()
                .map(TaskId::valueOf)
                .flatMap(this::findTask);
        return CiAutofixHttpModels.run(
                run,
                task.map(value -> metricsService.taskTotals(value.id())),
                task.flatMap(value -> value.taskWorktree().map(worktree -> worktree.generatedBranch())),
                service.canMessage(run));
    }

    private Optional<AgentTask> findTask(TaskId taskId)
    {
        try {
            return Optional.of(taskService.get(taskId));
        }
        catch (AgentTaskNotFoundException ignored) {
            return Optional.empty();
        }
    }

    public record MessageRequest(String message, Optional<String> expectedTurnId) {}
}
