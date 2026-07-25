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

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Per-task mutual exclusion for commands that perform or gate external
 * effects: pause, the terminal commands, review submission, and (later)
 * the local/auto push sagas. It exists so an effect saga can hold one
 * task-scoped gate across several short task commands without holding
 * the task stripe over network I/O.
 *
 * <p>Lock order is ALWAYS effect gate → task stripe
 * ({@link TaskPhaseMachine#withTaskLock}); never acquire the gate while
 * holding the task lock. Both are reentrant, so a gated command may
 * re-enter either.
 */
public final class TaskExternalEffectGate
{
    private static final ReentrantLock[] GATES = createGates(64);

    private TaskExternalEffectGate() {}

    public static <T> T withEffectGate(String taskId, Supplier<T> action)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(action, "action is null");
        ReentrantLock gate = GATES[Math.floorMod(taskId.hashCode(), GATES.length)];
        gate.lock();
        try {
            return action.get();
        }
        finally {
            gate.unlock();
        }
    }

    private static ReentrantLock[] createGates(int count)
    {
        ReentrantLock[] gates = new ReentrantLock[count];
        Arrays.setAll(gates, ignored -> new ReentrantLock());
        return gates;
    }
}
