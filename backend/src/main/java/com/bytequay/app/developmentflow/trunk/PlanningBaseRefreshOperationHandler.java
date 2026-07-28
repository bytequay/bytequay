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
package com.bytequay.app.developmentflow.trunk;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.service.threads.WorktreeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Executes one exact V2 planning snapshot refresh on Trunk-control capacity. */
public final class PlanningBaseRefreshOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "REFRESH_PLANNING_BASE";
    public static final String CALLBACK_ROUTE = "PLANNING_BASE_REFRESH_RESULT";

    private final Store store;
    private final PlanningGit git;
    private final ObjectMapper json;
    private final Clock clock;

    public PlanningBaseRefreshOperationHandler(
            Store store, PlanningGit git, ObjectMapper json, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.git = requireNonNull(git, "git is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext execution)
            throws Exception
    {
        requireNonNull(execution, "execution is null");
        DispatchTicket.DispatchEnvelope envelope = execution.envelope();
        OperationContext operation = store.require(envelope.fence().operationId());
        requireExact(envelope, operation);
        if (operation.liveThreadTurn() || operation.unlaunchedPredecessor()) {
            throw new ExecutionPorts.RetryableExecutionException(
                    "planning refresh waits for the preceding Trunk turn");
        }
        if (execution.isCancellationRequested()) {
            return DispatchTicket.DispatchResult.canceled(envelope.fence());
        }
        Optional<WorktreeService.PlanningSync> refreshed = git.refresh(
                Path.of(operation.repositoryRoot()), operation.trunkId());
        if (refreshed.isEmpty()) {
            return new DispatchTicket.DispatchResult(
                    envelope.fence(), DispatchTicket.Outcome.FAILED,
                    null,
                    "{\"schema\":\"PLANNING_BASE_REFRESH_V1\","
                            + "\"result\":\"snapshot unavailable\"}",
                    "planning snapshot unavailable");
        }
        WorktreeService.PlanningSync result = refreshed.orElseThrow();
        Snapshot snapshot = new Snapshot(
                1, operation.operationId(), operation.trunkId(),
                operation.repositoryRoot(),
                result.worktree().toAbsolutePath().normalize().toString(),
                result.baseRef(), result.baseSha(), clock.millis());
        String payload = write(snapshot);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), DispatchTicket.Outcome.SUCCEEDED,
                payload, payload, null);
    }

    /** Refresh/reset is idempotent and therefore safe after an ambiguous crash. */
    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext execution)
            throws Exception
    {
        return execute(execution);
    }

    private static void requireExact(
            DispatchTicket.DispatchEnvelope envelope,
            OperationContext operation)
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.family() != DispatchTicket.AsyncFamily.LOCAL_GIT
                || envelope.owner().kind() != DispatchTicket.OwnerKind.TRUNK
                || !operation.trunkId().equals(envelope.owner().id())
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !operation.operationId().equals(fence.operationId())
                || operation.semanticAttempt() != fence.attempt()
                || fence.taskEpoch() != null || fence.stageId() != null
                || fence.stageGeneration() != null
                || fence.expectedCodeFingerprint() != null
                || fence.expectedHeadSha() != null
                || !Objects.equals(
                operation.previousBaseSha(), fence.expectedBaseSha())
                || !capacity.lanes().equals(Set.of(
                CapacityManager.CapacityLane.LOCAL_GIT))
                || !capacity.trunkControl()
                || capacity.exclusiveTask() || capacity.writerRequired()
                || !operation.workspaceId().equals(capacity.scope().workspaceId())
                || !operation.trunkId().equals(capacity.scope().trunkId())
                || capacity.scope().taskId() != null) {
            throw new IllegalArgumentException(
                    "Planning-base ticket differs from its exact Operation");
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not encode planning snapshot", e);
        }
    }

    @FunctionalInterface
    public interface Store
    {
        OperationContext require(String operationId);
    }

    @FunctionalInterface
    public interface PlanningGit
    {
        Optional<WorktreeService.PlanningSync> refresh(
                Path repositoryRoot, String trunkId);
    }

    public record OperationContext(
            String planningOperationId,
            String operationId,
            String trunkId,
            String workspaceId,
            int semanticAttempt,
            String repositoryRoot,
            String previousBaseSha,
            boolean liveThreadTurn,
            boolean unlaunchedPredecessor)
    {
        public OperationContext
        {
            requireText(planningOperationId, "planningOperationId");
            requireText(operationId, "operationId");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(repositoryRoot, "repositoryRoot");
            if (semanticAttempt < 1) {
                throw new IllegalArgumentException(
                        "semanticAttempt must be positive");
            }
        }
    }

    public record Snapshot(
            int schemaVersion,
            String operationId,
            String trunkId,
            String repositoryRoot,
            String worktreePath,
            String baseRef,
            String baseSha,
            long refreshedAtMs)
    {
        public Snapshot
        {
            if (schemaVersion != 1 || refreshedAtMs < 0) {
                throw new IllegalArgumentException(
                        "planning snapshot version/time is invalid");
            }
            requireText(operationId, "operationId");
            requireText(trunkId, "trunkId");
            requireText(repositoryRoot, "repositoryRoot");
            requireText(worktreePath, "worktreePath");
            requireText(baseRef, "baseRef");
            requireText(baseSha, "baseSha");
            Path root = Path.of(repositoryRoot);
            Path worktree = Path.of(worktreePath);
            if (!root.isAbsolute() || !root.normalize().equals(root)
                    || !worktree.isAbsolute()
                    || !worktree.normalize().equals(worktree)) {
                throw new IllegalArgumentException(
                        "planning snapshot paths must be absolute and normalized");
            }
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
