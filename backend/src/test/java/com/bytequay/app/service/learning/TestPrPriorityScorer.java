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

import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.Reactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance criterion (1): a behavioural review whose concern the author
 * actually fixed outranks both a mechanical PR and a comment-only debate. The
 * scorer ranks outcome/change linkage, never raw comment count — so a
 * 40-comment naming debate that changed nothing loses to a small fix.
 */
class TestPrPriorityScorer
{
    private static final Instant RAISED = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant BEFORE = Instant.parse("2019-12-01T00:00:00Z");
    private static final Instant AFTER = Instant.parse("2020-01-02T00:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private final PrPriorityScorer scorer = new PrPriorityScorer(json);
    private final OutcomeChainReconstructor reconstructor = new OutcomeChainReconstructor();

    @Test
    void testAuthorFixedReviewOutranksMechanicalAndDebate()
    {
        double fix = score(behaviouralFix());
        double mechanical = score(mechanicalBump());
        double debate = score(commentOnlyDebate());

        assertThat(fix).isGreaterThan(debate);
        assertThat(fix).isGreaterThan(mechanical);
        // The debate's 40 comments never lift it over the real, linked fix,
        // and the mechanical PR sits at the bottom.
        assertThat(debate).isGreaterThan(mechanical);
    }

    private double score(Fixture f)
    {
        List<OutcomeChain> chains = reconstructor.reconstruct(f.bundle());
        return scorer.refine(f.source(), f.bundle(), chains);
    }

    // ── fixtures ────────────────────────────────────────────────────

    /** A reviewer requested a change on Scheduler.java; the author pushed a
     *  follow-up commit, the thread resolved, and the PR merged. */
    private Fixture behaviouralFix()
    {
        RepoPrSource source = source(1, meta("alice", "Fix scheduler race", 30, 10, 3, "[]"));
        PrEvidenceBundle bundle = bundle(1, "alice", "m1",
                List.of(new PrReviewState("bob", "CHANGES_REQUESTED", RAISED)),
                List.of(file("core/Scheduler.java"), file("core/SchedulerTest.java")),
                List.of(commit("c1", "alice", AFTER)),
                List.of(root(101, "bob", "core/Scheduler.java", RAISED, true, "c1")));
        return new Fixture(source, bundle);
    }

    /** A dependabot bump: no review, no linkage, mechanical label. */
    private Fixture mechanicalBump()
    {
        RepoPrSource source = source(2, meta("dependabot[bot]", "Bump lodash from 1 to 2",
                4, 4, 0, "[\"dependencies\"]"));
        PrEvidenceBundle bundle = bundle(2, "dependabot[bot]", "m2",
                List.of(), List.of(file("package.json")),
                List.of(commit("c2", "dependabot[bot]", AFTER)), List.of());
        return new Fixture(source, bundle);
    }

    /** 40 comments of naming debate; the author never changed the code. */
    private Fixture commentOnlyDebate()
    {
        RepoPrSource source = source(3, meta("carol", "Rename widget to gadget", 5, 5, 40, "[]"));
        PrEvidenceBundle bundle = bundle(3, "carol", "m3",
                List.of(new PrReviewState("dave", "CHANGES_REQUESTED", RAISED)),
                List.of(file("core/Widget.java")),
                List.of(commit("c0", "carol", BEFORE)),   // authored BEFORE the concern
                List.of(root(201, "dave", "core/Widget.java", RAISED, false, "c0"),
                        root(202, "erin", "core/Widget.java", RAISED, false, "c0")));
        return new Fixture(source, bundle);
    }

    private record Fixture(RepoPrSource source, PrEvidenceBundle bundle) {}

    private String meta(String author, String title, int additions, int deletions,
            int commentCount, String labelsJson)
    {
        return ("{\"author\":\"%s\",\"title\":\"%s\",\"additions\":%d,\"deletions\":%d,"
                + "\"commentCount\":%d,\"labels\":%s,\"headRef\":\"feature/x\"}")
                .formatted(author, title, additions, deletions, commentCount, labelsJson);
    }

    private RepoPrSource source(int prNumber, String metadataJson)
    {
        return new RepoPrSource("ws-1", "acme/widget", prNumber, "2020-01-05T00:00:00Z",
                null, metadataJson, "{\"catalog\":\"complete\"}", "digest", null, "selected", 1, null, null);
    }

    private PrEvidenceBundle bundle(
            int prNumber, String author, String mergeSha,
            List<PrReviewState> reviews,
            List<PullRequestDetail.ChangedFile> files,
            List<PullRequestCommit> commits,
            List<PrReviewThreadMessage> comments)
    {
        return new PrEvidenceBundle("ws-1", "acme/widget", prNumber, author,
                "Title", "Body", "base", "head", mergeSha, "repoSha",
                reviews, files, commits, comments, List.of(),
                Map.of("reviews", "complete"), "complete", List.of(), List.of());
    }

    private static PullRequestDetail.ChangedFile file(String path)
    {
        return new PullRequestDetail.ChangedFile(path, 10, 2, "modified");
    }

    private static PullRequestCommit commit(String sha, String author, Instant at)
    {
        return new PullRequestCommit(sha, author, author, at, "message " + sha);
    }

    private static PrReviewThreadMessage root(
            long id, String author, String path, Instant at, boolean resolved, String commitId)
    {
        return new PrReviewThreadMessage(id, null, null, author, "please change this",
                path, 10, "RIGHT", null, commitId, at, Reactions.EMPTY, false,
                null, null, null, null, null, null, resolved, null);
    }
}
