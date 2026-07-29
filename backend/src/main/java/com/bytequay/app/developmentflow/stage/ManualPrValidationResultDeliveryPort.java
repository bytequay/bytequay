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
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.ManualPrValidationOperationHandler.ValidationResult;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.ExecutionContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.Operation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.Status;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static java.util.Objects.requireNonNull;

/** Accepts one Manual PR validation result against its persisted Task subject. */
@Component
public final class ManualPrValidationResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final SqliteManualPrValidationStore store;
    private final ObjectReader reader;
    private final Clock clock;

    @Autowired
    public ManualPrValidationResultDeliveryPort(
            SqliteManualPrValidationStore store, ObjectMapper json)
    {
        this(store, json, Clock.systemUTC());
    }

    ManualPrValidationResultDeliveryPort(
            SqliteManualPrValidationStore store, ObjectMapper json, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.reader = requireNonNull(json, "json is null")
                .readerFor(ValidationResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != TASK
                || !ManualPrValidationOperationHandler.CALLBACK_ROUTE.equals(
                        owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "{}");
        }
        ExecutionContext context = store.requireExecutionContext(
                expectedFence.operationId());
        requireOwner(owner, expectedFence, context);
        Operation existing = store.requireOperation(context.operationId());
        if (existing.terminal()) {
            return receipt(
                    existing.status() == Status.SUPERSEDED ? SUPERSEDED : ACCEPTED,
                    existing.resultJson());
        }

        String evidence = rawResult.evidenceJson() == null
                ? "{}" : rawResult.evidenceJson();
        if (rawResult.outcome() == CANCELED) {
            store.finish(context.operationId(), Status.CANCELED.name(),
                    evidence, rawResult.error(), clock.instant());
            return receipt(ACCEPTED, evidence);
        }
        if (rawResult.outcome() != SUCCEEDED) {
            store.finish(context.operationId(), Status.FAILED.name(),
                    evidence, rawResult.error(), clock.instant());
            return receipt(ACCEPTED, evidence);
        }
        if (!Objects.equals(rawResult.payloadJson(), rawResult.evidenceJson())) {
            throw new IllegalArgumentException(
                    "Manual PR validation payload and evidence differ");
        }
        ValidationResult result = decode(rawResult.payloadJson());
        requireResult(context, result);
        if (!context.current() || !result.subjectCurrent()) {
            store.finish(context.operationId(), Status.SUPERSEDED.name(),
                    evidence, null, clock.instant());
            return receipt(SUPERSEDED, evidence);
        }
        store.finish(context.operationId(), Status.COMPLETED.name(),
                evidence, null, clock.instant());
        return receipt(ACCEPTED, evidence);
    }

    private ValidationResult decode(String value)
    {
        try {
            return reader.readValue(requireNonNull(value, "result payload is null"));
        }
        catch (IOException e) {
            throw new IllegalArgumentException(
                    "Manual PR validation returned invalid typed evidence", e);
        }
    }

    private static void requireOwner(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            ExecutionContext context)
    {
        if (!context.taskId().equals(owner.id())
                || !context.operationId().equals(fence.operationId())
                || !Objects.equals(context.taskEpoch(), fence.taskEpoch())
                || fence.stageId() != null || fence.stageGeneration() != null
                || fence.attempt() != 1
                || !context.codeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                || !context.headSha().equals(fence.expectedHeadSha())
                || !context.baseSha().equals(fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Manual PR validation delivery differs from its owner");
        }
    }

    private static void requireResult(
            ExecutionContext context, ValidationResult result)
    {
        if (!context.operationId().equals(result.operationId())
                || !context.prId().equals(result.prId())
                || !context.taskId().equals(result.taskId())
                || context.taskEpoch() != result.taskEpoch()
                || result.subjectCurrent() && (
                    !context.codeFingerprint().equals(
                            result.observedCodeFingerprint())
                    || !context.headSha().equals(result.observedHeadSha())
                    || !context.baseSha().equals(result.observedBaseSha()))) {
            throw new IllegalArgumentException(
                    "Manual PR validation result differs from its exact subject");
        }
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String evidence)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance, evidence == null ? "{}" : evidence);
    }
}
