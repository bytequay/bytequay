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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentTurn;
import com.bytequay.core.context.ContextSelectedEntry;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewOperation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Everything the pull-request pane shows for one review, read from durable facts. Runs are sent
 * without their frozen patches; the diff the user sees is the Changes tab's own.
 *
 * @param byteQuayTurnIds the turns ByteQuay started with review requests, whose input the transcript attributes to ByteQuay
 * @param queuedInputIds queue rows still waiting, so a question that names one is shown as queued
 * @param pullRequest the stored row for the reviewed pull request, which names it in lists; absent until ByteQuay has stored it
 */
public record AgentReviewProjection(
        AgentReview review,
        List<RunView> runs,
        List<ReviewFinding> findings,
        List<MessageView> messages,
        TaskView task,
        Optional<TurnView> activeTurn,
        List<String> queuedInputIds,
        int pendingApprovals,
        List<String> byteQuayTurnIds,
        Optional<ReviewDraft> draft,
        Optional<PullRequest> pullRequest)
{
    private static final ObjectMapper ACTIVITY_MAPPER = new ObjectMapper();

    public AgentReviewProjection
    {
        requireNonNull(review, "review is null");
        runs = ImmutableList.copyOf(runs);
        findings = ImmutableList.copyOf(findings);
        messages = ImmutableList.copyOf(messages);
        requireNonNull(task, "task is null");
        requireNonNull(activeTurn, "activeTurn is null");
        queuedInputIds = ImmutableList.copyOf(queuedInputIds);
        byteQuayTurnIds = ImmutableList.copyOf(byteQuayTurnIds);
        requireNonNull(draft, "draft is null");
        requireNonNull(pullRequest, "pullRequest is null");
    }

    /** Saved discussion content with the current delivery identities from its accepted input. */
    public record MessageView(ReviewMessage message, Optional<String> turnId, Optional<String> messageEventId)
    {
        public MessageView
        {
            requireNonNull(message, "message is null");
            requireNonNull(turnId, "turnId is null");
            requireNonNull(messageEventId, "messageEventId is null");
        }
    }

    public record RunView(
            String id,
            int ordinal,
            ReviewRun.Scope scope,
            String baseRef,
            String baseSha,
            String mergeBaseSha,
            String headSha,
            Optional<String> priorHeadSha,
            List<ReviewCommit> commits,
            int changedFiles,
            List<String> turnIds,
            ReviewRun.State state,
            Optional<ReviewSummary> summary,
            Optional<ReviewCoverage> coverage,
            Optional<ReviewInvestigation> investigation,
            List<ReviewStep> steps,
            List<ReviewEvidence> evidence,
            Optional<ReviewContextSnapshot> context,
            Map<String, ContextDeliveryState> contextDeliveryStates,
            List<ContextPreparationActivity> contextPreparationActivities,
            List<ContextNoteRead> contextNoteReads,
            boolean completionRepairUsed,
            Optional<String> outcome,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> endedAt)
    {
        public RunView
        {
            contextDeliveryStates = ImmutableMap.copyOf(contextDeliveryStates);
            contextPreparationActivities = ImmutableList.copyOf(contextPreparationActivities);
            contextNoteReads = ImmutableList.copyOf(contextNoteReads);
        }

        public static RunView of(ReviewRun run, Map<String, ContextDeliveryState> deliveryStates)
        {
            return new RunView(
                    run.id(),
                    run.ordinal(),
                    run.scope(),
                    run.baseRef(),
                    run.baseSha(),
                    run.mergeBaseSha(),
                    run.headSha(),
                    run.priorHeadSha(),
                    run.commits(),
                    run.diff().size(),
                    run.turnIds(),
                    run.state(),
                    run.summary(),
                    run.coverage(),
                    run.investigation(),
                    run.steps(),
                    ImmutableList.copyOf(run.evidence().values()),
                    run.context(),
                    deliveryStates,
                    preparationActivities(run.id(), run.operations()),
                    noteReads(run),
                    run.completionRepairUsed(),
                    run.outcome(),
                    run.createdAt(),
                    run.updatedAt(),
                    run.endedAt());
        }
    }

    /** Exact note reads retained after initial selection; the frozen bootstrap remains unchanged. */
    public record ContextNoteRead(
            String entryId,
            long entryVersion,
            Status status,
            Optional<String> text,
            Optional<String> evidenceRef,
            Map<String, String> sourceEvidenceRefs)
    {
        public ContextNoteRead
        {
            requireNonNull(entryId, "entryId is null");
            requireNonNull(status, "status is null");
            requireNonNull(text, "text is null");
            requireNonNull(evidenceRef, "evidenceRef is null");
            sourceEvidenceRefs = ImmutableMap.copyOf(sourceEvidenceRefs);
        }

        public enum Status
        {
            AVAILABLE,
            RECEIPT_UNAVAILABLE
        }
    }

    static List<ContextNoteRead> noteReads(ReviewRun run)
    {
        return run.context().stream().flatMap(snapshot -> snapshot.selectedEntries().stream())
                .map(selected -> noteRead(selected, run.operations(), run.evidence())).flatMap(Optional::stream).collect(toImmutableList());
    }

    static Optional<ContextNoteRead> noteRead(ContextSelectedEntry selected, Map<String, ReviewOperation> operations, Map<String, ReviewEvidence> evidence)
    {
        String digest = Hashing.sha256().hashString(selected.entryId() + "\n" + selected.entryVersion(), StandardCharsets.UTF_8).toString();
        String key = "context-note:" + digest;
        Optional<ReviewOperation> operation = Optional.ofNullable(operations.get(key));
        if (operation.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode result = ACTIVITY_MAPPER.readTree(operation.orElseThrow().result());
            checkArgument(operation.orElseThrow().payloadDigest().equals(digest) && result.path("status").asText().equals("AVAILABLE")
                    && result.path("entryId").asText().equals(selected.entryId()) && result.path("entryVersion").asLong() == selected.entryVersion()
                    && result.path("evidenceRef").asText().equals(key), "Note receipt identity differs");
            String text = activityText(result, "text").orElseThrow();
            checkArgument(evidence.containsKey(key) && evidence.get(key).excerpt().equals(text), "Note evidence differs");
            JsonNode sources = result.path("sourceEvidenceRefs");
            checkArgument(sources.isObject(), "Missing note source references");
            ImmutableMap.Builder<String, String> references = ImmutableMap.builder();
            sources.fields().forEachRemaining(entry -> {
                checkArgument(entry.getValue().isTextual() && evidence.containsKey(entry.getValue().textValue()), "Note source evidence is unavailable");
                references.put(entry.getKey(), entry.getValue().textValue());
            });
            return Optional.of(new ContextNoteRead(selected.entryId(), selected.entryVersion(), ContextNoteRead.Status.AVAILABLE,
                    Optional.of(text), Optional.of(key), references.buildOrThrow()));
        }
        catch (JsonProcessingException | RuntimeException failure) {
            return Optional.of(new ContextNoteRead(selected.entryId(), selected.entryVersion(), ContextNoteRead.Status.RECEIPT_UNAVAILABLE,
                    Optional.empty(), Optional.empty(), ImmutableMap.of()));
        }
    }

    /** Display-only facts from retained tool results; no activity observation changes replay or run state. */
    public record ContextPreparationActivity(
            String operationRef,
            Status status,
            Optional<String> attemptId,
            Optional<Instant> recordedAt,
            Optional<Long> elapsedMillis,
            List<PreparationScope> scopes,
            List<String> gaps)
    {
        public ContextPreparationActivity
        {
            requireNonNull(operationRef, "operationRef is null");
            requireNonNull(status, "status is null");
            requireNonNull(attemptId, "attemptId is null");
            requireNonNull(recordedAt, "recordedAt is null");
            requireNonNull(elapsedMillis, "elapsedMillis is null");
            scopes = ImmutableList.copyOf(scopes);
            gaps = ImmutableList.copyOf(gaps);
        }

        public enum Status
        {
            OWNED,
            REUSED,
            JOINED,
            DEFERRED,
            APPLIED,
            UNAVAILABLE
        }
    }

    public record PreparationScope(String scopeId, Optional<String> outcome, String explanation, List<String> noteIds, List<String> checkedSourceIds)
    {
        public PreparationScope
        {
            requireNonNull(scopeId, "scopeId is null");
            requireNonNull(outcome, "outcome is null");
            requireNonNull(explanation, "explanation is null");
            noteIds = ImmutableList.copyOf(noteIds);
            checkedSourceIds = ImmutableList.copyOf(checkedSourceIds);
        }
    }

    static List<ContextPreparationActivity> preparationActivities(String runId, Map<String, ReviewOperation> operations)
    {
        return operations.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("context-activity:")
                        || entry.getKey().startsWith("review-step:" + runId + ":") && entry.getKey().contains(":repository_context_prepare:"))
                .map(entry -> preparationActivity(entry.getKey(), entry.getValue().result()))
                .collect(toImmutableList());
    }

    private static ContextPreparationActivity preparationActivity(String reference, String result)
    {
        try {
            JsonNode value = ACTIVITY_MAPPER.readTree(result);
            ContextPreparationActivity.Status status = ContextPreparationActivity.Status.valueOf(value.path("status").asText());
            Optional<Instant> recordedAt = activityText(value, "recordedAt").map(Instant::parse);
            JsonNode elapsed = value.path("elapsedMillis");
            checkArgument(elapsed.isMissingNode() || elapsed.isNull() || elapsed.isIntegralNumber() && elapsed.canConvertToLong() && elapsed.longValue() >= 0,
                    "Invalid recorded preparation duration");
            Optional<Long> elapsedMillis = elapsed.isIntegralNumber() ? Optional.of(elapsed.longValue()) : Optional.empty();
            JsonNode attempt = value.path("attempt");
            Optional<String> attemptId = activityText(value, "attemptId").or(() -> activityText(attempt, "attemptId"));
            ImmutableList.Builder<PreparationScope> scopes = ImmutableList.builder();
            for (JsonNode scope : attempt.path("scopes")) {
                scopes.add(new PreparationScope(activityText(scope, "scopeId").orElseThrow(), Optional.empty(), "", ImmutableList.of(), ImmutableList.of()));
            }
            for (String field : ImmutableList.of("reused", "scopeResults")) {
                for (JsonNode scope : value.path(field)) {
                    String outcome = activityText(scope, "outcome").orElseThrow();
                    checkArgument(ImmutableList.of("READY", "EMPTY", "FAILED").contains(outcome), "Invalid preparation scope outcome");
                    scopes.add(new PreparationScope(activityText(scope, "scopeId").orElseThrow(), Optional.of(outcome),
                            activityText(scope, "explanation").orElse(""), activityStrings(scope, "noteIds"), activityStrings(scope, "checkedSourceIds")));
                }
            }
            return new ContextPreparationActivity(reference, status, attemptId, recordedAt, elapsedMillis, scopes.build(), activityStrings(value, "gaps"));
        }
        catch (JsonProcessingException | RuntimeException failure) {
            return new ContextPreparationActivity(reference, ContextPreparationActivity.Status.UNAVAILABLE, Optional.empty(), Optional.empty(), Optional.empty(),
                    ImmutableList.of(), ImmutableList.of("The saved preparation receipt could not be read. Its outcome and timing are unavailable."));
        }
    }

    private static Optional<String> activityText(JsonNode value, String field)
    {
        JsonNode text = value.path(field);
        checkArgument(text.isMissingNode() || text.isNull() || text.isTextual(), "Invalid preparation text");
        return text.isTextual() ? Optional.of(text.textValue()) : Optional.empty();
    }

    private static List<String> activityStrings(JsonNode value, String field)
    {
        JsonNode values = value.path(field);
        checkArgument(values.isMissingNode() || values.isArray(), "Invalid preparation list");
        ImmutableList.Builder<String> result = ImmutableList.builder();
        for (JsonNode item : values) {
            checkArgument(item.isTextual(), "Invalid preparation list item");
            result.add(item.textValue());
        }
        return result.build();
    }

    /** Delivery labels derive only from durable provider receipts; runtime cleanup never establishes acceptance. */
    public record ContextDeliveryState(Outcome outcome, Optional<String> detail)
    {
        public ContextDeliveryState
        {
            requireNonNull(outcome, "outcome is null");
            requireNonNull(detail, "detail is null");
        }

        public static ContextDeliveryState of(Optional<AgentTurn> turn)
        {
            if (turn.isEmpty()) {
                return new ContextDeliveryState(Outcome.UNCERTAIN, Optional.of("The provider receipt is unavailable."));
            }
            AgentTurn value = turn.orElseThrow();
            if (value.providerTurnReference().isPresent()) {
                return new ContextDeliveryState(Outcome.CONFIRMED, Optional.empty());
            }
            if (value.knownUnsent() || value.startedAt().isEmpty()) {
                return new ContextDeliveryState(Outcome.PREPARED, Optional.of(value.state().terminal()
                        ? "Not sent: the turn ended before provider submission." : "Prepared before provider submission."));
            }
            return value.state().active()
                    ? new ContextDeliveryState(Outcome.SUBMITTED, Optional.of("Submission started; provider acceptance is not yet recorded."))
                    : new ContextDeliveryState(Outcome.UNCERTAIN, Optional.of("Submission may have reached the provider; acceptance could not be confirmed."));
        }

        public enum Outcome
        {
            PREPARED,
            SUBMITTED,
            CONFIRMED,
            UNCERTAIN
        }
    }

    /** The task facts the pane derives Running, Waiting for approval, Stopped and Ready from. */
    public record TaskView(
            String id,
            String workspaceId,
            String state,
            long version,
            String laneId,
            String laneLabel,
            Optional<String> model,
            Optional<String> mutationClaim,
            boolean steering,
            boolean nestedAgents) {}

    public record TurnView(String id, String state, Optional<Instant> startedAt, Optional<Instant> cancellationRequestedAt) {}
}
