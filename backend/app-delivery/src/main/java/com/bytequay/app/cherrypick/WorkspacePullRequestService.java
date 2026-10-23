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
package com.bytequay.app.cherrypick;

import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.StartCiAutofixCommand;
import com.bytequay.app.repository.github.CreatedPullRequest;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.ManagedWorkspaceGit;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.Delivery;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickLifecycle;
import com.bytequay.core.cherrypick.CherryPickNotFoundException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.utils.Locks.withLock;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Remote delivery for cherry-pick executions: their own publication, and a backport chain's. */
@Service
public class WorkspacePullRequestService
{
    private final WorkspaceStore workspaces;
    private final CherryPickExecutionStore executions;
    private final WorkspaceProvisioningService provisioning;
    private final GitHubAccountClient repositories;
    private final GitHubPullRequestWriteClient pullRequests;
    private final CredentialService credentials;
    private final ManagedWorkspaceGit git;
    private final PullRequestService pullRequestCatalog;
    private final CiAutofixService ciAutofix;
    private final Clock clock;
    private final WorkspaceRelationships relationships;
    private final BackportChainStore chains;
    private final GitHubPullRequestReadClient reads;
    private final Striped<Lock> locks = Striped.lock(64);

    public WorkspacePullRequestService(
            WorkspaceStore workspaces,
            CherryPickExecutionStore executions,
            WorkspaceProvisioningService provisioning,
            GitHubAccountClient repositories,
            GitHubPullRequestWriteClient pullRequests,
            CredentialService credentials,
            ManagedWorkspaceGit git,
            PullRequestService pullRequestCatalog,
            CiAutofixService ciAutofix,
            Clock clock,
            WorkspaceRelationships relationships, BackportChainStore chains, GitHubPullRequestReadClient reads)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.executions = requireNonNull(executions, "executions is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequestCatalog = requireNonNull(pullRequestCatalog, "pullRequestCatalog is null");
        this.ciAutofix = requireNonNull(ciAutofix, "ciAutofix is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.relationships = requireNonNull(relationships, "relationships is null");
        this.chains = requireNonNull(chains, "chains is null");
        this.reads = requireNonNull(reads, "reads is null");
    }

    /**
     * Pushes the generated branch to the persisted writable {@code origin} without force.
     *
     * <p>Direct and fork workspaces both push to their selected repository at {@code origin};
     * ByteQuay never falls back to another remote.</p>
     *
     * @param command owning workspace, completed execution, and explicit confirmation
     * @return persisted push identity; a repeated confirmation returns the existing result
     */
    public PushResult push(PushCommand command)
    {
        requireNonNull(command, "command is null");
        requireConfirmed(command.confirmed());
        Lock lock = locks.get(command.executionId());
        return withLock(lock, () -> {
            Workspace workspace = requireWorkspace(command.workspaceId());
            CherryPickExecution execution = requireExecution(workspace, command.executionId());
            relationships.requireTransferAllowed(execution.workspace().sourceRepository(), execution.workspace().repository());
            requireDeliverable(execution);
            if (execution.delivery().isPresent()) {
                return pushResult(execution.delivery().orElseThrow());
            }
            UserRepo selected = requireCurrentSelected(workspace);
            if (!selected.viewerCanPush()) {
                throw WorkspaceException.pushNotWritable();
            }
            String branch = execution.workspace().workBranch();
            try {
                git.pushBranch(workspace, branch, execution.currentTargetSha());
            }
            catch (ManagedWorkspaceGit.Failure failure) {
                throw WorkspaceException.deliveryFailed(failure.safeMessage());
            }
            CherryPickExecution saved = executions.save(execution.recordPush("origin", branch, execution.currentTargetSha(), clock.instant()));
            return pushResult(saved.delivery().orElseThrow());
        });
    }

    /**
     * Creates a pull request by delegating to the existing authenticated PR write client.
     *
     * <p>A direct workspace targets its selected repository. A fork uses its selected fork as
     * the head and its one persisted upstream as the base repository.</p>
     *
     * @param command owning workspace, pushed execution, and a second explicit confirmation
     * @return persisted GitHub pull-request identity; a repeat returns the existing result
     */
    public PullRequestResult createPullRequest(PullRequestCommand command)
    {
        return createPullRequest(command, () -> {});
    }

    private PullRequestResult createPullRequest(PullRequestCommand command, Runnable requireAuthority)
    {
        requireAuthority.run();
        requireNonNull(command, "command is null");
        requireConfirmed(command.confirmed());
        Lock lock = locks.get(command.executionId());
        return withLock(lock, () -> {
            Workspace workspace = requireWorkspace(command.workspaceId());
            CherryPickExecution execution = requireExecution(workspace, command.executionId());
            relationships.requireTransferAllowed(execution.workspace().sourceRepository(), execution.workspace().repository());
            requireDeliverable(execution);
            Delivery delivery = execution.delivery().orElseThrow(() ->
                    WorkspaceException.deliveryUnavailable("Push the generated branch before creating a pull request."));
            if (delivery.pullRequestId().isPresent()) {
                activatePullRequest(workspace, execution, delivery);
                return pullRequestResult(workspace, execution, delivery);
            }
            requireCurrentSelected(workspace);
            PullRequestDraft draft = execution.pullRequestDraft().orElseThrow();
            String head = head(workspace, execution);
            requireAuthority.run();
            CreatedPullRequest created = pullRequests.createPullRequest(
                    credentials.resolveGitHubToken(workspace.id()),
                    workspace.monitoredRepository(),
                    head,
                    execution.workspace().targetBranch(),
                    draft.title(),
                    draft.body(),
                    false);
            CherryPickExecution saved = executions.save(execution.recordPullRequest(
                    created.id(), created.number(), created.url(), clock.instant()));
            Delivery savedDelivery = saved.delivery().orElseThrow();
            requireAuthority.run();
            activatePullRequest(workspace, saved, savedDelivery);
            return pullRequestResult(workspace, saved, savedDelivery);
        });
    }

    /** Publishes only the execution named by an active, accepted chain; uncertain writes are inspected, never repeated. */
    public PullRequestResult deliverChain(String chainId, int index, boolean uncertain)
    {
        var chain = requireChain(chainId, index);
        var hop = chain.hops().get(index);
        var executionId = CherryPickExecutionId.valueOf(hop.executionId().orElseThrow());
        Lock lock = locks.get(executionId);
        return withLock(lock, () -> {
            Workspace workspace = requireWorkspace(hop.route().workspaceId());
            CherryPickExecution execution = requireExecution(workspace, executionId);
            checkState(execution.startIdempotencyKey().equals(chain.executionKey(index)), "Execution is not owned by this chain");
            requireDeliverable(execution);
            if (execution.delivery().flatMap(Delivery::pullRequestId).isPresent()) {
                var stored = execution.delivery().orElseThrow();
                var pr = reads.fetchPullRequestIdentity(credentials.resolveGitHubToken(workspace.id()), PullRequestRef.of(
                        workspace.monitoredRepository().owner(), workspace.monitoredRepository().repo(), stored.pullRequestNumber().orElseThrow()));
                checkState(pr.headRepository().equals(workspace.selectedRepository()) && pr.headBranch().equals(execution.workspace().workBranch())
                                && pr.baseRepository().equals(workspace.monitoredRepository()) && pr.baseBranch().equals(execution.workspace().targetBranch())
                                && pr.state() != GitHubPullRequestIdentity.State.CLOSED,
                        "The saved backport PR was closed or rerouted");
                requireChain(chainId, index);
                if (pr.state() == GitHubPullRequestIdentity.State.OPEN) {
                    activatePullRequest(workspace, execution, stored);
                }
                return pullRequestResult(workspace, execution, stored);
            }
            if (uncertain) {
                // An attempted publication never becomes another write merely because its response was lost.
                requireCurrentSelected(workspace);
                checkState(git.remoteBranchSha(workspace, execution.workspace().workBranch()).filter(execution.currentTargetSha()::equals).isPresent(),
                        "Remote result unknown: the exact pushed branch was not verified. Inspect the existing branch and PR.");
                var matches = reads.fetchPullRequestIdentities(credentials.resolveGitHubToken(workspace.id()), workspace.monitoredRepository(),
                        workspace.selectedRepository(), execution.workspace().workBranch());
                checkState(matches.size() == 1, "Remote result unknown: no unique published PR was verified. Inspect it before continuing.");
                var pr = matches.getFirst();
                checkState(pr.headSha().equals(execution.currentTargetSha()) && pr.baseBranch().equals(execution.workspace().targetBranch())
                                && pr.baseRepository().equals(workspace.monitoredRepository()) && pr.headRepository().equals(workspace.selectedRepository())
                                && pr.headBranch().equals(execution.workspace().workBranch())
                                && pr.state() != GitHubPullRequestIdentity.State.CLOSED,
                        "Remote result differs from the saved backport; publication was not repeated");
                var rows = reads.fetchPullRequestsByHead(credentials.resolveGitHubToken(workspace.id()), workspace.monitoredRepository(),
                        workspace.selectedRepository().owner() + ":" + execution.workspace().workBranch());
                var row = rows.stream().filter(value -> value.number() == pr.pullRequest().number() && value.htmlUrl().equals(pr.url())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Published PR identity is unavailable"));
                requireChain(chainId, index);
                if (execution.delivery().isEmpty()) {
                    execution = executions.save(execution.recordPush("origin", execution.workspace().workBranch(), execution.currentTargetSha(), clock.instant()));
                }
                execution = executions.save(execution.recordPullRequest(row.githubId(), row.number(), row.htmlUrl(), clock.instant()));
                if (pr.state() == GitHubPullRequestIdentity.State.OPEN) {
                    requireChain(chainId, index);
                    activatePullRequest(workspace, execution, execution.delivery().orElseThrow());
                }
                return pullRequestResult(workspace, execution, execution.delivery().orElseThrow());
            }
            requireChain(chainId, index);
            push(new PushCommand(workspace.id(), executionId, true));
            requireChain(chainId, index);
            return createPullRequest(new PullRequestCommand(workspace.id(), executionId, true), () -> requireChain(chainId, index));
        });
    }

    /**
     * Pushes a delivering execution's work branch and opens its draft pull request.
     *
     * <p>Starting the execution authorized both writes. A branch or pull request that a lost
     * response already created is recorded rather than written again.
     *
     * @param executionId execution in its publish stage
     * @return the execution with its push and pull request recorded
     */
    public CherryPickExecution publish(CherryPickExecutionId executionId)
    {
        requireNonNull(executionId, "executionId is null");
        Lock lock = locks.get(executionId);
        return withLock(lock, () -> {
            CherryPickExecution execution = executions.find(executionId).orElseThrow(() -> new CherryPickNotFoundException(executionId));
            Workspace workspace = requireWorkspace(execution.workspace().workspaceId());
            requireExecution(workspace, executionId);
            relationships.requireTransferAllowed(execution.workspace().sourceRepository(), execution.workspace().repository());
            checkState(execution.lifecycle().delivers() && execution.lifecycle().stage() == CherryPickLifecycle.Stage.PUBLISH && execution.status() == Status.RUNNING,
                    "Execution is not publishing");
            UserRepo selected = requireCurrentSelected(workspace);
            String branch = execution.workspace().workBranch();
            try {
                if (execution.delivery().isEmpty()) {
                    if (!selected.viewerCanPush()) {
                        throw WorkspaceException.pushNotWritable();
                    }
                    if (git.remoteBranchSha(workspace, branch).filter(execution.currentTargetSha()::equals).isEmpty()) {
                        git.pushBranch(workspace, branch, execution.currentTargetSha());
                    }
                    execution = executions.save(execution.recordPush("origin", branch, execution.currentTargetSha(), clock.instant()));
                }
            }
            catch (ManagedWorkspaceGit.Failure failure) {
                throw WorkspaceException.deliveryFailed(failure.safeMessage());
            }
            if (execution.delivery().flatMap(Delivery::pullRequestId).isEmpty()) {
                String token = credentials.resolveGitHubToken(workspace.id());
                Optional<FetchedPullRequest> existing = reads.fetchPullRequestsByHead(token, workspace.monitoredRepository(), workspace.selectedRepository().owner() + ":" + branch)
                        .stream()
                        .filter(row -> "open".equalsIgnoreCase(row.state()))
                        .findFirst();
                if (existing.isPresent()) {
                    FetchedPullRequest row = existing.orElseThrow();
                    execution = executions.save(execution.recordPullRequest(row.githubId(), row.number(), row.htmlUrl(), clock.instant()));
                }
                else {
                    PullRequestDraft draft = execution.pullRequestDraft().orElseThrow();
                    CreatedPullRequest created = pullRequests.createPullRequest(token, workspace.monitoredRepository(), head(workspace, execution),
                            execution.workspace().targetBranch(), draft.title(), draft.body(), true);
                    execution = executions.save(execution.recordPullRequest(created.id(), created.number(), created.url(), clock.instant()));
                }
                pullRequestCatalog.observeWorkspacePullRequest(workspace.monitoredRepository(), pullRequestNumber(execution));
            }
            return execution;
        });
    }

    /** Starts one CI Autofix round on the execution's pull request; the round number makes a repeated start return the same run. */
    public CiAutofixRun startCi(CherryPickExecution execution, int round)
    {
        checkArgument(round >= 1, "round is not positive");
        return ciAutofix.start(new StartCiAutofixCommand(execution.workspace().workspaceId(), pullRequestNumber(execution),
                new AgentLaneId(execution.workspace().laneId()), "cherry-pick-ci:" + execution.id() + ":" + round));
    }

    /** Replaces the published branch with a rebased or folded tip, only while origin still holds {@code leaseSha}. */
    public void replaceBranch(CherryPickExecution execution, String tipSha, String leaseSha)
    {
        Workspace workspace = requireWorkspace(execution.workspace().workspaceId());
        try {
            git.replaceBranch(workspace, execution.workspace().workBranch(), tipSha, leaseSha);
        }
        catch (ManagedWorkspaceGit.Failure failure) {
            throw WorkspaceException.deliveryFailed(failure.safeMessage());
        }
    }

    public GitHubPullRequestIdentity pullRequest(CherryPickExecution execution)
    {
        Workspace workspace = requireWorkspace(execution.workspace().workspaceId());
        return reads.fetchPullRequestIdentity(credentials.resolveGitHubToken(workspace.id()), pullRequestRef(workspace, execution));
    }

    /** Takes the draft out of draft once its squashed head is green, so the user can merge it. */
    public void markReady(CherryPickExecution execution)
    {
        Workspace workspace = requireWorkspace(execution.workspace().workspaceId());
        String token = credentials.resolveGitHubToken(workspace.id());
        String nodeId = pullRequests.pullRequestNodeId(token, pullRequestRef(workspace, execution))
                .orElseThrow(() -> WorkspaceException.deliveryFailed("GitHub returned no pull request node ID"));
        pullRequests.markReadyForReview(token, nodeId);
        pullRequestCatalog.observeWorkspacePullRequest(workspace.monitoredRepository(), pullRequestNumber(execution));
    }

    /**
     * Withdraws what an execution published: closes its open pull request when asked, then deletes
     * its branch from origin. Every write is attempted; the result names the ones that failed.
     */
    public List<String> withdraw(CherryPickExecution execution, boolean closePullRequest)
    {
        ImmutableList.Builder<String> problems = ImmutableList.builder();
        if (execution.delivery().isEmpty()) {
            return problems.build();
        }
        Workspace workspace = requireWorkspace(execution.workspace().workspaceId());
        if (closePullRequest && execution.delivery().flatMap(Delivery::pullRequestNumber).isPresent()) {
            try {
                if (pullRequest(execution).state() == GitHubPullRequestIdentity.State.OPEN) {
                    pullRequests.closePullRequest(credentials.resolveGitHubToken(workspace.id()), pullRequestRef(workspace, execution));
                    pullRequestCatalog.observeWorkspacePullRequest(workspace.monitoredRepository(), pullRequestNumber(execution));
                }
            }
            catch (RuntimeException failure) {
                problems.add("Could not close pull request #" + pullRequestNumber(execution) + ": " + failure.getMessage());
            }
        }
        String branch = execution.workspace().workBranch();
        try {
            git.remoteBranchSha(workspace, branch).ifPresent(sha -> git.deleteBranch(workspace, branch, sha));
        }
        catch (ManagedWorkspaceGit.Failure failure) {
            problems.add("Could not delete " + branch + " from origin: " + failure.safeMessage());
        }
        return problems.build();
    }

    private static int pullRequestNumber(CherryPickExecution execution)
    {
        return execution.delivery().flatMap(Delivery::pullRequestNumber).orElseThrow(() -> new IllegalStateException("Execution has no pull request"));
    }

    private static PullRequestRef pullRequestRef(Workspace workspace, CherryPickExecution execution)
    {
        return PullRequestRef.of(workspace.monitoredRepository().owner(), workspace.monitoredRepository().repo(), pullRequestNumber(execution));
    }

    private BackportChain requireChain(String id, int index)
    {
        var chain = chains.find(id).orElseThrow(() -> new IllegalArgumentException("Chain authority is unavailable"));
        checkState(chain.state() == BackportChain.State.ACTIVE && !chain.acceptances().isEmpty(), "Chain is stopped or blocked");
        checkArgument(index >= 0 && index < chain.hops().size(), "Invalid chain hop");
        var hop = chain.hops().get(index);
        checkState(hop.publicationAttempted() && !hop.completed(), "This hop has no publication authority");
        var source = chain.sourceRoute(index);
        checkState(source.equals(BackportChain.Route.of(requireWorkspace(source.workspaceId())))
                        && hop.route().equals(BackportChain.Route.of(requireWorkspace(hop.route().workspaceId()))),
                "Chain repository routing changed");
        relationships.requireRelated(hop.route().workspaceId(), source.workspaceId());
        checkState(relationships.list(hop.route().workspaceId()).stream()
                        .anyMatch(link -> link.workspaceId().equals(source.workspaceId()) && link.kind() == WorkspaceRelationships.Kind.FORK),
                "Backports require a direct Fork relationship");
        relationships.requireTransferAllowed(source.repository(), hop.route().repository());
        return chain;
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        Workspace workspace = workspaces.findById(workspaceId)
                .orElseThrow(() -> WorkspaceException.notFound(workspaceId));
        if (workspace.lastCodeSyncAt().isEmpty()) {
            provisioning.synchronizeCode(workspaceId);
            workspace = workspaces.findById(workspaceId)
                    .orElseThrow(() -> WorkspaceException.notFound(workspaceId));
            if (workspace.lastCodeSyncAt().isEmpty()) {
                throw WorkspaceException.repositoryNotReady();
            }
        }
        return workspace;
    }

    private CherryPickExecution requireExecution(Workspace workspace, CherryPickExecutionId executionId)
    {
        requireNonNull(executionId, "executionId is null");
        CherryPickExecution execution = executions.find(executionId)
                .orElseThrow(() -> new CherryPickNotFoundException(executionId));
        if (!execution.workspace().workspaceId().equals(workspace.id())
                || !execution.workspace().repository().equals(workspace.monitoredRepository())) {
            throw WorkspaceException.deliveryUnavailable("The execution does not belong to this workspace.");
        }
        return execution;
    }

    private UserRepo requireCurrentSelected(Workspace workspace)
    {
        UserRepo selected = repositories.fetchRepository(
                        credentials.resolveGitHubToken(workspace.id()), workspace.selectedRepositoryId())
                .orElseThrow(() -> WorkspaceException.repositoryNotFound(workspace.selectedRepositoryId()));
        if (!routingMatches(workspace, selected)) {
            throw WorkspaceException.deliveryUnavailable(
                    "Repository routing changed. Synchronize the workspace before delivery.");
        }
        return selected;
    }

    private boolean routingMatches(Workspace workspace, UserRepo selected)
    {
        if (selected.id() != workspace.selectedRepositoryId()
                || !RepoRef.of(selected.owner(), selected.name()).equals(workspace.selectedRepository())
                || !selected.cloneUrl().equals(workspace.originCloneUrl())) {
            return false;
        }
        if (workspace.upstreamRepositoryId().isEmpty()) {
            return workspace.upstreamRepository().isEmpty()
                    && workspace.upstreamDefaultBranch().isEmpty()
                    && workspace.upstreamCloneUrl().isEmpty()
                    && workspace.monitoredRepositoryId() == workspace.selectedRepositoryId()
                    && workspace.monitoredRepository().equals(workspace.selectedRepository());
        }
        long upstreamId = workspace.upstreamRepositoryId().orElseThrow();
        return repositories.fetchRepository(credentials.resolveGitHubToken(workspace.id()), upstreamId)
                .filter(upstream -> upstream.id() == upstreamId)
                .filter(upstream -> workspace.upstreamRepository()
                        .filter(RepoRef.of(upstream.owner(), upstream.name())::equals).isPresent())
                .filter(upstream -> workspace.upstreamDefaultBranch()
                        .filter(upstream.defaultBranch()::equals).isPresent())
                .filter(upstream -> workspace.upstreamCloneUrl()
                        .filter(upstream.cloneUrl()::equals).isPresent())
                .filter(upstream -> workspace.monitoredRepositoryId() == upstream.id())
                .filter(upstream -> workspace.monitoredRepository().equals(RepoRef.of(upstream.owner(), upstream.name())))
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
        if (execution.lifecycle().delivers()) {
            return Optional.of("This execution publishes its own branch.");
        }
        if (execution.status() != Status.FINISHED
                || execution.finishReason().filter(FinishReason.COMPLETED::equals).isEmpty()) {
            return Optional.of("Only a successfully completed execution can be delivered.");
        }
        if (execution.pullRequestDraft().isEmpty()) {
            return Optional.of("This execution has no saved pull-request draft.");
        }
        return Optional.empty();
    }

    private void activatePullRequest(Workspace workspace, CherryPickExecution execution, Delivery delivery)
    {
        int number = delivery.pullRequestNumber().orElseThrow();
        pullRequestCatalog.observeWorkspacePullRequest(workspace.monitoredRepository(), number);
        ciAutofix.start(new StartCiAutofixCommand(workspace.id(), number, new AgentLaneId(execution.workspace().laneId()), ciAutofixKey(execution)));
    }

    private static String ciAutofixKey(CherryPickExecution execution)
    {
        return "cherry-pick-delivery-ci:" + execution.id();
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
        return workspace.upstreamRepositoryId().isPresent()
                ? workspace.selectedRepository().owner() + ":" + execution.workspace().workBranch()
                : execution.workspace().workBranch();
    }

    public record PushCommand(String workspaceId, CherryPickExecutionId executionId, boolean confirmed)
    {
        public PushCommand
        {
            requireNonNull(workspaceId, "workspaceId is null");
            requireNonNull(executionId, "executionId is null");
        }
    }

    public record PullRequestCommand(String workspaceId, CherryPickExecutionId executionId, boolean confirmed)
    {
        public PullRequestCommand
        {
            requireNonNull(workspaceId, "workspaceId is null");
            requireNonNull(executionId, "executionId is null");
        }
    }

    public record PushResult(String remote, String head, String sha)
    {
        public PushResult
        {
            checkText(remote, "remote");
            checkText(head, "head");
            checkText(sha, "sha");
        }
    }

    public record PullRequestResult(
            long providerId,
            int number,
            String url,
            RepoRef baseRepository,
            String baseBranch,
            String head)
    {
        public PullRequestResult
        {
            checkArgument(providerId > 0, "providerId is not positive");
            checkArgument(number > 0, "number is not positive");
            checkText(url, "url");
            requireNonNull(baseRepository, "baseRepository is null");
            checkText(baseBranch, "baseBranch");
            checkText(head, "head");
        }
    }
}
