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
package com.bytequay.core.backport;

import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.MergedPullRequestRange;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.google.common.collect.ImmutableList;
import lombok.AccessLevel;
import lombok.Builder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Saved route and child references; cherry-pick and CI services own execution. A chain started in Commits has no requesting conversation. */
@Builder(builderClassName = "Draft", builderMethodName = "", toBuilder = true, access = AccessLevel.PRIVATE)
public record BackportChain(
        String id, String workspaceId, Optional<String> requestingTaskId, Optional<String> sourceTaskId, String sourceBranch, Route source,
        Optional<PullRequestRef> sourcePullRequest, boolean sourceMerged, List<Acceptance> acceptances, List<Hop> hops,
        State state, Optional<String> reason, long version, Instant createdAt, Instant updatedAt)
{
    public BackportChain
    {
        checkText(id, "id", 100);
        checkText(workspaceId, "workspaceId", 100);
        requireNonNull(requestingTaskId, "requestingTaskId is null");
        requestingTaskId.ifPresent(value -> checkText(value, "requestingTaskId", 100));
        requireNonNull(sourceTaskId, "sourceTaskId is null");
        sourceTaskId.ifPresent(value -> checkText(value, "sourceTaskId", 100));
        checkText(sourceBranch, "sourceBranch");
        requireNonNull(source, "source is null");
        requireNonNull(sourcePullRequest, "sourcePullRequest is null");
        checkArgument(sourceTaskId.isPresent() || sourcePullRequest.isPresent(), "Choose a source conversation or PR");
        acceptances = ImmutableList.copyOf(acceptances);
        hops = ImmutableList.copyOf(hops);
        checkArgument(!acceptances.isEmpty() && !hops.isEmpty() && hops.size() <= 20, "A chain needs 1 to 20 destinations and accepted authority");
        checkArgument(hops.stream().map(hop -> hop.route().repositoryId()).distinct().count() == hops.size()
                && hops.stream().noneMatch(hop -> hop.route().repositoryId() == source.repositoryId()), "A route cannot repeat a repository");
        for (int index = 0; index < hops.size(); index++) {
            checkArgument(hops.get(index).sourceHop() >= -1 && hops.get(index).sourceHop() < index, "Each hop must use the source PR or an earlier hop");
        }
        requireNonNull(state, "state is null");
        requireNonNull(reason, "reason is null");
        checkArgument(version >= 0, "version is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    public enum State { ACTIVE, BLOCKED, STOPPING, STOPPED, COMPLETED }

    public record Route(String workspaceId, long repositoryId, RepoRef repository, long pushRepositoryId, RepoRef pushRepository,
            String sourceUrl, String pushUrl, String remote, String branch)
    {
        public Route
        {
            checkText(workspaceId, "workspaceId", 100);
            checkArgument(repositoryId > 0 && pushRepositoryId > 0, "Invalid repository identity");
            requireNonNull(repository, "repository is null");
            requireNonNull(pushRepository, "pushRepository is null");
            checkText(sourceUrl, "sourceUrl");
            checkText(pushUrl, "pushUrl");
            checkText(remote, "remote");
            checkText(branch, "branch");
        }

        public static Route of(Workspace workspace)
        {
            return new Route(workspace.id(), workspace.monitoredRepositoryId(), workspace.monitoredRepository(),
                    workspace.selectedRepositoryId(), workspace.selectedRepository(), workspace.upstreamCloneUrl().orElse(workspace.originCloneUrl()),
                    workspace.originCloneUrl(), workspace.upstreamRepositoryId().isPresent() ? "upstream" : "origin",
                    workspace.upstreamDefaultBranch().orElse(workspace.defaultBranch()));
        }
    }

    /** The accepted root turn, or none for a click in Commits, and the exact requested extension, retained for idempotent replay. */
    public record Acceptance(String operationKey, Optional<String> turnId, List<String> destinations, List<Integer> sourceHops)
    {
        public Acceptance
        {
            checkText(operationKey, "operationKey", 100);
            requireNonNull(turnId, "turnId is null");
            turnId.ifPresent(value -> checkText(value, "turnId", 100));
            destinations = ImmutableList.copyOf(destinations);
            sourceHops = ImmutableList.copyOf(sourceHops);
            checkArgument(!destinations.isEmpty() && destinations.size() == sourceHops.size(), "Choose destinations and their sources");
        }
    }

    @Builder(builderMethodName = "", toBuilder = true, access = AccessLevel.PRIVATE)
    public record Hop(int sourceHop, Route route, String laneId, Optional<String> model, Optional<String> effort,
            Optional<MergedPullRequestRange> landedSource, Optional<Selection> selection, Optional<Plan> plan, Optional<String> executionId, boolean publicationAttempted,
            Optional<PullRequestRef> pullRequest, boolean completed, boolean noOp)
    {
        public Hop
        {
            requireNonNull(route, "route is null");
            checkText(laneId, "laneId");
            requireNonNull(model, "model is null");
            requireNonNull(effort, "effort is null");
            requireNonNull(landedSource, "landedSource is null");
            requireNonNull(selection, "selection is null");
            requireNonNull(plan, "plan is null");
            requireNonNull(executionId, "executionId is null");
            requireNonNull(pullRequest, "pullRequest is null");
            checkArgument(!publicationAttempted || executionId.isPresent(), "Publication needs an execution");
            checkArgument(!noOp || completed, "No-op must be complete");
        }

        public Hop withSource(MergedPullRequestRange source)
        {
            return toBuilder().landedSource(Optional.of(source)).build();
        }

        public Hop withSelection(Selection value)
        {
            return toBuilder().selection(Optional.of(value)).build();
        }

        public String sourceBaseSha()
        {
            return selection.map(Selection::baseSha).orElseGet(() -> landedSource.orElseThrow().sourceBaseSha());
        }

        public String sourceTipSha()
        {
            return selection.map(Selection::tipSha).orElseGet(() -> landedSource.orElseThrow().mergeSha());
        }

        public List<String> sourceCommits()
        {
            return selection.map(Selection::commits).orElseGet(() -> landedSource.orElseThrow().commits());
        }

        public Hop withPlan(Plan value)
        {
            return toBuilder().plan(Optional.of(value)).build();
        }

        public Hop withExecution(String id)
        {
            return toBuilder().executionId(Optional.of(id)).build();
        }

        public Hop publishing()
        {
            return toBuilder().publicationAttempted(true).build();
        }

        public Hop withPullRequest(PullRequestRef pr)
        {
            return toBuilder().pullRequest(Optional.of(pr)).build();
        }

        public Hop finish(boolean empty)
        {
            return toBuilder().completed(true).noOp(empty).build();
        }
    }

    public record Plan(String targetSha, PullRequestDraft draft)
    {
        public Plan
        {
            com.bytequay.core.domain.Checks.checkSha(targetSha, "targetSha");
            requireNonNull(draft, "draft is null");
        }
    }

    /** Exact source range explicitly chosen by the user through the existing commit picker. */
    public record Selection(String baseSha, String tipSha, List<String> commits, Instant acceptedAt)
    {
        public Selection
        {
            com.bytequay.core.domain.Checks.checkSha(baseSha, "baseSha");
            com.bytequay.core.domain.Checks.checkSha(tipSha, "tipSha");
            commits = ImmutableList.copyOf(commits);
            checkArgument(!commits.isEmpty() && commits.getLast().equals(tipSha), "Selection must end at its tip");
            commits.forEach(sha -> com.bytequay.core.domain.Checks.checkSha(sha, "commit"));
            checkArgument(commits.stream().distinct().count() == commits.size(), "Selection repeats a commit");
            requireNonNull(acceptedAt, "acceptedAt is null");
        }
    }

    public Route sourceRoute(int index)
    {
        int parent = hops.get(index).sourceHop();
        return parent < 0 ? source : hops.get(parent).route();
    }

    public Optional<PullRequestRef> sourcePr(int index)
    {
        int parent = hops.get(index).sourceHop();
        return parent < 0 ? sourcePullRequest : hops.get(parent).pullRequest();
    }

    public boolean ready(int index)
    {
        int parent = hops.get(index).sourceHop();
        return !hops.get(index).completed() && (parent < 0 || hops.get(parent).completed());
    }

    public String executionKey(int index)
    {
        return "backport:" + id + ":" + index;
    }

    public BackportChain withState(State next, Optional<String> message, Instant now)
    {
        return edit(now, draft -> {
            draft.state = next;
            draft.reason = message;
        });
    }

    public BackportChain withSource(PullRequestRef pr, Instant now)
    {
        return edit(now, draft -> draft.sourcePullRequest = Optional.of(pr));
    }

    public BackportChain withSourceMerged(Instant now)
    {
        checkArgument(sourcePullRequest.isPresent(), "No source PR to mark merged");
        return edit(now, draft -> draft.sourceMerged = true);
    }

    public BackportChain withHop(int index, Hop hop, Instant now)
    {
        List<Hop> next = new ArrayList<>(hops);
        next.set(index, hop);
        return edit(now, draft -> draft.hops = next);
    }

    public BackportChain append(Acceptance authority, List<Hop> next, Instant now)
    {
        return edit(now, draft -> {
            draft.acceptances = ImmutableList.<Acceptance>builder().addAll(acceptances).add(authority).build();
            draft.hops = ImmutableList.<Hop>builder().addAll(hops).addAll(next).build();
            draft.state = state == State.COMPLETED ? State.ACTIVE : state;
        });
    }

    public BackportChain withVersion(long next)
    {
        Draft draft = toBuilder();
        draft.version = next;
        return draft.build();
    }

    public boolean involves(String workspace)
    {
        return workspaceId.equals(workspace) || source.workspaceId().equals(workspace)
                || hops.stream().anyMatch(hop -> hop.route().workspaceId().equals(workspace));
    }

    /** Applies {@code edits} to a mutable copy of this chain and stamps it with {@code now}, so each update names only what it changes. */
    private BackportChain edit(Instant now, Consumer<Draft> edits)
    {
        Draft draft = toBuilder();
        draft.updatedAt = now;
        edits.accept(draft);
        return draft.build();
    }
}
