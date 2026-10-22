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
package com.bytequay.app.backport;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.agent.RelatedWorkspaceWork;
import com.bytequay.app.cherrypick.CherryPickExecutionService;
import com.bytequay.app.cherrypick.CherryPickPreviewService;
import com.bytequay.app.cherrypick.MergedPullRequestResolver;
import com.bytequay.app.cherrypick.PreviewCherryPickRangeCommand;
import com.bytequay.app.cherrypick.StartCherryPickExecutionCommand;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.discussion.ReviewFixService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspacePullRequestService;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChain.Acceptance;
import com.bytequay.core.backport.BackportChain.Hop;
import com.bytequay.core.backport.BackportChain.Route;
import com.bytequay.core.backport.BackportChain.State;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.stream.IntStream;

import static com.bytequay.app.utils.Locks.withLock;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** One bounded transition per tick. Existing execution, publication and CI services do the work. */
@Service
public class BackportChainService
{
    private final BackportChainStore chains;
    private final WorkspaceStore workspaces;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final RelatedWorkspaceWork related;
    private final WorkspaceRelationships relationships;
    private final WorkspaceRepositoryService repositories;
    private final WorkspaceRemoteSourceService remotes;
    private final ModelSettingsService models;
    private final GitHubPullRequestReadClient pullRequests;
    private final CredentialService credentials;
    private final MergedPullRequestResolver landed;
    private final CherryPickPreviewService previews;
    private final CherryPickExecutionService executions;
    private final CherryPickExecutionStore executionStore;
    private final WorkspacePullRequestService delivery;
    private final CiAutofixStore ciStore;
    private final CiAutofixService ci;
    private final Clock clock;
    private final GitCommandRunner git;
    private final Striped<Lock> locks = Striped.lock(64);
    private final Striped<Lock> requests = Striped.lock(64);

    public BackportChainService(BackportChainStore chains, WorkspaceStore workspaces, AgentTaskStore tasks, AgentTurnStore turns,
            RelatedWorkspaceWork related, WorkspaceRelationships relationships, WorkspaceRepositoryService repositories,
            WorkspaceRemoteSourceService remotes, ModelSettingsService models, GitHubPullRequestReadClient pullRequests,
            CredentialService credentials, MergedPullRequestResolver landed, CherryPickPreviewService previews,
            CherryPickExecutionService executions, CherryPickExecutionStore executionStore, WorkspacePullRequestService delivery,
            CiAutofixStore ciStore, CiAutofixService ci, Clock clock, GitCommandRunner git)
    {
        this.chains = chains;
        this.workspaces = workspaces;
        this.tasks = tasks;
        this.turns = turns;
        this.related = related;
        this.relationships = relationships;
        this.repositories = repositories;
        this.remotes = remotes;
        this.models = models;
        this.pullRequests = pullRequests;
        this.credentials = credentials;
        this.landed = landed;
        this.previews = previews;
        this.executions = executions;
        this.executionStore = executionStore;
        this.delivery = delivery;
        this.ciStore = ciStore;
        this.ci = ci;
        this.clock = clock;
        this.git = git;
    }

    public record SourcePullRequest(String workspaceId, int number)
    {
        public SourcePullRequest
        {
            checkText(workspaceId, "workspaceId", 100);
            checkArgument(number > 0, "Choose a positive PR number");
        }
    }

    public record Request(String operationKey, Optional<String> sourceTaskId, Optional<String> chainId, List<String> destinations,
            Optional<SourcePullRequest> sourcePullRequest, Optional<List<Integer>> sourceHops)
    {
        public Request
        {
            checkText(operationKey, "operationKey", 100);
            requireNonNull(sourceTaskId, "sourceTaskId is null");
            sourceTaskId.ifPresent(value -> checkText(value, "sourceTaskId", 100));
            requireNonNull(sourcePullRequest, "sourcePullRequest is null");
            checkArgument(sourceTaskId.isPresent() != sourcePullRequest.isPresent(), "Choose a source conversation or an existing PR");
            sourceHops = requireNonNull(sourceHops, "sourceHops is null").map(ImmutableList::copyOf);
            requireNonNull(chainId, "chainId is null");
            chainId.ifPresent(value -> checkText(value, "chainId", 100));
            destinations = ImmutableList.copyOf(destinations);
            checkArgument(!destinations.isEmpty() && destinations.size() <= 20, "Choose 1 to 20 destinations");
            destinations.forEach(value -> checkText(value, "destination", 100));
            checkArgument(sourceHops.isEmpty() || sourceHops.orElseThrow().size() == destinations.size(), "Each destination needs a source hop");
        }
    }

    /** A backport chosen in Commits: one pull request of the source workspace and each destination's source hop. */
    public record StartRequest(String sourceWorkspaceId, int number, List<String> destinations, List<Integer> sourceHops)
    {
        public StartRequest
        {
            checkText(sourceWorkspaceId, "sourceWorkspaceId", 100);
            checkArgument(number > 0, "Choose a positive PR number");
            destinations = ImmutableList.copyOf(destinations);
            sourceHops = ImmutableList.copyOf(sourceHops);
            checkArgument(!destinations.isEmpty() && destinations.size() <= 20 && destinations.size() == sourceHops.size(),
                    "Choose 1 to 20 destinations, each with its source hop");
            destinations.forEach(value -> checkText(value, "destination", 100));
        }
    }

    public record BackportDiscovery(String currentWorkspaceId, List<WorkspaceRelationships.BackportWorkspace> workspaces)
    {
        public BackportDiscovery
        {
            workspaces = ImmutableList.copyOf(workspaces);
        }
    }

    public BackportDiscovery discover(String taskId, TurnId turnId)
    {
        var task = requireTurn(taskId, turnId);
        return new BackportDiscovery(task.workspaceId().orElseThrow(), relationships.backportWorkspaces());
    }

    public BackportChain save(String taskId, TurnId turnId, Request request)
    {
        Lock requestLock = requests.get(taskId + ":" + request.operationKey());
        return withLock(requestLock, () -> {
            return saveAccepted(taskId, turnId, request);
        });
    }

    private BackportChain saveAccepted(String taskId, TurnId turnId, Request request)
    {
        AgentTask owner = requireTurn(taskId, turnId);
        String id = request.chainId().orElseGet(() -> UUID.nameUUIDFromBytes((taskId + ":" + request.operationKey()).getBytes(StandardCharsets.UTF_8)).toString());
        checkArgument(chains.list().stream().filter(chain -> chain.requestingTaskId().equals(Optional.of(taskId)))
                        .filter(chain -> chain.acceptances().stream().anyMatch(acceptance -> acceptance.operationKey().equals(request.operationKey())))
                        .allMatch(chain -> chain.id().equals(id)),
                "This operationKey already belongs to another chain");
        Lock lock = locks.get(id);
        return withLock(lock, () -> {
            requireTurn(taskId, turnId);
            Optional<BackportChain> previous = chains.find(id);
            int offset = previous.map(chain -> chain.hops().size()).orElse(0);
            var parentIndices = request.sourceHops().orElseGet(() -> IntStream.range(0, request.destinations().size())
                    .mapToObj(index -> offset + index - 1).collect(toImmutableList()));
            if (previous.isPresent()) {
                var chain = previous.orElseThrow();
                checkArgument(chain.requestingTaskId().equals(Optional.of(taskId)) && chain.sourceTaskId().equals(request.sourceTaskId()), "Another conversation owns this chain");
                request.sourcePullRequest().ifPresent(value -> checkArgument(chain.source().workspaceId().equals(resolveDestination(value.workspaceId()))
                        && chain.sourcePullRequest().map(PullRequestRef::number).equals(Optional.of(value.number())), "The source PR changed"));
                var accepted = chain.acceptances().stream().filter(value -> value.operationKey().equals(request.operationKey())).findFirst();
                if (accepted.isPresent()) {
                    // Retry defaults are relative to the original extension, not the chain's current length.
                    int start = 0;
                    for (Acceptance acceptance : chain.acceptances()) {
                        if (acceptance.operationKey().equals(request.operationKey())) {
                            break;
                        }
                        start += acceptance.destinations().size();
                    }
                    int first = start;
                    var replayParents = request.sourceHops().orElseGet(() -> IntStream.range(0, request.destinations().size())
                            .mapToObj(index -> first + index - 1).collect(toImmutableList()));
                    checkArgument(accepted.orElseThrow().destinations().equals(request.destinations()) && accepted.orElseThrow().sourceHops().equals(replayParents),
                            "The saved request changed; choose a new operationKey");
                    return chain;
                }
                checkArgument(request.chainId().isPresent(), "The saved request changed");
                validate(chain);
            }
            Optional<AgentTask> source = request.sourceTaskId().map(value -> tasks.find(TaskId.valueOf(value))
                    .orElseThrow(() -> new IllegalArgumentException("Source conversation is unavailable")));
            source.filter(value -> !value.id().equals(owner.id())).ifPresent(value -> {
                checkArgument(related.list(owner.workspaceId().orElseThrow()).stream().anyMatch(link ->
                                taskId.equals(link.sourceTaskId()) && value.id().toString().equals(link.targetTaskId()) && link.purpose().equals("FIX")),
                        "Choose this conversation or its linked fix conversation");
                relationships.requireRelated(owner.workspaceId().orElseThrow(), value.workspaceId().orElseThrow());
            });
            Route sourceRoute = route(source.map(value -> value.workspaceId().orElseThrow())
                    .orElseGet(() -> resolveDestination(request.sourcePullRequest().orElseThrow().workspaceId())));
            Optional<PullRequestRef> sourcePr = request.sourcePullRequest().map(value -> PullRequestRef.of(
                    sourceRoute.repository().owner(), sourceRoute.repository().repo(), value.number()));
            Optional<GitHubPullRequestIdentity> identity = sourcePr.map(pr -> pullRequests.fetchPullRequestIdentity(credentials.resolveGitHubToken(sourceRoute.workspaceId()), pr));
            identity.ifPresent(pr -> {
                checkArgument(pr.pullRequest().equals(sourcePr.orElseThrow()) && pr.baseRepository().equals(sourceRoute.repository())
                        && pr.baseBranch().equals(sourceRoute.branch()), "Source PR does not match the selected workspace and branch");
                checkArgument(pr.state() != GitHubPullRequestIdentity.State.CLOSED, "Source PR was closed without merging");
            });
            List<Route> earlier = previous.map(chain -> chain.hops().stream().map(Hop::route).collect(toImmutableList())).orElse(ImmutableList.of());
            List<Hop> next = newHops(sourceRoute, earlier, request.destinations(), parentIndices);
            Acceptance authority = new Acceptance(request.operationKey(), Optional.of(turnId.toString()), request.destinations(), parentIndices);
            if (previous.isPresent()) {
                return chains.save(previous.orElseThrow().append(authority, next, clock.instant()));
            }
            String branch = identity.map(GitHubPullRequestIdentity::headBranch).orElseGet(() -> source.orElseThrow().taskWorktree()
                    .orElseThrow(() -> new IllegalArgumentException("Source has no managed branch")).generatedBranch());
            return chains.createOrGet(new BackportChain(id, owner.workspaceId().orElseThrow(), Optional.of(taskId), request.sourceTaskId(), branch,
                    sourceRoute, sourcePr, false, ImmutableList.of(authority), next, State.ACTIVE, Optional.empty(), 0, clock.instant(), clock.instant()));
        });
    }

    /**
     * A click in Commits grants what an accepted @backport turn grants, for exactly this pull request and route: each local
     * cherry-pick, PR publication and the existing CI repair. Repeating the same click returns the same chain.
     */
    public BackportChain start(String workspaceId, StartRequest request)
    {
        checkArgument(workspaces.findById(workspaceId).isPresent(), "Workspace is unavailable");
        String id = UUID.nameUUIDFromBytes(("commits:" + workspaceId + ":" + request).getBytes(StandardCharsets.UTF_8)).toString();
        Lock lock = locks.get(id);
        return withLock(lock, () -> {
            Optional<BackportChain> existing = chains.find(id);
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            Route sourceRoute = route(request.sourceWorkspaceId());
            PullRequestRef sourcePr = PullRequestRef.of(sourceRoute.repository().owner(), sourceRoute.repository().repo(), request.number());
            GitHubPullRequestIdentity pr = pullRequests.fetchPullRequestIdentity(credentials.resolveGitHubToken(sourceRoute.workspaceId()), sourcePr);
            checkArgument(pr.pullRequest().equals(sourcePr) && pr.baseRepository().equals(sourceRoute.repository()) && pr.baseBranch().equals(sourceRoute.branch()),
                    "Backports start from a pull request into %s %s", sourceRoute.repository().fullName(), sourceRoute.branch());
            checkArgument(pr.state() != GitHubPullRequestIdentity.State.CLOSED, "Source PR was closed without merging");
            List<Hop> hops = newHops(sourceRoute, ImmutableList.of(), request.destinations(), request.sourceHops());
            Acceptance authority = new Acceptance("commits", Optional.empty(), request.destinations(), request.sourceHops());
            return chains.createOrGet(new BackportChain(id, workspaceId, Optional.empty(), Optional.empty(), pr.headBranch(), sourceRoute, Optional.of(sourcePr), false,
                    ImmutableList.of(authority), hops, State.ACTIVE, Optional.empty(), 0, clock.instant(), clock.instant()));
        });
    }

    private List<Hop> newHops(Route source, List<Route> earlier, List<String> destinations, List<Integer> parents)
    {
        var routes = new ArrayList<>(earlier);
        var next = ImmutableList.<Hop>builder();
        for (int index = 0; index < destinations.size(); index++) {
            int parent = parents.get(index);
            checkArgument(parent >= -1 && parent < routes.size(), "Each hop must use the source PR or an earlier hop");
            Route from = parent < 0 ? source : routes.get(parent);
            Route to = route(resolveDestination(destinations.get(index)));
            requireHop(from, to);
            var model = models.agentModel(AgentModelType.DEVELOPMENT, Optional.of(to.workspaceId()));
            next.add(new Hop(parent, to, model.configId(), Optional.of(model.model()), model.effort(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty(), false, false));
            routes.add(to);
        }
        return next.build();
    }

    public List<BackportChain> list(String workspaceId)
    {
        checkArgument(workspaces.findById(workspaceId).isPresent(), "Workspace is unavailable");
        return chains.list().stream().filter(chain -> chain.involves(workspaceId)).collect(toImmutableList());
    }

    public record View(BackportChain chain, String title, String requestingRepository, boolean archived, List<HopStatus> progress) {}

    public record HopStatus(Optional<String> taskId, Optional<String> ciRunId, String status, Optional<String> summary) {}

    public View view(BackportChain chain)
    {
        var owner = chain.requestingTaskId().flatMap(id -> tasks.find(TaskId.valueOf(id)));
        var progress = ImmutableList.<HopStatus>builder();
        for (int index = 0; index < chain.hops().size(); index++) {
            var hop = chain.hops().get(index);
            var execution = executionStore.findByIdempotencyKey(chain.executionKey(index));
            var run = execution.flatMap(value -> ciStore.findByIdempotencyKey("cherry-pick-delivery-ci:" + value.id()));
            String status = hop.completed() ? hop.noOp() ? "ALREADY_PRESENT" : "MERGED"
                    : hop.pullRequest().isPresent() ? "WAITING_FOR_MERGE"
                    : execution.map(value -> value.status().name()).orElse((hop.landedSource().isPresent() || hop.selection().isPresent()) ? "STARTING" : "WAITING");
            progress.add(new HopStatus(execution.flatMap(CherryPickExecution::agentTaskId), run.map(value -> value.id().toString()),
                    status, run.flatMap(CiAutofixRun::summary).or(() -> execution.flatMap(CherryPickExecution::summary))));
        }
        String title = chain.requestingTaskId().isEmpty()
                ? "Backport " + chain.sourcePullRequest().map(PullRequestRef::fullName).orElse(chain.source().repository().fullName())
                : owner.map(AgentTask::title).orElse("Unavailable conversation");
        return new View(chain, title,
                workspaces.findById(chain.workspaceId()).map(workspace -> workspace.monitoredRepository().fullName()).orElse(chain.workspaceId()),
                owner.flatMap(AgentTask::archivedAt).isPresent(), progress.build());
    }

    public BackportChain read(String taskId, TurnId turnId, String id)
    {
        requireTurn(taskId, turnId);
        var chain = require(id);
        checkArgument(chain.requestingTaskId().equals(Optional.of(taskId)), "Another conversation owns this chain");
        return chain;
    }

    public record SelectSource(long version, int hopIndex, String oldest, String newest)
    {
        public SelectSource
        {
            checkArgument(version >= 0, "Invalid chain version");
            com.bytequay.core.domain.Checks.checkSha(oldest, "oldest");
            com.bytequay.core.domain.Checks.checkSha(newest, "newest");
        }
    }

    /** The existing commit picker supplies explicit user selection; selecting never publishes or starts a child. */
    public BackportChain selectSource(String workspaceId, String id, SelectSource selection)
    {
        Lock lock = locks.get(id);
        return withLock(lock, () -> {
            var chain = owned(workspaceId, id);
            checkState(chain.version() == selection.version() && chain.state() == State.BLOCKED, "Chain changed; reload before selecting commits");
            validate(chain);
            int index = selection.hopIndex();
            checkArgument(index >= 0 && index < chain.hops().size() && chain.ready(index), "Choose a waiting hop whose source is ready");
            var hop = chain.hops().get(index);
            checkState(hop.executionId().isEmpty() && executionStore.findByIdempotencyKey(chain.executionKey(index)).isEmpty(),
                    "This cherry-pick already started; inspect its retained execution");
            Route source = chain.sourceRoute(index);
            var sourcePr = chain.sourcePr(index);
            if (sourcePr.isPresent()) {
                var fresh = pullRequests.fetchMergedPullRequest(credentials.resolveGitHubToken(source.workspaceId()), sourcePr.orElseThrow());
                checkState(fresh.identity().baseRepository().equals(source.repository()) && fresh.identity().baseBranch().equals(source.branch()), "Source PR routing changed");
            }
            else {
                checkState(hop.sourceHop() >= 0 && chain.hops().get(hop.sourceHop()).noOp(), "Wait for the source PR to merge");
            }
            String remote = sourceRemote(chain, hop.route(), source);
            var local = repositories.requireReadyRepository(hop.route().workspaceId());
            var preview = previews.preview(new PreviewCherryPickRangeCommand(local.repository(), local.workspaceId(), OptionalLong.of(source.repositoryId()),
                    remote, selection.oldest() + "^", selection.newest(), hop.route().remote(), hop.route().branch()));
            var ancestry = git.run(local.repository(), "merge-base", "--is-ancestor", preview.sourceTipSha(), "refs/remotes/" + remote + "/" + source.branch());
            checkState(ancestry.successful() && !ancestry.truncated(), "Selected commits are not landed on the source branch; synchronize it first");
            var selected = new BackportChain.Selection(preview.sourceBaseSha(), preview.sourceTipSha(),
                    preview.commits().stream().map(com.bytequay.app.cherrypick.CherryPickCommitPreview::sourceSha).collect(toImmutableList()), clock.instant());
            return chains.save(chain.withHop(index, hop.withSelection(selected), clock.instant()).withState(State.BLOCKED,
                    Optional.of("Selected " + selected.commits().size() + " landed commits. Resume to continue the saved route."), clock.instant()));
        });
    }

    public BackportChain stop(String workspaceId, String id)
    {
        // Revoke before waiting for an in-flight push or child dispatch. Each later action reads this flag again.
        for (int attempt = 0; attempt < 8; attempt++) {
            var chain = owned(workspaceId, id);
            if (chain.state() == State.COMPLETED || chain.state() == State.STOPPED || chain.state() == State.STOPPING) {
                return chain;
            }
            try {
                return chains.save(chain.withState(State.STOPPING, Optional.of("Stopping chain-owned work; branches and PRs are kept."), clock.instant()));
            }
            catch (RuntimeException failure) {
                if (require(id).version() == chain.version()) {
                    throw failure;
                }
            }
        }
        throw new IllegalStateException("Chain changed while stopping; retry Stop");
    }

    public BackportChain resume(String workspaceId, String id)
    {
        Lock lock = locks.get(id);
        return withLock(lock, () -> {
            var chain = owned(workspaceId, id);
            checkArgument(chain.state() == State.BLOCKED || chain.state() == State.STOPPED, "This chain is not paused");
            validate(chain);
            return chains.save(chain.withState(State.ACTIVE, Optional.empty(), clock.instant()));
        });
    }

    public void advance(String id)
    {
        Lock lock = locks.get(id);
        if (!lock.tryLock()) {
            return;
        }
        try {
            BackportChain chain = require(id);
            if (chain.state() == State.STOPPING) {
                cancelChildren(chain);
                chains.save(chain.withState(State.STOPPED, Optional.of("Stopped. Existing work and PRs are kept."), clock.instant()));
                return;
            }
            if (chain.state() != State.ACTIVE) {
                return;
            }
            try {
                validate(chain);
                advance(chain);
                var current = require(id);
                if (current.version() == chain.version()) {
                    chains.save(current.withState(current.state(), current.reason(), clock.instant()));
                }
            }
            catch (RuntimeException failure) {
                var current = require(id);
                if (current.state() == State.ACTIVE) {
                    String message = Optional.ofNullable(failure.getMessage()).orElse("Backport needs attention");
                    try {
                        chains.save(current.withState(State.BLOCKED, Optional.of(message.substring(0, Math.min(2000, message.length()))), clock.instant()));
                    }
                    catch (RuntimeException conflict) {
                        if (require(id).state() == State.ACTIVE) {
                            throw conflict;
                        }
                        // Stop can win after the read above; leave its durable revocation intact.
                    }
                }
            }
        }
        finally {
            lock.unlock();
        }
    }

    private void advance(BackportChain chain)
    {
        if (chain.sourcePullRequest().isEmpty()) {
            var matches = pullRequests.fetchPullRequestIdentities(credentials.resolveGitHubToken(chain.source().workspaceId()),
                    chain.source().repository(), chain.source().pushRepository(), chain.sourceBranch());
            checkState(matches.size() <= 1, "Several PRs match the source conversation; choose its intended published PR first");
            if (matches.isEmpty()) {
                checkState(tasks.find(TaskId.valueOf(chain.sourceTaskId().orElseThrow())).isPresent(), "Source conversation is unavailable");
                var sourceTurns = turns.list(TaskId.valueOf(chain.sourceTaskId().orElseThrow()));
                if (!sourceTurns.isEmpty()) {
                    var latest = sourceTurns.getLast();
                    checkState(latest.state() == com.bytequay.agent.core.TurnState.ACCEPTED || latest.state() == com.bytequay.agent.core.TurnState.RUNNING
                                    || latest.state() == com.bytequay.agent.core.TurnState.COMPLETED,
                            "The source conversation stopped before publishing. Continue there, then resume this chain.");
                }
                return;
            }
            var pr = matches.getFirst();
            verifyPr(chain.source(), chain.sourceBranch(), pr);
            checkState(pr.state() != GitHubPullRequestIdentity.State.CLOSED, "Source PR was closed without merging");
            chains.save(chain.withSource(pr.pullRequest(), clock.instant()));
            return;
        }
        for (int index = 0; index < chain.hops().size(); index++) {
            Hop hop = chain.hops().get(index);
            if (!chain.ready(index)) {
                continue;
            }
            Route source = chain.sourceRoute(index);
            Optional<PullRequestRef> sourcePr = chain.sourcePr(index);
            if (hop.landedSource().isEmpty() && hop.selection().isEmpty()) {
                checkState(sourcePr.isPresent(), "Select landed commits in the previous destination to continue after its no-op.");
                var pr = pullRequests.fetchPullRequestIdentity(credentials.resolveGitHubToken(source.workspaceId()), sourcePr.orElseThrow());
                String branch = hop.sourceHop() < 0 ? chain.sourceBranch() : executionStore.findByIdempotencyKey(chain.executionKey(hop.sourceHop()))
                        .orElseThrow(() -> new IllegalStateException("Previous execution is unavailable")).workspace().workBranch();
                if (hop.sourceHop() < 0 && chain.sourceTaskId().isEmpty()) {
                    checkState(pr.pullRequest().equals(sourcePr.orElseThrow()) && pr.baseRepository().equals(source.repository())
                            && pr.baseBranch().equals(source.branch()), "Source PR routing changed");
                }
                else {
                    verifyPr(source, branch, pr);
                }
                checkState(pr.state() != GitHubPullRequestIdentity.State.CLOSED, "Source PR was closed without merging");
                if (pr.state() != GitHubPullRequestIdentity.State.MERGED) {
                    continue;
                }
                if (hop.sourceHop() < 0 && !chain.sourceMerged()) {
                    chain = chains.save(chain.withSourceMerged(clock.instant()));
                }
                chains.save(chain.withHop(index, hop.withSource(landed.resolve(source.workspaceId(), source.remote(), sourcePr.orElseThrow())), clock.instant()));
                return;
            }
            var existing = executionStore.findByIdempotencyKey(chain.executionKey(index));
            if (hop.executionId().isEmpty()) {
                if (existing.isPresent()) {
                    var execution = existing.orElseThrow();
                    verifyExecution(chain, index, execution);
                    chains.save(chain.withHop(index, hop.withExecution(execution.id().toString()), clock.instant()));
                    return;
                }
                String remote = sourceRemote(chain, hop.route(), source);
                var local = repositories.requireReadyRepository(hop.route().workspaceId());
                var command = new PreviewCherryPickRangeCommand(local.repository(), local.workspaceId(), OptionalLong.of(source.repositoryId()),
                        remote, hop.sourceBaseSha(), hop.sourceTipSha(), hop.route().remote(), hop.route().branch());
                var preview = hop.selection().isPresent() ? previews.preview(command) : previews.preview(command, hop.landedSource().orElseThrow());
                checkState(preview.commits().stream().map(com.bytequay.app.cherrypick.CherryPickCommitPreview::sourceSha).collect(toImmutableList())
                        .equals(hop.sourceCommits()), "The selected source changed");
                String sourceLabel = sourcePr.map(PullRequestRef::fullName).orElse(source.repository().fullName());
                var plan = new BackportChain.Plan(preview.targetSha(),
                        new PullRequestDraft("Backport " + sourceLabel, "Backport of " + sourceLabel + "\n\nLanded source: " + hop.sourceTipSha()));
                chain = chains.save(chain.withHop(index, hop.withPlan(plan), clock.instant()));
                hop = chain.hops().get(index);
                requireActive(chain.id());
                validate(chain);
                var execution = executions.start(new StartCherryPickExecutionCommand(preview.previewId(), preview.sourceBaseSha(),
                        preview.sourceTipSha(), preview.targetSha(), ImmutableList.of(), hop.laneId(), hop.model(), hop.effort(), chain.executionKey(index),
                        plan.draft(), false),
                        local.workspaceId(), local.repository(), new RepositoryIdentity(local.selectedRepository().owner(), local.selectedRepository().repo()));
                chains.save(chain.withHop(index, hop.withExecution(execution.id().toString()), clock.instant()));
                return;
            }
            var execution = existing.orElseThrow(() -> new IllegalStateException("Backport execution is unavailable"));
            verifyExecution(chain, index, execution);
            checkState(execution.status() != CherryPickExecution.Status.PAUSED, execution.summary().orElse("Cherry-pick needs attention"));
            if (execution.status() != CherryPickExecution.Status.FINISHED) {
                continue;
            }
            checkState(execution.finishReason().orElseThrow() == CherryPickExecution.FinishReason.COMPLETED,
                    execution.summary().orElse("Cherry-pick stopped; inspect its retained work"));
            if (execution.items().stream().noneMatch(item -> item.result() == CherryPickItem.Result.APPLIED)) {
                var next = chain.withHop(index, hop.finish(true), clock.instant());
                int completedIndex = index;
                if (chain.hops().stream().anyMatch(value -> value.sourceHop() == completedIndex)) {
                    next = next.withState(State.BLOCKED, Optional.of("Already present in this destination. Select its landed commits to continue to the next repository."),
                            clock.instant());
                }
                chains.save(next);
                return;
            }
            if (hop.pullRequest().isEmpty()) {
                boolean uncertain = hop.publicationAttempted();
                if (!uncertain) {
                    chain = chains.save(chain.withHop(index, hop.publishing(), clock.instant()));
                }
                var published = delivery.deliverChain(chain.id(), index, uncertain);
                chains.save(require(chain.id()).withHop(index, require(chain.id()).hops().get(index).withPullRequest(
                        PullRequestRef.of(hop.route().repository().owner(), hop.route().repository().repo(), published.number())), clock.instant()));
                return;
            }
            var pr = pullRequests.fetchPullRequestIdentity(credentials.resolveGitHubToken(hop.route().workspaceId()), hop.pullRequest().orElseThrow());
            verifyPr(hop.route(), execution.workspace().workBranch(), pr);
            checkState(pr.state() != GitHubPullRequestIdentity.State.CLOSED, "Backport PR was closed without merging");
            if (pr.state() == GitHubPullRequestIdentity.State.MERGED) {
                chains.save(chain.withHop(index, hop.finish(false), clock.instant()));
                return;
            }
            ciStore.findByIdempotencyKey("cherry-pick-delivery-ci:" + execution.id()).ifPresent(run -> {
                checkState(run.status() != CiAutofixRun.Status.PAUSED, run.summary().orElse("CI needs attention"));
                checkState(run.status() != CiAutofixRun.Status.FINISHED || run.finishReason().filter(CiAutofixRun.FinishReason.GREEN::equals).isPresent(),
                        run.summary().orElse("CI stopped; inspect its run before continuing"));
            });
        }
        if (chain.hops().stream().allMatch(Hop::completed)) {
            chains.save(chain.withState(State.COMPLETED, Optional.empty(), clock.instant()));
        }
    }

    private String sourceRemote(BackportChain chain, Route target, Route source)
    {
        var current = remotes.list(target.workspaceId());
        var found = current.stream().filter(remote -> remote.url().equalsIgnoreCase(source.sourceUrl())
                || remote.url().equalsIgnoreCase(source.sourceUrl() + ".git") || (remote.url() + ".git").equalsIgnoreCase(source.sourceUrl())).findFirst();
        if (found.isPresent()) {
            return found.orElseThrow().name();
        }
        String name = "backport-" + source.repositoryId();
        checkState(current.stream().noneMatch(remote -> remote.name().equals(name)), "Backport remote names another repository");
        checkState(require(chain.id()).state() == chain.state(), "Chain state changed");
        requireHop(source, target);
        remotes.add(target.workspaceId(), name, source.sourceUrl());
        return name;
    }

    private void verifyExecution(BackportChain chain, int index, CherryPickExecution execution)
    {
        var hop = chain.hops().get(index);
        checkState(execution.startIdempotencyKey().equals(chain.executionKey(index))
                        && execution.workspace().workspaceId().equals(hop.route().workspaceId())
                        && execution.workspace().repository().equals(hop.route().repository())
                        && execution.workspace().physicalRepository().equals(hop.route().pushRepository())
                        && execution.workspace().targetBranch().equals(hop.route().branch())
                        && execution.workspace().laneId().equals(hop.laneId())
                        && execution.workspace().model().equals(hop.model()) && execution.workspace().effort().equals(hop.effort())
                        && execution.workspace().targetRemote().equals(hop.route().remote())
                        && execution.workspace().sourceRepository().equals(Optional.of(chain.sourceRoute(index).repository()))
                        && execution.range().initialTargetSha().equals(hop.plan().orElseThrow().targetSha())
                        && execution.pullRequestDraft().equals(hop.plan().map(BackportChain.Plan::draft))
                        && execution.items().stream().noneMatch(item -> item.disposition() == CherryPickItem.Disposition.USER_SKIPPED)
                        && execution.range().sourceBaseSha().equals(hop.sourceBaseSha())
                        && execution.range().sourceTipSha().equals(hop.sourceTipSha())
                        && execution.items().stream().map(CherryPickItem::sourceSha).collect(toImmutableList()).equals(hop.sourceCommits()),
                "Saved execution differs from the accepted backport");
        checkState(hop.executionId().isEmpty() || hop.executionId().orElseThrow().equals(execution.id().toString()), "Backport execution identity changed");
    }

    private static void verifyPr(Route route, String branch, GitHubPullRequestIdentity pr)
    {
        checkState(pr.baseRepository().equals(route.repository()) && pr.baseBranch().equals(route.branch())
                        && pr.headRepository().equals(route.pushRepository()) && pr.headBranch().equals(branch),
                "Published PR does not match the conversation or accepted route");
    }

    private void cancelChildren(BackportChain chain)
    {
        for (int index = 0; index < chain.hops().size(); index++) {
            executionStore.findByIdempotencyKey(chain.executionKey(index)).ifPresent(execution -> {
                if (execution.status() != CherryPickExecution.Status.FINISHED) {
                    executions.cancel(execution.id(), execution.workspace().workspaceId());
                }
                ciStore.findByIdempotencyKey("cherry-pick-delivery-ci:" + execution.id()).ifPresent(run -> ci.cancel(run.id()));
            });
        }
    }

    private AgentTask requireTurn(String taskId, TurnId turnId)
    {
        var task = related.eligible(taskId).orElseThrow(() -> new IllegalArgumentException("Requesting conversation is unavailable"));
        var turn = turns.findActive(task.id()).filter(value -> value.id().equals(turnId) && value.cancellationRequestedAt().isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("The requesting turn is no longer active"));
        checkArgument(turn.developmentOrigin().isEmpty() && !turn.idempotencyKey().equals("related-work:" + taskId) && !ReviewFixService.fixTurn(turn),
                "An automated turn cannot authorize a chain");
        return task;
    }

    private void requireActive(String id)
    {
        checkState(require(id).state() == State.ACTIVE, "Chain is stopped or blocked");
    }

    private void validate(BackportChain chain)
    {
        checkState(workspaces.findById(chain.workspaceId()).isPresent(), "Requesting workspace is unavailable");
        checkState(chain.source().equals(route(chain.source().workspaceId())), "Source workspace routing changed");
        for (int index = 0; index < chain.hops().size(); index++) {
            requireHop(chain.sourceRoute(index), chain.hops().get(index).route());
        }
    }

    private void requireHop(Route source, Route destination)
    {
        checkState(source.equals(route(source.workspaceId())) && destination.equals(route(destination.workspaceId())), "Workspace routing changed");
        relationships.requireRelated(destination.workspaceId(), source.workspaceId());
        checkArgument(relationships.list(destination.workspaceId()).stream()
                        .anyMatch(link -> link.workspaceId().equals(source.workspaceId()) && link.kind() == WorkspaceRelationships.Kind.FORK),
                "Each destination must list its source as a Fork");
        relationships.requireTransferAllowed(source.repository(), destination.repository());
    }

    private String resolveDestination(String selector)
    {
        if (workspaces.findById(selector).isPresent()) {
            return selector;
        }
        var matches = workspaces.findAllRetained().stream()
                .filter(workspace -> workspace.monitoredRepository().fullName().equalsIgnoreCase(selector) && workspaces.isWatched(workspace.id()))
                .collect(toImmutableList());
        checkArgument(matches.size() == 1, "Choose an available destination workspace ID or an unambiguous owner/repository: %s", selector);
        return matches.getFirst().id();
    }

    private Route route(String workspaceId)
    {
        checkState(workspaces.isWatched(workspaceId), "A route workspace is unavailable");
        return Route.of(workspaces.findById(workspaceId).orElseThrow(() -> new IllegalArgumentException("Workspace is unavailable")));
    }

    private BackportChain owned(String workspaceId, String id)
    {
        var chain = require(id);
        checkArgument(chain.workspaceId().equals(workspaceId), "Another workspace owns this chain");
        return chain;
    }

    private BackportChain require(String id)
    {
        return chains.find(id).orElseThrow(() -> new IllegalArgumentException("Backport chain is unavailable"));
    }
}
