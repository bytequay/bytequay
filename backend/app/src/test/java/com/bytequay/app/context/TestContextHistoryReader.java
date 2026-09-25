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

import com.bytequay.app.repository.github.GitHubIssuePreview;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.pr.SelectedPullRequestDiffReader;
import com.bytequay.app.service.pr.SelectedPullRequestReader;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextHistoryRead;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static com.bytequay.app.context.ContextHistoryReader.SourceKind.ISSUE;
import static com.bytequay.app.context.ContextHistoryReader.SourceKind.PR;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestContextHistoryReader
{
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant UPDATED = NOW.minusSeconds(60);
    private static final String SHA = "a".repeat(40);
    private static final RepoRef REPO = RepoRef.of("owner", "repo");
    private static final int NUMBER = 7;
    private static final WorkspaceRepositoryContext WORKSPACE = new WorkspaceRepositoryContext("workspace", Path.of("/repository"), 1,
            RepoRef.of("fork", "repo"), OptionalLong.of(9), REPO, "upstream", "origin", "main", ImmutableList.of());

    private final SelectedPullRequestReader pullRequests = mock(SelectedPullRequestReader.class);
    private final SelectedPullRequestDiffReader diffs = mock(SelectedPullRequestDiffReader.class);
    private final RepoService repositories = mock(RepoService.class);
    private final WorkspaceIssueService issues = mock(WorkspaceIssueService.class);
    private final WorkspaceIssueRefreshService issueRefresh = mock(WorkspaceIssueRefreshService.class);
    private final ContextHistoryReader reader = new ContextHistoryReader(pullRequests, diffs, repositories, issues, issueRefresh, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void capturesMergedPrMetadataDiscussionAndSeparateDefaultDiffWithoutClaimingApplicability()
    {
        selected(detail(NOW.minusSeconds(120), UPDATED, "Rationale", ImmutableList.of(activity("Discussed contract"))), false);
        when(diffs.read(REPO.fullName(), NUMBER, Optional.empty(), false)).thenReturn(diff(ImmutableList.of(
                new DiffFile("src/File.java", "modified", 1, 1, "@@ -1 +1 @@\n-old\n+new"))));

        ContextHistoryReader.Read read = reader.read(WORKSPACE, run(true, 9), PR, NUMBER);

        assertThat(read.reference()).isEqualTo(new ContextReference.PullRequest(9, REPO, NUMBER, Optional.empty()));
        assertThat(read.state()).isEqualTo(ContextHistoryRead.State.MERGED);
        assertThat(read.sourceUpdatedAt()).contains(UPDATED);
        assertThat(read.sourceRevision()).contains(SHA);
        assertThat(read.readAt()).isEqualTo(NOW);
        assertThat(read.excerpt()).contains("Observed state=MERGED", "mergedAt=", "sourceRevision=" + SHA, "Rationale", "Discussed contract", "src/File.java", "+new");
        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains("does not establish correctness"));
        assertThat(read.gaps()).contains("Default artifact does not verify the final merged diff.");
        assertThat(read.truncated()).isFalse();
        assertThat(read.refreshFailed()).isFalse();
        assertHash(read);
        verify(pullRequests).read(REPO.fullName(), NUMBER, SelectedPullRequestReader.Section.TIMELINE);
        verify(diffs).read(REPO.fullName(), NUMBER, Optional.empty(), false);
        verifyNoInteractions(repositories, issues, issueRefresh);
    }

    @Test
    void requiresActualMergedAtAndMatchingSelectedPrIdentity()
    {
        selected(detail(null, UPDATED, "Closed without merge", ImmutableList.of()), false);
        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(WORKSPACE, run(true, 9), PR, NUMBER)).withMessageContaining("merge timestamp");
        when(pullRequests.read(REPO.fullName(), NUMBER + 1, SelectedPullRequestReader.Section.TIMELINE))
                .thenReturn(new SelectedPullRequestReader.Read(detail(NOW, UPDATED, "Wrong number", ImmutableList.of()), Optional.of(UPDATED), Optional.of(NOW),
                        Optional.of(SHA), false, false, false, false));
        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(WORKSPACE, run(true, 9), PR, NUMBER + 1)).withMessageContaining("identity");
        verifyNoInteractions(diffs);
    }

    @Test
    void preservesCachedMetadataFailureAndUnavailablePatchAsLimitations()
    {
        selected(detail(NOW, UPDATED, "Cached rationale", ImmutableList.of()), true);
        when(diffs.read(REPO.fullName(), NUMBER, Optional.empty(), false)).thenThrow(new IllegalStateException("Provider unavailable"));

        ContextHistoryReader.Read read = reader.read(WORKSPACE, run(true, 9), PR, NUMBER);

        assertThat(read.refreshFailed()).isTrue();
        assertThat(read.excerpt()).contains("Cached rationale", "refreshFailed=true", "Original cached data", "default diff could not be read");
        assertThat(read.provenance()).contains("cacheUpdatedAt=" + NOW);
        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains("unavailable or incomplete"));
        assertHash(read);
    }

    @Test
    void closedIssueUsesExactPreviewAndFreshLocalActivityWithoutWholeIssueRefresh()
    {
        preview("closed", false, UPDATED);
        RepositoryIssueDetail detail = issueDetail(NOW, "Closed as duplicate; no fix established");
        when(issues.detail("workspace", NUMBER)).thenReturn(detail);

        ContextHistoryReader.Read read = reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER);

        assertThat(read.reference()).isEqualTo(new ContextReference.Issue(9, REPO, NUMBER, Optional.empty()));
        assertThat(read.state()).isEqualTo(ContextHistoryRead.State.CLOSED);
        assertThat(read.sourceRevision()).isEmpty();
        assertThat(read.excerpt()).contains("Closed does not mean fixed", "Closed as duplicate", "activityCompleteness=unknown");
        assertThat(read.truncated()).isFalse();
        assertThat(read.refreshFailed()).isFalse();
        assertHash(read);
        verify(repositories).getIssuePreview("owner", "repo", NUMBER);
        verify(issues).detail("workspace", NUMBER);
        verifyNoMoreInteractions(repositories, issues);
        verifyNoInteractions(pullRequests, diffs, issueRefresh);
    }

    @Test
    void missingClosedIssueActivityUsesExistingSelectedRefresh()
    {
        preview("closed", false, UPDATED);
        when(issues.detail("workspace", NUMBER)).thenReturn(RepositoryIssueDetail.empty(9, NUMBER));
        when(issueRefresh.refreshDetail("workspace", NUMBER)).thenReturn(issueDetail(NOW, "Closure activity"));

        ContextHistoryReader.Read read = reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER);

        assertThat(read.excerpt()).contains("Closure activity");
        assertThat(read.provenance()).contains("activityRefreshAttempted=true", "activityRefreshedAt=" + NOW);
        assertThat(read.refreshFailed()).isFalse();
        verify(issueRefresh).refreshDetail("workspace", NUMBER);
        verifyNoMoreInteractions(issueRefresh);
    }

    @Test
    void failedClosedIssueActivityRefreshRetainsOriginalCacheAndTimestamp()
    {
        preview("closed", false, UPDATED);
        Instant cachedAt = UPDATED.minusSeconds(60);
        when(issues.detail("workspace", NUMBER)).thenReturn(issueDetail(cachedAt, "Original cached discussion"));
        when(issueRefresh.refreshDetail("workspace", NUMBER)).thenThrow(new IllegalStateException("Unavailable"));

        ContextHistoryReader.Read read = reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER);

        assertThat(read.refreshFailed()).isTrue();
        assertThat(read.excerpt()).contains("Original cached discussion", "original cached activity and timestamps", "may omit the closure");
        assertThat(read.provenance()).contains("activityRefreshedAt=" + cachedAt);
        assertHash(read);
    }

    @Test
    void rejectsOpenIssuesPullRequestsAndFailedTerminalProofWithoutReadingActivity()
    {
        preview("open", false, UPDATED);
        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER)).withMessageContaining("closed issue");
        preview("closed", true, UPDATED);
        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER)).withMessageContaining("closed issue");
        when(repositories.getIssuePreview("owner", "repo", NUMBER)).thenThrow(new IllegalStateException("Preview unavailable"));
        assertThatThrownBy(() -> reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER)).isInstanceOf(IllegalStateException.class).hasMessage("Preview unavailable");
        verifyNoInteractions(issues, issueRefresh, pullRequests, diffs);
    }

    @Test
    void rejectsMismatchedIssueCacheAndRetainsOriginalIfRefreshChangesRepository()
    {
        preview("closed", false, UPDATED);
        when(issues.detail("workspace", NUMBER)).thenReturn(RepositoryIssueDetail.empty(1, NUMBER));
        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER)).withMessageContaining("identity");
        when(issues.detail("workspace", NUMBER)).thenReturn(issueDetail(UPDATED.minusSeconds(1), "Original"));
        when(issueRefresh.refreshDetail("workspace", NUMBER)).thenReturn(RepositoryIssueDetail.empty(1, NUMBER));
        ContextHistoryReader.Read read = reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER);
        assertThat(read.refreshFailed()).isTrue();
        assertThat(read.excerpt()).contains("Original");
    }

    @Test
    void boundsUnicodeExcerptsAndRetainsLimitationsAndObservedIdentity()
    {
        List<PullRequestDetail.ActivityItem> activity = IntStream.range(0, 30).mapToObj(_ -> activity("😀".repeat(1000))).collect(toImmutableList());
        selected(detail(NOW, UPDATED, "😀".repeat(2000), activity), false);
        when(diffs.read(REPO.fullName(), NUMBER, Optional.empty(), false)).thenReturn(diff(IntStream.range(0, 4)
                .mapToObj(index -> new DiffFile("file-" + index, "modified", 1, 0, "😀".repeat(500))).collect(toImmutableList())));

        ContextHistoryReader.Read read = reader.read(WORKSPACE, run(true, 9), PR, NUMBER);

        assertThat(read.truncated()).isTrue();
        assertThat(ContextLimits.bytes(read.excerpt())).isBetween(1, 16 * 1024);
        assertThat(read.excerpt()).contains("Observed state=MERGED", "excerptTruncated=true", "omits source text or items").doesNotContain("\uFFFD");
        assertThat(read.gaps()).hasSizeLessThanOrEqualTo(10).allSatisfy(gap -> assertThat(ContextLimits.bytes(gap)).isLessThanOrEqualTo(1000));
        assertHash(read);
    }

    @Test
    void longPrHistoryRetainsLatestMergeExplanationAheadOfOlderAndUndatedActivity()
    {
        List<PullRequestDetail.ActivityItem> activity = ImmutableList.<PullRequestDetail.ActivityItem>builder()
                .addAll(IntStream.range(0, 21).mapToObj(index -> new PullRequestDetail.ActivityItem("alice", "commented", NOW.minusSeconds(30 - index),
                        "Earlier PR discussion " + index, (long) index, PrTimelinePayload.empty())).collect(toImmutableList()))
                .add(new PullRequestDetail.ActivityItem("alice", "merged", NOW, "Final merge explanation", 100L, PrTimelinePayload.empty()))
                .add(new PullRequestDetail.ActivityItem("alice", "commented", null, "Undated PR discussion", 999L, PrTimelinePayload.empty()))
                .build();
        selected(detail(NOW, UPDATED, "Rationale", activity), false);
        when(diffs.read(REPO.fullName(), NUMBER, Optional.empty(), false)).thenReturn(diff(ImmutableList.of()));

        ContextHistoryReader.Read read = reader.read(WORKSPACE, run(true, 9), PR, NUMBER);

        assertThat(read.excerpt()).contains("Final merge explanation", "Earlier PR discussion 2\n", "Earlier PR discussion 20\n")
                .doesNotContain("Earlier PR discussion 0\n", "Earlier PR discussion 1\n", "Undated PR discussion");
        assertThat(read.excerpt().indexOf("Final merge explanation")).isLessThan(read.excerpt().indexOf("Earlier PR discussion 20\n"));
        assertThat(read.gaps()).contains("Only the latest 20 of 23 observed activity items were selected, with unknown timestamps last; 3 other observed items were omitted.");
        assertThat(read.truncated()).isTrue();
        assertHash(read);
    }

    @Test
    void longIssueHistoryRetainsLatestClosureAndOrdersEqualTimestampsById()
    {
        preview("closed", false, UPDATED);
        List<PrTimelineEvent> timeline = ImmutableList.<PrTimelineEvent>builder()
                .addAll(IntStream.range(0, 21).mapToObj(index -> new PrTimelineEvent((long) index, "commented", "alice", NOW.minusSeconds(30 - index),
                        "Earlier issue discussion " + index, Optional.empty())).collect(toImmutableList()))
                .add(new PrTimelineEvent(null, "commented", "alice", NOW, "Same-time event without ID", Optional.empty()))
                .add(new PrTimelineEvent(100L, "closed", "alice", NOW, "Closure explanation", Optional.empty()))
                .add(new PrTimelineEvent(101L, "commented", "alice", NOW, "Closure follow-up", Optional.empty()))
                .add(new PrTimelineEvent(999L, "commented", "alice", null, "Undated issue discussion", Optional.empty()))
                .build();
        when(issues.detail("workspace", NUMBER)).thenReturn(new RepositoryIssueDetail(9, NUMBER, timeline, null, ImmutableList.of(), Optional.of(NOW)));

        ContextHistoryReader.Read read = reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER);

        assertThat(read.excerpt()).contains("Closure explanation", "Closure follow-up", "Same-time event without ID", "Earlier issue discussion 4\n")
                .doesNotContain("Earlier issue discussion 0\n", "Earlier issue discussion 3\n", "Undated issue discussion");
        assertThat(read.excerpt().indexOf("Closure follow-up")).isLessThan(read.excerpt().indexOf("Closure explanation"));
        assertThat(read.excerpt().indexOf("Closure explanation")).isLessThan(read.excerpt().indexOf("Same-time event without ID"));
        assertThat(read.gaps()).contains("Only the latest 20 of 25 observed activity items were selected, with unknown timestamps last; 5 other observed items were omitted.");
        assertThat(read.truncated()).isTrue();
        assertHash(read);
    }

    @Test
    void contentHashChangesWithObservedUpdatesButNotReadTime()
    {
        preview("closed", false, UPDATED);
        when(issues.detail("workspace", NUMBER)).thenReturn(issueDetail(NOW, "Same discussion"));
        ContextHistoryReader.Read first = reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER);
        ContextHistoryReader later = new ContextHistoryReader(pullRequests, diffs, repositories, issues, issueRefresh, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        assertThat(later.read(WORKSPACE, run(true, 9), ISSUE, NUMBER).contentHash()).isEqualTo(first.contentHash());
        preview("closed", false, UPDATED.plusSeconds(1));
        assertThat(reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER).contentHash()).isNotEqualTo(first.contentHash());
    }

    @Test
    void successfulInitialRefreshAndEquivalentCachedReadHaveTheSameContentIdentity()
    {
        preview("closed", false, UPDATED);
        RepositoryIssueDetail detail = issueDetail(NOW, "Same closed discussion");
        when(issues.detail("workspace", NUMBER)).thenReturn(RepositoryIssueDetail.empty(9, NUMBER), detail);
        when(issueRefresh.refreshDetail("workspace", NUMBER)).thenReturn(detail);
        ContextHistoryReader.Read refreshedIssue = reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER);
        ContextHistoryReader.Read cachedIssue = reader.read(WORKSPACE, run(true, 9), ISSUE, NUMBER);
        assertThat(refreshedIssue.provenance()).contains("activityRefreshAttempted=true");
        assertThat(cachedIssue.provenance()).contains("activityRefreshAttempted=false");
        assertThat(cachedIssue.contentHash()).isEqualTo(refreshedIssue.contentHash());

        PullRequestDetail pr = detail(NOW, UPDATED, "Same merged rationale", ImmutableList.of());
        when(pullRequests.read(REPO.fullName(), NUMBER, SelectedPullRequestReader.Section.TIMELINE)).thenReturn(
                new SelectedPullRequestReader.Read(pr, Optional.of(UPDATED), Optional.of(NOW), Optional.of(SHA), true, false, false, false),
                new SelectedPullRequestReader.Read(pr, Optional.of(UPDATED), Optional.of(NOW.plusSeconds(1)), Optional.of(SHA), false, false, false, false));
        when(diffs.read(REPO.fullName(), NUMBER, Optional.empty(), false)).thenReturn(diff(ImmutableList.of()));
        ContextHistoryReader.Read refreshedPr = reader.read(WORKSPACE, run(true, 9), PR, NUMBER);
        ContextHistoryReader.Read cachedPr = reader.read(WORKSPACE, run(true, 9), PR, NUMBER);
        assertThat(refreshedPr.provenance()).contains("refreshAttempted=true");
        assertThat(cachedPr.provenance()).contains("refreshAttempted=false");
        assertThat(cachedPr.contentHash()).isEqualTo(refreshedPr.contentHash());
    }

    @Test
    void rejectsOffAbsentDifferentRepositoryAndInvalidNumberBeforeSourceReads()
    {
        for (ReviewRun run : ImmutableList.of(runWithoutContext(), run(false, 9), run(true, 1))) {
            assertThatIllegalArgumentException().isThrownBy(() -> reader.read(WORKSPACE, run, PR, NUMBER));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(WORKSPACE, run(true, 9), ISSUE, 0));
        verifyNoInteractions(pullRequests, diffs, repositories, issues, issueRefresh);
    }

    private void selected(PullRequestDetail detail, boolean failed)
    {
        when(pullRequests.read(REPO.fullName(), NUMBER, SelectedPullRequestReader.Section.TIMELINE)).thenReturn(
                new SelectedPullRequestReader.Read(detail, Optional.of(UPDATED), Optional.of(NOW), Optional.of(SHA), failed, failed, failed, failed));
    }

    private void preview(String state, boolean pullRequest, Instant updated)
    {
        when(repositories.getIssuePreview("owner", "repo", NUMBER)).thenReturn(
                new GitHubIssuePreview("Issue title", "Issue body", "alice", ImmutableList.of(), updated, state, pullRequest));
    }

    private static SelectedPullRequestDiffReader.Read diff(List<DiffFile> files)
    {
        return new SelectedPullRequestDiffReader.Read(files, "b".repeat(64), Optional.of(SHA), Optional.of(UPDATED), Optional.of(files.size()), NOW, false, false,
                ImmutableList.of("Default artifact does not verify the final merged diff."));
    }

    private static PullRequestDetail.ActivityItem activity(String body)
    {
        return new PullRequestDetail.ActivityItem("alice", "commented", UPDATED, body, 100L, PrTimelinePayload.empty());
    }

    private static RepositoryIssueDetail issueDetail(Instant refreshed, String body)
    {
        return new RepositoryIssueDetail(9, NUMBER, ImmutableList.of(new PrTimelineEvent(100L, "commented", "alice", UPDATED, body, Optional.empty())),
                null, ImmutableList.of(), Optional.of(refreshed));
    }

    private static PullRequestDetail detail(Instant mergedAt, Instant updatedAt, String body, List<PullRequestDetail.ActivityItem> activity)
    {
        return new PullRequestDetail(12, REPO.fullName(), NUMBER, "Historical PR", "https://github.com/owner/repo/pull/7", "closed", false, null, "unknown", "alice", "",
                NOW.minusSeconds(600), updatedAt, mergedAt, "main", "topic", body, ImmutableList.of(), ImmutableList.of(), null, ImmutableList.of(),
                1, 1, 1, 0, PullRequestDetail.CiStatus.NONE, activity, ImmutableList.of(), ImmutableList.of(), false);
    }

    private static void assertHash(ContextHistoryReader.Read read)
    {
        assertThat(read.contentHash()).isEqualTo(Hashing.sha256().hashString(read.excerpt(), UTF_8).toString());
        ContextHistoryRead receipt = new ContextHistoryRead("context-read:test", read.reference(), read.state(), read.sourceUpdatedAt(), read.sourceRevision(),
                read.contentHash(), read.readAt(), ContextLimits.bytes(read.excerpt()), read.truncated(), read.refreshFailed(), read.gaps(), Optional.of("E1"));
        receipt.checkExcerpt(read.excerpt());
    }

    private static ReviewRun run(boolean enabled, long repositoryId)
    {
        ReviewContextSnapshot snapshot = new ReviewContextSnapshot("context:run", enabled, 0, repositoryId, Optional.of(SHA),
                enabled ? Optional.of(ContextPacket.create("Prepared context")) : Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        return runWithoutContext().withContext(snapshot, ImmutableMap.of(), NOW);
    }

    private static ReviewRun runWithoutContext()
    {
        return new ReviewRun("run", "review", 1, ReviewRun.Scope.FULL, "main", SHA, SHA, "c".repeat(40), Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(),
                ImmutableMap.of(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }
}
