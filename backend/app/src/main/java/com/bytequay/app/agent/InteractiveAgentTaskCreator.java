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
import com.bytequay.agent.core.AgentTaskTitleService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskNames;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.agent.AgentTaskStartupProgress.Phase;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.app.agent.WorkflowAgentTasks.repositoryIdentity;
import static com.bytequay.app.agent.WorkflowAgentTasks.requireReady;
import static com.google.common.base.Preconditions.checkArgument;
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
    private final AgentTaskTitleService names;
    private final PullRequestStore pullRequests;
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
            AgentTaskTitleService names,
            PullRequestStore pullRequests,
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
        this.names = requireNonNull(names, "names is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
    }

    public AgentTask create(
            TaskId taskId,
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            String idempotencyKey,
            Optional<String> attachmentDigest,
            Optional<DevelopmentAutomation> initialAutomation,
            Optional<GitHubIssueSource> issueSource,
            Optional<Consumer<AgentTask>> persistTask)
    {
        return create(taskId, workspaceId, message, laneId, defaults, idempotencyKey, attachmentDigest, initialAutomation, issueSource, Optional.empty(), persistTask);
    }

    public AgentTask create(
            TaskId taskId,
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            String idempotencyKey,
            Optional<String> attachmentDigest,
            Optional<DevelopmentAutomation> initialAutomation,
            Optional<GitHubIssueSource> issueSource,
            Optional<JiraIssueSource> jiraSource,
            Optional<Consumer<AgentTask>> persistTask)
    {
        return create(taskId, workspaceId, message, laneId, defaults, idempotencyKey, attachmentDigest, initialAutomation, issueSource, jiraSource, persistTask, Optional.empty());
    }

    public AgentTask createRelated(TaskId taskId, String workspaceId, String message, AgentLaneId laneId, AgentTaskDefaults defaults,
            String idempotencyKey, Optional<String> startCommit)
    {
        startCommit.ifPresent(value -> checkArgument(value.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Investigation requires a full commit SHA"));
        try {
            return create(taskId, workspaceId, message, laneId, defaults, idempotencyKey, Optional.empty(), defaults.developmentAutomation(),
                    Optional.empty(), Optional.empty(), Optional.empty(), startCommit);
        }
        finally {
            progress.clear(taskId);
        }
    }

    private AgentTask create(TaskId taskId, String workspaceId, String message, AgentLaneId laneId, AgentTaskDefaults defaults,
            String idempotencyKey, Optional<String> attachmentDigest, Optional<DevelopmentAutomation> initialAutomation,
            Optional<GitHubIssueSource> issueSource, Optional<JiraIssueSource> jiraSource, Optional<Consumer<AgentTask>> persistTask, Optional<String> startCommit)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(persistTask, "persistTask is null");
        requireNonNull(issueSource, "issueSource is null");
        requireNonNull(jiraSource, "jiraSource is null");
        checkArgument(issueSource.isEmpty() || jiraSource.isEmpty(), "Choose only one issue source");
        requireNonNull(initialAutomation, "initialAutomation is null");
        requireNonNull(attachmentDigest, "attachmentDigest is null");
        Lock lock = creationLocks.get(taskId);
        lock.lock();
        try {
            return createLocked(taskId, workspaceId, message, laneId, defaults, idempotencyKey, attachmentDigest, initialAutomation,
                    issueSource, jiraSource, persistTask, startCommit);
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
            Optional<DevelopmentAutomation> initialAutomation,
            Optional<GitHubIssueSource> issueSource,
            Optional<JiraIssueSource> jiraSource,
            Optional<Consumer<AgentTask>> persistTask,
            Optional<String> startCommit)
    {
        checkNotBlank(workspaceId, "workspaceId");
        checkNotBlank(message, "message");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(defaults, "defaults is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        String baseFingerprint = fingerprint(workspaceId, message, laneId, defaults, idempotencyKey, attachmentDigest, initialAutomation,
                issueSource.map(source -> source.host() + ":" + source.issueId()).or(() -> jiraSource.map(source -> "jira:" + source.identity())));
        String fingerprint = startCommit
                .map(commit -> Hashing.sha256().hashString(baseFingerprint + ":commit=" + commit, StandardCharsets.UTF_8).toString()).orElse(baseFingerprint);
        Optional<AgentTask> replay = interactiveTask(workspaceId, taskId);
        if (replay.isPresent()) {
            AgentTask task = requireReplay(replay.orElseThrow(), fingerprint);
            if (turns.findTurnByIdempotencyKey(taskId, idempotencyKey).isEmpty()) {
                requireReady(drivers, laneId, defaults.permissionPolicy(), defaults.model());
            }
            return task;
        }
        DevelopmentAutomation automation = defaults.developmentAutomation().orElseGet(settings::developmentAutomation);
        settings.validateDevelopmentAutomation(automation);
        initialAutomation.ifPresent(settings::validateDevelopmentAutomation);
        progress.record(taskId, Phase.PROVIDER);
        requireReady(drivers, laneId, defaults.permissionPolicy(), defaults.model());
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
        String startRef = startCommit.orElse("refs/remotes/" + workspace.sourceRemote() + "/" + workspace.defaultBranch()) + "^{commit}";
        Result head = git.run(workspace.repository(), "rev-parse", "--verify", startRef);
        if (!head.successful() || head.truncated() || head.output().isBlank() || startCommit.filter(commit -> !commit.equals(head.output().strip())).isPresent()) {
            throw new AgentStorageException("Cannot resolve the workspace start commit");
        }
        progress.record(taskId, Phase.NAMING);
        String context = issueSource.map(source -> "GitHub issue #" + source.number() + ": " + source.title() + "\n" + source.body()
                + "\n\nUser request:\n" + message)
                .or(() -> jiraSource.map(source -> "Jira " + source.key() + ": " + source.title() + "\n" + source.body() + "\n\nUser request:\n" + message)).orElse(message);
        Optional<TaskNames> generated = names.generate(laneId, defaults, context);
        String branch = TaskBranches.prefix(credentials.resolveGitHubCredential().profile().login())
                + generated.map(TaskNames::branch).orElse("task-" + taskId.toString().substring(0, 8));
        progress.record(taskId, Phase.WORKTREE);
        Lock workspaceLock = WorkspaceOperationLocks.get(workspaceId);
        workspaceLock.lock();
        try {
            Result existing = git.run(workspace.repository(), "for-each-ref", "--format=%(refname)",
                    "refs/heads/" + branch, "refs/remotes/origin/" + branch, "refs/remotes/upstream/" + branch);
            if (!existing.successful() || existing.truncated()) {
                throw new AgentStorageException("Cannot check the generated task branch");
            }
            if (!existing.output().isBlank() || !pullRequests.findByHeadRef(workspace.monitoredRepository().fullName(), branch).isEmpty()) {
                branch += "-" + taskId.toString().substring(0, 8);
            }
            CreateInteractiveAgentTaskCommand command = new CreateInteractiveAgentTaskCommand(
                    taskId,
                    workspace.workspaceId(),
                    laneId,
                    repositoryIdentity(workspace.selectedRepository()),
                    head.output().strip(),
                    branch,
                    message,
                    defaults.withDevelopmentAutomation(automation),
                    fingerprint,
                    generated);
            return persistTask.map(writer -> tasks.createInteractive(command, writer)).orElseGet(() -> tasks.createInteractive(command));
        }
        catch (AgentConflictException conflict) {
            return interactiveTask(workspaceId, taskId)
                    .map(task -> requireReplay(task, fingerprint))
                    .orElseThrow(() -> conflict);
        }
        finally {
            workspaceLock.unlock();
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

    static String fingerprint(
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            String idempotencyKey,
            Optional<String> attachmentDigest,
            Optional<DevelopmentAutomation> initialAutomation,
            Optional<String> issueIdentity)
    {
        Hasher digest = Hashing.sha256().newHasher();
        add(digest, workspaceId);
        add(digest, message);
        add(digest, laneId.value());
        add(digest, defaults.model().orElse(""));
        add(digest, defaults.effort().orElse(""));
        defaults.serviceTier().ifPresent(value -> add(digest, "serviceTier=" + value));
        add(digest, defaults.permissionPolicy().name());
        defaults.developmentAutomation().ifPresent(value -> add(digest, "defaults.automation=" + value));
        initialAutomation.ifPresent(value -> add(digest, "initial.automation=" + value));
        issueIdentity.ifPresent(value -> add(digest, "issueSource=" + value));
        add(digest, idempotencyKey);
        attachmentDigest.ifPresent(value -> add(digest, value));
        return digest.hash().toString();
    }

    private static void add(Hasher digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // Big-endian length prefix: persisted fingerprints depend on this exact layout.
        digest.putBytes(Ints.toByteArray(bytes.length)).putBytes(bytes);
    }
}
