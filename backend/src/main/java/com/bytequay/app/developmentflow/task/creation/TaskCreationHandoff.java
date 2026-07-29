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
package com.bytequay.app.developmentflow.task.creation;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.nio.file.Path;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Two trunk-striped command transactions: durable authorization, then an atomic Task bundle. */
public final class TaskCreationHandoff
{
    private final TaskCommandExecutor commands;
    private final TrunkManager trunks;
    private final TaskManager tasks;
    private final IdGenerator ids;

    public TaskCreationHandoff(
            TaskCommandExecutor commands,
            TrunkManager trunks,
            TaskManager tasks,
            IdGenerator ids)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.trunks = requireNonNull(trunks, "trunks is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.ids = requireNonNull(ids, "ids is null");
    }

    public Result create(Command command)
    {
        requireNonNull(command, "command is null");
        String trunkId = command.authorization().input()
                .assignment().identity().trunkId();
        return create(trunkId, () -> command);
    }

    /** Builds the version-fenced authorization while holding the Trunk stripe. */
    public Result create(String trunkId, Supplier<Command> commandFactory)
    {
        requireText(trunkId, "trunkId");
        requireNonNull(commandFactory, "commandFactory is null");
        Prepared prepared = commands.execute("v2-trunk/" + trunkId, () -> {
            Command command = requireNonNull(
                    commandFactory.get(), "commandFactory returned null");
            String commandTrunk = command.authorization().input()
                    .assignment().identity().trunkId();
            if (!trunkId.equals(commandTrunk)) {
                throw new IllegalArgumentException(
                        "Task creation command does not belong to its Trunk stripe");
            }
            return new Prepared(
                    command,
                    trunks.authorizeTaskCreationInCommand(command.authorization()));
        });
        return commands.execute("v2-trunk/" + trunkId, () -> {
            TaskManager.TaskCreationResult created = tasks.createTaskInCommand(
                    prepared.authorization(), prepared.command().repositoryRoot(), ids);
            return new Result(
                    prepared.authorization().trunkState(), created,
                    created.disposition());
        });
    }

    private record Prepared(
            Command command,
            TrunkManager.AuthorizedTaskCreation authorization)
    {}

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public record Command(
            TrunkManager.TaskCreationCommand authorization,
            Path repositoryRoot)
    {
        public Command
        {
            requireNonNull(authorization, "authorization is null");
            requireNonNull(repositoryRoot, "repositoryRoot is null");
            if (!repositoryRoot.isAbsolute()) {
                throw new IllegalArgumentException(
                        "repositoryRoot must be absolute");
            }
            repositoryRoot = repositoryRoot.normalize();
        }
    }

    public record Result(
            TrunkManager.State trunk,
            TaskManager.TaskCreationResult task,
            CommandResult.Disposition disposition)
    {
        public Result
        {
            requireNonNull(trunk, "trunk is null");
            requireNonNull(task, "task is null");
            requireNonNull(disposition, "disposition is null");
        }
    }
}
