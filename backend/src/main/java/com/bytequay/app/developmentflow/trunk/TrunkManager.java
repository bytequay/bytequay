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
package com.bytequay.app.developmentflow.trunk;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.util.Optional;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.COMMAND_ID_CONFLICT;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.NOT_FOUND;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static java.util.Objects.requireNonNull;

/** Sole synchronous writer for V2 Trunk lifecycle. */
public final class TrunkManager
{
    private final TaskCommandExecutor commands;
    private final Store store;

    public TrunkManager(TaskCommandExecutor commands, Store store)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.store = requireNonNull(store, "store is null");
    }

    public CommandResult<State> markIdle(Command command)
    {
        return apply(command, TrunkLifecycle.IDLE, "MARK_IDLE");
    }

    public CommandResult<State> activate(Command command)
    {
        return apply(command, TrunkLifecycle.ACTIVE, "ACTIVATE");
    }

    public CommandResult<State> archive(Command command)
    {
        return apply(command, TrunkLifecycle.ARCHIVED, "ARCHIVE");
    }

    private CommandResult<State> apply(
            Command command, TrunkLifecycle target, String cause)
    {
        requireNonNull(command, "command is null");
        String scope = scope(command.trunkId());
        return commands.execute(scope, () -> applyInCommand(command, target, cause, scope));
    }

    private CommandResult<State> applyInCommand(
            Command command, TrunkLifecycle target, String cause, String scope)
    {
        TaskCommandExecutor.requireCurrent(scope);
        Optional<CommandReceipt> duplicate = store.findCommandResult(
                command.trunkId(), command.commandId());
        if (duplicate.isPresent()) {
            CommandReceipt receipt = duplicate.orElseThrow();
            if (!receipt.cause().equals(cause)
                    || !receipt.actor().equals(command.actor())
                    || receipt.expectedVersion() != command.expectedVersion()
                    || receipt.disposition() != CommandResult.Disposition.APPLIED) {
                throw rejected(COMMAND_ID_CONFLICT,
                        "Command id was already used for another Trunk command");
            }
            return CommandResult.duplicate(receipt.state());
        }

        State current = store.findById(command.trunkId())
                .orElseThrow(() -> rejected(NOT_FOUND, "Trunk not found: " + command.trunkId()));
        if (current.version() != command.expectedVersion()) {
            throw rejected(STALE_VERSION, "Stale Trunk version for " + command.trunkId());
        }
        if (!current.lifecycle().allows(target)) {
            throw rejected(INVALID_STATE,
                    "Cannot " + target + " Trunk from " + current.lifecycle());
        }

        State updated = new State(current.id(), target, current.version() + 1);
        return CommandResult.applied(store.commit(
                command.commandId(), cause, command.actor(), command.expectedVersion(),
                current, updated));
    }

    private static String scope(String trunkId)
    {
        return "v2-trunk/" + trunkId;
    }

    private static CommandRejectedException rejected(
            CommandRejectedException.Reason reason, String message)
    {
        return new CommandRejectedException(reason, message);
    }

    public record Command(
            String commandId, String actor, String trunkId, long expectedVersion)
    {
        public Command
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(trunkId, "trunkId");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
        }
    }

    public record State(String id, TrunkLifecycle lifecycle, long version)
    {
        public State
        {
            requireText(id, "id");
            requireNonNull(lifecycle, "lifecycle is null");
            if (version < 0) {
                throw new IllegalArgumentException("version is negative");
            }
        }
    }

    /** Immutable replay result, stored separately from versioned transition audit. */
    public record CommandReceipt(
            State state,
            String cause,
            String actor,
            long expectedVersion,
            CommandResult.Disposition disposition)
    {
        public CommandReceipt
        {
            requireNonNull(state, "state is null");
            requireText(cause, "cause");
            requireText(actor, "actor");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is negative");
            }
            requireNonNull(disposition, "disposition is null");
            if (disposition != CommandResult.Disposition.APPLIED) {
                throw new IllegalArgumentException("Trunk receipt must describe an applied command");
            }
        }
    }

    /** Persistence port. Commit must atomically compare version and record command id. */
    public interface Store
    {
        Optional<State> findById(String trunkId);

        Optional<CommandReceipt> findCommandResult(String trunkId, String commandId);

        State commit(
                String commandId,
                String cause,
                String actor,
                long expectedVersion,
                State expected,
                State updated);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
