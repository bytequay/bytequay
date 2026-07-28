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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static java.util.Objects.requireNonNull;

/** Executes finite Remote CI and no-conflict branch-sync effects. */
public final class RemoteEffectOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String RERUN_CI = "RERUN_REMOTE_CI";
    public static final String VALIDATE_CI_REPAIR = "VALIDATE_REMOTE_CI_REPAIR";
    public static final String PUSH_CI_REPAIR = "PUSH_REMOTE_CI_REPAIR";
    public static final String FETCH_BRANCH = "FETCH_COMPARE_REMOTE_BRANCH";
    public static final String REBASE_BRANCH = "REBASE_REMOTE_BRANCH";
    public static final String VALIDATE_BRANCH = "VALIDATE_REMOTE_BRANCH_SYNC";
    public static final String PUSH_BRANCH = "FORCE_WITH_LEASE_REMOTE_BRANCH";

    private static final Map<String, Shape> SHAPES = Map.of(
            RERUN_CI, new Shape(
                    DispatchTicket.AsyncFamily.GITHUB_EFFECT,
                    "REMOTE_CI_RERUN_RESULT"),
            VALIDATE_CI_REPAIR, new Shape(
                    DispatchTicket.AsyncFamily.VALIDATION,
                    "REMOTE_CI_VALIDATION_RESULT"),
            PUSH_CI_REPAIR, new Shape(
                    DispatchTicket.AsyncFamily.GITHUB_EFFECT,
                    "REMOTE_CI_PUSH_RESULT"),
            FETCH_BRANCH, new Shape(
                    DispatchTicket.AsyncFamily.LOCAL_GIT,
                    "BRANCH_SYNC_FETCH_RESULT"),
            REBASE_BRANCH, new Shape(
                    DispatchTicket.AsyncFamily.LOCAL_GIT,
                    "BRANCH_SYNC_REBASE_RESULT"),
            VALIDATE_BRANCH, new Shape(
                    DispatchTicket.AsyncFamily.VALIDATION,
                    "BRANCH_SYNC_VALIDATION_RESULT"),
            PUSH_BRANCH, new Shape(
                    DispatchTicket.AsyncFamily.LOCAL_GIT,
                    "BRANCH_SYNC_PUSH_RESULT"));

    private final Store store;
    private final EffectPort effects;
    private final WorktreeWriterLeaseManager writers;
    private final ObjectMapper json;

    public RemoteEffectOperationHandler(
            Store store,
            EffectPort effects,
            WorktreeWriterLeaseManager writers,
            ObjectMapper json)
    {
        this.store = requireNonNull(store, "store is null");
        this.effects = requireNonNull(effects, "effects is null");
        this.writers = requireNonNull(writers, "writers is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext execution)
            throws Exception
    {
        return perform(execution, Mode.EXECUTE);
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext execution)
            throws Exception
    {
        return perform(execution, Mode.PROBE);
    }

    private DispatchTicket.DispatchResult perform(
            ExecutionContext execution, Mode mode)
            throws Exception
    {
        requireNonNull(execution, "execution is null");
        OperationContext context = store.requireEffect(
                execution.envelope().fence().operationId());
        requireExact(execution.envelope(), context);
        if (execution.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Remote effect was canceled");
        }
        Result result;
        if (execution.envelope().capacityRequest().writerRequired()) {
            WorktreeWriterLeaseManager.Lease writer = writers.acquire(
                    execution, context.request().worktreePath());
            try {
                result = writers.authorizeMutation(execution, writer).run(fence -> {
                    try {
                        return effects.perform(
                                context.request(), mode, execution, fence);
                    }
                    catch (Exception failure) {
                        throw new EffectFailure(failure);
                    }
                });
            }
            catch (EffectFailure failure) {
                throw failure.cause();
            }
        }
        else {
            result = effects.perform(context.request(), mode, execution, null);
        }
        if (!context.operationId().equals(result.operationId())) {
            throw new IllegalArgumentException(
                    "Remote effect result belongs to another Operation");
        }
        String payload = write(result);
        return new DispatchTicket.DispatchResult(
                execution.envelope().fence(), SUCCEEDED, payload, payload, null);
    }

    private static void requireExact(
            DispatchTicket.DispatchEnvelope envelope, OperationContext context)
    {
        Shape shape = SHAPES.get(context.operationKind());
        DispatchTicket.OperationFence fence = envelope.fence();
        if (shape == null
                || !context.operationKind().equals(envelope.operationKind())
                || shape.family() != envelope.family()
                || envelope.owner().kind() != STAGE
                || !context.stageId().equals(envelope.owner().id())
                || !shape.callbackRoute().equals(
                        envelope.owner().callbackRoute())
                || !context.operationId().equals(fence.operationId())
                || !Long.valueOf(context.taskEpoch()).equals(fence.taskEpoch())
                || !context.stageId().equals(fence.stageId())
                || !Long.valueOf(context.stageGeneration()).equals(
                        fence.stageGeneration())
                || context.semanticAttempt() != fence.attempt()
                || !context.expectedHeadSha().equals(fence.expectedHeadSha())
                || !context.expectedBaseSha().equals(fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Remote effect ticket differs from its exact Operation");
        }
    }

    private String write(Result result)
    {
        try {
            return json.writeValueAsString(result);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Remote effect", e);
        }
    }

    public static boolean supports(String operationKind)
    {
        return SHAPES.containsKey(operationKind);
    }

    @FunctionalInterface
    public interface Store
    {
        OperationContext requireEffect(String operationId);
    }

    @FunctionalInterface
    public interface EffectPort
    {
        Result perform(
                Request request,
                Mode mode,
                ExecutionContext execution,
                WorktreeWriterLeaseManager.MutationFence writerFence)
                throws Exception;
    }

    public enum Mode
    {
        EXECUTE,
        PROBE
    }

    public enum Disposition
    {
        SUCCEEDED,
        FAILED,
        CONFLICT
    }

    public record OperationContext(
            String operationId,
            String operationKind,
            String stageId,
            long taskEpoch,
            long stageGeneration,
            int semanticAttempt,
            String expectedHeadSha,
            String expectedBaseSha,
            Request request)
    {
        public OperationContext
        {
            requireText(operationId, "operationId");
            requireText(operationKind, "operationKind");
            requireText(stageId, "stageId");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(expectedBaseSha, "expectedBaseSha");
            requireNonNull(request, "request is null");
            if (taskEpoch < 1 || stageGeneration < 1 || semanticAttempt < 1) {
                throw new IllegalArgumentException(
                        "Remote effect Operation identity is invalid");
            }
        }
    }

    public record Request(
            String operationId,
            String operationKind,
            String taskId,
            String stageId,
            String repositoryId,
            int pullRequestNumber,
            String worktreePath,
            String headRef,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String targetBaseSha,
            String forceWithLeaseExpectedSha,
            String idempotencyKey)
    {
        public Request
        {
            requireText(operationId, "operationId");
            requireText(operationKind, "operationKind");
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(repositoryId, "repositoryId");
            requireText(worktreePath, "worktreePath");
            requireText(headRef, "headRef");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(expectedBaseSha, "expectedBaseSha");
            requireText(idempotencyKey, "idempotencyKey");
            if ((PUSH_BRANCH.equals(operationKind)
                    || PUSH_CI_REPAIR.equals(operationKind))) {
                requireText(forceWithLeaseExpectedSha,
                        "forceWithLeaseExpectedSha");
            }
            if (pullRequestNumber < 1) {
                throw new IllegalArgumentException(
                        "pullRequestNumber must be positive");
            }
        }
    }

    public record Result(
            int schemaVersion,
            String operationId,
            Disposition disposition,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String evidence,
            String error)
    {
        public Result
        {
            if (schemaVersion != 1) {
                throw new IllegalArgumentException(
                        "Unsupported Remote effect result schema");
            }
            requireText(operationId, "operationId");
            requireNonNull(disposition, "disposition is null");
            if (disposition == Disposition.SUCCEEDED) {
                requireText(evidence, "evidence");
            }
        }
    }

    private record Shape(
            DispatchTicket.AsyncFamily family, String callbackRoute) {}

    private static final class EffectFailure
            extends RuntimeException
    {
        private final Exception cause;

        private EffectFailure(Exception cause)
        {
            super(cause);
            this.cause = cause;
        }

        private Exception cause()
        {
            return cause;
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
