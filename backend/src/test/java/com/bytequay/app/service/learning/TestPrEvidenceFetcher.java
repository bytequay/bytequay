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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.codegraph.CodeGraphUpdateCoordinator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance criteria (2)-(4) for the snapshot-pinned evidence assembler,
 * driven by a fake GitHub client:
 *
 * <ul>
 *   <li>(2) missing later pages yield {@code partial:<source>} and a lower
 *       completeness rather than claiming success;</li>
 *   <li>(3) CodeGraph unavailable still produces a path-based bundle;</li>
 *   <li>(4) no bundle reference crosses the pinned repository snapshot.</li>
 * </ul>
 */
class TestPrEvidenceFetcher
{
    private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

    /** CodeGraph disabled throughout — exercises the path-fallback branch. */
    private PrEvidenceFetcher fetcher(PullRequestRepository gitHub)
    {
        return new PrEvidenceFetcher(gitHub, new OutcomeChainReconstructor(),
                new EvidenceCodeGraphMapper(CodeGraphUpdateCoordinator.disabled()));
    }

    @Test
    void testMissingLaterPagesMarkSourcePartial()
    {
        // Reviews came back short a page; every other source is complete.
        FakeGitHub github = new FakeGitHub()
                .withReviews(PullRequestRepository.Paged.partial(
                        List.of(new PrReviewState("bob", "APPROVED", T0))));

        PrEvidenceBundle bundle = fetcher(github)
                .fetch("pat", "ws-1", "acme/widget", 7, "alice", "Title", null, "repoSha");

        assertThat(bundle.completeness().get("reviews")).isEqualTo("partial:reviews");
        assertThat(bundle.completeness().get("files")).isEqualTo("complete");
        // The roll-up names the first incomplete source — never "complete".
        assertThat(bundle.overallCompleteness()).isEqualTo("partial:reviews");
    }

    @Test
    void testCodeGraphUnavailableStillYieldsPathBundle()
    {
        FakeGitHub github = new FakeGitHub()
                .withFiles(PullRequestRepository.Paged.complete(
                        List.of(file("core/Scheduler.java"), file("core/SchedulerTest.java"))));

        PrEvidenceBundle bundle = fetcher(github)
                .fetch("pat", "ws-1", "acme/widget", 7, "alice", "Title", null, "repoSha");

        // Every changed file still produced a path ref pinned to repoSha, even
        // with no CodeGraph to enrich symbols.
        List<PrEvidenceBundle.EvidenceRef> pathRefs = bundle.refs().stream()
                .filter(r -> "file".equals(r.kind()) || "test".equals(r.kind()))
                .toList();
        assertThat(pathRefs).extracting(PrEvidenceBundle.EvidenceRef::filePath)
                .contains("core/Scheduler.java", "core/SchedulerTest.java");
        assertThat(pathRefs).allMatch(r -> "repoSha".equals(r.commitSha()));
        assertThat(bundle.refs()).noneMatch(r -> "symbol".equals(r.kind()));
    }

    @Test
    void testNoReferenceCrossesThePinnedSnapshot()
    {
        // A thread anchored to an in-PR commit (c1) keeps its SHA; a thread
        // anchored to a SHA outside the pinned set is dropped to null rather
        // than allowed to cross the snapshot.
        FakeGitHub github = new FakeGitHub()
                .withCommits(PullRequestRepository.Paged.complete(List.of(
                        commit("c1"), commit("c2"))))
                .withComments(PullRequestRepository.Paged.complete(List.of(
                        root(301, "bob", "core/Scheduler.java", "c1"),
                        root(302, "bob", "core/Other.java", "sha-from-a-later-push"))));

        PrEvidenceBundle bundle = fetcher(github)
                .fetch("pat", "ws-1", "acme/widget", 7, "alice", "Title", null, "repoSha");

        assertThat(bundle.refs())
                .allMatch(r -> r.commitSha() == null || bundle.pinnedShas().contains(r.commitSha()));
        // The crossing thread survived as a ref but with its SHA nulled out.
        PrEvidenceBundle.EvidenceRef crossing = bundle.refs().stream()
                .filter(r -> "thread".equals(r.kind()) && "302".equals(r.githubId()))
                .findFirst().orElseThrow();
        assertThat(crossing.commitSha()).isNull();
    }

    @Test
    void testTimelineEventsWithIdsBecomeRefs()
    {
        FakeGitHub github = new FakeGitHub()
                .withTimeline(PullRequestRepository.Paged.complete(List.of(
                        timeline(9001L, "merged"),
                        timeline(null, "labeled"))));

        PrEvidenceBundle bundle = fetcher(github)
                .fetch("pat", "ws-1", "acme/widget", 7, "alice", "Title", null, "repoSha");

        // The fetched timeline now yields queryable refs — but only for events
        // carrying a stable GitHub id; the id-less one is skipped.
        assertThat(bundle.refs().stream().filter(r -> "timeline".equals(r.kind())).toList())
                .extracting(PrEvidenceBundle.EvidenceRef::githubId)
                .containsExactly("9001");
    }

    @Test
    void testResolvedThreadDoesNotInventCommitLinkage()
    {
        // The explicit GraphQL resolution is retained, but a later author
        // commit cannot prove it changed the concern's path.
        FakeGitHub github = new FakeGitHub()
                .withCommits(PullRequestRepository.Paged.complete(List.of(
                        commitAt("c1", Instant.parse("2020-01-02T00:00:00Z")))))
                .withComments(PullRequestRepository.Paged.complete(List.of(
                        root(301, "bob", "core/Scheduler.java", "c1"))))
                .withResolvedRoot(301L);

        PrEvidenceBundle bundle = fetcher(github)
                .fetch("pat", "ws-1", "acme/widget", 7, "alice", "Title", null, "repoSha");

        OutcomeChain chain = bundle.chains().stream()
                .filter(c -> "comment:301".equals(c.concernRef()))
                .findFirst().orElseThrow();
        assertThat(chain.resolved()).isTrue();
        assertThat(chain.addressed()).isFalse();
        assertThat(chain.depth()).isEqualTo(1);   // explicit resolution only
    }

    // ── fake ────────────────────────────────────────────────────────

    private static PullRequestDetail.ChangedFile file(String path)
    {
        return new PullRequestDetail.ChangedFile(path, 5, 1, "modified");
    }

    private static PullRequestCommit commit(String sha)
    {
        return new PullRequestCommit(sha, "alice", "alice", T0, "message " + sha);
    }

    private static PullRequestCommit commitAt(String sha, Instant authoredAt)
    {
        return new PullRequestCommit(sha, "alice", "alice", authoredAt, "message " + sha);
    }

    private static PrTimelineEvent timeline(Long githubId, String event)
    {
        return new PrTimelineEvent(githubId, event, "bob", null, T0, null, null, null,
                null, null, null, Reactions.EMPTY);
    }

    private static PrReviewThreadMessage root(long id, String author, String path, String commitId)
    {
        return new PrReviewThreadMessage(id, null, null, author, "body", path, 10, "RIGHT",
                null, commitId, T0, Reactions.EMPTY, false, null, null, null, null, null,
                null, false, null);
    }

    /** Fake GitHub returning per-source paged evidence; complete by default. */
    private static final class FakeGitHub
            implements PullRequestRepository
    {
        private PullRequestRepository.Paged<PrReviewState> reviews = Paged.complete(List.of());
        private PullRequestRepository.Paged<PullRequestDetail.ChangedFile> files = Paged.complete(List.of());
        private PullRequestRepository.Paged<PullRequestCommit> commits = Paged.complete(List.of());
        private PullRequestRepository.Paged<PrReviewThreadMessage> comments = Paged.complete(List.of());
        private PullRequestRepository.Paged<PrTimelineEvent> timeline = Paged.complete(List.of());
        private final List<ReviewThreadMeta> resolutions = new ArrayList<>();

        FakeGitHub withReviews(Paged<PrReviewState> p)
        {
            this.reviews = p;
            return this;
        }

        FakeGitHub withFiles(Paged<PullRequestDetail.ChangedFile> p)
        {
            this.files = p;
            return this;
        }

        FakeGitHub withCommits(Paged<PullRequestCommit> p)
        {
            this.commits = p;
            return this;
        }

        FakeGitHub withComments(Paged<PrReviewThreadMessage> p)
        {
            this.comments = p;
            return this;
        }

        FakeGitHub withTimeline(Paged<PrTimelineEvent> p)
        {
            this.timeline = p;
            return this;
        }

        FakeGitHub withResolvedRoot(long rootCommentDatabaseId)
        {
            this.resolutions.add(new ReviewThreadMeta(rootCommentDatabaseId, "node", true, "bob"));
            return this;
        }

        @Override
        public PrRawDetail fetchPrDetail(String pat, PullRequestRef pr)
        {
            return new PrRawDetail(null, List.of(), false, null, null, 0, 0, 0, 0, List.of(),
                    "head", "feature/x", "acme/widget", "main", "acme/widget", "closed", true,
                    "base", "merge");
        }

        @Override
        public Paged<PrReviewState> fetchAllPrReviews(String pat, PullRequestRef pr)
        {
            return reviews;
        }

        @Override
        public Paged<PullRequestDetail.ChangedFile> fetchAllPrFiles(String pat, PullRequestRef pr)
        {
            return files;
        }

        @Override
        public Paged<PullRequestCommit> fetchAllPrCommits(String pat, PullRequestRef pr)
        {
            return commits;
        }

        @Override
        public Paged<PrReviewThreadMessage> fetchAllPrReviewComments(String pat, PullRequestRef pr)
        {
            return comments;
        }

        @Override
        public Paged<PrTimelineEvent> fetchAllPrTimeline(String pat, PullRequestRef pr)
        {
            return timeline;
        }

        @Override
        public List<ReviewThreadMeta> fetchReviewThreadResolution(String pat, PullRequestRef pr)
        {
            return resolutions;
        }
    }
}
