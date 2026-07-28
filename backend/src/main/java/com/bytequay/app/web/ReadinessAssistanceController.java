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

import com.bytequay.app.developmentflow.stage.V2ReadinessAssistanceRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.Action;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.AssistanceKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.AuthorizationRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.Availability;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** Explicit user boundary for scenario-42 Remote assistance. */
@RestController
@RequestMapping("/api/tasks/{taskId}/stages/{stageId}/readiness-assistance")
public final class ReadinessAssistanceController
{
    private final SqliteReadinessAssistanceStore store;
    private final V2ReadinessAssistanceRuntime runtime;

    public ReadinessAssistanceController(
            SqliteReadinessAssistanceStore store,
            V2ReadinessAssistanceRuntime runtime)
    {
        this.store = requireNonNull(store, "store is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @GetMapping
    public ResponseEntity<Availability> availability(
            @PathVariable String taskId,
            @PathVariable String stageId)
    {
        return ResponseEntity.of(store.availability(taskId, stageId));
    }

    @PostMapping
    public ActionResponse authorize(
            @PathVariable String taskId,
            @PathVariable String stageId,
            @RequestBody AssistanceRequest body)
    {
        Action action = runtime.authorize(new AuthorizationRequest(
                body.commandId(), taskId, body.taskEpoch(), stageId,
                body.stageGeneration(), body.snapshotId(), body.readinessId(),
                body.policyId(), body.headSha(), body.baseSha(), body.kind(),
                body.externalTarget(), body.payload()));
        return new ActionResponse(action.id(), action.status());
    }

    public record AssistanceRequest(
            String commandId,
            long taskEpoch,
            long stageGeneration,
            String snapshotId,
            String readinessId,
            String policyId,
            String headSha,
            String baseSha,
            AssistanceKind kind,
            String externalTarget,
            String payload)
    {
    }

    public record ActionResponse(String actionId, String status)
    {
    }
}
