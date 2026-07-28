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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.AuthorityKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.StartContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.StartReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.StartRequest;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/** Synchronously consumes readiness/consent and creates one async merge ticket. */
@Component
public final class RemoteMergeRuntimeCoordinator
{
    private final TaskCommandExecutor commands;
    private final RemoteDevelopmentStageManager remote;
    private final SqliteRemoteMergeRuntimeStore store;
    private final Clock clock;

    @Autowired
    public RemoteMergeRuntimeCoordinator(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            SqliteRemoteMergeRuntimeStore store)
    {
        this(commands, remote, store, Clock.systemUTC());
    }

    RemoteMergeRuntimeCoordinator(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            SqliteRemoteMergeRuntimeStore store,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.store = requireNonNull(store, "store is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Result start(Command command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.taskId(), () -> startInCommand(command));
    }

    private Result startInCommand(Command command)
    {
        StartReceipt duplicate = store.findStart(command.authorizationId())
                .orElse(null);
        if (duplicate != null) {
            requireSame(command, duplicate);
            return new Result(duplicate, CommandResult.Disposition.DUPLICATE);
        }
        StartContext context = store.requireStartContext(
                command.taskId(), command.stageId(),
                command.readinessEvidenceId());
        StartReceipt receipt = store.insertStart(
                new StartRequest(
                        command.authorizationId(), command.operationId(),
                        command.ticketId(), command.authorityKind(), command.actor(),
                        command.attemptLimit(), clock.instant()),
                context);
        ResultFence fence = new ResultFence(
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                command.operationId(), 1, null,
                context.headSha(), context.baseSha());
        CommandResult<StageManager.State> authorized = remote.authorizeMergeInCommand(
                new RemoteDevelopmentStageManager.MergeAuthorizationCommand(
                        new StageManager.Command(
                                command.commandId(), command.actor(), context.taskId(),
                                context.taskEpoch(), context.stageId(),
                                context.stageGeneration(), context.stageVersion()),
                        command.authorizationId(), context.readinessEvidenceId(),
                        context.observationRevision(), context.automationPolicyId(),
                        command.authorizationId(), fence));
        if (authorized.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "current exact-head merge authorization was superseded");
        }
        return new Result(receipt, CommandResult.Disposition.APPLIED);
    }

    private static void requireSame(Command command, StartReceipt receipt)
    {
        boolean same = command.authorizationId().equals(receipt.authorizationId())
                && command.readinessEvidenceId().equals(receipt.readinessEvidenceId())
                && command.authorityKind() == receipt.authorityKind()
                && Objects.equals(
                        command.authorityKind() == AuthorityKind.MANUAL
                                ? command.actor() : null,
                        receipt.actor())
                && command.operationId().equals(receipt.operationId())
                && command.ticketId().equals(receipt.ticketId())
                && command.taskId().equals(receipt.taskId())
                && command.stageId().equals(receipt.stageId())
                && command.attemptLimit() == receipt.attemptLimit();
        if (!same) {
            throw new IllegalArgumentException(
                    "merge authorization id was replayed with different identity");
        }
    }

    public record Command(
            String commandId,
            String actor,
            String taskId,
            String stageId,
            String readinessEvidenceId,
            String authorizationId,
            String operationId,
            String ticketId,
            AuthorityKind authorityKind,
            int attemptLimit)
    {
        public Command
        {
            requireText(commandId, "commandId");
            requireText(actor, "actor");
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(readinessEvidenceId, "readinessEvidenceId");
            requireText(authorizationId, "authorizationId");
            requireText(operationId, "operationId");
            requireText(ticketId, "ticketId");
            requireNonNull(authorityKind, "authorityKind is null");
            if (attemptLimit < 1) {
                throw new IllegalArgumentException("attemptLimit must be positive");
            }
        }
    }

    public record Result(StartReceipt receipt, CommandResult.Disposition disposition)
    {
        public Result
        {
            requireNonNull(receipt, "receipt is null");
            requireNonNull(disposition, "disposition is null");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
