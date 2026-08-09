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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Fail-closed compatibility boundary for the retired legacy Task phase
 * machine.  V2 lifecycle state is changed only by typed Task and Stage owner
 * commands.  This bean remains temporarily so read-oriented legacy services
 * can be constructed without retaining any legacy storage or executor graph.
 */
@Component
public class TaskPhaseMachine
{
    private static final Object[] TASK_LOCKS = createTaskLocks(64);
    private static final String RETIRED =
            "TaskPhaseMachine is retired; use typed V2 Task and Stage owner commands";

    /** Historical policy value retained for read-only presentation. */
    public static final int DEFAULT_AUTO_PUSH_CAP = 5;

    /**
     * Compatibility mutex for callers that have not yet moved their local
     * check-then-act section to a typed aggregate command. It owns no state and
     * cannot make a lifecycle transition.
     */
    public static <T> T withTaskLock(String taskId, Supplier<T> action)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(action, "action is null");
        Object lock = TASK_LOCKS[Math.floorMod(taskId.hashCode(), TASK_LOCKS.length)];
        synchronized (lock) {
            return action.get();
        }
    }

    public void transition(String taskId, TaskPhase to, String reason, Actor actor)
    {
        throw retired();
    }

    public void transitionInCommand(String taskId, TaskPhase to, String reason, Actor actor)
    {
        throw retired();
    }

    public Task parkForLocalReviewInCommand(String taskId, Actor actor, String reason)
    {
        throw retired();
    }

    public Task markRemoteInReviewInCommand(String taskId, Actor actor, String reason)
    {
        throw retired();
    }

    public void parkOperationalInCommand(String taskId, Actor actor, String reason)
    {
        throw retired();
    }

    public void observe(String taskId, TaskPhase to, String reason)
    {
        throw retired();
    }

    public void finishTerminalInCommand(
            String taskId, TaskStatus terminalStatus, Actor actor, String reason)
    {
        throw retired();
    }

    public Task pauseInCommand(String taskId, Actor actor, String reason)
    {
        throw retired();
    }

    public Task resumeFromLocalReviewInCommand(String taskId, Actor actor, String reason)
    {
        throw retired();
    }

    private static Object[] createTaskLocks(int count)
    {
        Object[] locks = new Object[count];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private static UnsupportedOperationException retired()
    {
        return new UnsupportedOperationException(RETIRED);
    }
}
