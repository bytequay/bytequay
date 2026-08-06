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

import com.bytequay.app.service.harness.HarnessModels.CycleDetail;
import com.bytequay.app.service.harness.HarnessModels.HarnessDashboard;
import com.bytequay.app.service.harness.HarnessModels.WatchSummary;
import com.bytequay.app.service.harness.HarnessOrchestrator;
import com.bytequay.app.service.harness.HarnessService;
import com.bytequay.app.service.harness.HarnessService.CreateWatchCommand;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Renderer API for the local-only CI harness. Intentionally exposes no push action. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/ci-harness")
public class HarnessController
{
    private final HarnessService harness;
    private final HarnessOrchestrator orchestrator;

    public HarnessController(HarnessService harness, HarnessOrchestrator orchestrator)
    {
        this.harness = requireNonNull(harness, "harness is null");
        this.orchestrator = requireNonNull(orchestrator, "orchestrator is null");
    }

    @GetMapping("/watches")
    public List<WatchSummary> watches(@PathVariable String workspaceId)
    {
        return harness.list(workspaceId);
    }

    @PostMapping("/watches")
    public HarnessDashboard create(
            @PathVariable String workspaceId,
            @RequestBody CreateWatchBody body)
    {
        requireNonNull(body, "body is null");
        // A watch created by hand has no picks behind it, so it opens its own
        // session on the first round.
        return harness.create(workspaceId, new CreateWatchCommand(
                body.owner(), body.repo(), body.prNumber(), body.localPrId(), body.localPath(),
                body.branch(), body.title(), body.budgetMilliUsd(), null));
    }

    @GetMapping("/watches/{watchId}")
    public HarnessDashboard watch(
            @PathVariable String workspaceId,
            @PathVariable String watchId)
    {
        return harness.get(workspaceId, watchId);
    }

    @PostMapping("/watches/{watchId}/run")
    public HarnessDashboard run(
            @PathVariable String workspaceId,
            @PathVariable String watchId,
            @RequestBody(required = false) RunWatchBody body)
    {
        // Ownership is checked before the asynchronous request is accepted.
        harness.get(workspaceId, watchId);
        orchestrator.requestRun(
                watchId, "manual", body == null ? null : body.steeringText());
        return harness.get(workspaceId, watchId);
    }

    @PostMapping("/watches/{watchId}/stop")
    public HarnessDashboard stop(
            @PathVariable String workspaceId,
            @PathVariable String watchId)
    {
        return harness.stop(workspaceId, watchId);
    }

    /** The other answer to a park: carry on with a higher ceiling. */
    @PostMapping("/watches/{watchId}/budget")
    public HarnessDashboard raiseBudget(
            @PathVariable String workspaceId,
            @PathVariable String watchId,
            @RequestBody RaiseBudgetBody body)
    {
        requireNonNull(body, "body is null");
        HarnessDashboard raised = harness.raiseBudget(
                workspaceId, watchId, body.additionalMilliUsd());
        // Resuming is the point of raising it — the run picks its session back up.
        orchestrator.requestRun(watchId, "budget_raised");
        return raised;
    }

    public record RaiseBudgetBody(long additionalMilliUsd) {}

    @GetMapping("/watches/{watchId}/cycles/{cycleId}")
    public CycleDetail cycle(
            @PathVariable String workspaceId,
            @PathVariable String watchId,
            @PathVariable String cycleId)
    {
        return harness.cycle(workspaceId, watchId, cycleId);
    }

    @PostMapping("/watches/{watchId}/failures/{failureId}/resolve")
    public HarnessDashboard resolveFailure(
            @PathVariable String workspaceId,
            @PathVariable String watchId,
            @PathVariable String failureId,
            @RequestBody(required = false) FailureNoteBody body)
    {
        harness.resolveFailure(
                workspaceId, watchId, failureId, body == null ? null : body.note());
        return harness.get(workspaceId, watchId);
    }

    @PostMapping("/watches/{watchId}/failures/{failureId}/retry")
    public HarnessDashboard retryFailure(
            @PathVariable String workspaceId,
            @PathVariable String watchId,
            @PathVariable String failureId,
            @RequestBody(required = false) FailureNoteBody body)
    {
        String note = harness.resolveFailure(
                workspaceId, watchId, failureId, body == null ? null : body.note());
        orchestrator.requestRun(watchId, "escalation_retry", note);
        return harness.get(workspaceId, watchId);
    }

    @PostMapping("/watches/{watchId}/ask")
    public HarnessDashboard ask(
            @PathVariable String workspaceId,
            @PathVariable String watchId,
            @RequestBody AskBody body)
    {
        requireNonNull(body, "body is null");
        return harness.ask(workspaceId, watchId, body.question());
    }

    public record CreateWatchBody(
            String owner,
            String repo,
            int prNumber,
            String localPrId,
            String localPath,
            String branch,
            String title,
            Long budgetMilliUsd) {}

    /** Optional run body: {@code {"steeringText":"advisory context"}}. */
    public record RunWatchBody(String steeringText) {}

    /** Optional escalation body: {@code {"note":"what you decided"}}. */
    public record FailureNoteBody(String note) {}

    public record AskBody(String question) {}
}
