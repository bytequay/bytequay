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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.CONCURRENT_UPDATE;
import static java.util.Objects.requireNonNull;

final class CommandTestSupport
{
    private CommandTestSupport() {}

    static TaskCommandExecutor executor()
    {
        return new TaskCommandExecutor(new CountingTransactionManager());
    }

    static final class CountingTransactionManager
            extends AbstractPlatformTransactionManager
    {
        private int begins;
        private int commits;

        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition)
        {
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status)
        {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}

        int begins()
        {
            return begins;
        }

        int commits()
        {
            return commits;
        }
    }

    static final class Trunks
            implements TrunkManager.Store
    {
        private final Map<String, TrunkManager.State> states = new HashMap<>();
        private final Map<String, TrunkManager.CommandReceipt> results = new HashMap<>();

        void put(TrunkManager.State state)
        {
            states.put(state.id(), state);
        }

        @Override
        public Optional<TrunkManager.State> findById(String trunkId)
        {
            return Optional.ofNullable(states.get(trunkId));
        }

        @Override
        public Optional<TrunkManager.CommandReceipt> findCommandResult(
                String trunkId, String commandId)
        {
            return Optional.ofNullable(results.get(key(trunkId, commandId)));
        }

        @Override
        public TrunkManager.State commit(
                String commandId,
                String cause,
                String actor,
                long expectedVersion,
                TrunkManager.State expected,
                TrunkManager.State updated)
        {
            if (expectedVersion != expected.version()) {
                throw new AssertionError("Trunk command version differs from expected state");
            }
            requireCurrent(states.get(expected.id()), expected);
            states.put(updated.id(), updated);
            results.put(key(updated.id(), commandId),
                    new TrunkManager.CommandReceipt(
                            updated, cause, actor, expectedVersion,
                            CommandResult.Disposition.APPLIED));
            return updated;
        }
    }

    static final class Tasks
            implements TaskManager.Store
    {
        private final Map<String, TaskManager.State> states = new HashMap<>();
        private final Map<String, TaskManager.CommandReceipt> results = new HashMap<>();
        private final Map<String, TaskManager.ProvisioningResult> provisioning = new HashMap<>();
        private final Map<String, TaskManager.ReplanEvidence> replans = new HashMap<>();
        private final Map<String, TaskManager.QuiescenceEvidence> quiescence = new HashMap<>();
        private final Map<String, TaskManager.PauseEvidence> pauses = new HashMap<>();
        private final Map<String, TaskManager.ResumeEvidence> resumes = new HashMap<>();
        private final Map<String, TaskManager.ArchiveEvidence> archives = new HashMap<>();
        private final Map<String, String> appliedReplans = new HashMap<>();
        private final List<String> commitOrder;

        Tasks()
        {
            this(new ArrayList<>());
        }

        Tasks(List<String> commitOrder)
        {
            this.commitOrder = requireNonNull(commitOrder, "commitOrder is null");
        }

        void put(TaskManager.State state)
        {
            states.put(state.id(), state);
        }

        void put(TaskManager.ProvisioningResult result)
        {
            provisioning.put(key(result.taskId(), result.operationId()), result);
        }

        void put(TaskManager.ReplanEvidence evidence)
        {
            replans.put(key(evidence.taskId(), evidence.replanRequestId()), evidence);
        }

        void put(TaskManager.QuiescenceEvidence evidence)
        {
            quiescence.put(key(evidence.taskId(), evidence.barrierId()), evidence);
        }

        void put(TaskManager.PauseEvidence evidence)
        {
            pauses.put(key(evidence.taskId(), evidence.barrierId()), evidence);
        }

        void put(TaskManager.ResumeEvidence evidence)
        {
            resumes.put(key(evidence.taskId(), evidence.reconciliationId()), evidence);
        }

        void put(TaskManager.ArchiveEvidence evidence)
        {
            archives.put(key(evidence.taskId(), evidence.archiveEvidenceId()), evidence);
        }

        String appliedReplan(String requestId)
        {
            return appliedReplans.get(requestId);
        }

        @Override
        public Optional<TaskManager.State> findById(String taskId)
        {
            return Optional.ofNullable(states.get(taskId));
        }

        @Override
        public Optional<TaskManager.CommandReceipt> findCommandResult(
                String taskId, String commandId)
        {
            return Optional.ofNullable(results.get(key(taskId, commandId)));
        }

        @Override
        public Optional<TaskManager.ProvisioningResult> findAcceptedProvisioningResult(
                String taskId, String operationId)
        {
            return Optional.ofNullable(provisioning.get(key(taskId, operationId)));
        }

        @Override
        public Optional<TaskManager.ReplanEvidence> findReplanEvidence(
                String taskId, String replanRequestId)
        {
            return Optional.ofNullable(replans.get(key(taskId, replanRequestId)));
        }

        @Override
        public Optional<TaskManager.QuiescenceEvidence> findSatisfiedQuiescence(
                String taskId, String barrierId)
        {
            return Optional.ofNullable(quiescence.get(key(taskId, barrierId)));
        }

        @Override
        public Optional<TaskManager.PauseEvidence> findPauseEvidence(
                String taskId, String barrierId)
        {
            return Optional.ofNullable(pauses.get(key(taskId, barrierId)));
        }

        @Override
        public Optional<TaskManager.ResumeEvidence> findResumeEvidence(
                String taskId, String reconciliationId)
        {
            return Optional.ofNullable(resumes.get(key(taskId, reconciliationId)));
        }

        @Override
        public Optional<TaskManager.ArchiveEvidence> findArchiveEvidence(
                String taskId, String archiveEvidenceId)
        {
            return Optional.ofNullable(archives.get(key(taskId, archiveEvidenceId)));
        }

        @Override
        public TaskManager.State commit(
                String commandId,
                String cause,
                String actor,
                Long expectedEpoch,
                Long expectedVersion,
                ResultFence resultFence,
                TaskManager.BrainVerdict brainVerdict,
                String proofId,
                String nextStageId,
                StageKind nextStageKind,
                Long nextStageGeneration,
                TaskManager.State expected,
                TaskManager.State updated)
        {
            requireCurrent(states.get(expected.id()), expected);
            commitOrder.add("task");
            states.put(updated.id(), updated);
            results.put(key(updated.id(), commandId), new TaskManager.CommandReceipt(
                    updated, cause, actor, expectedEpoch, expectedVersion,
                    resultFence, brainVerdict, proofId,
                    nextStageId, nextStageKind,
                    nextStageGeneration,
                    CommandResult.Disposition.APPLIED));
            return updated;
        }

        @Override
        public TaskManager.State recordSuperseded(
                String commandId,
                String cause,
                String actor,
                Long expectedEpoch,
                Long expectedVersion,
                ResultFence resultFence,
                TaskManager.BrainVerdict brainVerdict,
                String proofId,
                String nextStageId,
                StageKind nextStageKind,
                Long nextStageGeneration,
                TaskManager.State current)
        {
            results.put(key(current.id(), commandId), new TaskManager.CommandReceipt(
                    current, cause, actor, expectedEpoch, expectedVersion,
                    resultFence, brainVerdict, proofId,
                    nextStageId, nextStageKind,
                    nextStageGeneration,
                    CommandResult.Disposition.SUPERSEDED));
            return current;
        }

        @Override
        public void markReplanApplied(
                TaskManager.ReplanEvidence evidence,
                String newPlanStageId,
                long newPlanGeneration)
        {
            appliedReplans.put(
                    evidence.replanRequestId(), newPlanStageId + ":" + newPlanGeneration);
        }
    }

    static final class Stages
            implements StageManager.Store
    {
        private final Map<String, StageManager.State> states = new HashMap<>();
        private final Map<String, StageManager.CommandReceipt> results = new HashMap<>();
        private final Function<String, TaskManager.State> owner;
        private final List<String> commitOrder;

        Stages(Function<String, TaskManager.State> owner)
        {
            this(owner, new ArrayList<>());
        }

        Stages(Function<String, TaskManager.State> owner, List<String> commitOrder)
        {
            this.owner = requireNonNull(owner, "owner is null");
            this.commitOrder = requireNonNull(commitOrder, "commitOrder is null");
        }

        void put(StageManager.State state)
        {
            states.put(state.id(), state);
        }

        @Override
        public Optional<StageManager.OwnerState> findOwner(String taskId, String stageId)
        {
            StageManager.State stage = states.get(stageId);
            TaskManager.State task = owner.apply(taskId);
            if (stage == null || task == null
                    || !taskId.equals(task.id())
                    || !taskId.equals(stage.taskId())) {
                return Optional.empty();
            }
            return Optional.of(new StageManager.OwnerState(
                    task.id(),
                    task.lifecycle(),
                    task.epoch(),
                    task.currentStageId(),
                    stage));
        }

        @Override
        public Optional<StageManager.CommandReceipt> findCommandResult(
                String taskId, String stageId, String commandId)
        {
            StageManager.CommandReceipt receipt = results.get(key(stageId, commandId));
            if (receipt == null || !receipt.taskId().equals(taskId)) {
                return Optional.empty();
            }
            return Optional.of(receipt);
        }

        @Override
        public StageManager.State commit(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State expected,
                StageManager.State updated)
        {
            requireCurrent(states.get(expected.id()), expected);
            commitOrder.add("stage");
            states.put(updated.id(), updated);
            results.put(key(updated.id(), commandId), new StageManager.CommandReceipt(
                    updated.taskId(), updated, cause, actor,
                    expectedTaskEpoch, expectedStageGeneration, expectedStageVersion,
                    sourceCheckpoint, subjectFence, proofId,
                    CommandResult.Disposition.APPLIED));
            return updated;
        }

        @Override
        public StageManager.State create(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State state)
        {
            if (states.putIfAbsent(state.id(), state) != null) {
                throw new CommandRejectedException(
                        CONCURRENT_UPDATE, "Stage already exists");
            }
            commitOrder.add("stage");
            results.put(key(state.id(), commandId), new StageManager.CommandReceipt(
                    state.taskId(), state, cause, actor,
                    expectedTaskEpoch, expectedStageGeneration, expectedStageVersion,
                    sourceCheckpoint, subjectFence, proofId,
                    CommandResult.Disposition.APPLIED));
            return state;
        }

        @Override
        public StageManager.State recordSuperseded(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State current)
        {
            results.put(key(current.id(), commandId), new StageManager.CommandReceipt(
                    current.taskId(), current, cause, actor,
                    expectedTaskEpoch, expectedStageGeneration, expectedStageVersion,
                    sourceCheckpoint, subjectFence, proofId,
                    CommandResult.Disposition.SUPERSEDED));
            return current;
        }
    }

    private static String key(String aggregateId, String commandId)
    {
        return aggregateId + '\0' + commandId;
    }

    private static void requireCurrent(Object actual, Object expected)
    {
        if (!expected.equals(actual)) {
            throw new CommandRejectedException(
                    CONCURRENT_UPDATE, "Aggregate changed before commit");
        }
    }
}
