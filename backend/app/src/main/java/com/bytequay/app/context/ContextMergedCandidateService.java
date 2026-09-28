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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.app.agent.WorkflowAgentTasks;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Local merged-PR candidates from proven task links; generation still requires an explicit Learn request. */
@Service
public class ContextMergedCandidateService
{
    private static final int MAX_LINKS = 50;
    private static final int MAX_VISIBLE = 10;
    private final ContextMergedCandidateStore store;
    private final ContextBuildStore buildStore;
    private final PullRequestStore pullRequests;
    private final PrDetailStore details;
    private final PullRequestTaskStore imports;
    private final IssueDevelopmentStore issues;
    private final JiraIssueTaskStore jira;
    private final AgentReviewStore reviews;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final WorkspaceRepositoryService workspaces;
    private final RepositoryContextService notebooks;
    private final ContextBuildService builds;
    private final Clock clock;

    public ContextMergedCandidateService(ContextMergedCandidateStore store, ContextBuildStore buildStore, PullRequestStore pullRequests, PrDetailStore details,
            PullRequestTaskStore imports, IssueDevelopmentStore issues, JiraIssueTaskStore jira, AgentReviewStore reviews,
            AgentTaskStore tasks, AgentTurnStore turns, WorkspaceRepositoryService workspaces,
            RepositoryContextService notebooks, ContextBuildService builds, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.buildStore = requireNonNull(buildStore, "buildStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.imports = requireNonNull(imports, "imports is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.builds = requireNonNull(builds, "builds is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Called only after the development coordinator verified the published head and both repository routes. */
    public void recordPublished(AgentTask task, GitHubPullRequestIdentity published)
    {
        String workspaceId = task.workspaceId().orElseThrow();
        WorkspaceRepositoryContext workspace = workspaces.requireReadableRepository(workspaceId);
        RepositoryIdentity physical = WorkflowAgentTasks.repositoryIdentity(workspace.selectedRepository());
        checkArgument(task.interactive() && task.repository().equals(physical)
                && task.taskWorktree().filter(worktree -> worktree.workspaceId().equals(workspaceId)).isPresent(),
                "Published task is outside the workspace's physical repository");
        checkArgument(published.headRepository().equals(workspace.selectedRepository())
                && published.baseRepository().equals(workspace.monitoredRepository())
                && published.pullRequest().owner().equalsIgnoreCase(workspace.monitoredRepository().owner())
                && published.pullRequest().repo().equalsIgnoreCase(workspace.monitoredRepository().repo())
                && published.state() == GitHubPullRequestIdentity.State.OPEN,
                "Published pull request is outside the verified workspace route");
        observe(workspaceId, new ContextMergedCandidateStore.Link(
                id(workspace.monitoredRepository().fullName(), published.pullRequest().number()),
                workspace.monitoredRepository().fullName(), published.pullRequest().number(),
                task.id().toString(), physical, true, Instant.now(clock)));
    }

    public View get(String workspaceId)
    {
        notebooks.getNotebook(workspaceId);
        WorkspaceRepositoryContext workspace = workspaces.requireReadableRepository(workspaceId);
        RepositoryIdentity physical = WorkflowAgentTasks.repositoryIdentity(workspace.selectedRepository());
        List<String> gaps = new ArrayList<>();
        captureImports(workspace, physical, gaps);
        ContextMergedCandidateStore.State state = store.get(workspaceId);
        gaps.add("Only 50 retained task links and 10 merged candidates are inspected; older work may be omitted.");
        gaps.add("Merge records and cached check runs do not prove the change was correct or that it still applies.");
        List<ReviewRun> inspectedReviews;
        try {
            inspectedReviews = reviews.listRecentRuns(workspaceId, workspace.monitoredRepository().fullName(), 10);
        }
        catch (RuntimeException failure) {
            inspectedReviews = ImmutableList.of();
            gaps.add("Retained review history could not be read; merged PR candidates remain available.");
        }
        List<ReviewRun> recentReviews = inspectedReviews;
        List<MergedLink> merged = new ArrayList<>();
        for (ContextMergedCandidateStore.Link link : state.links()) {
            if (!link.repo().equalsIgnoreCase(workspace.monitoredRepository().fullName()) || !link.physicalRepository().equals(physical)) {
                continue;
            }
            try {
                pullRequests.findIdByRepoAndNumber(link.repo(), link.number()).flatMap(pullRequests::findById)
                        .filter(pr -> pr.repo().equalsIgnoreCase(link.repo()) && pr.number() == link.number()
                                && pr.mergedAt() != null && pr.htmlUrl().equalsIgnoreCase(
                                        "https://github.com/" + link.repo() + "/pull/" + link.number()))
                        .ifPresent(pr -> merged.add(new MergedLink(link, pr)));
            }
            catch (RuntimeException failure) {
                if (gaps.stream().noneMatch(gap -> gap.startsWith("Some cached PR rows"))) {
                    gaps.add("Some cached PR rows could not be read; those candidates were omitted.");
                }
            }
        }
        List<ContextMergedCandidate> candidates = merged.stream()
                .sorted(Comparator.comparing((MergedLink value) -> value.pullRequest().mergedAt()).reversed()
                        .thenComparing(value -> value.pullRequest().number(), Comparator.reverseOrder()))
                .limit(MAX_VISIBLE).map(value -> candidate(workspaceId, physical, value.link(), value.pullRequest(), recentReviews))
                .toList();
        return new View(candidates, gaps);
    }

    public ContextBuildService.View learn(String workspaceId, LearnRequest request)
    {
        ContextMergedCandidateStore.State before = store.get(workspaceId);
        Optional<ContextMergedCandidateStore.Selection> replay = before.selection()
                .filter(selection -> selection.requestKey().equals(request.requestKey()));
        if (replay.isPresent()) {
            ContextMergedCandidateStore.Selection selected = replay.orElseThrow();
            checkArgument(selected.candidate().id().equals(request.candidateId())
                    && selected.expectedRevision() == request.expectedRevision()
                    && selected.expectedBuildSequence() == request.expectedBuildSequence(),
                    "Candidate request key was reused with different inputs");
            return launch(workspaceId, selected);
        }
        ContextMergedCandidate candidate = get(workspaceId).candidates().stream()
                .filter(value -> value.id().equals(request.candidateId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Merged candidate changed; inspect the current list"));
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        checkArgument(notebook.revision() == request.expectedRevision()
                && notebook.scopes().stream().anyMatch(scope -> scope.id().equals("project") && scope.enabled()),
                "Project learning scope or notebook revision changed");
        ContextBuildService.View build = builds.get(workspaceId);
        checkArgument(build.buildRequestSequence() == request.expectedBuildSequence() && build.canStart(),
                "Finish and clean up the current learning task before selecting merged work");
        ContextMergedCandidateStore.Selection selected = new ContextMergedCandidateStore.Selection(
                request.requestKey(), request.expectedRevision(), request.expectedBuildSequence(), candidate);
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            ContextMergedCandidateStore.State current = store.get(workspaceId);
            checkArgument(current.selection().equals(before.selection()), "Candidate selection changed");
            checkArgument(notebooks.getNotebook(workspaceId).revision() == request.expectedRevision()
                    && buildStore.find(workspaceId).map(ContextBuild::sequence).orElse(0L) == request.expectedBuildSequence(),
                    "Notebook or learning task changed");
            store.save(current, new ContextMergedCandidateStore.State(workspaceId, current.links(), Optional.of(selected)));
        });
        return launch(workspaceId, selected);
    }

    /** Exact saved bundle for the selected history build, never a later recomputation from changing PR caches. */
    public Optional<ContextMergedCandidate> selected(ContextBuild build)
    {
        if (build.mode() != ContextBuild.Mode.LEARN || build.assignment().isPresent() || build.history().size() != 1) {
            return Optional.empty();
        }
        return store.get(build.workspaceId()).selection().filter(selection ->
                selection.requestKey().equals(build.requestKey())
                        && selection.expectedBuildSequence() + 1 == build.sequence()
                        && selection.expectedRevision() == build.notebookRevision()
                        && build.history().getFirst().kind() == ContextHistoryReader.SourceKind.PR
                        && build.history().getFirst().number() == selection.candidate().number()
                        && build.repository().fullName().equalsIgnoreCase(selection.candidate().repo()))
                .map(ContextMergedCandidateStore.Selection::candidate);
    }

    private ContextBuildService.View launch(String workspaceId, ContextMergedCandidateStore.Selection selection)
    {
        return builds.learnHistory(workspaceId, new ContextBuildService.Request(
                selection.requestKey(), selection.expectedRevision(), selection.expectedBuildSequence(),
                ImmutableList.of("project"), ImmutableList.of(
                        new ContextBuild.History(ContextHistoryReader.SourceKind.PR, selection.candidate().number()))));
    }

    private void captureImports(WorkspaceRepositoryContext workspace, RepositoryIdentity physical, List<String> gaps)
    {
        try {
            for (AgentTask task : tasks.listRecentCompletedInteractive(workspace.workspaceId(), physical, MAX_LINKS)) {
                if (reviews.findReviewByTask(task.id().toString()).isPresent()) {
                    continue;
                }
                imports.findByTaskId(task.id().toString())
                        .filter(imported -> imported.workspaceId().equals(workspace.workspaceId())
                                && imported.repo().equalsIgnoreCase(workspace.monitoredRepository().fullName()))
                        .ifPresent(imported -> observe(workspace.workspaceId(), new ContextMergedCandidateStore.Link(
                                id(imported.repo(), imported.number()), imported.repo(), imported.number(),
                                task.id().toString(), physical, false, task.createdAt())));
            }
        }
        catch (RuntimeException failure) {
            gaps.add("Older imported task associations could not all be inspected; saved verified links remain available.");
        }
    }

    private void observe(String workspaceId, ContextMergedCandidateStore.Link link)
    {
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            ContextMergedCandidateStore.State current = store.get(workspaceId);
            Optional<ContextMergedCandidateStore.Link> existing = current.links().stream()
                    .filter(value -> value.id().equals(link.id())).findFirst();
            if (existing.filter(value -> value.verifiedPublication() || !link.verifiedPublication()).isPresent()) {
                return;
            }
            List<ContextMergedCandidateStore.Link> links = new ArrayList<>();
            links.add(link);
            current.links().stream().filter(value -> !value.id().equals(link.id())).limit(MAX_LINKS - 1).forEach(links::add);
            store.save(current, new ContextMergedCandidateStore.State(workspaceId, links, current.selection()));
        });
    }

    private ContextMergedCandidate candidate(String workspaceId, RepositoryIdentity physical, ContextMergedCandidateStore.Link link,
            PullRequest pr, List<ReviewRun> recentReviews)
    {
        List<String> gaps = new ArrayList<>();
        Optional<AgentTask> task = tasks.find(TaskId.valueOf(link.taskId())).filter(value -> value.workspaceId().filter(workspaceId::equals).isPresent()
                && value.repository().equals(physical) && value.interactive());
        if (task.isEmpty()) {
            gaps.add("The linked development task is no longer retained; the merged PR alone remains available.");
        }
        Optional<DevelopmentLearningSummary> summary = task.flatMap(value -> turns.list(value.id()).stream().reduce((first, last) -> last))
                .filter(turn -> turn.state() == TurnState.COMPLETED
                        && turn.developmentOrigin().filter(origin -> origin.kind() == com.bytequay.agent.core.DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent())
                .flatMap(turn -> turn.developmentResult().flatMap(report -> report.learningSummary()));
        if (summary.isEmpty()) {
            gaps.add("No final verified development learning summary was retained for this task.");
        }
        Optional<ContextMergedCandidate.Issue> issue = task.flatMap(value -> issues.findByTaskId(value.id().toString()))
                .filter(value -> value.workspaceId().equals(workspaceId))
                .map(value -> new ContextMergedCandidate.Issue("GITHUB", "#" + value.source().number(), value.source().url(),
                        excerpt(value.source().title(), 500), display(value.source().body(), 4000)))
                .or(() -> task.flatMap(value -> jira.findByTaskId(value.id().toString()))
                        .filter(value -> value.workspaceId().equals(workspaceId))
                        .map(value -> new ContextMergedCandidate.Issue("JIRA", value.source().key(), value.source().url(),
                                excerpt(value.source().title(), 500), display(value.source().body(), 4000))));
        Optional<ContextMergedCandidate.Review> review = recentReviews.stream()
                .filter(run -> run.state() == ReviewRun.State.ACCEPTED && run.summary().isPresent() && run.coverage().isPresent())
                .filter(run -> reviews.findReview(run.reviewId()).filter(value ->
                        value.workspaceId().equals(workspaceId) && value.repo().equalsIgnoreCase(pr.repo())
                                && value.number().isPresent() && value.number().getAsInt() == pr.number()).isPresent())
                .findFirst().map(run -> new ContextMergedCandidate.Review(run.reviewId(), run.id(),
                        display(run.summary().orElseThrow().purpose(), 4000),
                        display(run.summary().orElseThrow().combinedResult(), 4000),
                        run.coverage().orElseThrow().complete(),
                        run.coverage().orElseThrow().omissions().stream().limit(20).map(value -> excerpt(value, 1000)).toList(),
                        run.coverage().orElseThrow().warnings().stream().limit(20).map(value -> excerpt(value, 1000)).toList()));
        if (review.isEmpty()) {
            gaps.add("No accepted review was found among the 10 most recent local review runs.");
        }
        Optional<StoredPrDetail> detail;
        try {
            detail = details.find(pr.id());
        }
        catch (RuntimeException failure) {
            detail = Optional.empty();
            gaps.add("Cached PR detail could not be read; check evidence is unavailable.");
        }
        List<ContextMergedCandidate.Check> checks = detail.stream().flatMap(value -> value.checkRuns().stream()).limit(20)
                .map(check -> new ContextMergedCandidate.Check(display(check.name(), 400), display(check.status(), 100),
                        display(check.conclusion(), 100), display(check.detailsUrl(), 500))).toList();
        if (detail.isEmpty() || !detail.orElseThrow().checkRunsComplete()) {
            gaps.add("Cached PR checks are missing or incomplete; no test outcome was inferred.");
        }
        gaps.add("Task, issue, review and cached check text are historical context; validate them against current code before keeping a lesson.");
        return new ContextMergedCandidate(link.id(), pr.repo(), pr.number(), excerpt(pr.title(), 500), pr.htmlUrl(), pr.mergedAt(),
                task.map(value -> value.id().toString()), task.map(value -> excerpt(value.title(), 200)),
                task.flatMap(value -> value.summary().map(result -> excerpt(result.finalResponse(), 4000))),
                summary, issue, review, checks, gaps);
    }

    private static String id(String repo, int number)
    {
        return "pr:" + repo.toLowerCase(Locale.ROOT) + "#" + number;
    }

    private static String display(String text, int maxBytes)
    {
        return text == null || text.isBlank() ? "Unknown" : excerpt(text, maxBytes);
    }

    private static String excerpt(String text, int maxBytes)
    {
        return ContextText.excerpt(requireNonNull(text, "source text is null"), maxBytes);
    }

    private record MergedLink(ContextMergedCandidateStore.Link link, PullRequest pullRequest) {}

    public record View(List<ContextMergedCandidate> candidates, List<String> gaps)
    {
        public View
        {
            candidates = ImmutableList.copyOf(candidates);
            gaps = ImmutableList.copyOf(gaps);
        }
    }

    public record LearnRequest(String candidateId, String requestKey, long expectedRevision, long expectedBuildSequence)
    {
        public LearnRequest
        {
            ContextLimits.text(candidateId, "candidateId", 200);
            ContextLimits.text(requestKey, "requestKey", 100);
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Invalid candidate request revision");
        }
    }
}
