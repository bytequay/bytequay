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
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.EffectResult;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Status;
import com.bytequay.app.domain.IssueOrigin;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.service.IssueOriginService;
import com.bytequay.app.service.threads.PublishService.PublishResult;
import com.bytequay.app.service.tools.ParkedProposal;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static java.util.Objects.requireNonNull;

/** Human approval and result boundary for durable V2 quality issue creation. */
@Service
public final class V2QualityIssuePublishRuntime
        implements ExecutionPorts.ResultDeliveryPort
{
    private final SqliteQualityIssuePublishStore store;
    private final IssueOriginService issueOrigins;
    private final TransactionTemplate transactions;
    private final ObjectReader results;
    private final Clock clock;

    @Autowired
    public V2QualityIssuePublishRuntime(
            SqliteQualityIssuePublishStore store,
            IssueOriginService issueOrigins,
            TransactionTemplate transactions,
            ObjectMapper json)
    {
        this(store, issueOrigins, transactions, json, Clock.systemUTC());
    }

    V2QualityIssuePublishRuntime(
            SqliteQualityIssuePublishStore store,
            IssueOriginService issueOrigins,
            TransactionTemplate transactions,
            ObjectMapper json,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.issueOrigins = requireNonNull(issueOrigins, "issueOrigins is null");
        this.transactions = requireNonNull(transactions, "transactions is null");
        this.results = requireNonNull(json, "json is null")
                .readerFor(EffectResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    public PublishResult approve(
            Notification notification,
            ParkedProposal.CreateIssue proposal,
            String editedBody)
    {
        String body = editedBody == null || editedBody.isBlank()
                ? proposal.body()
                : editedBody;
        Operation operation = store.authorize(
                notification, proposal, body, clock.instant());
        String message = operation.status() == Status.DELIVERED
                ? "Created issue #" + operation.issue().number() + "."
                : "Issue publication queued.";
        return new PublishResult(true, "approved", message, proposal.action());
    }

    public PublishResult discard(
            Notification notification, ParkedProposal.CreateIssue proposal)
    {
        store.discard(notification, clock.instant());
        return new PublishResult(
                true, "discarded", "Discarded.", proposal.action());
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
                || !QualityIssuePublishOperationHandler.CALLBACK_ROUTE.equals(
                        owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "{}");
        }
        Operation operation = store.require(expectedFence.operationId());
        requireOwner(owner, expectedFence, operation);
        String evidence = rawResult.evidenceJson() == null
                ? "{}" : rawResult.evidenceJson();
        if (rawResult.outcome() == CANCELED) {
            finish(operation, Status.CANCELED, evidence, rawResult.error(), null);
            return receipt(ACCEPTED, evidence);
        }
        if (rawResult.outcome() != SUCCEEDED) {
            finish(operation, Status.FAILED, evidence, rawResult.error(), null);
            return receipt(ACCEPTED, evidence);
        }
        if (!Objects.equals(rawResult.payloadJson(), rawResult.evidenceJson())) {
            throw new IllegalArgumentException(
                    "Quality issue payload and evidence differ");
        }
        EffectResult result = decode(rawResult.payloadJson());
        requireResult(operation, result);
        RepoIssue issue = new RepoIssue(
                result.issueId(), result.issueNumber(), result.issueTitle(),
                null, "open", result.issueUrl(), null, List.of(), 0,
                IssueOrigin.QUALITY_SCAN);
        finish(operation, Status.DELIVERED, evidence, null, issue);
        return receipt(ACCEPTED, evidence);
    }

    private void finish(
            Operation operation,
            Status outcome,
            String resultJson,
            String error,
            RepoIssue issue)
    {
        transactions.executeWithoutResult(ignored -> {
            if (issue != null && operation.deliveredAt() == null) {
                issueOrigins.recordCreated(issue, IssueOrigin.QUALITY_SCAN);
            }
            store.finishDelivery(
                    operation.operationId(), outcome, resultJson,
                    error, clock.instant());
        });
    }

    private EffectResult decode(String value)
    {
        try {
            return results.readValue(requireNonNull(value, "result payload is null"));
        }
        catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Quality issue result is not valid typed evidence", failure);
        }
    }

    private static void requireOwner(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            Operation operation)
    {
        if (!operation.taskId().equals(owner.id())
                || !operation.operationId().equals(fence.operationId())
                || !Objects.equals(operation.taskEpoch(), fence.taskEpoch())
                || fence.stageId() != null || fence.stageGeneration() != null
                || fence.attempt() != 1
                || fence.expectedCodeFingerprint() != null
                || fence.expectedHeadSha() != null
                || fence.expectedBaseSha() != null) {
            throw new IllegalArgumentException(
                    "Quality issue delivery differs from its exact owner");
        }
    }

    private static void requireResult(
            Operation operation, EffectResult result)
    {
        if (!operation.operationId().equals(result.operationId())
                || !operation.notificationId().equals(result.notificationId())
                || !operation.taskId().equals(result.taskId())
                || operation.taskEpoch() != result.taskEpoch()
                || operation.issue() == null
                || operation.issue().id() != result.issueId()
                || operation.issue().number() != result.issueNumber()
                || !Objects.equals(operation.issue().htmlUrl(), result.issueUrl())) {
            throw new IllegalArgumentException(
                    "Quality issue result differs from its frozen subject");
        }
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String evidence)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance, evidence == null ? "{}" : evidence);
    }
}
