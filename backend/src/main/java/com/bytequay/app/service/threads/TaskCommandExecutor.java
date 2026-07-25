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

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * The one command boundary for lifecycle state writes: <b>acquire the
 * task stripe → begin a fresh transaction → all state writes and audits
 * → commit → unlock</b>. Holding the stripe across the COMMIT is the
 * whole point — the {@code @Transactional}-around-the-lock pattern
 * commits after the stripe unlocks, so a competing transition can read
 * pre-commit state.
 *
 * <p>Commands are top-level entry points: {@link #execute} fails fast
 * when an ambient transaction is already active, because on SQLite's
 * single-writer connection a nested fresh transaction inside an ambient
 * writer would deadlock. Same-transaction projections that run inside a
 * command (machine {@code ...InCommand} primitives, transition-event
 * listeners) never start a second command — they verify the caller's
 * stripe + transaction with {@link #requireCurrent}. After-commit
 * drivers re-enter through {@link #execute} on an executor thread and
 * reload current state there.
 */
@Component
public class TaskCommandExecutor
{
    private static final ThreadLocal<String> CURRENT_TASK = new ThreadLocal<>();

    private final TransactionTemplate transactionTemplate;

    public TaskCommandExecutor(PlatformTransactionManager transactionManager)
    {
        this.transactionTemplate = new TransactionTemplate(
                requireNonNull(transactionManager, "transactionManager is null"));
    }

    /**
     * Run {@code work} as one task command. The stripe is held across
     * the transaction commit, so the next command on this task observes
     * committed state.
     *
     * @throws IllegalStateException when called inside an ambient
     *         transaction or a running command
     */
    public <T> T execute(String taskId, Supplier<T> work)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(work, "work is null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "task command for " + taskId + " cannot start inside an ambient transaction");
        }
        String running = CURRENT_TASK.get();
        if (running != null) {
            throw new IllegalStateException(
                    "task command for " + taskId + " cannot nest inside the command for " + running);
        }
        return TaskPhaseMachine.withTaskLock(taskId, () -> {
            CURRENT_TASK.set(taskId);
            try {
                return transactionTemplate.execute(status -> work.get());
            }
            finally {
                CURRENT_TASK.remove();
            }
        });
    }

    /** {@link #execute} for commands without a result. */
    public void executeVoid(String taskId, Runnable work)
    {
        requireNonNull(work, "work is null");
        execute(taskId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Assert the calling thread already owns {@code taskId}'s command —
     * its stripe and its transaction. In-command primitives call this
     * instead of starting a nested command.
     */
    public static void requireCurrent(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        if (!taskId.equals(CURRENT_TASK.get())) {
            throw new IllegalStateException("no active task command for " + taskId);
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "task command for " + taskId + " has no active transaction");
        }
    }
}
