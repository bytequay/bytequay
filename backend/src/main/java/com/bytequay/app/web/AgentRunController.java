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

import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.StageDetailService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Read-only REST surface for {@link AgentRun} — the rail's run sub-rows and
 * a run's own detail / log. The log reuses {@link StageDetailService}
 * as-is for stage-backed runs. Detached artifact runs expose their dedicated
 * artifact log endpoint instead and return conflict here.
 */
@RestController
public class AgentRunController
{
    private final AgentRunService runs;
    private final StageDetailService detailService;

    public AgentRunController(AgentRunService runs, StageDetailService detailService)
    {
        this.runs = requireNonNull(runs, "runs is null");
        this.detailService = requireNonNull(detailService, "detailService is null");
    }

    @GetMapping("/api/tasks/{taskId}/runs")
    public List<AgentRun> runsForTask(@PathVariable String taskId)
    {
        return runs.findByTask(taskId, null, null);
    }

    @GetMapping("/api/runs/{runId}")
    public AgentRun run(@PathVariable String runId)
    {
        return findOrThrow(runId);
    }

    @GetMapping("/api/runs/{runId}/log")
    public StageDetailData log(@PathVariable String runId)
    {
        AgentRun run = findOrThrow(runId);
        if (run.stageId() == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409), "run has no stage-backed log: " + runId);
        }
        return detailService.getDetail(UUID.fromString(run.stageId()));
    }

    private AgentRun findOrThrow(String runId)
    {
        return runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no run: " + runId));
    }
}
