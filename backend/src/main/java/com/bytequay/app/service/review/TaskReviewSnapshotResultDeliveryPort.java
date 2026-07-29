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
package com.bytequay.app.service.review;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.service.review.TaskReviewSnapshotOperationHandler.SnapshotResult;
import com.bytequay.app.service.review.TaskReviewSnapshotRuntime.ExecutionSubject;
import com.bytequay.app.service.review.TaskReviewSnapshotRuntime.Status;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static java.util.Objects.requireNonNull;

/** Delivers one immutable Task review snapshot back to the AgentReview owner. */
@Component
public final class TaskReviewSnapshotResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final TaskReviewSnapshotRuntime operations;
    private final ObjectProvider<InvestigationReviewService> reviews;
    private final TaskCommandExecutor commands;
    private final ObjectReader reader;

    public TaskReviewSnapshotResultDeliveryPort(
            TaskReviewSnapshotRuntime operations,
            ObjectProvider<InvestigationReviewService> reviews,
            TaskCommandExecutor commands,
            ObjectMapper json)
    {
        this.operations = requireNonNull(operations, "operations is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.reader = requireNonNull(json, "json is null")
                .readerFor(SnapshotResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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
                || !TaskReviewSnapshotOperationHandler.CALLBACK_ROUTE.equals(
                        owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "{}");
        }
        ExecutionSubject subject = operations.requireExecutionSubject(
                expectedFence.operationId());
        requireOwner(owner, expectedFence, subject);
        if (subject.terminal()) {
            requireTerminalReplay(subject, rawResult);
            return receipt(subject.status() == Status.SUPERSEDED
                    ? SUPERSEDED : ACCEPTED, subject.resultJson());
        }

        String evidence = rawResult.evidenceJson() == null
                ? "{}" : rawResult.evidenceJson();
        if (rawResult.outcome() == CANCELED) {
            operations.finishTerminal(subject.operationId(), Status.CANCELED,
                    evidence, rawResult.error());
            return receipt(ACCEPTED, evidence);
        }
        if (rawResult.outcome() != SUCCEEDED) {
            operations.finishTerminal(subject.operationId(), Status.FAILED,
                    evidence, rawResult.error());
            return receipt(ACCEPTED, evidence);
        }
        if (!Objects.equals(rawResult.payloadJson(), rawResult.evidenceJson())) {
            throw new IllegalArgumentException(
                    "Task review snapshot payload and evidence differ");
        }
        SnapshotResult result = decode(rawResult.payloadJson());
        requireResult(subject, result);
        if (!subject.current() || !result.subjectCurrent()) {
            operations.finishTerminal(subject.operationId(), Status.SUPERSEDED,
                    evidence, "Task code changed before review snapshot delivery");
            return receipt(SUPERSEDED, evidence);
        }
        commands.executeVoid(subject.taskId(), () ->
                reviews.getObject().acceptTaskReviewSnapshot(subject, result));
        return receipt(ACCEPTED, evidence);
    }

    private SnapshotResult decode(String value)
    {
        try {
            return reader.readValue(requireNonNull(value, "result payload is null"));
        }
        catch (IOException e) {
            throw new IllegalArgumentException(
                    "Task review snapshot returned invalid typed evidence", e);
        }
    }

    private static void requireTerminalReplay(
            ExecutionSubject subject, DispatchTicket.DispatchResult result)
    {
        String evidence = result.evidenceJson() == null
                ? "{}" : result.evidenceJson();
        boolean exact = Objects.equals(subject.resultJson(), evidence)
                && switch (subject.status()) {
                    case COMPLETED, SUPERSEDED -> result.outcome() == SUCCEEDED
                            && Objects.equals(result.payloadJson(), evidence)
                            && result.error() == null;
                    case CANCELED -> result.outcome() == CANCELED
                            && Objects.equals(subject.error(), result.error());
                    case FAILED -> result.outcome() != SUCCEEDED
                            && result.outcome() != CANCELED
                            && Objects.equals(subject.error(), result.error());
                    case REQUESTED -> false;
                };
        if (!exact) {
            throw new IllegalStateException(
                    "Task review snapshot terminal replay differs from receipt");
        }
    }

    private static void requireOwner(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            ExecutionSubject subject)
    {
        if (!subject.taskId().equals(owner.id())
                || !subject.operationId().equals(fence.operationId())
                || !Objects.equals(subject.taskEpoch(), fence.taskEpoch())
                || fence.stageId() != null || fence.stageGeneration() != null
                || fence.attempt() != 1
                || !subject.codeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                || !subject.headSha().equals(fence.expectedHeadSha())
                || !subject.baseSha().equals(fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Task review snapshot delivery differs from its owner");
        }
    }

    private static void requireResult(
            ExecutionSubject subject, SnapshotResult result)
    {
        if (!subject.operationId().equals(result.operationId())
                || !subject.reviewId().equals(result.reviewId())
                || !subject.prId().equals(result.prId())
                || !subject.taskId().equals(result.taskId())
                || !Objects.equals(subject.repository(), result.repository())
                || !Objects.equals(
                    subject.remotePrNumber(), result.remotePrNumber())
                || !subject.baseBranch().equals(result.baseBranch())
                || !subject.prTitle().equals(result.prTitle())
                || !subject.prDescription().equals(result.prDescription())
                || subject.taskEpoch() != result.taskEpoch()
                || !subject.worktreePath().equals(result.worktreePath())
                || !subject.codeFingerprint().equals(result.codeFingerprint())
                || !subject.headSha().equals(result.headSha())
                || !subject.baseSha().equals(result.baseSha())
                || result.subjectCurrent() && (
                    !subject.codeFingerprint().equals(
                            result.observedCodeFingerprint())
                    || !subject.headSha().equals(result.observedHeadSha()))) {
            throw new IllegalArgumentException(
                    "Task review snapshot result differs from its exact subject");
        }
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String evidence)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance, evidence == null ? "{}" : evidence);
    }
}
