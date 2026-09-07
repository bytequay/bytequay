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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewOperation;
import com.bytequay.core.review.ReviewPublication;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteReviewStores
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-review-store-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String BASE_SHA = "0000000000000000000000000000000000000000";
    private static final String MERGE_BASE_SHA = "1111111111111111111111111111111111111111";
    private static final String PRIOR_HEAD_SHA = "2222222222222222222222222222222222222222";
    private static final String HEAD_SHA = "3333333333333333333333333333333333333333";
    private static final String NEXT_HEAD_SHA = "4444444444444444444444444444444444444444";
    private static final String REVIEW_TOOLS = "backend/app/src/main/java/com/bytequay/app/review/ReviewTools.java";
    private static final ReviewEvidence CONTEXT_EVIDENCE = new ReviewEvidence(
            "ev-1",
            REVIEW_TOOLS,
            "context_read",
            "int published = 0;\nfor (ReviewFinding finding : findings) {",
            "The counter the finding names is declared here and never incremented");
    private static final ReviewEvidence COMMAND_EVIDENCE = new ReviewEvidence(
            "ev-2",
            "mvn -pl app test",
            "command",
            "Tests run: 41, Failures: 0",
            "The suite passes without covering the branch the finding names");
    private static final ReviewSummary SUMMARY = new ReviewSummary(
            2,
            "Explain the new review tool surface",
            ImmutableList.of(new ReviewSummary.CommitExplanation(HEAD_SHA, "Adds the review tool surface and its prompts")),
            "Two findings, one of them critical",
            ImmutableList.of("finding-1"),
            ImmutableList.of("did not read the generated bindings"));
    private static final ReviewCoverage COVERAGE = new ReviewCoverage(
            false,
            ImmutableList.of("generated sources", "vendored fixtures"),
            ImmutableList.of("no network access"),
            NOW.plusSeconds(3));

    @Autowired
    private SqliteAgentReviewStore store;

    @Autowired
    private SqliteReviewDraftStore draftStore;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void roundTripsEveryReviewRecordThroughSqlite()
    {
        AgentReview review = newReview("review-1", "workspace-a", "bytequay/sample", 7, "task-1", NOW);
        ReviewRun run = newRun("run-1", "review-1")
                .withTurn("turn-2", NOW.plusSeconds(1))
                .withSummary(SUMMARY, NOW.plusSeconds(2))
                .withCoverage(COVERAGE, NOW.plusSeconds(3))
                .withOperation("review_finding:finding-1", new ReviewOperation("sha256:9f1c", "created"), NOW.plusSeconds(4))
                .withEvidence("ev-2", COMMAND_EVIDENCE, NOW.plusSeconds(5))
                .withCompletionRepairUsed(NOW.plusSeconds(6))
                .ended(ReviewRun.State.ACCEPTED, Optional.of("accepted"), NOW.plusSeconds(7));
        ReviewFinding finding = newFinding("finding-1", "review-1", "run-1");
        ReviewMessage question = newMessage("message-1", Optional.of("finding-1"), ReviewMessage.Author.USER, "Why is this critical?", NOW.plusSeconds(8));
        ReviewMessage answer = newMessage("message-2", Optional.empty(), ReviewMessage.Author.AGENT, "The published count never reaches the caller.", NOW.plusSeconds(9));

        store.insertReview(review);
        store.insertRun(run);
        store.insertFinding(finding);
        store.insertMessage(question);
        store.insertMessage(answer);
        entityManager.clear();

        assertThat(store.findReview("review-1")).contains(review);
        assertThat(store.findRun("run-1")).contains(run);
        assertThat(store.listRuns("review-1")).containsExactly(run);
        assertThat(store.findFinding("finding-1")).contains(finding);
        assertThat(store.listFindings("review-1")).containsExactly(finding);
        assertThat(store.listMessages("review-1")).containsExactly(question, answer);
    }

    @Test
    void roundTripsDraftWithManualAndPromotedItems()
    {
        ReviewPublication publication = new ReviewPublication(
                "attempt-1",
                ReviewPublication.State.PUBLISHED,
                OptionalLong.of(9001),
                OptionalLong.of(9002),
                Optional.of("https://github.com/bytequay/sample/pull/7#discussion_r9002"),
                NOW.plusSeconds(10));
        ReviewDraftItem manual = new ReviewDraftItem(
                "item-1",
                ReviewDraftItem.Source.MANUAL,
                Optional.empty(),
                OptionalInt.empty(),
                REVIEW_TOOLS,
                "RIGHT",
                42,
                OptionalInt.empty(),
                "Name this after what it returns.",
                true,
                Optional.empty());
        ReviewDraftItem promoted = new ReviewDraftItem(
                "item-2",
                ReviewDraftItem.Source.AGENT_FINDING,
                Optional.of("finding-1"),
                OptionalInt.of(1),
                REVIEW_TOOLS,
                "RIGHT",
                121,
                OptionalInt.of(118),
                "The published counter is never incremented.",
                false,
                Optional.of(publication));
        ReviewDraft draft = ReviewDraft.empty("ByteQuay/Sample", 7, NOW)
                .withReview("Close, but the counter is wrong.", "REQUEST_CHANGES", NOW.plusSeconds(1))
                .withItems(ImmutableList.of(manual, promoted), NOW.plusSeconds(2));

        ReviewDraft saved = draftStore.save(draft);
        entityManager.clear();

        assertThat(saved).isEqualTo(draft);
        assertThat(draftStore.find("bytequay/sample", 7)).contains(draft);
        ReviewDraft stored = draftStore.find("bytequay/sample", 7).orElseThrow();
        assertThat(stored.items()).containsExactly(manual, promoted);
        assertThat(stored.items().get(0).published()).isFalse();
        assertThat(stored.items().get(1).published()).isTrue();
    }

    @Test
    void enforcesOptimisticVersionsOnReviewsRunsAndFindings()
    {
        AgentReview review = store.insertReview(newReview("review-1", "workspace-a", "bytequay/sample", 7, "task-1", NOW));
        ReviewRun run = store.insertRun(newRun("run-1", "review-1"));
        ReviewFinding finding = store.insertFinding(newFinding("finding-1", "review-1", "run-1"));

        AgentReview updatedReview = store.updateReview(review.withObservedHead(NEXT_HEAD_SHA, NOW.plusSeconds(1)));
        ReviewRun updatedRun = store.updateRun(run.withTurn("turn-2", NOW.plusSeconds(1)));
        ReviewFinding updatedFinding = store.updateFinding(finding.withState(ReviewFinding.State.RESOLVED, Optional.of("fixed upstream"), NOW.plusSeconds(1)));

        assertThat(updatedReview.version()).isEqualTo(1);
        assertThat(updatedRun.version()).isEqualTo(1);
        assertThat(updatedFinding.version()).isEqualTo(1);
        assertThat(store.findReview("review-1")).contains(updatedReview);
        assertThat(store.findRun("run-1")).contains(updatedRun);
        assertThat(store.findFinding("finding-1")).contains(updatedFinding);
        assertThatThrownBy(() -> store.updateReview(review.withObservedHead(HEAD_SHA, NOW.plusSeconds(2))))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("version changed");
        assertThatThrownBy(() -> store.updateRun(run.withTurn("turn-3", NOW.plusSeconds(2))))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("version changed");
        assertThatThrownBy(() -> store.updateFinding(finding.withState(ReviewFinding.State.WITHDRAWN, Optional.of("stale"), NOW.plusSeconds(2))))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("version changed");
    }

    @Test
    void rejectsDuplicateReviewIdentifierInsteadOfMerging()
    {
        AgentReview review = newReview("review-1", "workspace-a", "bytequay/sample", 7, "task-1", NOW);
        store.insertReview(review);

        assertThatThrownBy(() -> store.insertReview(review))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void findsOnlyOpenReviewsAndIgnoresRepositoryCase()
    {
        AgentReview open = store.insertReview(newReview("review-1", "workspace-a", "bytequay/sample", 7, "task-1", NOW));
        AgentReview other = store.insertReview(newReview("review-2", "workspace-a", "bytequay/other", 9, "task-2", NOW.plusSeconds(1)));
        AgentReview inserted = store.insertReview(newReview("review-3", "workspace-a", "bytequay/sample", 11, "task-3", NOW.plusSeconds(2)));
        AgentReview closed = store.updateReview(inserted.closed(NOW.plusSeconds(3)));
        entityManager.clear();

        assertThat(store.findOpenReview("workspace-a", "ByteQuay/Sample", 7)).contains(open);
        assertThat(store.findOpenReview("workspace-a", "BYTEQUAY/SAMPLE", 11)).isEmpty();
        assertThat(store.findOpenReview("workspace-b", "bytequay/sample", 7)).isEmpty();
        assertThat(store.findReviewByTask("task-3")).contains(closed);
        assertThat(store.findReviewByTask("task-missing")).isEmpty();
        assertThat(store.listOpenReviews()).containsExactly(open, other);
    }

    @Test
    void keepsReviewsOfTheSameNumberInDifferentWorkspacesApart()
    {
        AgentReview mine = store.insertReview(newReview("review-1", "workspace-a", "bytequay/sample", 7, "task-1", NOW));
        AgentReview theirs = store.insertReview(newReview("review-2", "workspace-b", "bytequay/sample", 7, "task-2", NOW.plusSeconds(1)));
        AgentReview forked = store.insertReview(newReview("review-3", "workspace-a", "bytequay/fork", 7, "task-3", NOW.plusSeconds(2)));
        entityManager.clear();

        assertThat(store.findOpenReview("workspace-a", "bytequay/sample", 7)).contains(mine);
        assertThat(store.findOpenReview("workspace-b", "bytequay/sample", 7)).contains(theirs);
        assertThat(store.findOpenReview("workspace-a", "bytequay/fork", 7)).contains(forked);
        assertThat(store.listOpenReviews()).containsExactly(mine, theirs, forked);
    }

    @Test
    void savesDraftOnFirstWriteAndRejectsStaleVersions()
    {
        ReviewDraft saved = draftStore.save(ReviewDraft.empty("ByteQuay/Sample", 7, NOW));

        assertThat(saved.version()).isEqualTo(0);
        assertThat(saved.repo()).isEqualTo("bytequay/sample");
        assertThat(draftStore.find("BYTEQUAY/SAMPLE", 7)).contains(saved);
        assertThat(draftStore.find("bytequay/sample", 9)).isEmpty();

        ReviewDraft updated = draftStore.save(saved.withReview("Looks good.", "APPROVE", NOW.plusSeconds(1)));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(draftStore.find("ByteQuay/Sample", 7)).contains(updated);
        assertThatThrownBy(() -> draftStore.save(saved.withReview("Written against the old draft.", "COMMENT", NOW.plusSeconds(2))))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("version changed");
        assertThatThrownBy(() -> draftStore.save(ReviewDraft.empty("bytequay/other", 9, NOW).withVersion(3)))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("version changed");
    }

    @Test
    void acceptRunCommitsTheRunAndTheReviewCursorTogether()
    {
        AgentReview review = store.insertReview(newReview("review-1", "workspace-a", "bytequay/sample", 7, "task-1", NOW));
        ReviewRun run = store.insertRun(newRun("run-1", "review-1"));

        store.acceptRun(
                review.withLatestCompletedRun("run-1", NOW.plusSeconds(2)),
                run.withCoverage(COVERAGE, NOW.plusSeconds(2)).ended(ReviewRun.State.ACCEPTED, Optional.of("accepted"), NOW.plusSeconds(2)));
        entityManager.clear();

        AgentReview storedReview = store.findReview("review-1").orElseThrow();
        ReviewRun storedRun = store.findRun("run-1").orElseThrow();
        assertThat(storedReview.latestCompletedRunId()).contains("run-1");
        assertThat(storedReview.version()).isEqualTo(1);
        assertThat(storedRun.state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(storedRun.coverage()).contains(COVERAGE);
        assertThat(storedRun.endedAt()).contains(NOW.plusSeconds(2));
        assertThat(storedRun.version()).isEqualTo(1);
        assertThat(store.listRuns("review-1")).containsExactly(storedRun);
    }

    private static AgentReview newReview(String id, String workspaceId, String repo, int number, String taskId, Instant createdAt)
    {
        return new AgentReview(
                id,
                workspaceId,
                repo,
                number,
                OptionalLong.of(4242),
                taskId,
                AgentReview.State.OPEN,
                false,
                Optional.empty(),
                Optional.of(HEAD_SHA),
                0,
                createdAt,
                createdAt,
                Optional.empty());
    }

    private static ReviewRun newRun(String id, String reviewId)
    {
        return new ReviewRun(
                id,
                reviewId,
                1,
                ReviewRun.Scope.INCREMENTAL,
                "main",
                BASE_SHA,
                MERGE_BASE_SHA,
                HEAD_SHA,
                Optional.of(PRIOR_HEAD_SHA),
                ImmutableList.of(
                        new ReviewCommit(PRIOR_HEAD_SHA, "Add the review tool surface", "octocat"),
                        new ReviewCommit(HEAD_SHA, "Freeze the publication diff", "octocat")),
                ImmutableList.of(
                        new DiffFile(REVIEW_TOOLS, "modified", 41, 3, "@@ -115,4 +115,6 @@\n-int published;\n+int published = 0;\n"),
                        new DiffFile("docs/design/review.png", "added", 0, 0, null)),
                ImmutableList.of("turn-1"),
                ReviewRun.State.RUNNING,
                Optional.empty(),
                Optional.empty(),
                false,
                ImmutableMap.of("review_context:ev-1", new ReviewOperation("sha256:11ab", "read")),
                ImmutableMap.of("ev-1", CONTEXT_EVIDENCE),
                Optional.empty(),
                0,
                NOW,
                NOW,
                Optional.empty());
    }

    private static ReviewFinding newFinding(String id, String reviewId, String runId)
    {
        ReviewAnchor anchor = new ReviewAnchor(
                REVIEW_TOOLS,
                "RIGHT",
                118,
                121,
                ImmutableList.of(
                        "int published = 0;",
                        "for (ReviewFinding finding : findings) {",
                        "    publish(finding);",
                        "}"));
        return new ReviewFinding(
                id,
                reviewId,
                runId,
                1,
                "Published counter never increments",
                "The loop publishes every finding but leaves `published` at zero, so the caller reports nothing was sent.",
                Optional.of("critical"),
                anchor,
                ImmutableList.of(CONTEXT_EVIDENCE, COMMAND_EVIDENCE),
                2,
                ReviewFinding.State.OPEN,
                Optional.empty(),
                HEAD_SHA,
                false,
                0,
                NOW,
                NOW.plusSeconds(4));
    }

    private static ReviewMessage newMessage(String id, Optional<String> findingId, ReviewMessage.Author author, String body, Instant createdAt)
    {
        return new ReviewMessage(
                id,
                "review-1",
                findingId,
                author,
                body,
                2,
                "run-1",
                Optional.of("turn-2"),
                Optional.of("queued-1"),
                0,
                createdAt);
    }
}
