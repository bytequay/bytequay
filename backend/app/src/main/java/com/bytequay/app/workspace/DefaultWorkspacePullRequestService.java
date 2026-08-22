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
package com.bytequay.app.workspace;

import com.bytequay.app.repository.github.CreatedPullRequest;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceBranchPusher.Push;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.Delivery;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickNotFoundException;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static java.util.Objects.requireNonNull;

@Service
public final class DefaultWorkspacePullRequestService
        implements WorkspacePullRequestService
{
    private final WorkspaceStore workspaces;
    private final CherryPickExecutionStore executions;
    private final GitHubAccountClient repositories;
    private final GitHubPullRequestWriteClient pullRequests;
    private final CredentialService credentials;
    private final WorkspaceBranchPusher git;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(64);

    public DefaultWorkspacePullRequestService(
            WorkspaceStore workspaces,
            CherryPickExecutionStore executions,
            GitHubAccountClient repositories,
            GitHubPullRequestWriteClient pullRequests,
            CredentialService credentials,
            WorkspaceBranchPusher git,
            Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.executions = requireNonNull(executions, "executions is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.git = requireNonNull(git, "git is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DeliveryStatus getStatus(String workspaceId, CherryPickExecutionId executionId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        CherryPickExecution execution = requireExecution(workspace, executionId);
        Optional<String> reason = deliveryReason(execution);
        boolean writable = false;
        if (reason.isEmpty() && execution.delivery().isEmpty()) {
            Optional<UserRepo> selected = repositories.fetchRepository(
                    credentials.resolveGitHubToken(), workspace.selectedRepositoryId());
            if (selected.isEmpty()) {
                reason = Optional.of("The selected repository is no longer visible to the configured GitHub account.");
            }
            else if (!routingMatches(workspace, selected.orElseThrow())) {
                reason = Optional.of("Repository routing changed. Synchronize the workspace before delivery.");
            }
            else if (!selected.orElseThrow().viewerCanPush()) {
                reason = Optional.of("This repository is read-only. Grant write access or watch a writable fork.");
            }
            else {
                writable = true;
            }
        }
        else if (execution.delivery().isPresent()) {
            writable = true;
        }
        return status(workspace, execution, writable, reason);
    }

    @Override
    public PushResult push(PushCommand command)
    {
        requireNonNull(command, "command is null");
        requireConfirmed(command.confirmed());
        Lock lock = locks.get(command.executionId());
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(command.workspaceId());
            CherryPickExecution execution = requireExecution(workspace, command.executionId());
            requireDeliverable(execution);
            if (execution.delivery().isPresent()) {
                return pushResult(execution.delivery().orElseThrow());
            }
            UserRepo selected = requireCurrentSelected(workspace);
            if (!selected.viewerCanPush()) {
                throw WorkspaceException.pushNotWritable();
            }
            Push pushed;
            try {
                pushed = git.pushBranch(workspace, execution.workspace().worktree(), execution.workspace().workBranch());
            }
            catch (ManagedWorkspaceGit.Failure failure) {
                throw WorkspaceException.deliveryFailed(failure.safeMessage());
            }
            CherryPickExecution saved = executions.save(execution.recordPush(
                    pushed.remote(), pushed.head(), pushed.sha(), clock.instant()));
            return pushResult(saved.delivery().orElseThrow());
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public PullRequestResult createPullRequest(PullRequestCommand command)
    {
        requireNonNull(command, "command is null");
        requireConfirmed(command.confirmed());
        Lock lock = locks.get(command.executionId());
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(command.workspaceId());
            CherryPickExecution execution = requireExecution(workspace, command.executionId());
            requireDeliverable(execution);
            Delivery delivery = execution.delivery().orElseThrow(() ->
                    WorkspaceException.deliveryUnavailable("Push the generated branch before creating a pull request."));
            if (delivery.pullRequestId().isPresent()) {
                return pullRequestResult(workspace, execution, delivery);
            }
            requireCurrentSelected(workspace);
            PullRequestDraft draft = execution.pullRequestDraft().orElseThrow();
            String head = head(workspace, execution);
            CreatedPullRequest created = pullRequests.createPullRequest(
                    credentials.resolveGitHubToken(),
                    workspace.monitoredRepository(),
                    head,
                    execution.workspace().targetBranch(),
                    draft.title(),
                    draft.body());
            CherryPickExecution saved = executions.save(execution.recordPullRequest(
                    created.id(), created.number(), created.url(), clock.instant()));
            return pullRequestResult(workspace, saved, saved.delivery().orElseThrow());
        }
        finally {
            lock.unlock();
        }
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        Workspace workspace = workspaces.findById(workspaceId)
                .orElseThrow(() -> WorkspaceException.notFound(workspaceId));
        if (workspace.codeState() != CodeState.READY) {
            throw WorkspaceException.repositoryNotReady();
        }
        return workspace;
    }

    private CherryPickExecution requireExecution(Workspace workspace, CherryPickExecutionId executionId)
    {
        requireNonNull(executionId, "executionId is null");
        CherryPickExecution execution = executions.find(executionId)
                .orElseThrow(() -> new CherryPickNotFoundException(executionId));
        if (!execution.workspace().workspaceId().filter(workspace.id()::equals).isPresent()
                || !execution.workspace().repository().equals(workspace.monitoredRepository())) {
            throw WorkspaceException.deliveryUnavailable("The execution does not belong to this workspace.");
        }
        return execution;
    }

    private UserRepo requireCurrentSelected(Workspace workspace)
    {
        UserRepo selected = repositories.fetchRepository(
                        credentials.resolveGitHubToken(), workspace.selectedRepositoryId())
                .orElseThrow(() -> WorkspaceException.repositoryNotFound(workspace.selectedRepositoryId()));
        if (!routingMatches(workspace, selected)) {
            throw WorkspaceException.deliveryUnavailable(
                    "Repository routing changed. Synchronize the workspace before delivery.");
        }
        return selected;
    }

    private static boolean routingMatches(Workspace workspace, UserRepo selected)
    {
        if (selected.id() != workspace.selectedRepositoryId()
                || !RepoRef.of(selected.owner(), selected.name()).equals(workspace.selectedRepository())
                || !selected.cloneUrl().equals(workspace.originCloneUrl())
                || selected.fork() != (workspace.topology() == Topology.FORK)) {
            return false;
        }
        if (workspace.topology() == Topology.DIRECT) {
            return selected.upstream().isEmpty()
                    && workspace.monitoredRepository().equals(workspace.selectedRepository());
        }
        return selected.upstream()
                .filter(upstream -> workspace.upstreamRepositoryId().filter(id -> id == upstream.id()).isPresent())
                .filter(upstream -> RepoRef.of(upstream.owner(), upstream.name())
                        .equals(workspace.upstreamRepository().orElseThrow()))
                .filter(upstream -> upstream.cloneUrl().equals(workspace.upstreamCloneUrl().orElseThrow()))
                .isPresent();
    }

    private static void requireConfirmed(boolean confirmed)
    {
        if (!confirmed) {
            throw WorkspaceException.deliveryConfirmationRequired();
        }
    }

    private static void requireDeliverable(CherryPickExecution execution)
    {
        deliveryReason(execution).ifPresent(reason -> {
            throw WorkspaceException.deliveryUnavailable(reason);
        });
    }

    private static Optional<String> deliveryReason(CherryPickExecution execution)
    {
        if (execution.status() != Status.FINISHED
                || execution.finishReason().filter(FinishReason.COMPLETED::equals).isEmpty()) {
            return Optional.of("Only a successfully completed execution can be delivered.");
        }
        if (execution.pullRequestDraft().isEmpty()) {
            return Optional.of("This execution has no saved pull-request draft.");
        }
        return Optional.empty();
    }

    private static DeliveryStatus status(
            Workspace workspace,
            CherryPickExecution execution,
            boolean writable,
            Optional<String> reason)
    {
        Optional<PushResult> push = execution.delivery().map(DefaultWorkspacePullRequestService::pushResult);
        Optional<PullRequestResult> pullRequest = execution.delivery()
                .filter(delivery -> delivery.pullRequestId().isPresent())
                .map(delivery -> pullRequestResult(workspace, execution, delivery));
        return new DeliveryStatus(
                writable,
                writable ? Optional.empty() : reason,
                workspace.monitoredRepository(),
                execution.workspace().targetBranch(),
                head(workspace, execution),
                push,
                pullRequest);
    }

    private static PushResult pushResult(Delivery delivery)
    {
        return new PushResult(delivery.pushRemote(), delivery.head(), delivery.sha());
    }

    private static PullRequestResult pullRequestResult(
            Workspace workspace,
            CherryPickExecution execution,
            Delivery delivery)
    {
        return new PullRequestResult(
                delivery.pullRequestId().orElseThrow(),
                delivery.pullRequestNumber().orElseThrow(),
                delivery.pullRequestUrl().orElseThrow(),
                workspace.monitoredRepository(),
                execution.workspace().targetBranch(),
                head(workspace, execution));
    }

    private static String head(Workspace workspace, CherryPickExecution execution)
    {
        return workspace.topology() == Topology.FORK
                ? workspace.selectedRepository().owner() + ":" + execution.workspace().workBranch()
                : execution.workspace().workBranch();
    }
}
