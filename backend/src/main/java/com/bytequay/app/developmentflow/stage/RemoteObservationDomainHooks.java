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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.ObservationDisposition;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.ReadinessEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.RemoteContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.AuthorityKind;
import com.bytequay.app.domain.PR;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.util.Objects;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Branch, merge, and readiness commands emitted for accepted legacy snapshots. */
public final class RemoteObservationDomainHooks
{
    private static final String ACTOR = "remote-observer";

    private final SqliteRemoteDevelopmentRuntimeStore store;
    private final RemoteDevelopmentStageManager remote;
    private final BranchSyncRuntimeCoordinator branchSync;
    private final RemoteMergeObservationCoordinator mergeObservation;
    private final RemoteMergeRuntimeCoordinator merges;
    private final PRService prs;

    public RemoteObservationDomainHooks(
            SqliteRemoteDevelopmentRuntimeStore store,
            RemoteDevelopmentStageManager remote,
            BranchSyncRuntimeCoordinator branchSync,
            RemoteMergeObservationCoordinator mergeObservation,
            RemoteMergeRuntimeCoordinator merges,
            PRService prs)
    {
        this.store = requireNonNull(store, "store is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.branchSync = requireNonNull(branchSync, "branchSync is null");
        this.mergeObservation = requireNonNull(
                mergeObservation, "mergeObservation is null");
        this.merges = requireNonNull(merges, "merges is null");
        this.prs = requireNonNull(prs, "prs is null");
    }

    /** Advances neutral Remote lifecycle facts without starting legacy CI repair. */
    public void acceptLifecycleInCommand(Candidate candidate)
    {
        requireCurrent(candidate);
        RemoteContext context = store.requireContext(
                candidate.context().taskId(), candidate.context().stageId());
        boolean changed = !Objects.equals(
                candidate.context().currentHeadSha(), candidate.evidence().headSha())
                || !Objects.equals(candidate.context().currentBaseSha(),
                        candidate.evidence().baseSha());
        if (changed && !"WAITING_CI".equals(context.checkpoint())) {
            remote.acceptHeadChangeInCommand(
                    gate(context, candidate.evidence().snapshotId(),
                            candidate.evidence().headSha(),
                            candidate.evidence().baseSha(), "accept-head-change"),
                    StageCheckpoint.valueOf(context.checkpoint()));
            context = store.requireContext(
                    candidate.context().taskId(), candidate.context().stageId());
        }
        if (candidate.ciEvaluation().outcome()
                == RemoteCiPolicy.PolicyOutcome.ACCEPTED
                && "WAITING_CI".equals(context.checkpoint())) {
            remote.acceptCiEvidenceInCommand(gate(
                    context, candidate.evidence().ciEvaluationId(),
                    candidate.evidence().headSha(), candidate.evidence().baseSha(),
                    "accept-ci"));
            context = store.requireContext(
                    candidate.context().taskId(), candidate.context().stageId());
        }
        if (candidate.observation().prState()
                == RemoteObservationOperationHandler.PrState.OPEN
                && "AWAITING_READY".equals(context.checkpoint())) {
            var accepted = remote.acceptObservedReadyInCommand(gate(
                    context, candidate.evidence().snapshotId(),
                    candidate.evidence().headSha(), candidate.evidence().baseSha(),
                    "accept-open-pr"));
            if (accepted.disposition() != CommandResult.Disposition.SUPERSEDED) {
                projectRemoteOpen(context.taskId());
            }
        }
    }

    private void projectRemoteOpen(String taskId)
    {
        PR pr = prs.findByTask(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "Accepted open PR observation has no stable Task PR"));
        if (PR.STATUS_REMOTE_OPEN.equals(pr.status())) {
            return;
        }
        if (!PR.STATUS_REMOTE_DRAFTED.equals(pr.status())) {
            throw new IllegalStateException(
                    "Accepted open PR observation cannot project PR status "
                            + pr.status());
        }
        prs.transition(pr.id(), PR.STATUS_REMOTE_OPEN, ACTOR);
    }

    public ObservationDisposition acceptBranchInCommand(Candidate candidate)
    {
        requireCurrent(candidate);
        return branchSync.acceptObservationInCommand(candidate);
    }

    public void acceptMergeInCommand(Candidate candidate)
    {
        requireCurrent(candidate);
        mergeObservation.acceptInCommand(candidate);
    }

    public void acceptReadinessInCommand(
            Candidate candidate, ReadinessEvidence readiness)
    {
        requireCurrent(candidate);
        requireNonNull(readiness, "readiness is null");
        var policy = store.requireAutomationPolicy(candidate.context().taskId());
        if (!policy.autoMerge() || policy.stewardshipException()) {
            return;
        }
        String subject = readiness.id();
        merges.startInCommand(new RemoteMergeRuntimeCoordinator.Command(
                id("auto-merge-command", subject), "auto-merge-policy",
                candidate.context().taskId(), candidate.context().stageId(),
                readiness.id(), id("auto-merge-authorization", subject),
                id("auto-merge-operation", subject),
                id("auto-merge-ticket", subject), AuthorityKind.AUTO_MERGE_POLICY,
                "squash", 3));
    }

    private static void requireCurrent(Candidate candidate)
    {
        requireNonNull(candidate, "candidate is null");
        TaskCommandExecutor.requireCurrent(candidate.context().taskId());
    }

    private static RemoteDevelopmentStageManager.RemoteGateCommand gate(
            RemoteContext context,
            String proofId,
            String headSha,
            String baseSha,
            String commandKind)
    {
        return new RemoteDevelopmentStageManager.RemoteGateCommand(
                new StageManager.Command(
                        id(commandKind, proofId), ACTOR, context.taskId(),
                        context.taskEpoch(), context.stageId(),
                        context.stageGeneration(), context.stageVersion()),
                proofId, headSha, baseSha);
    }
}
