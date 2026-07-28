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
package com.bytequay.app.developmentflow.userwait;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.V2StageSteeringRuntime;
import com.bytequay.app.developmentflow.task.TaskBrainConversationRuntime;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** Thin redrive router; every transition remains in its typed owner runtime. */
@Component
public final class TypedUserWaitContinuation
        implements ExecutionPorts.MaintenanceWork
{
    private static final Logger log = LoggerFactory.getLogger(
            TypedUserWaitContinuation.class);
    private static final int SWEEP_LIMIT = 32;

    private final V2UserWaitStore waits;
    private final TrunkUserWaitContinuation trunks;
    private final ObjectProvider<PlanRuntimeCoordinator> plans;
    private final ObjectProvider<V2StageSteeringRuntime> stages;
    private final ObjectProvider<ReviewAssignmentTurnRuntime> reviews;
    private final ObjectProvider<TaskBrainConversationRuntime> taskBrain;

    @Autowired
    public TypedUserWaitContinuation(
            V2UserWaitStore waits,
            TrunkUserWaitContinuation trunks,
            ObjectProvider<PlanRuntimeCoordinator> plans,
            ObjectProvider<V2StageSteeringRuntime> stages,
            ObjectProvider<ReviewAssignmentTurnRuntime> reviews,
            ObjectProvider<TaskBrainConversationRuntime> taskBrain)
    {
        this.waits = requireNonNull(waits, "waits is null");
        this.trunks = requireNonNull(trunks, "trunks is null");
        this.plans = requireNonNull(plans, "plans is null");
        this.stages = requireNonNull(stages, "stages is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.taskBrain = requireNonNull(taskBrain, "taskBrain is null");
    }

    public void resumeQuestion(String questionId)
    {
        V2UserWaitStore.Question question = waits.findQuestion(questionId)
                .orElse(null);
        if (question == null || !question.continuationState().equals("READY")) {
            return;
        }
        resume("QUESTION", question.id(), question.owner(), question.answer());
    }

    public void resumePermission(String permissionId)
    {
        V2UserWaitStore.PermissionRequest permission =
                waits.findPermissionById(permissionId).orElse(null);
        if (permission == null
                || !permission.continuationState().equals("READY")) {
            return;
        }
        resume("PERMISSION", permission.id(), permission.owner(),
                permission.answer());
    }

    @Override
    public void maintain(Instant ignored)
    {
        for (V2UserWaitStore.ReadyContinuation ready
                : waits.listReadyContinuations(SWEEP_LIMIT)) {
            try {
                if (ready.waitKind().equals("QUESTION")) {
                    resumeQuestion(ready.waitId());
                }
                else {
                    resumePermission(ready.waitId());
                }
            }
            catch (RuntimeException e) {
                log.warn("Could not resume typed {} {} {}",
                        ready.ownerKind(), ready.waitKind(), ready.waitId(), e);
            }
        }
    }

    private void resume(
            String waitKind,
            String waitId,
            ActiveAgentContextRegistry.TypedOwner owner,
            String answer)
    {
        V2UserWaitStore.UserWaitReceipt receipt = waits
                .findUserWaitResult(owner.operationId()).orElse(null);
        if (receipt == null) {
            // The user can answer before dispatch commits USER_WAIT.  The
            // maintenance sweep will retry after the receipt is durable.
            return;
        }
        if (!receipt.owner().equals(owner)
                || !receipt.waitKind().equals(waitKind)
                || !receipt.waitId().equals(waitId)) {
            throw new IllegalStateException(
                    "ready continuation does not match its user-wait receipt");
        }
        switch (owner.kind()) {
            case THREAD_TURN -> {
                if (waitKind.equals("QUESTION")) {
                    trunks.resumeQuestion(waitId);
                }
                else {
                    trunks.resumePermission(waitId);
                }
            }
            case TASK_TURN -> continuePlan(
                    waitKind, waitId, owner, answer);
            case STAGE_TURN -> continueStage(
                    waitKind, waitId, owner, answer);
            case REVIEW_ASSIGNMENT_TURN -> continueReview(
                    waitKind, waitId, owner, answer);
            default -> throw new IllegalStateException(
                    "unsupported typed user-wait owner " + owner.kind());
        }
    }

    private void continuePlan(
            String waitKind,
            String waitId,
            ActiveAgentContextRegistry.TypedOwner owner,
            String answer)
    {
        V2UserWaitStore.WaitOwnerContext context =
                waits.requireWaitOwnerContext(owner);
        if (!context.purpose().equals("PLAN_DRAFT")
                && !context.purpose().equals("PLAN_SELF_REVIEW")) {
            if (!context.purpose().equals("TASK_BRAIN_CONVERSATION")
                    && !context.purpose().equals("DEVELOPMENT_BRAIN_REVIEW")
                    && !context.purpose().equals(
                            "REMOTE_FEEDBACK_BRAIN_REVIEW")) {
                throw new IllegalStateException(
                        "unsupported TaskTurn user-wait purpose "
                                + context.purpose());
            }
            TaskBrainConversationRuntime runtime = taskBrain.getIfAvailable();
            if (runtime == null) {
                return;
            }
            String successor = runtime.continueUserWait(
                    owner.turnId(), owner.operationId(),
                    waitKind, waitId, answer);
            if (successor == null) {
                waits.markContinuationSuperseded(
                        waitKind, waitId,
                        "Task Brain owner or subject is no longer current");
                return;
            }
            markWhenPhysical(waitKind, waitId,
                    DispatchTicket.OwnerKind.TASK_TURN, successor);
            return;
        }
        PlanRuntimeCoordinator runtime = plans.getIfAvailable();
        if (runtime == null) {
            return;
        }
        String successor = runtime.continueUserWait(
                owner.turnId(), owner.operationId(), waitKind, waitId, answer);
        if (successor == null) {
            waits.markContinuationSuperseded(
                    waitKind, waitId, "Plan owner or subject is no longer current");
            return;
        }
        markWhenPhysical(waitKind, waitId,
                DispatchTicket.OwnerKind.TASK_TURN, successor);
    }

    private void continueStage(
            String waitKind,
            String waitId,
            ActiveAgentContextRegistry.TypedOwner owner,
            String answer)
    {
        V2UserWaitStore.WaitOwnerContext context =
                waits.requireWaitOwnerContext(owner);
        V2StageSteeringRuntime runtime = stages.getIfAvailable();
        if (runtime == null || context.taskId() == null || context.stageId() == null) {
            return;
        }
        V2StageSteeringRuntime.ContinuationResult result = runtime.continueUserWait(
                owner.turnId(), owner.operationId(), context.taskId(),
                context.stageId(), waitKind, waitId, answer);
        if (result.status().equals("SUPERSEDED")) {
            waits.markContinuationSuperseded(
                    waitKind, waitId, result.detail());
            return;
        }
        if (result.status().equals("ADMITTED")) {
            markWhenPhysical(waitKind, waitId,
                    DispatchTicket.OwnerKind.STAGE_TURN,
                    result.successorTurnId());
        }
    }

    private void continueReview(
            String waitKind,
            String waitId,
            ActiveAgentContextRegistry.TypedOwner owner,
            String answer)
    {
        ReviewAssignmentTurnRuntime runtime = reviews.getIfAvailable();
        if (runtime == null) {
            return;
        }
        String successor = runtime.continueUserWait(
                owner.turnId(), owner.operationId(), waitKind, waitId, answer);
        if (successor == null) {
            waits.markContinuationSuperseded(
                    waitKind, waitId,
                    "Review assignment or subject is no longer current");
            return;
        }
        markWhenPhysical(waitKind, waitId,
                DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN, successor);
    }

    private void markWhenPhysical(
            String waitKind,
            String waitId,
            DispatchTicket.OwnerKind ownerKind,
            String successor)
    {
        if (successor != null && waits.typedTurnExists(ownerKind, successor)) {
            waits.markContinuationDispatched(waitKind, waitId, successor);
        }
    }
}
