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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.REMOTE_OBSERVATION;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static java.util.Objects.requireNonNull;

/** Performs one capacity-bounded, read-only observation of a remote PR. */
public final class RemoteObservationOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "OBSERVE_REMOTE_PR";
    public static final String CALLBACK_ROUTE = "REMOTE_OBSERVATION_RESULT";

    private final Store store;
    private final Observer observer;
    private final ObjectMapper json;

    public RemoteObservationOperationHandler(
            Store store, Observer observer, ObjectMapper json)
    {
        this.store = requireNonNull(store, "store is null");
        this.observer = requireNonNull(observer, "observer is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext execution)
            throws Exception
    {
        requireNonNull(execution, "execution is null");
        OperationContext context = store.requireObservation(
                execution.envelope().fence().operationId());
        requireExact(execution.envelope(), context);
        if (execution.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Remote observation was canceled");
        }
        Observation observation = observer.observe(context.request(), execution);
        String payload = write(observation);
        return new DispatchTicket.DispatchResult(
                execution.envelope().fence(), SUCCEEDED, payload, payload, null);
    }

    /** Read-only observations are safe to repeat after an ambiguous crash. */
    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext execution)
            throws Exception
    {
        return execute(execution);
    }

    private static void requireExact(
            DispatchTicket.DispatchEnvelope envelope, OperationContext context)
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.family() != REMOTE_OBSERVATION
                || envelope.owner().kind() != STAGE
                || !context.stageId().equals(envelope.owner().id())
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !context.operationId().equals(fence.operationId())
                || !Long.valueOf(context.taskEpoch()).equals(fence.taskEpoch())
                || !context.stageId().equals(fence.stageId())
                || !Long.valueOf(context.stageGeneration()).equals(
                        fence.stageGeneration())
                || context.semanticAttempt() != fence.attempt()
                || !context.expectedHeadSha().equals(fence.expectedHeadSha())
                || !context.expectedBaseSha().equals(fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Remote observation ticket differs from its exact Operation");
        }
    }

    private String write(Observation observation)
    {
        try {
            return json.writeValueAsString(observation);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize Remote observation", e);
        }
    }

    @FunctionalInterface
    public interface Store
    {
        OperationContext requireObservation(String operationId);
    }

    @FunctionalInterface
    public interface Observer
    {
        Observation observe(Request request, ExecutionContext execution)
                throws Exception;
    }

    public record OperationContext(
            String operationId,
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
            requireText(stageId, "stageId");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(expectedBaseSha, "expectedBaseSha");
            requireNonNull(request, "request is null");
            if (taskEpoch < 1 || stageGeneration < 1 || semanticAttempt < 1) {
                throw new IllegalArgumentException(
                        "Remote observation Operation identity is invalid");
            }
        }
    }

    public record Request(
            String repositoryId,
            int pullRequestNumber,
            String expectedHeadSha,
            String expectedBaseSha,
            List<String> requiredCheckNames)
    {
        public Request
        {
            requireText(repositoryId, "repositoryId");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(expectedBaseSha, "expectedBaseSha");
            if (pullRequestNumber < 1) {
                throw new IllegalArgumentException(
                        "pullRequestNumber must be positive");
            }
            requiredCheckNames = List.copyOf(requireNonNull(
                    requiredCheckNames, "requiredCheckNames is null"));
            requiredCheckNames.forEach(name -> requireText(
                    name, "requiredCheckName"));
        }
    }

    public record Observation(
            int schemaVersion,
            String observationKey,
            String headSha,
            String baseSha,
            PrState prState,
            Mergeability mergeability,
            MergeQueueState mergeQueueState,
            MergeQueueCapability mergeQueueCapability,
            int effectiveApprovalCount,
            int writeApprovalCount,
            int changesRequestedCount,
            int requestedReviewerCount,
            int unresolvedThreadCount,
            int unresolvedCommentCount,
            List<RemoteCiPolicy.Check> checks,
            List<FeedbackFact> feedback,
            String viewerLogin,
            Boolean viewerCanMerge,
            RemoteCiProvenance ciProvenance,
            String rawEvidence,
            long observedAtMs)
    {
        public Observation
        {
            if (schemaVersion < 1 || schemaVersion > 5) {
                throw new IllegalArgumentException(
                        "Unsupported Remote observation schema");
            }
            requireText(observationKey, "observationKey");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
            requireNonNull(prState, "prState is null");
            requireNonNull(mergeability, "mergeability is null");
            requireNonNull(mergeQueueState, "mergeQueueState is null");
            mergeQueueCapability = mergeQueueCapability == null
                    ? MergeQueueCapability.UNKNOWN : mergeQueueCapability;
            checks = List.copyOf(requireNonNull(checks, "checks is null"));
            feedback = feedback == null ? List.of() : List.copyOf(feedback);
            if (schemaVersion >= 2) {
                requireText(viewerLogin, "viewerLogin");
                requireNonNull(viewerCanMerge, "viewerCanMerge is null");
            }
            if ((schemaVersion >= 3) != (ciProvenance != null)) {
                throw new IllegalArgumentException(
                        "Remote CI provenance must match observation schema");
            }
            if (ciProvenance != null
                    && ciProvenance.schemaVersion() != schemaVersion) {
                throw new IllegalArgumentException(
                        "Remote CI provenance version must match observation schema");
            }
            if (effectiveApprovalCount < 0 || writeApprovalCount < 0
                    || changesRequestedCount < 0 || requestedReviewerCount < 0
                    || unresolvedThreadCount < 0 || unresolvedCommentCount < 0
                    || observedAtMs < 0) {
                throw new IllegalArgumentException(
                        "Remote observation counts are invalid");
            }
        }

        /** Compatibility constructor for results created before queue capability. */
        public Observation(
                int schemaVersion,
                String observationKey,
                String headSha,
                String baseSha,
                PrState prState,
                Mergeability mergeability,
                MergeQueueState mergeQueueState,
                int effectiveApprovalCount,
                int writeApprovalCount,
                int changesRequestedCount,
                int requestedReviewerCount,
                int unresolvedThreadCount,
                int unresolvedCommentCount,
                List<RemoteCiPolicy.Check> checks,
                List<FeedbackFact> feedback,
                String viewerLogin,
                Boolean viewerCanMerge,
                RemoteCiProvenance ciProvenance,
                String rawEvidence,
                long observedAtMs)
        {
            this(schemaVersion, observationKey, headSha, baseSha, prState,
                    mergeability, mergeQueueState, MergeQueueCapability.UNKNOWN,
                    effectiveApprovalCount, writeApprovalCount,
                    changesRequestedCount, requestedReviewerCount,
                    unresolvedThreadCount, unresolvedCommentCount, checks,
                    feedback, viewerLogin, viewerCanMerge, ciProvenance,
                    rawEvidence, observedAtMs);
        }

        /** Compatibility constructor for version-two results already in flight. */
        public Observation(
                int schemaVersion,
                String observationKey,
                String headSha,
                String baseSha,
                PrState prState,
                Mergeability mergeability,
                MergeQueueState mergeQueueState,
                int effectiveApprovalCount,
                int writeApprovalCount,
                int changesRequestedCount,
                int requestedReviewerCount,
                int unresolvedThreadCount,
                int unresolvedCommentCount,
                List<RemoteCiPolicy.Check> checks,
                List<FeedbackFact> feedback,
                String viewerLogin,
                Boolean viewerCanMerge,
                String rawEvidence,
                long observedAtMs)
        {
            this(schemaVersion, observationKey, headSha, baseSha, prState,
                    mergeability, mergeQueueState, MergeQueueCapability.UNKNOWN,
                    effectiveApprovalCount,
                    writeApprovalCount, changesRequestedCount,
                    requestedReviewerCount, unresolvedThreadCount,
                    unresolvedCommentCount, checks, feedback, viewerLogin,
                    viewerCanMerge, null, rawEvidence, observedAtMs);
        }

        /** Compatibility constructor for version-one results already in flight. */
        public Observation(
                int schemaVersion,
                String observationKey,
                String headSha,
                String baseSha,
                PrState prState,
                Mergeability mergeability,
                MergeQueueState mergeQueueState,
                int effectiveApprovalCount,
                int writeApprovalCount,
                int changesRequestedCount,
                int requestedReviewerCount,
                int unresolvedThreadCount,
                int unresolvedCommentCount,
                List<RemoteCiPolicy.Check> checks,
                List<FeedbackFact> feedback,
                String rawEvidence,
                long observedAtMs)
        {
            this(schemaVersion, observationKey, headSha, baseSha, prState,
                    mergeability, mergeQueueState, MergeQueueCapability.UNKNOWN,
                    effectiveApprovalCount,
                    writeApprovalCount, changesRequestedCount,
                    requestedReviewerCount, unresolvedThreadCount,
                    unresolvedCommentCount, checks, feedback, null, null,
                    null, rawEvidence, observedAtMs);
        }

        /** Compatibility constructor for observations produced before feedback identities. */
        public Observation(
                int schemaVersion,
                String observationKey,
                String headSha,
                String baseSha,
                PrState prState,
                Mergeability mergeability,
                MergeQueueState mergeQueueState,
                int effectiveApprovalCount,
                int writeApprovalCount,
                int changesRequestedCount,
                int requestedReviewerCount,
                int unresolvedThreadCount,
                int unresolvedCommentCount,
                List<RemoteCiPolicy.Check> checks,
                String rawEvidence,
                long observedAtMs)
        {
            this(schemaVersion, observationKey, headSha, baseSha, prState,
                    mergeability, mergeQueueState, MergeQueueCapability.UNKNOWN,
                    effectiveApprovalCount,
                    writeApprovalCount, changesRequestedCount,
                    requestedReviewerCount, unresolvedThreadCount,
                    unresolvedCommentCount, checks, List.of(), null, null,
                    null, rawEvidence, observedAtMs);
        }
    }

    /** One externally identified feedback fact inside an immutable snapshot. */
    public record FeedbackFact(
            FeedbackKind kind,
            String externalKey,
            String actorLogin,
            boolean ownAction,
            String threadId,
            String commentId,
            String reviewId,
            String requestedReviewer,
            String body,
            FeedbackVerdict verdict,
            String rawEvidence)
    {
        public FeedbackFact
        {
            requireNonNull(kind, "kind is null");
            requireText(externalKey, "externalKey");
            if (body != null && body.isBlank()) {
                throw new IllegalArgumentException("feedback body is blank");
            }
            switch (kind) {
                case INLINE_COMMENT -> {
                    requireText(threadId, "threadId");
                    requireText(commentId, "commentId");
                    requireText(body, "body");
                }
                case TOP_LEVEL_COMMENT -> {
                    requireText(commentId, "commentId");
                    requireText(body, "body");
                }
                case REVIEW_BODY -> {
                    requireText(reviewId, "reviewId");
                    requireText(body, "body");
                }
                case REVIEW_VERDICT -> {
                    requireText(reviewId, "reviewId");
                    requireNonNull(verdict, "verdict is null");
                }
                case REQUESTED_REVIEW ->
                        requireText(requestedReviewer, "requestedReviewer");
                case THREAD_RESOLVED, THREAD_REOPENED ->
                        requireText(threadId, "threadId");
            }
        }
    }

    public enum FeedbackKind
    {
        INLINE_COMMENT,
        TOP_LEVEL_COMMENT,
        REVIEW_BODY,
        REVIEW_VERDICT,
        REQUESTED_REVIEW,
        THREAD_RESOLVED,
        THREAD_REOPENED
    }

    public enum FeedbackVerdict
    {
        APPROVED,
        CHANGES_REQUESTED,
        COMMENTED,
        DISMISSED
    }

    public enum PrState
    {
        DRAFT,
        OPEN,
        MERGED,
        CLOSED
    }

    public enum Mergeability
    {
        UNKNOWN,
        MERGEABLE,
        CONFLICTING,
        BLOCKED
    }

    public enum MergeQueueState
    {
        NONE,
        QUEUED,
        DEQUEUED,
        MERGED
    }

    public enum MergeQueueCapability
    {
        UNKNOWN,
        UNSUPPORTED,
        SUPPORTED
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
