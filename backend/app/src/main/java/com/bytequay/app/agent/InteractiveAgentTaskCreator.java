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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/** Resolves trusted workspace Git facts before core creates an interactive task. */
@Component
public final class InteractiveAgentTaskCreator
{
    private final AgentTaskService tasks;
    private final AgentTurnService turns;
    private final AgentDriverRegistry drivers;
    private final ModelSettingsService models;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;
    private final Striped<Lock> creationLocks = Striped.lock(64);

    public InteractiveAgentTaskCreator(
            AgentTaskService tasks,
            AgentTurnService turns,
            AgentDriverRegistry drivers,
            ModelSettingsService models,
            WorkspaceRepositoryService workspaces,
            GitCommandRunner git)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.models = requireNonNull(models, "models is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
    }

    public AgentTask create(
            TaskId taskId,
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        Lock lock = creationLocks.get(taskId);
        lock.lock();
        try {
            return createLocked(taskId, workspaceId, message, laneId, defaults, idempotencyKey);
        }
        finally {
            lock.unlock();
        }
    }

    private AgentTask createLocked(
            TaskId taskId,
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            String idempotencyKey)
    {
        checkNotBlank(workspaceId, "workspaceId");
        checkNotBlank(message, "message");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(defaults, "defaults is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        String fingerprint = fingerprint(workspaceId, message, laneId, defaults, idempotencyKey);
        Optional<AgentTask> replay = interactiveTask(workspaceId, taskId);
        if (replay.isPresent()) {
            AgentTask task = requireReplay(replay.orElseThrow(), fingerprint);
            if (turns.findTurnByIdempotencyKey(taskId, idempotencyKey).isEmpty()) {
                requireReady(laneId, defaults);
            }
            return task;
        }
        requireReady(laneId, defaults);
        models.validateSelection(laneId.value(), defaults.model().orElseThrow(() -> new IllegalArgumentException("model must not be blank")), defaults.effort());
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(workspaceId);
        String startRef = "refs/remotes/origin/" + workspace.defaultBranch() + "^{commit}";
        Result head = git.run(workspace.repository(), "rev-parse", "--verify", startRef);
        if (!head.successful() || head.truncated() || head.output().isBlank()) {
            throw new AgentStorageException("Cannot resolve the workspace start commit");
        }
        try {
            return tasks.createInteractive(new CreateInteractiveAgentTaskCommand(
                    taskId,
                    workspace.workspaceId(),
                    laneId,
                    new RepositoryIdentity(
                            workspace.selectedRepository().owner(),
                            workspace.selectedRepository().repo()),
                    head.output().strip(),
                    message,
                    defaults,
                    fingerprint));
        }
        catch (AgentConflictException conflict) {
            return interactiveTask(workspaceId, taskId)
                    .map(task -> requireReplay(task, fingerprint))
                    .orElseThrow(() -> conflict);
        }
    }

    private Optional<AgentTask> interactiveTask(String workspaceId, TaskId taskId)
    {
        try {
            return Optional.of(tasks.getInteractive(workspaceId, taskId));
        }
        catch (AgentTaskNotFoundException ignored) {
            return Optional.empty();
        }
    }

    private static AgentTask requireReplay(AgentTask task, String fingerprint)
    {
        if (!task.creationFingerprint().equals(Optional.of(fingerprint))) {
            throw new AgentConflictException("Task creation request changed: " + task.id());
        }
        return task;
    }

    private static String fingerprint(
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            String idempotencyKey)
    {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
        add(digest, workspaceId);
        add(digest, message);
        add(digest, laneId.value());
        add(digest, defaults.model().orElse(""));
        add(digest, defaults.effort().orElse(""));
        add(digest, defaults.permissionPolicy().name());
        add(digest, idempotencyKey);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private void requireReady(AgentLaneId laneId, AgentTaskDefaults defaults)
    {
        AgentDriver driver = drivers.require(laneId);
        if (!driver.capabilities().permissionPolicies().contains(defaults.permissionPolicy())) {
            throw new AgentUnavailableException("Agent lane " + laneId + " cannot enforce " + defaults.permissionPolicy());
        }
        AgentProviderStatus status = driver.probe();
        if (!status.available()) {
            throw new AgentProviderUnavailableException(status);
        }
    }
}
