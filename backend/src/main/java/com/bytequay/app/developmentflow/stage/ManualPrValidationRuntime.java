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

import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.Operation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.RequestContext;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

/** Authorizes a durable manual validation and waits for the desktop UI call. */
@Service
public final class ManualPrValidationRuntime
{
    private static final Duration WAIT_TIMEOUT = Duration.ofMinutes(10);
    private static final long POLL_MILLIS = 100;

    private final SqliteManualPrValidationStore store;
    private final TaskCommandExecutor commands;
    private final Clock clock;
    private final Duration waitTimeout;

    @Autowired
    public ManualPrValidationRuntime(
            SqliteManualPrValidationStore store,
            TaskCommandExecutor commands)
    {
        this(store, commands, Clock.systemUTC());
    }

    ManualPrValidationRuntime(
            SqliteManualPrValidationStore store,
            TaskCommandExecutor commands,
            Clock clock)
    {
        this(store, commands, clock, WAIT_TIMEOUT);
    }

    ManualPrValidationRuntime(
            SqliteManualPrValidationStore store,
            TaskCommandExecutor commands,
            Clock clock,
            Duration waitTimeout)
    {
        this.store = requireNonNull(store, "store is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.waitTimeout = requireNonNull(waitTimeout, "waitTimeout is null");
        if (waitTimeout.isNegative()) {
            throw new IllegalArgumentException("waitTimeout is negative");
        }
    }

    public Operation runAndWait(String commandId, String prId)
    {
        requireText(commandId, "commandId");
        RequestContext context = store.requireRequestContext(prId);
        Operation operation = commands.execute(context.taskId(), () ->
                store.request(commandId, context, clock.instant()));
        return await(operation.id());
    }

    private Operation await(String operationId)
    {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        while (true) {
            Operation operation = store.requireOperation(operationId);
            if (operation.terminal()) {
                return operation;
            }
            if (System.nanoTime() >= deadline) {
                return operation;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted waiting for Manual PR validation", e);
            }
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
