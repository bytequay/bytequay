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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.AcceptedApproval;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.ApprovalContext;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskReplanMaintainer;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore.ReplanProgress;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore.ReplanSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Narrow compatibility adapter from existing Plan HTTP actions to V2 owners. */
@Component
public final class V2PlanControlService
{
    private static final String ACTOR = "user";

    private final PlanRuntimeCoordinator runtime;
    private final SqlitePlanRuntimeStore planStore;
    private final PlanStageManager plan;
    private final TaskManager tasks;
    private final SqliteTaskControlRuntimeStore taskStore;
    private final TaskReplanMaintainer replans;
    private final Clock clock;

    @Autowired
    public V2PlanControlService(
            PlanRuntimeCoordinator runtime,
            SqlitePlanRuntimeStore planStore,
            PlanStageManager plan,
            TaskManager tasks,
            SqliteTaskControlRuntimeStore taskStore,
            TaskReplanMaintainer replans)
    {
        this(runtime, planStore, plan, tasks, taskStore, replans,
                Clock.systemUTC());
    }

    public V2PlanControlService(
            PlanRuntimeCoordinator runtime,
            SqlitePlanRuntimeStore planStore,
            PlanStageManager plan,
            TaskManager tasks,
            SqliteTaskControlRuntimeStore taskStore,
            TaskReplanMaintainer replans,
            Clock clock)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.planStore = requireNonNull(planStore, "planStore is null");
        this.plan = requireNonNull(plan, "plan is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.replans = requireNonNull(replans, "replans is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Approval approve(String planStageId)
    {
        ApprovalContext context = planStore.findLatestApprovalContext(planStageId)
                .orElseThrow(() -> conflict(
                        "latest Plan revision is not eligible for approval"));
        try {
            AcceptedApproval accepted = runtime.approvePlan(
                    new PlanRuntimeCoordinator.PlanApprovalCommand(
                            UUID.randomUUID().toString(), ACTOR, context.taskId(),
                            context.taskEpoch(), context.taskVersion(),
                            context.stageId(), context.stageGeneration(),
                            context.stageVersion(), context.revisionId(),
                            context.selfReviewId()));
            return new Approval(accepted.taskId(), accepted.localStageId());
        }
        catch (CommandRejectedException | IllegalArgumentException failure) {
            throw conflict(failure.getMessage());
        }
    }

    public Replan replan(String taskId)
    {
        ReplanSource source;
        try {
            source = taskStore.requireReplanSource(taskId);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage());
        }
        String requestId = UUID.randomUUID().toString();
        String barrierId = PlanRuntimeCoordinator.id(
                "replan-barrier", requestId);
        TaskManager.ReplanRequest requested;
        try {
            requested = tasks.requestReplan(new TaskManager.ReplanRequestCommand(
                    new TaskManager.Command(
                            requestId, ACTOR, source.taskId(), source.taskEpoch(),
                            source.taskVersion()),
                    requestId, barrierId, source.stageId(),
                    source.stageGeneration(), source.stageKind(),
                    "user requested replan"));
        }
        catch (CommandRejectedException | IllegalArgumentException failure) {
            throw conflict(failure.getMessage());
        }
        replans.maintain(requested.requestId(), clock.instant());
        ReplanProgress progress = taskStore.replanProgress(requested.requestId())
                .orElseThrow(() -> new IllegalStateException(
                        "Replan request disappeared: " + requested.requestId()));
        return new Replan(
                progress.newPlanStageId(), !"APPLIED".equals(progress.status()));
    }

    public void resolveFollowup(
            String taskId,
            String planStageId,
            String followupId,
            String status)
    {
        PlanStageManager.FollowupStatus target = switch (normalize(status)) {
            case "addressed" -> PlanStageManager.FollowupStatus.RESOLVED;
            case "dismissed" -> PlanStageManager.FollowupStatus.DEFERRED;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "status must be 'addressed' or 'dismissed'");
        };
        PlanStageManager.FollowupEvidence current = planStore.find(
                        taskId, planStageId, followupId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no Plan follow-up: " + followupId));
        try {
            plan.resolveFollowup(new PlanStageManager.FollowupCommand(
                    taskId, planStageId, current.stageGeneration(), followupId,
                    target, ACTOR, normalize(status), clock.instant()));
        }
        catch (CommandRejectedException | IllegalArgumentException failure) {
            throw conflict(failure.getMessage());
        }
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record Approval(String taskId, String localStageId) {}

    public record Replan(String planStageId, boolean preparing) {}
}
