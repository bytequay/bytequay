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

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.git.GitBrowseService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.RepositoryEnrichmentService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestRepositoryContextReader
{
    private static final String WORKSPACE = "workspace";
    private static final RepoRef MONITORED = RepoRef.of("trino", "trino");
    private static final RepositoryIdentity PHYSICAL = RepositoryIdentity.valueOf("fork/trino");
    private static final long REPOSITORY_ID = 7;
    private static final Path REPOSITORY = Path.of("/tmp/context-reader/repository");
    private static final String BASELINE = "a".repeat(40);
    private static final String DIFF_BASE = "b".repeat(40);
    private static final String TARGET_BASE = "c".repeat(40);
    private static final String HEAD = "d".repeat(40);
    private static final String FILE = "plugin/iceberg/Metadata.java";
    private static final WorkspaceRepositoryContext WORKSPACE_CONTEXT = new WorkspaceRepositoryContext(
            WORKSPACE, REPOSITORY, 5, RepoRef.of("fork", "trino"), OptionalLong.of(REPOSITORY_ID), MONITORED, "upstream", "origin", "main", ImmutableList.of());
    private static final AgentReview REVIEW = new AgentReview("review-current", WORKSPACE, MONITORED.fullName(), 1, OptionalLong.of(1), "task",
            AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());

    private final GitBrowseService browse = mock(GitBrowseService.class);
    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final RepositoryEnrichmentService enrichment = mock(RepositoryEnrichmentService.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final PrDiffStore diffs = mock(PrDiffStore.class);
    private final WorkspaceIssueService issues = mock(WorkspaceIssueService.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final RepositoryContextReader reader = new RepositoryContextReader(
            new RepositoryContextMatcher(), browse, git, enrichment, pullRequests, details, diffs, issues, reviews, tasks);

    @BeforeEach
    void localSources()
    {
        when(git.run(REPOSITORY, "rev-parse", "--verify", "--end-of-options", "refs/remotes/upstream/main^{commit}"))
                .thenReturn(GitCommandRunner.Result.failed("No local baseline"));
        when(git.run(REPOSITORY, "diff", "--name-only", "--no-renames", "-z", DIFF_BASE, HEAD, "--"))
                .thenReturn(new GitCommandRunner.Result(0, FILE + '\0', false));
        when(reviews.listRecentRuns(WORKSPACE, MONITORED.fullName(), 10)).thenReturn(ImmutableList.of());
        when(tasks.listCompleted(WORKSPACE, PHYSICAL, 10)).thenReturn(ImmutableList.of());
        when(pullRequests.listPullRequests(MONITORED, 0, 20, "Iceberg"))
                .thenReturn(new PullRequestPage(ImmutableList.of(), 0, 20, 0, 0, false));
        when(issues.list(WORKSPACE, 0, 100)).thenReturn(issuePage(ImmutableList.of()));
    }

    @Test
    void localTitleCandidatesUseRecencyWithinTheirRankAndNeverHydrate()
    {
        stored(pr(1, "Iceberg update", 0));
        List<PullRequest> rows = ImmutableList.of(pr(2, "Iceberg oldest", 1), pr(3, "Iceberg newest", 40),
                pr(4, "Iceberg second", 30), pr(5, "Iceberg third", 20), pr(6, "Icebergish excluded", 50));
        rows.forEach(this::stored);
        when(pullRequests.listPullRequests(MONITORED, 0, 20, "Iceberg")).thenReturn(new PullRequestPage(rows, 0, 20, rows.size(), rows.size(), false));

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.related()).extracting(RepositoryContextReader.Related::id).containsExactly("pr:7:3", "pr:7:4", "pr:7:5");
        assertThat(mockingDetails(pullRequests).getInvocations()).extracting(invocation -> invocation.getMethod().getName())
                .containsOnly("findStored", "listPullRequests");
        verifyNoInteractions(browse, enrichment);
    }

    @Test
    void explicitReferencesHavePriorityAndKeepRecencyWithinTheirRank()
    {
        stored(pr(1, "#2 #3 #4 #5 https://github.com/another/repo/pull/99", 0));
        stored(pr(2, "Oldest", 1));
        stored(pr(3, "Newest", 40));
        stored(pr(4, "Second", 30));
        stored(pr(5, "Third", 20));

        RepositoryContextReader.Selection selected = select(RepositoryNotebook.empty());

        assertThat(selected.related()).extracting(RepositoryContextReader.Related::id).containsExactly("pr:7:3", "pr:7:4", "pr:7:5");
        assertThat(selected.gaps()).contains("A cross-repository reference was excluded from this workspace's local lookup.");
        verify(pullRequests, never()).findStored(anyString(), eq(99));
        verify(issues, never()).find(anyString(), anyInt());
    }

    @Test
    void multibyteDescriptionTruncationIsDisclosed()
    {
        stored(pr(1, "Current change", 0));
        PrRawDetail raw = new PrRawDetail("你".repeat(45_000), ImmutableList.of(), false, null, null, 0, 0, 1, 0, ImmutableList.of(), HEAD, "branch");
        when(details.find(1)).thenReturn(Optional.of(new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of())));

        assertThat(select(RepositoryNotebook.empty()).gaps()).contains("Text matching is bounded to the PR description preview and the first 100 commit messages.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableOrTruncatedPathsLeaveRepositoryAndTermMatchingAvailable(boolean truncated)
    {
        stored(pr(1, "Iceberg change", 0));
        when(git.run(REPOSITORY, "diff", "--name-only", "--no-renames", "-z", DIFF_BASE, HEAD, "--"))
                .thenReturn(truncated ? new GitCommandRunner.Result(0, "plugin/iceberg/part", true) : GitCommandRunner.Result.failed("Unknown head"));

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.notes().scopes()).extracting(RepositoryContextMatcher.ScopeMatch::rank)
                .containsExactly(RepositoryContextMatcher.Rank.REPOSITORY, RepositoryContextMatcher.Rank.TERM);
        assertThat(selected.gaps()).contains(
                "Frozen matching paths are unavailable or truncated; path matching and history were skipped, while repository and text matching remain available.");
        verifyNoInteractions(browse, enrichment);
    }

    @Test
    void unsupportedGitPathsAreExcludedWithoutDiscardingSupportedMatches()
    {
        when(git.run(REPOSITORY, "diff", "--name-only", "--no-renames", "-z", DIFF_BASE, HEAD, "--"))
                .thenReturn(new GitCommandRunner.Result(0, "-leading-option\0back\\slash\0" + FILE + '\0', false));

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.notes().scopes()).extracting(RepositoryContextMatcher.ScopeMatch::rank)
                .containsExactly(RepositoryContextMatcher.Rank.REPOSITORY, RepositoryContextMatcher.Rank.PATH);
        assertThat(selected.gaps()).contains("Some Git paths are outside the supported context path grammar and were excluded from path matching and history.");
    }

    @Test
    void excessiveMatchingPathsAreBoundedInsteadOfRejectingTheReview()
    {
        String paths = IntStream.range(0, 10_001).mapToObj(index -> "plugin/iceberg/File" + index + ".java").collect(joining("\0", "", "\0"));
        when(git.run(REPOSITORY, "diff", "--name-only", "--no-renames", "-z", DIFF_BASE, HEAD, "--"))
                .thenReturn(new GitCommandRunner.Result(0, paths, false));

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.notes().scopes()).extracting(RepositoryContextMatcher.ScopeMatch::rank)
                .containsExactly(RepositoryContextMatcher.Rank.REPOSITORY, RepositoryContextMatcher.Rank.PATH);
        assertThat(selected.gaps()).contains("Matching paths exceed the 10000-path or 1024 KiB UTF-8 budget; only complete supported paths within the budget were considered.");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 2, 128})
    void historyJoinsOnlyMonitoredRepositoryShaMappingsAndKeepsUnknownAncestryUnknown(int exitCode)
    {
        String associated = "e".repeat(40);
        String forkOnly = "f".repeat(40);
        when(git.run(REPOSITORY, "rev-parse", "--verify", "--end-of-options", "refs/remotes/upstream/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, BASELINE, false));
        List<GitBrowseService.Commit> history = ImmutableList.of(
                new GitBrowseService.Commit(associated, "Stored association", "", "author", "committer", "2001-01-01T00:00:00Z", "2001-01-01T00:00:00Z", Optional.empty()),
                new GitBrowseService.Commit(forkOnly, "Unverified suffix (#999)", "", "author", "committer", "2001-01-01T00:00:00Z", "2001-01-01T00:00:00Z", Optional.of(999)));
        when(browse.log(REPOSITORY, BASELINE, ImmutableList.of(FILE), 50)).thenReturn(history);
        when(enrichment.findCommits(eq(REPOSITORY_ID), any())).thenReturn(new CommitEnrichmentSnapshot(
                ImmutableList.of(mapping(REPOSITORY_ID, associated, 10), mapping(5, forkOnly, 11))));
        when(git.run(REPOSITORY, "merge-base", "--is-ancestor", associated, DIFF_BASE)).thenReturn(new GitCommandRunner.Result(exitCode, "", false));

        RepositoryContextReader.Selection selected = select(RepositoryNotebook.empty());

        assertThat(selected.baselineSha()).contains(BASELINE);
        assertThat(selected.related()).extracting(RepositoryContextReader.Related::id).containsExactly("pr:7:10");
        assertThat(selected.related().getFirst().presentInReviewedBase()).isEqualTo(exitCode == 0 ? Optional.of(true) : exitCode == 1 ? Optional.of(false) : Optional.empty());
        assertThat(selected.related().getFirst().provenance()).contains("frozen diff base " + DIFF_BASE, "does not establish that the reviewed head preserves");
        verify(git, never()).run(REPOSITORY, "merge-base", "--is-ancestor", associated, TARGET_BASE);
        verify(pullRequests, never()).findStored(MONITORED.fullName(), 999);
        verify(enrichment).findCommits(eq(REPOSITORY_ID), eq(ImmutableSet.of(associated, forkOnly)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void componentHistoryUsesDistinctPullRequestsIncludingDirectReferences(int directReferences)
    {
        stored(pr(1, directReferences == 1 ? "Iceberg #2" : "Iceberg #2 #3", 0));
        stored(pr(2, "First direct reference", 20));
        stored(pr(3, "Second direct reference", 10));
        String oldest = "1".repeat(40);
        String newest = "2".repeat(40);
        String middle = "3".repeat(40);
        String componentDuplicate = "4".repeat(40);
        String componentOnly = "5".repeat(40);
        when(git.run(REPOSITORY, "rev-parse", "--verify", "--end-of-options", "refs/remotes/upstream/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, BASELINE, false));
        when(browse.log(REPOSITORY, BASELINE, ImmutableList.of(FILE), 50)).thenReturn(ImmutableList.of(
                new GitBrowseService.Commit(oldest, "Oldest exact match", "", "author", "committer", "2001-01-01T00:00:00Z", "2001-01-01T00:00:00Z", Optional.empty()),
                new GitBrowseService.Commit(newest, "Newest exact match", "", "author", "committer", "2003-01-01T00:00:00Z", "2003-01-01T00:00:00Z", Optional.empty()),
                new GitBrowseService.Commit(middle, "Middle exact match", "", "author", "committer", "2002-01-01T00:00:00Z", "2002-01-01T00:00:00Z", Optional.empty())));
        when(enrichment.findCommits(REPOSITORY_ID, ImmutableSet.of(oldest, newest, middle))).thenReturn(new CommitEnrichmentSnapshot(ImmutableList.of(
                mapping(REPOSITORY_ID, oldest, 10), mapping(REPOSITORY_ID, newest, 10), mapping(REPOSITORY_ID, middle, 10))));
        when(git.run(REPOSITORY, "merge-base", "--is-ancestor", newest, DIFF_BASE)).thenReturn(new GitCommandRunner.Result(0, "", false));
        if (directReferences == 1) {
            when(browse.log(REPOSITORY, BASELINE, ImmutableList.of("plugin/iceberg/"), 47)).thenReturn(ImmutableList.of(
                    new GitBrowseService.Commit(componentDuplicate, "Newer component match", "", "author", "committer",
                            "2005-01-01T00:00:00Z", "2005-01-01T00:00:00Z", Optional.empty()),
                    new GitBrowseService.Commit(componentOnly, "Another component PR", "", "author", "committer",
                            "2004-01-01T00:00:00Z", "2004-01-01T00:00:00Z", Optional.empty())));
            when(enrichment.findCommits(REPOSITORY_ID, ImmutableSet.of(componentDuplicate, componentOnly))).thenReturn(new CommitEnrichmentSnapshot(ImmutableList.of(
                    mapping(REPOSITORY_ID, componentDuplicate, 10), mapping(REPOSITORY_ID, componentOnly, 11))));
            when(git.run(REPOSITORY, "merge-base", "--is-ancestor", componentOnly, DIFF_BASE)).thenReturn(new GitCommandRunner.Result(0, "", false));
        }

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.related()).extracting(RepositoryContextReader.Related::id).containsExactlyElementsOf(directReferences == 1
                ? ImmutableList.of("pr:7:2", "pr:7:10", "pr:7:11")
                : ImmutableList.of("pr:7:2", "pr:7:3", "pr:7:10"));
        RepositoryContextReader.Related history = selected.related().stream().filter(related -> related.id().equals("pr:7:10")).findFirst().orElseThrow();
        assertThat(history.provenance()).contains(newest).doesNotContain(oldest, middle, componentDuplicate);
        assertThat(history.matchReasons()).singleElement().asString().contains("required review path", newest);
        verify(browse, times(directReferences == 1 ? 1 : 0)).log(REPOSITORY, BASELINE, ImmutableList.of("plugin/iceberg/"), 47);
    }

    @Test
    void previousReviewPathOverlapPrecedesNewerTermOnlyConclusions()
    {
        stored(pr(1, "Iceberg change", 0));
        ReviewRun newestTerm = previous("term-new", "other/a.java", "Iceberg change", 40);
        ReviewRun secondTerm = previous("term-second", "other/b.java", "Iceberg change", 30);
        ReviewRun thirdTerm = previous("term-third", "other/c.java", "Iceberg change", 20);
        ReviewRun oldPath = previous("path", FILE, "Unrelated prose", 1);
        when(reviews.listRecentRuns(WORKSPACE, MONITORED.fullName(), 10)).thenReturn(ImmutableList.of(newestTerm, secondTerm, thirdTerm, oldPath));

        assertThat(select(componentNotebook()).related()).extracting(RepositoryContextReader.Related::id)
                .containsExactly("review:path", "review:term-new", "review:term-second");
    }

    @Test
    void previousTasksAndReviewsShareOneCapWithReferenceAndPathPriority()
    {
        AgentTask direct = task(1, "Older investigation", "Unrelated conclusion", Optional.empty(), 1);
        AgentTask newest = task(2, "Iceberg investigation", "Recent conclusion", Optional.empty(), 50);
        AgentTask second = task(3, "Iceberg investigation", "Other conclusion", Optional.empty(), 40);
        stored(pr(1, "Iceberg change; task:" + direct.id(), 0));
        when(tasks.getInteractive(WORKSPACE, direct.id())).thenReturn(direct);
        when(tasks.listCompleted(WORKSPACE, PHYSICAL, 10)).thenReturn(ImmutableList.of(newest, second));
        when(reviews.listRecentRuns(WORKSPACE, MONITORED.fullName(), 10)).thenReturn(ImmutableList.of(
                previous("term", "other.java", "Iceberg conclusion", 60), previous("path", FILE, "Earlier path review", 2)));

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.related()).extracting(RepositoryContextReader.Related::id)
                .containsExactly("task:" + direct.id(), "review:path", "review:term");
        assertThat(selected.related().getFirst().matchReasons()).anyMatch(reason -> reason.toLowerCase().contains("reference"));
        assertThat(mockingDetails(tasks).getInvocations()).extracting(invocation -> invocation.getMethod().getName())
                .containsOnly("listCompleted", "getInteractive");
        verifyNoInteractions(browse, enrichment);
    }

    @Test
    void equalRankAndRecencyUseStableIdsAcrossTasksAndReviews()
    {
        AgentTask first = task(1, "Iceberg task", "First", Optional.empty(), 30);
        AgentTask second = task(2, "Iceberg task", "Second", Optional.empty(), 30);
        AgentTask third = task(3, "Iceberg task", "Third", Optional.empty(), 30);
        when(tasks.listCompleted(WORKSPACE, PHYSICAL, 10)).thenReturn(ImmutableList.of(third, second, first));
        when(reviews.listRecentRuns(WORKSPACE, MONITORED.fullName(), 10)).thenReturn(ImmutableList.of(previous("term", "other.java", "Iceberg", 30)));

        assertThat(select(componentNotebook()).related()).extracting(RepositoryContextReader.Related::id)
                .containsExactly("review:term", "task:" + first.id(), "task:" + second.id());
    }

    @Test
    void taskMatchingUsesWholeTermsInTitleResponseAndNotesWithoutInferringPathOverlap()
    {
        AgentTask title = task(1, "ICEBERG task", "A conclusion", Optional.empty(), 10);
        AgentTask response = task(2, "Task", "Investigated Iceberg behavior", Optional.empty(), 20);
        AgentTask notes = task(3, "Task", "A conclusion", Optional.of("Relevant Iceberg limitation"), 30);
        AgentTask substring = task(4, "Icebergish", "An Icebergish claim", Optional.of("Icebergish notes"), 50);
        when(tasks.listCompleted(WORKSPACE, PHYSICAL, 10)).thenReturn(ImmutableList.of(substring, notes, response, title));

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.related()).extracting(RepositoryContextReader.Related::id)
                .containsExactly("task:" + notes.id(), "task:" + response.id(), "task:" + title.id());
        assertThat(selected.related()).allSatisfy(related -> {
            assertThat(related.matchReasons()).allMatch(reason -> !reason.contains("path overlap"));
            assertThat(related.presentInReviewedBase()).isEmpty();
        });
        assertThat(selected.related().getFirst().excerpt()).contains("Relevant Iceberg limitation");
    }

    @Test
    void unrelatedTaskPathCannotMatchWithoutConfiguredTerm()
    {
        AgentTask task = task(1, "Metadata task", "Changed other/Metadata.java", Optional.empty(), 10);
        stored(pr(1, "Iceberg change", 0));
        when(git.run(REPOSITORY, "diff", "--name-only", "--no-renames", "-z", DIFF_BASE, HEAD, "--"))
                .thenReturn(new GitCommandRunner.Result(0, "other/Metadata.java\0", false));
        when(tasks.listCompleted(WORKSPACE, PHYSICAL, 10)).thenReturn(ImmutableList.of(task));

        assertThat(select(componentNotebook()).related()).isEmpty();
        verify(tasks).listCompleted(WORKSPACE, PHYSICAL, 10);
    }

    @Test
    void recentTasksAreDefensivelyScopedToWorkspaceAndPhysicalRepository()
    {
        AgentTask selected = complete(openTask(1, WORKSPACE, true, RepositoryIdentity.valueOf("FORK/TRINO"), "Iceberg"), "Allowed", Optional.empty(), 1);
        AgentTask upstream = complete(openTask(2, WORKSPACE, true, RepositoryIdentity.valueOf("trino/trino"), "Iceberg"), "Wrong repository", Optional.empty(), 20);
        AgentTask foreign = complete(openTask(3, "another-workspace", true, PHYSICAL, "Iceberg"), "Wrong workspace", Optional.empty(), 30);
        AgentTask internal = complete(openTask(4, WORKSPACE, false, PHYSICAL, "Iceberg"), "Internal task", Optional.empty(), 40);
        AgentTask open = openTask(5, WORKSPACE, true, PHYSICAL, "Iceberg");
        when(tasks.listCompleted(WORKSPACE, PHYSICAL, 10)).thenReturn(ImmutableList.of(upstream, foreign, internal, open, selected));

        RepositoryContextReader.Selection result = select(componentNotebook());

        assertThat(result.related()).extracting(RepositoryContextReader.Related::id).containsExactly("task:" + selected.id());
        assertThat(result.related().getFirst().provenance()).containsIgnoringCase("fork/trino").contains(WORKSPACE);
        verify(tasks).listCompleted(WORKSPACE, PHYSICAL, 10);
        verify(tasks, never()).getInteractive(anyString(), any());
    }

    @Test
    void reviewOwnedTasksAreNotCountedAgainAsTaskSummaries()
    {
        AgentTask owner = task(1, "Iceberg review", "Review conclusion", Optional.empty(), 10);
        AgentTask prior = task(2, "Iceberg review", "Prior review conclusion", Optional.empty(), 20);
        AgentTask development = task(3, "Iceberg task", "Development conclusion", Optional.empty(), 30);
        AgentReview owningReview = new AgentReview(REVIEW.id(), WORKSPACE, MONITORED.fullName(), 1, OptionalLong.of(1), owner.id().toString(),
                AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
        stored(pr(1, "task:" + owner.id() + " task:" + prior.id(), 0));
        when(tasks.getInteractive(WORKSPACE, owner.id())).thenReturn(owner);
        when(tasks.getInteractive(WORKSPACE, prior.id())).thenReturn(prior);
        when(tasks.listCompleted(WORKSPACE, PHYSICAL, 10)).thenReturn(ImmutableList.of(owner, prior, development));
        when(reviews.findReviewByTask(prior.id().toString())).thenReturn(Optional.of(REVIEW));
        when(reviews.listRecentRuns(WORKSPACE, MONITORED.fullName(), 10)).thenReturn(ImmutableList.of(previous("prior", FILE, "Prior review", 20)));

        RepositoryContextReader.Selection result = reader.select(owningReview, run("run-current", REVIEW.id(), FILE, Instant.EPOCH),
                WORKSPACE_CONTEXT, Optional.empty(), componentNotebook());

        assertThat(result.related()).extracting(RepositoryContextReader.Related::id).containsExactly("review:prior", "task:" + development.id());
    }

    @Test
    void explicitTaskReferencesAreDeduplicatedBoundedAndRequireTheTaskPrefix()
    {
        List<AgentTask> candidates = IntStream.rangeClosed(1, 11).mapToObj(number -> task(number, "Task", "Conclusion", Optional.empty(), number))
                .collect(ImmutableList.toImmutableList());
        stored(pr(1, "Bare identifier " + taskId(12) + " task:" + candidates.getFirst().id() + " "
                + candidates.stream().map(task -> "task:" + task.id()).collect(joining(" ")), 0));
        candidates.forEach(task -> when(tasks.getInteractive(WORKSPACE, task.id())).thenReturn(task));

        RepositoryContextReader.Selection selected = select(RepositoryNotebook.empty());

        assertThat(selected.related()).extracting(RepositoryContextReader.Related::id)
                .containsExactly("task:" + taskId(10), "task:" + taskId(9), "task:" + taskId(8));
        verify(tasks, times(1)).getInteractive(WORKSPACE, candidates.getFirst().id());
        verify(tasks, times(10)).getInteractive(eq(WORKSPACE), any());
        verify(tasks, never()).getInteractive(WORKSPACE, taskId(11));
        verify(tasks, never()).getInteractive(WORKSPACE, taskId(12));
        assertThat(selected.gaps()).anyMatch(gap -> gap.toLowerCase().contains("task") && gap.contains("10 distinct"));
    }

    @Test
    void missingAndIneligibleExplicitTasksLeaveGapsWithoutLeakingTheirContent()
    {
        AgentTask foreign = complete(openTask(2, "another-workspace", true, PHYSICAL, "Secret workspace"), "Secret conclusion", Optional.empty(), 20);
        AgentTask upstream = complete(openTask(3, WORKSPACE, true, RepositoryIdentity.valueOf("trino/trino"), "Secret repository"), "Secret conclusion", Optional.empty(), 30);
        AgentTask internal = complete(openTask(4, WORKSPACE, false, PHYSICAL, "Secret internal task"), "Secret conclusion", Optional.empty(), 40);
        AgentTask open = openTask(5, WORKSPACE, true, PHYSICAL, "Secret unfinished task");
        stored(pr(1, IntStream.rangeClosed(1, 5).mapToObj(id -> "task:" + taskId(id)).collect(joining(" ")), 0));
        when(tasks.getInteractive(WORKSPACE, taskId(1))).thenThrow(new AgentTaskNotFoundException(taskId(1)));
        ImmutableList.of(foreign, upstream, internal, open).forEach(task -> when(tasks.getInteractive(WORKSPACE, task.id())).thenReturn(task));

        RepositoryContextReader.Selection selected = select(RepositoryNotebook.empty());

        assertThat(selected.related()).isEmpty();
        assertThat(selected.gaps()).anyMatch(gap -> gap.contains(taskId(1).toString()));
        assertThat(selected.gaps()).noneMatch(gap -> gap.contains("Secret"));
    }

    @Test
    void failedTaskStorageReadIsNotMisreportedAsAMissingTask()
    {
        stored(pr(1, "task:" + taskId(1), 0));
        when(tasks.getInteractive(WORKSPACE, taskId(1))).thenThrow(new IllegalStateException("Task storage unavailable"));

        assertThatThrownBy(() -> select(RepositoryNotebook.empty())).isInstanceOf(IllegalStateException.class).hasMessage("Task storage unavailable");
    }

    @Test
    void taskConclusionRemainsUnverifiedAndRetainsItsKnownHead()
    {
        AgentTask open = openTask(1, WORKSPACE, true, PHYSICAL, "Completed task");
        TaskWorktree worktree = new TaskWorktree(open.id(), WORKSPACE, PHYSICAL, open.worktree(), "bytequay/task/" + open.id(), DIFF_BASE);
        TaskSummary summary = new TaskSummary("Investigated behavior", Optional.of(new WorktreeSnapshot(worktree, HEAD, false, false)), AgentMetrics.empty(), Optional.empty());
        AgentTask completed = open.complete(summary, Instant.EPOCH.plusSeconds(1), Instant.EPOCH.plusSeconds(100));
        stored(pr(1, "task:" + completed.id(), 0));
        when(tasks.getInteractive(WORKSPACE, completed.id())).thenReturn(completed);

        RepositoryContextReader.Related selected = select(RepositoryNotebook.empty()).related().getFirst();

        assertThat(selected.id()).isEqualTo("task:" + completed.id());
        assertThat(selected.provenance()).contains("Retained agent conclusion, not verified fact", HEAD, "COMPLETED", WORKSPACE, "fork/trino");
        assertThat(selected.presentInReviewedBase()).isEmpty();
        verify(git, never()).run(eq(REPOSITORY), eq("merge-base"), anyString(), anyString(), anyString());
    }

    @Test
    void emptyTaskConclusionLeavesAGapWithoutInventingAnExcerpt()
    {
        AgentTask empty = task(1, "Iceberg investigation", "", Optional.empty(), 10);
        stored(pr(1, "task:" + empty.id(), 0));
        when(tasks.getInteractive(WORKSPACE, empty.id())).thenReturn(empty);
        when(tasks.listCompleted(WORKSPACE, PHYSICAL, 10)).thenReturn(ImmutableList.of(empty));

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.related()).isEmpty();
        assertThat(selected.gaps()).anyMatch(gap -> gap.contains(empty.id().toString()) && gap.contains("no retained conclusion text"));
    }

    @Test
    void oversizedTaskTextUsesUtf8BudgetsAndReportsLimitedMatching()
    {
        AgentTask matching = task(1, "Iceberg " + "你".repeat(100), "你".repeat(100_000), Optional.empty(), 10);
        AgentTask trailingTerm = task(2, "Other task", "你".repeat(100_000) + " Iceberg", Optional.empty(), 20);
        when(tasks.listCompleted(WORKSPACE, PHYSICAL, 10)).thenReturn(ImmutableList.of(trailingTerm, matching));

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.related()).extracting(RepositoryContextReader.Related::id).containsExactly("task:" + matching.id());
        assertThat(ContextLimits.bytes(selected.related().getFirst().excerpt())).isLessThanOrEqualTo(1536);
        assertThat(ContextLimits.bytes(selected.related().getFirst().title())).isLessThanOrEqualTo(165);
        assertThat(selected.related().getFirst().excerpt()).doesNotContain("\uFFFD");
        assertThat(selected.related().getFirst().title()).doesNotContain("\uFFFD");
        assertThat(selected.gaps()).anyMatch(gap -> gap.contains(matching.id().toString()) && gap.contains("matching text budget"));
        assertThat(selected.gaps()).anyMatch(gap -> gap.contains(trailingTerm.id().toString()) && gap.contains("matching text budget"));
    }

    @Test
    void oversizedIssueTextIsBoundedWithAVisibleGap()
    {
        stored(pr(1, "Iceberg change", 0));
        RepositoryIssue issue = new RepositoryIssue(2, 2, "Iceberg limitation", "你".repeat(100_000), "author", "avatar", "url",
                ImmutableList.of(), ImmutableList.of(), 0, Instant.EPOCH, Instant.EPOCH);
        when(issues.list(WORKSPACE, 0, 100)).thenReturn(issuePage(ImmutableList.of(issue)));
        when(issues.detail(WORKSPACE, 2)).thenReturn(RepositoryIssueDetail.empty(REPOSITORY_ID, 2));

        RepositoryContextReader.Selection selected = select(componentNotebook());

        assertThat(selected.related()).extracting(RepositoryContextReader.Related::id).containsExactly("issue:7:2");
        assertThat(ContextLimits.bytes(selected.related().getFirst().excerpt())).isLessThanOrEqualTo(1536);
        assertThat(selected.gaps()).contains("Issue #2 matching text exceeds the local matching text budget; only its UTF-8 preview was searched.");
        assertThat(mockingDetails(issues).getInvocations()).extracting(invocation -> invocation.getMethod().getName()).containsOnly("list", "detail");
    }

    private RepositoryContextReader.Selection select(RepositoryNotebook notebook)
    {
        return reader.select(REVIEW, run("run-current", REVIEW.id(), FILE, Instant.EPOCH), WORKSPACE_CONTEXT, Optional.empty(), notebook);
    }

    private void stored(PullRequest row)
    {
        when(pullRequests.findStored(MONITORED.fullName(), row.number())).thenReturn(Optional.of(row));
    }

    private static PullRequest pr(int number, String title, long seconds)
    {
        return new PullRequest(number, MONITORED.fullName(), number, title, "author", "url", Instant.EPOCH, Instant.EPOCH.plusSeconds(seconds),
                PullRequest.Origin.OTHER, ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null,
                0, 0, 0, null, "merged", null, null, null, null, null, null, null, null, null, null);
    }

    private static CommitEnrichment mapping(long repositoryId, String sha, int number)
    {
        return new CommitEnrichment(repositoryId, sha, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(number),
                Optional.of("Associated PR"), Optional.empty(), Optional.empty(), Optional.of(Instant.EPOCH), Instant.EPOCH);
    }

    private static RepositoryNotebook componentNotebook()
    {
        ContextScope scope = new ContextScope("iceberg", "Iceberg", new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of("Iceberg")), true);
        return new RepositoryNotebook(1, 0, true, ImmutableList.of(), ImmutableList.of(ContextScope.project(), scope), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    private static RepositoryIssuePage issuePage(List<RepositoryIssue> values)
    {
        return new RepositoryIssuePage(REPOSITORY_ID, MONITORED, values, 0, 100, values.size(), false, Optional.of(Instant.EPOCH));
    }

    private static ReviewRun previous(String id, String file, String purpose, long seconds)
    {
        return run(id, "review-" + id, file, Instant.EPOCH.plusSeconds(seconds))
                .withSummary(new ReviewSummary(1, purpose, ImmutableList.of(), "Retained conclusion", Optional.empty(), ImmutableList.of()), Instant.EPOCH.plusSeconds(seconds));
    }

    private static TaskId taskId(int id)
    {
        return new TaskId(new UUID(0, id));
    }

    private static AgentTask openTask(int id, String workspace, boolean interactive, RepositoryIdentity repository, String title)
    {
        return AgentTask.created(taskId(id), Optional.of(workspace), interactive, title, AgentLaneId.CODEX_CLI,
                repository, REPOSITORY.resolve(taskId(id).toString()), AgentPermissionPolicy.AUTO, Instant.EPOCH);
    }

    private static AgentTask task(int id, String title, String response, Optional<String> notes, long seconds)
    {
        return complete(openTask(id, WORKSPACE, true, PHYSICAL, title), response, notes, seconds);
    }

    private static AgentTask complete(AgentTask task, String response, Optional<String> notes, long seconds)
    {
        return task.complete(new TaskSummary(response, Optional.empty(), AgentMetrics.empty(), notes),
                Instant.EPOCH.plusSeconds(seconds), Instant.EPOCH.plusSeconds(seconds + 100));
    }

    private static ReviewRun run(String id, String reviewId, String file, Instant updated)
    {
        return new ReviewRun(id, reviewId, 1, ReviewRun.Scope.FULL, "main", TARGET_BASE, DIFF_BASE, HEAD, Optional.empty(), ImmutableList.of(),
                ImmutableList.of(new DiffFile(file, "modified", 1, 0, "patch")), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), Optional.empty(), 0, Instant.EPOCH, updated, Optional.empty());
    }
}
