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
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.agent.AgentTaskStartupProgress.Phase;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
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
import static com.bytequay.app.agent.WorkflowAgentTasks.repositoryIdentity;
import static com.bytequay.app.agent.WorkflowAgentTasks.requireReady;
import static java.util.Objects.requireNonNull;

/** Resolves trusted workspace Git facts before core creates an interactive task. */
@Component
public final class InteractiveAgentTaskCreator
{
    private final AgentTaskService tasks;
    private final AgentTurnService turns;
    private final AgentDriverRegistry drivers;
    private final ModelSettingsService models;
    private final AppSettingsService settings;
    private final WorkspaceRepositoryService workspaces;
    private final WorkspaceProvisioningService provisioning;
    private final GitCommandRunner git;
    private final AgentTaskStartupProgress progress;
    private final CredentialService credentials;
    private final Striped<Lock> creationLocks = Striped.lock(64);

    public InteractiveAgentTaskCreator(
            AgentTaskService tasks,
            AgentTurnService turns,
            AgentDriverRegistry drivers,
            ModelSettingsService models,
            WorkspaceRepositoryService workspaces,
            WorkspaceProvisioningService provisioning,
            GitCommandRunner git,
            AppSettingsService settings,
            AgentTaskStartupProgress progress,
            CredentialService credentials)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.models = requireNonNull(models, "models is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.git = requireNonNull(git, "git is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.progress = requireNonNull(progress, "progress is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
    }

    public AgentTask create(
            TaskId taskId,
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            String idempotencyKey)
    {
        return create(taskId, workspaceId, message, laneId, defaults, idempotencyKey, Optional.empty());
    }

    public AgentTask create(
            TaskId taskId,
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            String idempotencyKey,
            Optional<String> attachmentDigest)
    {
        return create(taskId, workspaceId, message, laneId, defaults, idempotencyKey, attachmentDigest, Optional.empty());
    }

    public AgentTask create(
            TaskId taskId,
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            String idempotencyKey,
            Optional<String> attachmentDigest,
            Optional<DevelopmentAutomation> initialAutomation)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(initialAutomation, "initialAutomation is null");
        requireNonNull(attachmentDigest, "attachmentDigest is null");
        Lock lock = creationLocks.get(taskId);
        lock.lock();
        try {
            return createLocked(taskId, workspaceId, message, laneId, defaults, idempotencyKey, attachmentDigest, initialAutomation);
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
            String idempotencyKey,
            Optional<String> attachmentDigest,
            Optional<DevelopmentAutomation> initialAutomation)
    {
        checkNotBlank(workspaceId, "workspaceId");
        checkNotBlank(message, "message");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(defaults, "defaults is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        String fingerprint = fingerprint(workspaceId, message, laneId, defaults, idempotencyKey, attachmentDigest, initialAutomation);
        Optional<AgentTask> replay = interactiveTask(workspaceId, taskId);
        if (replay.isPresent()) {
            AgentTask task = requireReplay(replay.orElseThrow(), fingerprint);
            if (turns.findTurnByIdempotencyKey(taskId, idempotencyKey).isEmpty()) {
                requireReady(drivers, laneId, defaults.permissionPolicy());
            }
            return task;
        }
        DevelopmentAutomation automation = defaults.developmentAutomation().orElseGet(settings::developmentAutomation);
        settings.validateDevelopmentAutomation(automation);
        initialAutomation.ifPresent(settings::validateDevelopmentAutomation);
        progress.record(taskId, Phase.PROVIDER);
        requireReady(drivers, laneId, defaults.permissionPolicy());
        models.validateSelection(
                laneId.value(),
                defaults.model().orElseThrow(() -> new IllegalArgumentException("model must not be blank")),
                defaults.effort(),
                defaults.serviceTier());
        progress.record(taskId, Phase.REPOSITORY);
        if (!provisioning.synchronizeCode(workspaceId).successful()) {
            throw new AgentUnavailableException(
                    "Cannot start task: workspace repository synchronization failed. Check repository access and connectivity, then retry.");
        }
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(workspaceId);
        String startRef = "refs/remotes/" + workspace.sourceRemote() + "/" + workspace.defaultBranch() + "^{commit}";
        Result head = git.run(workspace.repository(), "rev-parse", "--verify", startRef);
        if (!head.successful() || head.truncated() || head.output().isBlank()) {
            throw new AgentStorageException("Cannot resolve the workspace start commit");
        }
        progress.record(taskId, Phase.WORKTREE);
        try {
            return tasks.createInteractive(new CreateInteractiveAgentTaskCommand(
                    taskId,
                    workspace.workspaceId(),
                    laneId,
                    repositoryIdentity(workspace.selectedRepository()),
                    head.output().strip(),
                    TaskBranches.generate(taskId, credentials.resolveGitHubCredential().profile().login(), message),
                    message,
                    defaults.withDevelopmentAutomation(automation),
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
            String idempotencyKey,
            Optional<String> attachmentDigest,
            Optional<DevelopmentAutomation> initialAutomation)
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
        defaults.serviceTier().ifPresent(value -> add(digest, "serviceTier=" + value));
        add(digest, defaults.permissionPolicy().name());
        defaults.developmentAutomation().ifPresent(value -> add(digest, "defaults.automation=" + value));
        initialAutomation.ifPresent(value -> add(digest, "initial.automation=" + value));
        add(digest, idempotencyKey);
        attachmentDigest.ifPresent(value -> add(digest, value));
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
}
