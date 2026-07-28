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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.CONCURRENT_UPDATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.NOT_FOUND;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_EPOCH;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static java.util.Objects.requireNonNull;
import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * HTTP-facing V2 Task controls. The Task manager records intent first; exact
 * non-terminal tickets are then canceled through the dispatcher. Completion
 * remains proof-gated by the Task control and Cleanup handoffs.
 */
public final class V2TaskControlService
{
    private static final String ACTOR = "user";
    private static final String IDLE_ARCHIVER = "task-idle-archiver";

    private final TaskManager tasks;
    private final TaskManager.Store store;
    private final DispatchTicketControl tickets;
    private final RemoteCiRepairRuntimeCoordinator ciRepair;
    private final JdbcTemplate jdbc;

    public V2TaskControlService(
            TaskManager tasks,
            TaskManager.Store store,
            DispatchTicketControl tickets,
            RemoteCiRepairRuntimeCoordinator ciRepair,
            JdbcTemplate jdbc)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.store = requireNonNull(store, "store is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.ciRepair = requireNonNull(ciRepair, "ciRepair is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public TaskManager.State cancel(String taskId)
    {
        TaskManager.State current = requireTask(taskId);
        if (current.lifecycle() == TaskLifecycle.CANCELING
                || current.lifecycle() == TaskLifecycle.CLEANING
                || current.lifecycle() == TaskLifecycle.CANCELED) {
            cancelLiveTickets(taskId);
            return current;
        }
        TaskManager.State requested = tasks.requestCancel(command(current)).state();
        cancelLiveTickets(taskId);
        return requested;
    }

    public TaskManager.State pause(String taskId)
    {
        TaskManager.State current = requireTask(taskId);
        if (current.lifecycle() == TaskLifecycle.PAUSING
                || current.lifecycle() == TaskLifecycle.PAUSED) {
            cancelLiveTickets(taskId);
            return current;
        }
        TaskManager.State requested = tasks.requestPause(command(current)).state();
        cancelLiveTickets(taskId);
        return requested;
    }

    public TaskManager.State resume(String taskId)
    {
        TaskManager.State current = requireTask(taskId);
        if (current.lifecycle() == TaskLifecycle.ACTIVE
                || current.lifecycle() == TaskLifecycle.RESUMING) {
            return current;
        }
        return tasks.requestResume(command(current)).state();
    }

    public List<String> idleArchiveCandidates(
            Instant cutoff, Instant observedAt, int limit)
    {
        return store.findIdleArchiveCandidates(cutoff, observedAt, limit);
    }

    /** Rechecks typed liveness inside the Task command before recording ARCHIVING. */
    public boolean archiveIfIdle(
            String taskId, Instant cutoff, Instant observedAt)
    {
        requireNonNull(taskId, "taskId is null");
        TaskManager.State current = store.findById(taskId).orElse(null);
        if (current == null) {
            return false;
        }
        try {
            return tasks.requestArchiveIfIdle(
                    command(current, IDLE_ARCHIVER), cutoff, observedAt).isPresent();
        }
        catch (CommandRejectedException raced) {
            if (raced.reason() == STALE_VERSION
                    || raced.reason() == STALE_EPOCH
                    || raced.reason() == INVALID_STATE
                    || raced.reason() == NOT_FOUND
                    || raced.reason() == CONCURRENT_UPDATE) {
                return false;
            }
            throw raced;
        }
    }

    /** One explicit user action extends each independent CI repair budget once. */
    public TaskManager.State retryFailedCi(String taskId)
    {
        TaskManager.State current = requireTask(taskId);
        String episodeId = jdbc.query("""
                SELECT episode.id
                FROM ci_repair_episode episode
                JOIN tasks task ON task.id = episode.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                WHERE episode.task_id = ?
                  AND episode.status = 'EXHAUSTED'
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = episode.task_epoch
                  AND current.stage_id = episode.remote_development_stage_id
                  AND current.stage_generation = episode.stage_generation
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.completed_at_ms IS NULL
                ORDER BY episode.completed_at_ms DESC
                LIMIT 1
                """, (rs, row) -> rs.getString("id"), taskId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        CONFLICT, "task " + taskId
                                + " has no current exhausted CI repair episode"));
        ciRepair.extendBudget(
                taskId, episodeId, UUID.randomUUID().toString(),
                1, 1, 1, ACTOR, "explicit Retry CI action");
        return current;
    }

    public boolean isAutoApprove(String taskId)
    {
        return tasks.policy(taskId).autoApprove();
    }

    public boolean isAutoMerge(String taskId)
    {
        return tasks.policy(taskId).autoMerge();
    }

    public int minApprovals(String taskId)
    {
        return tasks.policy(taskId).minApprovals();
    }

    public boolean setAutoApprove(String taskId, boolean enabled)
    {
        TaskManager.PolicyRevision current = tasks.policy(taskId);
        boolean autoMerge = enabled && current.autoMerge();
        return revisePolicy(
                current, enabled, autoMerge, current.minApprovals()).autoApprove();
    }

    public boolean setAutoMerge(String taskId, boolean enabled)
    {
        TaskManager.PolicyRevision current = tasks.policy(taskId);
        return revisePolicy(
                current, enabled || current.autoApprove(), enabled,
                current.minApprovals()).autoMerge();
    }

    public int setMinApprovals(String taskId, int minApprovals)
    {
        TaskManager.PolicyRevision current = tasks.policy(taskId);
        return revisePolicy(
                current, current.autoApprove(), current.autoMerge(),
                minApprovals).minApprovals();
    }

    private TaskManager.PolicyRevision revisePolicy(
            TaskManager.PolicyRevision current,
            boolean autoApprove,
            boolean autoMerge,
            int minApprovals)
    {
        if (current.autoApprove() == autoApprove
                && current.autoMerge() == autoMerge
                && current.minApprovals() == minApprovals) {
            return current;
        }
        TaskManager.State task = requireTask(current.taskId());
        return tasks.revisePolicy(new TaskManager.PolicyCommand(
                command(task), UUID.randomUUID().toString(), autoApprove,
                autoMerge, minApprovals, current.maxBrainRounds(),
                current.maxCiFixPushes(),
                current.requireRemoteBranchCleanup(),
                current.permissionPolicyRef())).state();
    }

    private void cancelLiveTickets(String taskId)
    {
        liveTicketIds(taskId).forEach(tickets::requestCancel);
    }

    private List<String> liveTicketIds(String taskId)
    {
        return jdbc.query("""
                SELECT id
                FROM dispatch_ticket
                WHERE task_id = ?
                  AND status IN (
                      'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                      'RESULT_PENDING', 'CLAIMED', 'RUNNING')
                ORDER BY created_at_ms, id
                """, (rs, row) -> rs.getString("id"), taskId);
    }

    private TaskManager.State requireTask(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return store.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no V2 Task: " + taskId));
    }

    private static TaskManager.Command command(TaskManager.State state)
    {
        return command(state, ACTOR);
    }

    private static TaskManager.Command command(
            TaskManager.State state, String actor)
    {
        return new TaskManager.Command(
                UUID.randomUUID().toString(), actor, state.id(),
                state.epoch(), state.version());
    }
}
