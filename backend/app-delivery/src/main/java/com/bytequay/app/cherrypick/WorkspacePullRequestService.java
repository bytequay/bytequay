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
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.ManagedWorkspaceGit;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
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
import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.utils.Locks.withLock;
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
    private final WorkspaceHostingService hosting;
    private final HostingIntegrations integrations;
    private final ManagedWorkspaceGit git;
    private final PullRequestService pullRequestCatalog;
    private final CiAutofixService ciAutofix;
    private final Clock clock;
    private final WorkspaceRelationships relationships;
    private final BackportChainStore chains;
    private final Striped<Lock> locks = Striped.lock(64);

    public WorkspacePullRequestService(
            WorkspaceStore workspaces,
            CherryPickExecutionStore executions,
            WorkspaceProvisioningService provisioning,
            WorkspaceHostingService hosting,
            HostingIntegrations integrations,
            ManagedWorkspaceGit git,
            PullRequestService pullRequestCatalog,
            CiAutofixService ciAutofix,
            Clock clock,
            WorkspaceRelationships relationships, BackportChainStore chains)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.executions = requireNonNull(executions, "executions is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.integrations = requireNonNull(integrations, "integrations is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequestCatalog = requireNonNull(pullRequestCatalog, "pullRequestCatalog is null");
        this.ciAutofix = requireNonNull(ciAutofix, "ciAutofix is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.relationships = requireNonNull(relationships, "relationships is null");
        this.chains = requireNonNull(chains, "chains is null");
    }

    private CherryPickExecution push(Workspace workspace, CherryPickExecution execution)
    {
        relationships.requireTransferAllowed(execution.workspace().sourceRepository(), execution.workspace().repository());
        if (execution.delivery().isPresent()) {
            return execution;
        }
        requireWritable(workspace, remote(workspace));
        String branch = execution.workspace().workBranch();
        try {
            git.pushBranch(workspace, branch, execution.currentTargetSha());
        }
        catch (ManagedWorkspaceGit.Failure failure) {
            throw WorkspaceException.deliveryFailed(failure.safeMessage());
        }
        return executions.save(execution.recordPush("origin", branch, execution.currentTargetSha(), clock.instant()));
    }

    private int createPullRequest(Workspace workspace, CherryPickExecution execution, Runnable requireAuthority)
    {
        requireAuthority.run();
        relationships.requireTransferAllowed(execution.workspace().sourceRepository(), execution.workspace().repository());
        Remote remote = remote(workspace);
        hosting.pushTarget(workspace.id(), remote.read());
        PullRequestDraft draft = execution.pullRequestDraft().orElseThrow();
        requireAuthority.run();
        CreatedPullRequest created = remote.integration().writer().create(remote.target(), workspace.selectedRepository(), execution.workspace().workBranch(),
                execution.workspace().targetBranch(), draft.title(), draft.body(), false);
        CherryPickExecution saved = executions.save(execution.recordPullRequest(created.number(), created.url(), clock.instant()));
        requireAuthority.run();
        activatePullRequest(workspace, saved, saved.delivery().orElseThrow());
        return created.number();
    }

    /**
     * Publishes only the execution named by an active, accepted chain; uncertain writes are inspected, never repeated.
     *
     * @return the number of the hop's pull request
     */
    public int deliverChain(String chainId, int index, boolean uncertain)
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
            if (execution.delivery().flatMap(Delivery::pullRequestNumber).isPresent()) {
                var stored = execution.delivery().orElseThrow();
                Remote remote = remote(workspace);
                var pr = remote.integration().contents().identity(remote.target(), stored.pullRequestNumber().orElseThrow());
                checkState(pr.headRepository().equals(workspace.selectedRepository()) && pr.headBranch().equals(execution.workspace().workBranch())
                                && pr.baseRepository().equals(workspace.monitoredRepository()) && pr.baseBranch().equals(execution.workspace().targetBranch())
                                && pr.state() != PullRequestIdentity.State.CLOSED,
                        "The saved backport PR was closed or rerouted");
                requireChain(chainId, index);
                if (pr.state() == PullRequestIdentity.State.OPEN) {
                    activatePullRequest(workspace, execution, stored);
                }
                return stored.pullRequestNumber().orElseThrow();
            }
            if (uncertain) {
                // An attempted publication never becomes another write merely because its response was lost.
                Remote remote = remote(workspace);
                hosting.pushTarget(workspace.id(), remote.read());
                checkState(git.remoteBranchSha(workspace, execution.workspace().workBranch()).filter(execution.currentTargetSha()::equals).isPresent(),
                        "Remote result unknown: the exact pushed branch was not verified. Inspect the existing branch and PR.");
                var matches = remote.integration().contents().identitiesByHead(remote.target(), workspace.selectedRepository(), execution.workspace().workBranch());
                checkState(matches.size() == 1, "Remote result unknown: no unique published PR was verified. Inspect it before continuing.");
                var pr = matches.getFirst();
                checkState(pr.headSha().equals(execution.currentTargetSha()) && pr.baseBranch().equals(execution.workspace().targetBranch())
                                && pr.baseRepository().equals(workspace.monitoredRepository()) && pr.headRepository().equals(workspace.selectedRepository())
                                && pr.headBranch().equals(execution.workspace().workBranch())
                                && pr.state() != PullRequestIdentity.State.CLOSED,
                        "Remote result differs from the saved backport; publication was not repeated");
                requireChain(chainId, index);
                if (execution.delivery().isEmpty()) {
                    execution = executions.save(execution.recordPush("origin", execution.workspace().workBranch(), execution.currentTargetSha(), clock.instant()));
                }
                execution = executions.save(execution.recordPullRequest(pr.pullRequest().number(), pr.url(), clock.instant()));
                if (pr.state() == PullRequestIdentity.State.OPEN) {
                    requireChain(chainId, index);
                    activatePullRequest(workspace, execution, execution.delivery().orElseThrow());
                }
                return pr.pullRequest().number();
            }
            requireChain(chainId, index);
            execution = push(workspace, execution);
            requireChain(chainId, index);
            return createPullRequest(workspace, execution, () -> requireChain(chainId, index));
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
            Remote remote = remote(workspace);
            String branch = execution.workspace().workBranch();
            try {
                if (execution.delivery().isEmpty()) {
                    requireWritable(workspace, remote);
                    if (git.remoteBranchSha(workspace, branch).filter(execution.currentTargetSha()::equals).isEmpty()) {
                        git.pushBranch(workspace, branch, execution.currentTargetSha());
                    }
                    execution = executions.save(execution.recordPush("origin", branch, execution.currentTargetSha(), clock.instant()));
                }
            }
            catch (ManagedWorkspaceGit.Failure failure) {
                throw WorkspaceException.deliveryFailed(failure.safeMessage());
            }
            if (execution.delivery().flatMap(Delivery::pullRequestNumber).isEmpty()) {
                Optional<PullRequestIdentity> existing = remote.integration().contents().identitiesByHead(remote.target(), workspace.selectedRepository(), branch)
                        .stream()
                        .filter(identity -> identity.state() == PullRequestIdentity.State.OPEN)
                        .findFirst();
                if (existing.isPresent()) {
                    PullRequestIdentity identity = existing.orElseThrow();
                    requirePullRequestRoute(execution, identity);
                    checkState(identity.headSha().equals(execution.currentTargetSha()), "The existing pull request does not match the published cherry-pick head");
                    execution = executions.save(execution.recordPullRequest(identity.pullRequest().number(), identity.url(), clock.instant()));
                }
                else {
                    PullRequestDraft draft = execution.pullRequestDraft().orElseThrow();
                    CreatedPullRequest created = remote.integration().writer().create(remote.target(), workspace.selectedRepository(), branch,
                            execution.workspace().targetBranch(), draft.title(), draft.body(), true);
                    execution = executions.save(execution.recordPullRequest(created.number(), created.url(), clock.instant()));
                }
                pullRequestCatalog.observeWorkspacePullRequest(workspace.id(), pullRequestNumber(execution));
            }
            return execution;
        });
    }

    /** Starts one CI Autofix round on the execution's pull request; the round number makes a repeated start return the same run. */
    public CiAutofixRun startCi(CherryPickExecution execution, int round)
    {
        checkArgument(round >= 1, "round is not positive");
        return ciAutofix.start(new StartCiAutofixCommand(execution.workspace().workspaceId(), pullRequestNumber(execution),
                new AgentLaneId(execution.workspace().laneId()), "cherry-pick-ci:" + execution.id() + ":" + round), expectedCiPullRequest(execution));
    }

    private PullRequestIdentity expectedCiPullRequest(CherryPickExecution execution)
    {
        PullRequestIdentity current = pullRequest(execution);
        // A lost CI-start response may be retried after that same run already pushed a repair.
        return new PullRequestIdentity(current.pullRequest(), current.url(), current.headRepository(), current.headBranch(),
                execution.currentTargetSha(), current.baseRepository(), current.baseBranch(), current.baseSha(), current.state(), current.draft(), current.author());
    }

    /** Replaces the published branch with a rebased or folded tip, only while origin still holds {@code leaseSha}. */
    public void replaceBranch(CherryPickExecution execution, String tipSha, String leaseSha)
    {
        Workspace workspace = requireWorkspace(execution.workspace().workspaceId());
        requirePublishedHead(execution, leaseSha);
        try {
            git.replaceBranch(workspace, execution.workspace().workBranch(), tipSha, leaseSha);
        }
        catch (ManagedWorkspaceGit.Failure failure) {
            throw WorkspaceException.deliveryFailed(failure.safeMessage());
        }
    }

    public PullRequestIdentity pullRequest(CherryPickExecution execution)
    {
        Workspace workspace = requireWorkspace(execution.workspace().workspaceId());
        requireExecution(workspace, execution.id());
        Remote remote = remote(workspace);
        PullRequestIdentity identity = remote.integration().contents().identity(remote.target(), pullRequestNumber(execution));
        requirePullRequestRoute(execution, identity);
        checkState(identity.pullRequest().equals(pullRequestRef(workspace, execution))
                        && execution.delivery().flatMap(Delivery::pullRequestUrl).filter(identity.url()::equals).isPresent(),
                "The saved pull request identity changed");
        return identity;
    }

    private PullRequestIdentity requirePublishedHead(CherryPickExecution execution, String headSha)
    {
        PullRequestIdentity identity = pullRequest(execution);
        checkState(identity.state() == PullRequestIdentity.State.OPEN && identity.headSha().equals(headSha),
                "The published cherry-pick head changed or its pull request is no longer open");
        return identity;
    }

    private static void requirePullRequestRoute(CherryPickExecution execution, PullRequestIdentity identity)
    {
        if (!identity.headRepository().equals(execution.workspace().physicalRepository())
                || !identity.headBranch().equals(execution.workspace().workBranch())
                || !identity.baseRepository().equals(execution.workspace().repository())
                || !identity.baseBranch().equals(execution.workspace().targetBranch())) {
            throw WorkspaceException.deliveryUnavailable("The cherry-pick pull request was retargeted. Restore its accepted repositories and branches before continuing.");
        }
    }

    /** Takes the draft out of draft once its squashed head is green, so the user can merge it. */
    public void markReady(CherryPickExecution execution)
    {
        Workspace workspace = requireWorkspace(execution.workspace().workspaceId());
        requirePublishedHead(execution, execution.currentTargetSha());
        Remote remote = remote(workspace);
        remote.integration().writer().markReady(remote.target(), pullRequestNumber(execution));
        pullRequestCatalog.observeWorkspacePullRequest(workspace.id(), pullRequestNumber(execution));
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
        if (execution.delivery().flatMap(Delivery::pullRequestNumber).isPresent()) {
            PullRequestIdentity identity;
            try {
                identity = pullRequest(execution);
            }
            catch (RuntimeException failure) {
                return ImmutableList.of("Could not verify the published pull request; its branch was preserved: " + failure.getMessage());
            }
            try {
                if (closePullRequest && identity.state() == PullRequestIdentity.State.OPEN) {
                    Remote remote = remote(workspace);
                    remote.integration().writer().close(remote.target(), pullRequestNumber(execution));
                    pullRequestCatalog.observeWorkspacePullRequest(workspace.id(), pullRequestNumber(execution));
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
                || !execution.workspace().repository().equals(workspace.monitoredRepository())
                || !execution.workspace().physicalRepository().equals(workspace.selectedRepository())) {
            throw WorkspaceException.deliveryUnavailable("The execution does not belong to this workspace.");
        }
        return execution;
    }

    private Remote remote(Workspace workspace)
    {
        WorkspaceHostingRead read = hosting.captureRead(workspace.id());
        return new Remote(integrations.of(read.target()), read);
    }

    private void requireWritable(Workspace workspace, Remote remote)
    {
        if (!remote.integration().contents().viewerCanWrite(hosting.pushTarget(workspace.id(), remote.read()))) {
            throw WorkspaceException.pushNotWritable();
        }
    }

    /** The workspace's verified repository and the provider integration that reaches it. */
    private record Remote(HostingIntegration integration, WorkspaceHostingRead read)
    {
        HostingTarget target()
        {
            return read.target();
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
        pullRequestCatalog.observeWorkspacePullRequest(workspace.id(), number);
        ciAutofix.start(new StartCiAutofixCommand(workspace.id(), number, new AgentLaneId(execution.workspace().laneId()), ciAutofixKey(execution)),
                expectedCiPullRequest(execution));
    }

    private static String ciAutofixKey(CherryPickExecution execution)
    {
        return "cherry-pick-delivery-ci:" + execution.id();
    }
}
