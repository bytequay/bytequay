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
package com.bytequay.app.developmentflow.execution.quality;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.domain.RepoIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.GITHUB_EFFECT;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static java.util.Objects.requireNonNull;

/** Executes one human-authorized quality-scan issue through the GITHUB lane. */
public final class QualityIssuePublishOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "PUBLISH_V2_QUALITY_ISSUE";
    public static final String CALLBACK_ROUTE = "V2_QUALITY_ISSUE_RESULT";

    private final OperationStore store;
    private final Gateway github;
    private final ObjectMapper json;
    private final Clock clock;

    public QualityIssuePublishOperationHandler(
            OperationStore store, Gateway github, ObjectMapper json, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.github = requireNonNull(github, "github is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        Operation operation = store.require(
                context.envelope().fence().operationId());
        requireEnvelope(context.envelope(), operation);
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Quality issue publication was canceled before execution");
        }
        return switch (operation.status()) {
            case SUCCEEDED, DELIVERED -> succeeded(
                    context.envelope().fence(), operation, operation.issue());
            case FAILED -> failed(context.envelope().fence(), operation.lastError());
            case CANCELED -> throw new ExecutionPorts.OperationCanceledException(
                    "Quality issue publication was canceled");
            case EXECUTING, INDETERMINATE -> reconcile(context);
            case REQUESTED -> executeRequested(context, operation);
        };
    }

    private DispatchTicket.DispatchResult executeRequested(
            ExecutionContext context, Operation operation)
            throws Exception
    {
        Optional<RepoIssue> replay;
        try {
            replay = github.findExisting(operation);
        }
        catch (Exception failure) {
            throw new ExecutionPorts.RetryableExecutionException(
                    "Could not check quality issue idempotency marker", failure);
        }
        if (replay.isPresent()) {
            Operation succeeded = store.markSucceeded(
                    operation.operationId(), replay.orElseThrow(), clock.instant());
            return succeeded(context.envelope().fence(), succeeded, succeeded.issue());
        }
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Quality issue publication was canceled before mutation");
        }
        Operation executing = store.markExecuting(
                operation.operationId(), clock.instant());
        try {
            RepoIssue created = github.create(executing);
            Operation succeeded = store.markSucceeded(
                    executing.operationId(), created, clock.instant());
            return succeeded(context.envelope().fence(), succeeded, created);
        }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().is4xxClientError()) {
                store.markFailed(executing.operationId(), message(failure), clock.instant());
                return failed(context.envelope().fence(), message(failure));
            }
            store.markIndeterminate(
                    executing.operationId(), message(failure), clock.instant());
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Quality issue creation outcome is unknown", failure);
        }
        catch (Exception failure) {
            store.markIndeterminate(
                    executing.operationId(), message(failure), clock.instant());
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Quality issue creation outcome is unknown", failure);
        }
    }

    /** Recovery probes the immutable marker and never repeats createIssue. */
    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        Operation operation = store.require(
                context.envelope().fence().operationId());
        requireEnvelope(context.envelope(), operation);
        if (operation.status() == Status.SUCCEEDED
                || operation.status() == Status.DELIVERED) {
            return succeeded(context.envelope().fence(), operation, operation.issue());
        }
        if (operation.status() == Status.FAILED) {
            return failed(context.envelope().fence(), operation.lastError());
        }
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Quality issue reconciliation was canceled");
        }
        Optional<RepoIssue> observed = github.findExisting(operation);
        if (observed.isEmpty()) {
            if (operation.status() == Status.EXECUTING) {
                store.markIndeterminate(operation.operationId(),
                        "Remote issue marker was not observed", clock.instant());
            }
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Quality issue creation remains unconfirmed; create will not be repeated");
        }
        Operation succeeded = store.markSucceeded(
                operation.operationId(), observed.orElseThrow(), clock.instant());
        return succeeded(context.envelope().fence(), succeeded, succeeded.issue());
    }

    private DispatchTicket.DispatchResult succeeded(
            DispatchTicket.OperationFence fence,
            Operation operation,
            RepoIssue issue)
    {
        requireNonNull(issue, "confirmed issue is null");
        EffectResult result = new EffectResult(
                1, operation.operationId(), operation.notificationId(),
                operation.taskId(), operation.taskEpoch(), issue.id(),
                issue.number(), issue.htmlUrl(), issue.title());
        try {
            String value = json.writeValueAsString(result);
            return new DispatchTicket.DispatchResult(
                    fence, DispatchTicket.Outcome.SUCCEEDED,
                    value, value, null);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Could not encode quality issue result", failure);
        }
    }

    private static DispatchTicket.DispatchResult failed(
            DispatchTicket.OperationFence fence, String error)
    {
        return new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.FAILED, null, "{}",
                error == null ? "Quality issue publication failed" : error);
    }

    private static void requireEnvelope(
            DispatchTicket.DispatchEnvelope envelope, Operation operation)
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.family() != GITHUB_EFFECT
                || envelope.owner().kind() != TASK
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !operation.taskId().equals(envelope.owner().id())
                || !operation.operationId().equals(fence.operationId())
                || !Objects.equals(operation.taskEpoch(), fence.taskEpoch())
                || fence.stageId() != null || fence.stageGeneration() != null
                || fence.attempt() != 1
                || fence.expectedCodeFingerprint() != null
                || fence.expectedHeadSha() != null
                || fence.expectedBaseSha() != null) {
            throw new IllegalArgumentException(
                    "Quality issue ticket differs from its exact Task owner");
        }
    }

    private static String message(Throwable failure)
    {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    public interface Gateway
    {
        Optional<RepoIssue> findExisting(Operation operation);

        RepoIssue create(Operation operation);
    }

    public interface OperationStore
    {
        Operation require(String operationId);

        Operation markExecuting(String operationId, Instant now);

        Operation markSucceeded(String operationId, RepoIssue issue, Instant now);

        Operation markIndeterminate(String operationId, String error, Instant now);

        Operation markFailed(String operationId, String error, Instant now);
    }

    public enum Status
    {
        REQUESTED,
        EXECUTING,
        SUCCEEDED,
        INDETERMINATE,
        FAILED,
        CANCELED,
        DELIVERED
    }

    public record Operation(
            String id,
            String operationId,
            String notificationId,
            String taskId,
            long taskEpoch,
            String workspaceId,
            String trunkId,
            String repoOwner,
            String repoName,
            String title,
            String body,
            String marker,
            String payloadDigest,
            Status status,
            RepoIssue issue,
            String lastError,
            String resultJson,
            Instant deliveredAt,
            String ticketId)
    {
        public Operation
        {
            requireNonNull(id, "id is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(notificationId, "notificationId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(status, "status is null");
            if (taskEpoch < 1) {
                throw new IllegalArgumentException("taskEpoch must be positive");
            }
            if ((status == Status.SUCCEEDED || status == Status.DELIVERED)
                    != (issue != null)) {
                throw new IllegalArgumentException(
                        "confirmed quality issue evidence is inconsistent");
            }
            if (deliveredAt != null && resultJson == null) {
                throw new IllegalArgumentException(
                        "delivered quality issue result is missing");
            }
        }
    }

    public record EffectResult(
            int schemaVersion,
            String operationId,
            String notificationId,
            String taskId,
            long taskEpoch,
            long issueId,
            int issueNumber,
            String issueUrl,
            String issueTitle)
    {
        public EffectResult
        {
            if (schemaVersion != 1 || taskEpoch < 1
                    || issueId < 1 || issueNumber < 1) {
                throw new IllegalArgumentException(
                        "Quality issue result identity is invalid");
            }
        }
    }
}
