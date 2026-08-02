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

import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.ObservationDisposition;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.InboxKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.ObservedInboxItem;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.OpenFeedbackBatch;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.Provenance;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.ReadinessEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.RemoteContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.Verdict;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/**
 * One synchronous fold for accepted Remote truth. Named hooks keep CI,
 * branch-sync, and merge ownership explicit without making this a workflow engine.
 */
public final class RemoteDevelopmentObservationConsumer
        implements RemoteObservationConsumer
{
    private static final String ACTOR = "remote-observer";

    private final SqliteRemoteDevelopmentRuntimeStore store;
    private final RemoteFeedbackRuntimeCoordinator feedback;
    private final RemoteDevelopmentRuntimeCoordinator remote;
    private final Hooks hooks;
    private final Clock clock;

    public RemoteDevelopmentObservationConsumer(
            SqliteRemoteDevelopmentRuntimeStore store,
            RemoteFeedbackRuntimeCoordinator feedback,
            RemoteDevelopmentRuntimeCoordinator remote,
            Hooks hooks,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.feedback = requireNonNull(feedback, "feedback is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.hooks = requireNonNull(hooks, "hooks is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public Consumption consume(Candidate candidate, SubjectAcceptance acceptance)
    {
        requireNonNull(candidate, "candidate is null");
        requireNonNull(acceptance, "acceptance is null");
        TaskCommandExecutor.requireCurrent(candidate.context().taskId());
        if (!candidate.context().current()) {
            return Consumption.SUPERSEDED;
        }

        acceptance.accept();
        ingest(candidate);
        if (hooks.branch().acceptInCommand(candidate)
                != ObservationDisposition.CONTINUE) {
            return Consumption.ACCEPTED;
        }
        if (hooks.ci().acceptInCommand(candidate)
                != ObservationDisposition.CONTINUE) {
            return Consumption.ACCEPTED;
        }
        store.findFeedbackBatchAwaitingHead(
                        candidate.context().stageId(), candidate.evidence().headSha(),
                        candidate.evidence().baseSha())
                .ifPresent(batchId -> remote.resumeFeedbackCompletionInCommand(
                        candidate.context().taskId(), batchId));

        hooks.merge().acceptInCommand(candidate);

        if (candidate.observation().prState()
                == RemoteObservationOperationHandler.PrState.MERGED
                || candidate.observation().prState()
                == RemoteObservationOperationHandler.PrState.CLOSED) {
            return Consumption.ACCEPTED;
        }
        continueFeedbackAndReadiness(candidate);
        return Consumption.ACCEPTED;
    }

    private void continueFeedbackAndReadiness(Candidate candidate)
    {
        if (candidate.ciEvaluation().outcome()
                != RemoteCiPolicy.PolicyOutcome.ACCEPTED
                || candidate.observation().mergeQueueCapability()
                    == RemoteObservationOperationHandler.MergeQueueCapability.UNKNOWN) {
            return;
        }
        RemoteContext context = store.requireContext(
                candidate.context().taskId(), candidate.context().stageId());
        if (candidate.observation().prState()
                == RemoteObservationOperationHandler.PrState.DRAFT) {
            startPolicyMarkReady(context);
            return;
        }
        if (candidate.observation().prState()
                != RemoteObservationOperationHandler.PrState.OPEN) {
            return;
        }
        if (!"WAITING_REMOTE_REVIEW".equals(context.checkpoint())) {
            return;
        }

        OpenFeedbackBatch open = store.findOpenFeedbackBatch(
                        context.stageId(), context.headSha(), context.baseSha())
                .orElse(null);
        if (open != null) {
            if ("FROZEN".equals(open.status())) {
                feedback.startInCommand(context.taskId(), open.id());
            }
            return;
        }

        String batchId = id("remote-feedback-batch", candidate.evidence().snapshotId());
        Optional<SqliteRemoteDevelopmentRuntimeStore.FrozenBatch> frozen =
                store.freezeNextBatch(
                        batchId, context.taskId(), context.stageId(), true,
                        ACTOR, clock.instant());
        if (frozen.isPresent()) {
            feedback.startInCommand(context.taskId(), frozen.orElseThrow().id());
            return;
        }

        String eligibilityId = store.findAutomationEligibilityEvidenceId(
                        context.taskId(), context.taskEpoch())
                .orElse(null);
        ReadinessEvidence readiness = remote.proveReadinessInCommand(
                id("remote-readiness", candidate.evidence().snapshotId()),
                context.taskId(), context.stageId(), eligibilityId,
                candidate.observation().rawEvidence());
        if (readiness.ready()) {
            hooks.readinessAccepted().acceptInCommand(candidate, readiness);
        }
    }

    private void startPolicyMarkReady(RemoteContext context)
    {
        if (!"AWAITING_READY".equals(context.checkpoint())) {
            return;
        }
        var policy = store.requireAutomationPolicy(context.taskId());
        if (!policy.keepDraft() && !policy.stewardshipException()) {
            remote.startMarkReadyInCommand(
                    context.taskId(), context.stageId(),
                    RemoteDevelopmentRuntimeCoordinator.MarkReadyAuthority.POLICY,
                    null, 3);
        }
    }

    private void ingest(Candidate candidate)
    {
        Instant observedAt = Instant.ofEpochMilli(
                candidate.observation().observedAtMs());
        for (RemoteObservationOperationHandler.FeedbackFact fact
                : candidate.observation().feedback()) {
            store.ingestObserved(observed(candidate, fact, observedAt));
        }
        if (!Objects.equals(
                candidate.context().currentHeadSha(),
                candidate.evidence().headSha())) {
            store.ingestObserved(new ObservedInboxItem(
                    candidate.context().taskId(), candidate.context().taskEpoch(),
                    candidate.context().stageId(),
                    candidate.context().stageGeneration(),
                    candidate.context().remotePrBindingId(),
                    candidate.evidence().snapshotId(), InboxKind.HEAD_CHANGED,
                    "remote-head", candidate.evidence().headSha(),
                    candidate.evidence().baseSha(), null, Provenance.EXTERNAL,
                    false, null, null, null, null, null, null,
                    candidate.context().currentHeadSha(),
                    candidate.evidence().headSha(), observedAt,
                    candidate.observation().rawEvidence()));
        }
    }

    private static ObservedInboxItem observed(
            Candidate candidate,
            RemoteObservationOperationHandler.FeedbackFact fact,
            Instant observedAt)
    {
        return new ObservedInboxItem(
                candidate.context().taskId(), candidate.context().taskEpoch(),
                candidate.context().stageId(),
                candidate.context().stageGeneration(),
                candidate.context().remotePrBindingId(),
                candidate.evidence().snapshotId(),
                InboxKind.valueOf(fact.kind().name()), fact.externalKey(),
                candidate.evidence().headSha(), candidate.evidence().baseSha(),
                fact.actorLogin(), fact.ownAction()
                        ? Provenance.OWN_REPLY : Provenance.EXTERNAL,
                fact.ownAction(), fact.threadId(), fact.commentId(),
                fact.reviewId(), fact.requestedReviewer(), fact.body(),
                fact.verdict() == null ? null
                        : Verdict.valueOf(fact.verdict().name()),
                null, null, observedAt, fact.rawEvidence());
    }

    @FunctionalInterface
    public interface CiObservationHook
    {
        /** May defer the rest of this fold until an accepted push is durable. */
        ObservationDisposition acceptInCommand(Candidate candidate);
    }

    @FunctionalInterface
    public interface AcceptedObservationHook
    {
        /** Runs inside the same exact Task command after subject acceptance. */
        void acceptInCommand(Candidate candidate);
    }

    @FunctionalInterface
    public interface ReadinessAcceptedHook
    {
        /** Runs after exact readiness has advanced the Remote Stage. */
        void acceptInCommand(Candidate candidate, ReadinessEvidence readiness);
    }

    public record Hooks(
            CiObservationHook ci,
            CiObservationHook branch,
            AcceptedObservationHook merge,
            ReadinessAcceptedHook readinessAccepted)
    {
        public Hooks
        {
            requireNonNull(ci, "ci hook is null");
            requireNonNull(branch, "branch hook is null");
            requireNonNull(merge, "merge hook is null");
            requireNonNull(
                    readinessAccepted, "readinessAccepted hook is null");
        }
    }
}
