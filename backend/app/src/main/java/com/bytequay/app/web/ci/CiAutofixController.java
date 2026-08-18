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
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.SendCiAutofixMessageCommand;
import com.bytequay.app.ci.StartCiAutofixCommand;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/ci-autofix/runs")
public class CiAutofixController
{
    private final CiAutofixService service;
    private final AgentMetricsService metricsService;

    public CiAutofixController(CiAutofixService service, AgentMetricsService metricsService)
    {
        this.service = requireNonNull(service, "service is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
    }

    @PostMapping
    public ResponseEntity<CiAutofixHttpModels.RunResponse> start(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StartRequest request)
    {
        requireNotBlank(idempotencyKey, "Idempotency-Key must not be blank");
        requireNonNull(request, "request is null");
        requireNotBlank(request.repository(), "repository must not be blank");
        requireNotBlank(request.worktree(), "worktree must not be blank");
        requireNotBlank(request.laneId(), "laneId must not be blank");
        RepositoryIdentity repository = RepositoryIdentity.valueOf(request.repository());
        CiAutofixRun run = service.start(new StartCiAutofixCommand(
                PullRequestRef.of(repository.owner(), repository.name(), request.pullRequestNumber()),
                Path.of(request.worktree()),
                new AgentLaneId(request.laneId()),
                request.maxAttempts(),
                request.allowFailedJobReruns(),
                request.allowHistoryRewrites(),
                request.allowExactLeasePush(),
                idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(response(run));
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
        requireNotBlank(idempotencyKey, "Idempotency-Key must not be blank");
        requireNonNull(request, "request is null");
        requireNotBlank(request.message(), "message must not be blank");
        CiAutofixRun run = service.sendMessage(new SendCiAutofixMessageCommand(
                CiAutofixRunId.valueOf(runId),
                request.message(),
                idempotencyKey));
        return ResponseEntity.accepted().body(response(run));
    }

    @PostMapping("/{runId}/cancel")
    public CiAutofixHttpModels.RunResponse cancel(@PathVariable String runId)
    {
        return response(service.cancel(CiAutofixRunId.valueOf(runId)));
    }

    private CiAutofixHttpModels.RunResponse response(CiAutofixRun run)
    {
        return CiAutofixHttpModels.run(
                run,
                run.agentTaskId().map(TaskId::valueOf).map(metricsService::taskTotals));
    }

    public record StartRequest(
            String repository,
            int pullRequestNumber,
            String worktree,
            String laneId,
            int maxAttempts,
            boolean allowFailedJobReruns,
            boolean allowHistoryRewrites,
            boolean allowExactLeasePush) {}

    public record MessageRequest(String message) {}
}
