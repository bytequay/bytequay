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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.QueuedRepair;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.FinalRedRegistration;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The one concrete transaction joining CI evidence to runtime dispatch.
 *
 * <p>It starts no process and grants no writer. Its only responsibilities are
 * exact-head enqueue and removal of stale CI inbox facts before the runtime's
 * generic selector can materialize a writer operation.
 */
public final class CiAutofixCoordinator
{
    private final TransactionTemplate transactions;
    private final CiAutofix autofix;
    private final FlowRuntime runtime;

    public CiAutofixCoordinator(
            DataSource dataSource, CiAutofix autofix, FlowRuntime runtime)
    {
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(
                        requireNonNull(dataSource, "dataSource is null")));
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    /** Atomically freezes failed logs and records one exact runtime cause. */
    public QueuedRepair enqueueRepair(String roundId)
    {
        requireText(roundId, "roundId");
        return requireNonNull(transactions.execute(ignored -> {
            CiRound round = autofix.roundById(roundId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown CI round: " + roundId));
            Task task = requireExactPublishedSubject(round);
            if (isTerminal(task.status())) {
                var refreshed = autofix.finalizeHeadSnapshot(
                        round.prId(), round.remoteHead());
                if (!(refreshed instanceof FinalizedRound finalized)
                        || (finalized.round().state() != RoundState.FINAL_RED
                        && finalized.round().state() != RoundState.QUEUED)) {
                    throw new IllegalStateException(
                            "Terminal Task has no current red CI evidence");
                }
                round = finalized.round();
                FinalRedRegistration registration = runtime.registerFinalRed(
                        round.roundId(),
                        round.taskId(),
                        round.prId(),
                        round.remoteHead(),
                        "ci-round:" + round.roundId());
                return new QueuedRepair(
                        round,
                        registration.inboxId(),
                        registration.reconciliationOperationId(),
                        registration.terminalReason());
            }
            if (!isParkable(task.status())) {
                throw new IllegalStateException(
                        "CI round Task cannot accept repair work");
            }
            CiRound queued = autofix.queueCurrentFinalRed(roundId);
            FinalRedRegistration registration = runtime.registerFinalRed(
                    queued.roundId(),
                    queued.taskId(),
                    queued.prId(),
                    queued.remoteHead(),
                    "ci-round:" + queued.roundId());
            return new QueuedRepair(
                    queued,
                    registration.inboxId(),
                    registration.reconciliationOperationId(),
                    registration.terminalReason());
        }), "enqueue transaction returned null");
    }

    /**
     * Rechecks provider evidence before delegating one selection to the
     * runtime. Newer red evidence is queued before the stale cause is consumed,
     * so a missed or duplicate provider delivery cannot strand repair work.
     */
    public Optional<Operation> selectNext(Claim reconciliationClaim)
    {
        requireNonNull(reconciliationClaim, "reconciliationClaim is null");
        return requireNonNull(transactions.execute(ignored -> {
            while (true) {
                Optional<PendingWork> candidate =
                        runtime.nextPendingForReconciliation(
                                reconciliationClaim);
                if (candidate.isEmpty()
                        || candidate.get().kind() != PendingKind.FINAL_RED) {
                    return runtime.selectNext(reconciliationClaim);
                }
                PendingWork pending = candidate.get();
                CiRound queued = autofix.roundById(pending.externalKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "Runtime references an unknown CI round"));
                var refreshed = autofix.finalizeHeadSnapshot(
                        queued.prId(), queued.remoteHead());
                if (refreshed instanceof FinalizedRound finalized
                        && finalized.round().roundId().equals(queued.roundId())
                        && finalized.round().state() == RoundState.QUEUED) {
                    return runtime.selectNext(reconciliationClaim);
                }
                if (refreshed instanceof FinalizedRound finalized
                        && finalized.round().state() == RoundState.FINAL_RED
                        && exactNonterminalSubject(finalized.round())) {
                    CiRound successor = autofix.queueCurrentFinalRed(
                            finalized.round().roundId());
                    runtime.registerFinalRed(
                            successor.roundId(),
                            successor.taskId(),
                            successor.prId(),
                            successor.remoteHead(),
                            "ci-round:" + successor.roundId());
                }
                CiRound current = autofix.roundById(queued.roundId())
                        .orElseThrow();
                if (current.state() == RoundState.QUEUED) {
                    autofix.supersedeQueuedRound(current.roundId());
                }
                runtime.discardPendingFinalRed(
                        reconciliationClaim, pending.pendingId());
            }
        }), "selection transaction returned null");
    }

    private Task requireExactPublishedSubject(CiRound round)
    {
        Task task = runtime.task(round.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI round Task does not exist"));
        PullRequestSubject pr = runtime.pullRequest(round.prId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI round PR does not exist"));
        if (!pr.published()
                || !pr.taskId().equals(task.taskId())
                || !round.prId().equals(task.prId())
                || !round.remoteHead().equals(task.currentHeadSha())
                || !round.remoteHead().equals(pr.currentRemoteHead())) {
            throw new IllegalStateException(
                    "CI round is not the exact published Task/PR head");
        }
        return task;
    }

    private boolean exactNonterminalSubject(CiRound round)
    {
        Task task = requireExactPublishedSubject(round);
        return isParkable(task.status());
    }

    private static boolean isParkable(TaskStatus status)
    {
        return status == TaskStatus.ACTIVE
                || status == TaskStatus.WAITING_USER
                || status == TaskStatus.NEEDS_ATTENTION;
    }

    private static boolean isTerminal(TaskStatus status)
    {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.CANCELED;
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
