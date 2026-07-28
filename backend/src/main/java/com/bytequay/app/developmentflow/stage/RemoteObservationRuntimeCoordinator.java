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
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Consumption;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.DeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.RemoteContext;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static java.util.Objects.requireNonNull;

/** Requests and synchronously folds exact Remote PR observations. */
public final class RemoteObservationRuntimeCoordinator
{
    private final TaskCommandExecutor commands;
    private final SqliteRemoteRuntimeStore store;
    private final RemoteObservationConsumer consumer;
    private final ObjectMapper json;
    private final ObjectReader observationReader;
    private final Clock clock;

    public RemoteObservationRuntimeCoordinator(
            TaskCommandExecutor commands,
            SqliteRemoteRuntimeStore store,
            RemoteObservationConsumer consumer,
            ObjectMapper json,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.store = requireNonNull(store, "store is null");
        this.consumer = requireNonNull(consumer, "consumer is null");
        this.json = requireNonNull(json, "json is null");
        this.observationReader = json.readerFor(
                        RemoteObservationOperationHandler.Observation.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    public ObservationRequest requestObservation(String taskId, String stageId)
    {
        requireText(taskId, "taskId");
        requireText(stageId, "stageId");
        return commands.execute(taskId,
                () -> requestObservationInCommand(taskId, stageId));
    }

    /** Opens the observation in the same transaction that made Remote current. */
    public ObservationRequest requestObservationInCommand(
            String taskId, String stageId)
    {
        requireText(taskId, "taskId");
        requireText(stageId, "stageId");
        TaskCommandExecutor.requireCurrent(taskId);
        ObservationRequest duplicate = store.findLiveObservation(stageId)
                .orElse(null);
        if (duplicate != null) {
            if (!taskId.equals(duplicate.taskId())) {
                throw new IllegalArgumentException(
                        "Remote Stage belongs to another Task");
            }
            return duplicate;
        }
        RemoteContext context = store.requireRemoteContext(taskId, stageId);
        return store.insertObservation(context, clock.instant());
    }

    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !RemoteObservationOperationHandler.CALLBACK_ROUTE.equals(
                        owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "Remote observation owner/fence is stale");
        }
        String taskId = store.requireObservationTaskId(
                expectedFence.operationId());
        return commands.execute(taskId, () -> deliverInCommand(
                owner, expectedFence, rawResult));
    }

    private DispatchTicket.DeliveryReceipt deliverInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        String rawDigest = digest(write(rawResult));
        DeliveryReceipt duplicate = store.findObservationReceipt(
                        expectedFence.operationId())
                .orElse(null);
        if (duplicate != null) {
            if (!rawDigest.equals(duplicate.rawDigest())) {
                throw new IllegalStateException(
                        "Remote observation was redelivered with different evidence");
            }
            return receipt(duplicate.acceptance(), evidence(duplicate));
        }

        ObservationDelivery context = store.requireObservationDelivery(
                expectedFence.operationId());
        if (!owner.id().equals(context.stageId())
                || !matches(expectedFence, context)) {
            return finishWithoutEvidence(
                    context, rawResult, rawDigest, SUPERSEDED,
                    "Remote observation operation fence is stale");
        }
        Instant now = clock.instant();
        if (rawResult.outcome() != SUCCEEDED) {
            DispatchTicket.Acceptance acceptance = context.current()
                    ? ACCEPTED : SUPERSEDED;
            store.finishObservation(
                    context, rawResult.outcome().name(), rawDigest,
                    acceptance.name(), null, rawResult.error(), now);
            return receipt(acceptance, rawResult.error());
        }

        RemoteObservationOperationHandler.Observation observation =
                decode(rawResult.payloadJson());
        RemoteCiPolicy.Evaluation evaluation = RemoteCiPolicy.evaluate(
                observation.checks(), context.requiredChecks(), context.ciPolicy());
        ObservationEvidence persisted = store.insertObservationEvidence(
                context, observation, evaluation);
        if (!context.current()) {
            store.finishObservation(
                    context, rawResult.outcome().name(), rawDigest,
                    SUPERSEDED.name(), persisted, "stale Remote subject", now);
            return receipt(SUPERSEDED, "stale Remote subject");
        }

        OneShotAcceptance acceptance = new OneShotAcceptance(
                () -> store.acceptObservation(context, persisted, now));
        Consumption consumed = requireNonNull(consumer.consume(
                new Candidate(context, observation, evaluation, persisted),
                acceptance), "Remote observation consumption is null");
        if ((consumed == Consumption.ACCEPTED) != acceptance.accepted()) {
            throw new IllegalStateException(
                    "Remote owner acceptance and subject acceptance disagree");
        }
        DispatchTicket.Acceptance result = consumed == Consumption.ACCEPTED
                ? ACCEPTED : SUPERSEDED;
        store.finishObservation(
                context, rawResult.outcome().name(), rawDigest, result.name(),
                persisted, null, now);
        return receipt(result, persisted.ciEvaluationId());
    }

    private DispatchTicket.DeliveryReceipt finishWithoutEvidence(
            ObservationDelivery context,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest,
            DispatchTicket.Acceptance acceptance,
            String reason)
    {
        // A successful observation must remain immutable history, even when
        // stale. A fence mismatch before decoding is rejected by the router.
        if (rawResult.outcome() == SUCCEEDED) {
            throw new IllegalArgumentException(reason);
        }
        store.finishObservation(
                context, rawResult.outcome().name(), rawDigest,
                acceptance.name(), null, reason, clock.instant());
        return receipt(acceptance, reason);
    }

    private static boolean matches(
            DispatchTicket.OperationFence fence, ObservationDelivery context)
    {
        return Objects.equals(fence.taskEpoch(), context.taskEpoch())
                && context.stageId().equals(fence.stageId())
                && Objects.equals(
                        fence.stageGeneration(), context.stageGeneration())
                && context.operationId().equals(fence.operationId())
                && fence.attempt() == context.semanticAttempt()
                && Objects.equals(
                        fence.expectedHeadSha(), context.expectedHeadSha())
                && Objects.equals(
                        fence.expectedBaseSha(), context.expectedBaseSha());
    }

    private RemoteObservationOperationHandler.Observation decode(String payload)
    {
        try {
            return observationReader.readValue(payload);
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Remote observation payload is invalid", e);
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize Remote observation evidence", e);
        }
    }

    private static String evidence(DeliveryReceipt receipt)
    {
        return receipt.ciEvaluationId() == null
                ? receipt.rawOutcome() : receipt.ciEvaluationId();
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String evidence)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                "{\"schema\":\"REMOTE_OBSERVATION_DELIVERY_V1\","
                        + "\"result\":\"" + escape(evidence) + "\"}");
    }

    private static String escape(String value)
    {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static final class OneShotAcceptance
            implements RemoteObservationConsumer.SubjectAcceptance
    {
        private final Runnable action;
        private boolean accepted;

        private OneShotAcceptance(Runnable action)
        {
            this.action = requireNonNull(action, "action is null");
        }

        @Override
        public void accept()
        {
            if (accepted) {
                throw new IllegalStateException(
                        "Remote subject was accepted more than once");
            }
            action.run();
            accepted = true;
        }

        private boolean accepted()
        {
            return accepted;
        }
    }
}
