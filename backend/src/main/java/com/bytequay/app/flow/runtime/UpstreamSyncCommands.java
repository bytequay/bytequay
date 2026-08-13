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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.TaskProvisioning.RepositoryCatalog;
import com.bytequay.app.flow.runtime.TaskProvisioning.RepositoryConfig;
import com.bytequay.app.flow.upstream.UpstreamPicker;
import com.bytequay.app.flow.upstream.UpstreamSync;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.SelectedCommit;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRequest;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The one entry command for an upstream cherry-pick range.
 *
 * <p>A run owns a range, so there is deliberately no way to attach one to an
 * existing pull request. The confirmed selection becomes an ordinary flow Task
 * — which is what lets the ordinary {@code INITIAL_PUBLISH} gate and generic
 * CI Autofix own everything after the first push, with their gates and
 * adversarial review intact.
 *
 * <p>No Git runs here. The handler persists the request and the run, and the
 * runtime dispatcher does the rest.
 */
public final class UpstreamSyncCommands
{
    /**
     * ponytail: one budget knob, spent per conflict-repair turn — no pick
     * ceiling and no round count, because a large range legitimately needs
     * many repairs. A budget of zero means no cap at all, which is what a
     * start without an explicit budget gets; this bounded default is only for
     * the convenience overload below.
     */
    public static final int DEFAULT_REPAIR_TURN_BUDGET = 50;

    public record StartReceipt(Task task, UpstreamSyncRun run)
    {
        public StartReceipt
        {
            requireNonNull(task, "task is null");
            requireNonNull(run, "run is null");
        }
    }

    private final TaskProvisioning provisioning;
    private final RepositoryCatalog repositories;
    private final UpstreamSync upstreamSync;
    private final FlowRuntime runtime;
    private final TransactionTemplate transactions;
    private final NewFlowDispatcher dispatcher;
    private final InitialTaskDispatcher initialTasks;

    public UpstreamSyncCommands(
            TaskProvisioning provisioning,
            RepositoryCatalog repositories,
            UpstreamSync upstreamSync,
            FlowRuntime runtime,
            DataSource dataSource,
            NewFlowDispatcher dispatcher,
            InitialTaskDispatcher initialTasks)
    {
        this.provisioning = requireNonNull(
                provisioning, "provisioning is null");
        this.repositories = requireNonNull(
                repositories, "repositories is null");
        this.upstreamSync = requireNonNull(
                upstreamSync, "upstreamSync is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(requireNonNull(
                        dataSource, "dataSource is null")));
        this.dispatcher = requireNonNull(dispatcher, "dispatcher is null");
        this.initialTasks = requireNonNull(
                initialTasks, "initialTasks is null");
    }

    /**
     * Reopens a parked run and re-arms its Task's INITIAL work.
     *
     * <p>The park consumed the pending INITIAL fact when its turn failed, so
     * resuming is two owner updates in one transaction: the run leaves
     * {@code WAITING_USER} with its budget topped up, and the Task returns to
     * {@code ACTIVE} with the next revision of the same self-contained fact
     * registered — which is what makes the dispatcher produce another turn.
     * The turn re-enters at the recorded conflict and re-picks it.
     */
    public UpstreamSyncRun resume(String runId, int additionalRepairTurns)
    {
        UpstreamSyncRun resumed = requireNonNull(
                transactions.execute(ignored -> {
                    UpstreamSyncRun run = upstreamSync.resume(
                            runId, additionalRepairTurns);
                    runtime.rearmInitialTask(
                            run.taskId(), "UPSTREAM_SYNC_RESUMED");
                    return run;
                }), "upstream resume transaction returned null");
        dispatcher.wake();
        initialTasks.wake();
        return resumed;
    }

    public StartReceipt startConfirmed(
            String requestKey,
            String repositoryId,
            String goalText,
            String prTitle,
            String sourceRemote,
            String sourceFromRef,
            String sourceToRef,
            String targetRef,
            List<SelectedCommit> selectedCommits,
            String requestedByUserId,
            Path sourceRepository,
            Path targetRepository)
    {
        return startConfirmed(
                requestKey, repositoryId, goalText, prTitle, sourceRemote,
                sourceFromRef, sourceToRef, targetRef, selectedCommits,
                requestedByUserId, DEFAULT_REPAIR_TURN_BUDGET,
                sourceRepository, targetRepository);
    }

    /**
     * @param prTitle the user's own PR title, or null to leave the title to the
     *         agent that requests the review.
     */
    public StartReceipt startConfirmed(
            String requestKey,
            String repositoryId,
            String goalText,
            String prTitle,
            String sourceRemote,
            String sourceFromRef,
            String sourceToRef,
            String targetRef,
            List<SelectedCommit> selectedCommits,
            String requestedByUserId,
            int repairTurnBudget,
            Path sourceRepository,
            Path targetRepository)
    {
        String effectiveGoal = requireNonNull(goalText, "goalText is null");
        Path source = requireNonNull(
                sourceRepository, "sourceRepository is null");
        Path target = requireNonNull(
                targetRepository, "targetRepository is null");
        RepositoryConfig configured = requireNonNull(
                repositories.repository(repositoryId),
                "repository catalog returned null");
        if (!configured.repositoryId().equals(repositoryId)
                || !configured.repositoryRoot().equals(realPath(target))) {
            throw new IllegalArgumentException(
                    "target repository is not the configured Task repository");
        }

        List<SelectedCommit> confirmed = confirmRange(
                source, target, sourceFromRef, sourceToRef, selectedCommits);
        String confirmedTarget = UpstreamPicker.resolveCommit(target, targetRef);
        String configuredTarget = UpstreamPicker.resolveCommit(
                target, configured.baseRef());
        if (!confirmedTarget.equals(configuredTarget)) {
            throw new IllegalArgumentException(
                    "confirmed target ref is not the Task launch base");
        }

        String confirmedFrom = UpstreamPicker.resolveCommit(
                source, sourceFromRef);
        String confirmedTo = UpstreamPicker.resolveCommit(source, sourceToRef);
        Optional<UpstreamSyncRequest> replayRequest =
                upstreamSync.requestForKey(requestKey);
        if (replayRequest.isPresent()) {
            UpstreamSyncRequest existing = replayRequest.orElseThrow();
            UpstreamSyncRun run = upstreamSync.runForRequest(
                    existing.requestId()).orElseThrow();
            if (!existing.repositoryId().equals(repositoryId)
                    || !existing.goalText().equals(effectiveGoal)
                    || !Objects.equals(existing.prTitle(), prTitle)
                    || !existing.sourceRemote().equals(sourceRemote)
                    || !existing.sourceFromRef().equals(confirmedFrom)
                    || !existing.sourceToRef().equals(confirmedTo)
                    || !existing.targetRef().equals(confirmedTarget)
                    || !existing.selectedUpstreamShas().equals(
                            confirmed.stream().map(SelectedCommit::sha).toList())
                    || !Objects.equals(existing.requestedByUserId(),
                            requestedByUserId)
                    || run.repairTurnBudget() != repairTurnBudget) {
                throw new IllegalStateException(
                        "requestKey already owns a different upstream sync");
            }
            Task task = provisioning.startTask(
                    requestKey, repositoryId, effectiveGoal);
            if (!run.taskId().equals(task.taskId())) {
                throw new IllegalStateException(
                        "upstream sync replay owns a different Task");
            }
            return new StartReceipt(task, run);
        }

        StartReceipt receipt = requireNonNull(transactions.execute(ignored -> {
            Task task = provisioning.startTask(
                    requestKey, repositoryId, effectiveGoal);
            UpstreamSyncRun run = upstreamSync.startRun(
                    requestKey, repositoryId, effectiveGoal, prTitle,
                    sourceRemote,
                    confirmedFrom, confirmedTo, confirmedTarget, confirmed,
                    requestedByUserId, task.taskId(), repairTurnBudget);
            return new StartReceipt(task, run);
        }), "upstream start transaction returned null");
        dispatcher.wake();
        initialTasks.wake();
        return receipt;
    }

    private static List<SelectedCommit> confirmRange(
            Path source,
            Path target,
            String sourceFromRef,
            String sourceToRef,
            List<SelectedCommit> selectedCommits)
    {
        List<SelectedCommit> selected = List.copyOf(requireNonNull(
                selectedCommits, "selectedCommits is null"));
        if (new HashSet<>(selected.stream().map(SelectedCommit::sha).toList())
                .size() != selected.size()) {
            throw new IllegalArgumentException(
                    "an upstream commit was selected more than once");
        }
        List<String> requested = selected.stream()
                .map(SelectedCommit::sha)
                .toList();
        List<String> resolved = requested.stream()
                .map(sha -> UpstreamPicker.resolveCommit(source, sha))
                .toList();
        String from = UpstreamPicker.resolveCommit(source, sourceFromRef);
        String to = UpstreamPicker.resolveCommit(source, sourceToRef);
        String previous = from;
        for (String sha : resolved) {
            if (!UpstreamPicker.isAncestor(source, previous, sha)
                    || !UpstreamPicker.isAncestor(source, sha, to)) {
                throw new IllegalArgumentException(
                        "selected commits are outside the confirmed range");
            }
            previous = sha;
        }
        UpstreamPicker.transferObjects(source, target, resolved);
        List<SelectedCommit> confirmed = new ArrayList<>(selected.size());
        for (int index = 0; index < selected.size(); index++) {
            confirmed.add(new SelectedCommit(
                    resolved.get(index), selected.get(index).subject()));
        }
        return List.copyOf(confirmed);
    }

    private static Path realPath(Path path)
    {
        try {
            return path.toAbsolutePath().normalize().toRealPath();
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "repository path is unavailable", failure);
        }
    }
}
