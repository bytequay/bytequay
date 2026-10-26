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
import com.bytequay.core.review.ReviewCandidate;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewEvidenceAnswer;
import com.bytequay.core.review.ReviewEvidenceRequest;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewOperation;
import com.bytequay.core.review.ReviewPerspectiveAssessment;
import com.bytequay.core.review.ReviewPublication;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewRunOverview;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.review.ReviewTestCheck;
import com.bytequay.core.review.ReviewThreadVerdict;
import com.bytequay.core.review.ReviewValidation;
import com.bytequay.core.review.ReviewVerification;
import com.bytequay.persistence.sqlite.converter.JsonColumns;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class TestSqliteReviewStores
        extends AbstractTestSqliteStore
{
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
            Optional.of("The focused parser tests passed"),
            ImmutableList.of("finding-1"));
    private static final ReviewCoverage COVERAGE = new ReviewCoverage(
            false,
            ImmutableList.of("generated sources", "vendored fixtures"),
            ImmutableList.of("Snowflake cloud tests unavailable without credentials"),
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
        registerSqliteProperties(registry, newDatabaseUrl("bytequay-review-store-") + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void retainsDevelopmentSourceWithoutAPullRequest()
    {
        AgentReview review = new AgentReview(
                "local-review",
                "workspace-a",
                "bytequay/sample",
                OptionalInt.empty(),
                OptionalLong.empty(),
                "reviewer",
                Optional.of("developer"),
                AgentReview.State.OPEN,
                false,
                Optional.empty(),
                Optional.of(HEAD_SHA),
                Optional.of(HEAD_SHA),
                0,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty());
        store.insertReview(review);
        entityManager.flush();
        entityManager.clear();

        assertThat(store.findReview("local-review")).contains(review);
        assertThat(store.findReviewByTask("reviewer")).contains(review);
        assertThat(store.findOpenReview("workspace-a", "bytequay/sample", 7)).isEmpty();
        assertThat(store.listReviews("workspace-a")).containsExactly(review);
    }

    @Test
    void workingCopyBaselineRoundTripsIndependentlyOfTheAssessedAndObservedHeads()
    {
        AgentReview review = store.insertReview(newReview("review-sync", "workspace-a", "bytequay/sample", 7, "task-sync", NOW)
                .withObservedHead(NEXT_HEAD_SHA, NOW).withWorktreeHead(PRIOR_HEAD_SHA, NOW));
        ReviewRun run = store.insertRun(newRun("run-sync", review.id()));
        entityManager.clear();

        AgentReview restored = store.findReview(review.id()).orElseThrow();
        assertThat(restored.worktreeHeadSha()).contains(PRIOR_HEAD_SHA);
        assertThat(restored.observedHeadSha()).contains(NEXT_HEAD_SHA);
        assertThat(store.findRun(run.id()).orElseThrow().headSha()).isEqualTo(HEAD_SHA);
        assertThat(store.findRun(run.id()).orElseThrow().worktreeTreeSha()).contains("d".repeat(40));

        store.updateReview(restored.withWorktreeHead(NEXT_HEAD_SHA, NOW.plusSeconds(1)));
        entityManager.clear();
        assertThat(store.findReview(review.id()).orElseThrow().worktreeHeadSha()).contains(NEXT_HEAD_SHA);
        assertThat(store.findRun(run.id())).contains(run);
    }

    @Test
    void summarizesLatestRunsWithoutLoadingRetainedPatchesOrHistory()
    {
        String patch = "+retained source line\n".repeat(25_000);
        store.insertRun(newRun("latest", "review-one", 2, ReviewRun.Scope.INCREMENTAL,
                ImmutableList.of(new ReviewCommit(HEAD_SHA, "Latest change", "octocat")),
                ImmutableList.of(new DiffFile("one.java", "modified", Integer.MAX_VALUE, 3, patch),
                        new DiffFile("two.java", "modified", Integer.MAX_VALUE, 7, patch)))
                .ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW));
        // An earlier run can remain active and be updated after the latest ordinal.
        store.insertRun(newRun("earlier", "review-one").withTurn("late-turn", NOW.plusSeconds(100)));
        store.insertRun(newRun("empty", "review-two", 1, ReviewRun.Scope.FULL, ImmutableList.of(), ImmutableList.of())
                .ended(ReviewRun.State.STOPPED, Optional.empty(), NOW));
        store.insertRun(newRun("excluded", "review-three"));
        entityManager.flush();
        entityManager.clear();

        assertThat(store.summarizeRuns(ImmutableSet.of("review-one", "review-two", "no-runs"))).containsExactlyInAnyOrderEntriesOf(ImmutableMap.of(
                "review-one", new ReviewRunOverview(true, ReviewRun.Scope.INCREMENTAL, 1, 2, 2L * Integer.MAX_VALUE, 10, ReviewRun.State.ACCEPTED, Optional.of(NOW)),
                "review-two", new ReviewRunOverview(false, ReviewRun.Scope.FULL, 0, 0, 0, 0, ReviewRun.State.STOPPED, Optional.of(NOW))));
        assertThat(entityManager.unwrap(Session.class).getStatistics().getEntityCount()).isZero();
        assertThat(store.summarizeRuns(ImmutableSet.of())).isEmpty();
    }

    @Test
    void roundTripsEveryReviewRecordThroughSqlite()
    {
        AgentReview review = newReview("review-1", "workspace-a", "bytequay/sample", 7, "task-1", NOW);
        ReviewRun run = newRun("run-1", "review-1")
                .withTurn("turn-2", NOW.plusSeconds(1))
                .withSummary(SUMMARY, NOW.plusSeconds(2))
                .withCoverage(COVERAGE, NOW.plusSeconds(3))
                .withInvestigation(new ReviewInvestigation("Follow the new publication count", ImmutableList.of(REVIEW_TOOLS), ImmutableList.of("ev-1"),
                        ImmutableList.of(candidate("The count might never reach callers")),
                        ImmutableList.of(new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.SUPPORTED, "The caller receives the unchanged value",
                                ImmutableList.of("ev-1"), "No cleanup in caller", Optional.of("finding-1"), Optional.of(1), Optional.empty())),
                        ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty()), NOW.plusSeconds(3))
                .withStep(new ReviewStep(2, ReviewStep.Kind.VALIDATE, false, Optional.empty(), Optional.empty()), NOW.plusSeconds(3))
                .withOperation("review_finding:finding-1", new ReviewOperation("sha256:9f1c", "created"), NOW.plusSeconds(4))
                .withEvidence("ev-2", COMMAND_EVIDENCE, NOW.plusSeconds(5))
                .withCompletionRepairUsed(NOW.plusSeconds(6))
                .ended(ReviewRun.State.ACCEPTED, Optional.of("accepted"), NOW.plusSeconds(7));
        ReviewFinding finding = newFinding("finding-1", "review-1", "run-1");
        ReviewMessage question = newMessage("message-1", Optional.of("finding-1"), ReviewMessage.Author.USER, "Why is this critical?", NOW.plusSeconds(8));
        ReviewMessage answer = newMessage("message-2", Optional.empty(), ReviewMessage.Author.AGENT, "The published count never reaches the caller.", NOW.plusSeconds(9));
        ReviewMessage developerReply = new ReviewMessage("message-3", "review-1", Optional.of("finding-1"), Optional.empty(), ReviewMessage.Author.AGENT,
                "Fixed in abc1234.", 2, "run-1", Optional.empty(), Optional.empty(), 0, NOW.plusSeconds(10), Optional.of("developer-task"));

        store.insertReview(review);
        store.insertRun(run);
        store.insertFinding(finding);
        store.insertMessage(question);
        store.insertMessage(answer);
        store.insertMessage(developerReply);
        entityManager.clear();

        assertThat(store.findReview("review-1")).contains(review);
        assertThat(store.findRun("run-1")).contains(run);
        assertThat(store.listRuns("review-1")).containsExactly(run);
        assertThat(store.findFinding("finding-1")).contains(finding);
        assertThat(store.listFindings("review-1")).containsExactly(finding);
        // The development task that replied stays on the message; the user's and the reviewer's messages name none.
        assertThat(store.listMessages("review-1")).containsExactly(question, answer, developerReply);
        assertThat(store.findMessage("message-3")).map(ReviewMessage::developerTaskId).contains(Optional.of("developer-task"));
        assertThatThrownBy(() -> new ReviewMessage("message-4", "review-1", Optional.of("finding-1"), Optional.empty(), ReviewMessage.Author.USER,
                "Mine", 2, "run-1", Optional.empty(), Optional.empty(), 0, NOW, Optional.of("developer-task")))
                .isInstanceOf(IllegalArgumentException.class);

        // A finding about the whole pull request has no lines, and keeps none through storage; the memories it cites survive.
        ReviewFinding whole = new ReviewFinding("finding-whole", "review-1", "run-1", 2, "Nit: commit subject", "Commit message: name the behavior.", Optional.empty(),
                Optional.empty(), ImmutableList.of(CONTEXT_EVIDENCE), 1, ReviewFinding.State.OPEN, Optional.empty(), finding.validatedHeadSha(), false, 0, NOW, NOW,
                ImmutableList.of(), ReviewFinding.Origin.DISCUSSION, Optional.empty())
                .withMemories(ImmutableList.of(new ReviewFinding.MemoryCitation("m12", "Name the behavior in commit subjects. (PR #31201 @alice)")));
        store.insertFinding(whole);
        entityManager.clear();
        assertThat(store.findFinding("finding-whole")).contains(whole);
        assertThat(store.findFinding("finding-whole")).map(ReviewFinding::anchor).contains(Optional.empty());

        // A development agent's resolution names its task; the user's or the reviewer's own decision names none.
        ReviewFinding fixed = store.updateFinding(store.findFinding("finding-whole").orElseThrow().resolvedByDeveloper("developer-task", NOW.plusSeconds(11)));
        entityManager.clear();
        assertThat(store.findFinding("finding-whole")).contains(fixed);
        assertThat(fixed.state()).isEqualTo(ReviewFinding.State.RESOLVED);
        assertThat(fixed.resolvedByTaskId()).contains("developer-task");
        assertThat(fixed.withMemories(ImmutableList.of()).resolvedByTaskId()).contains("developer-task");
        assertThat(fixed.withState(ReviewFinding.State.RESOLVED, Optional.of("Mine"), NOW).resolvedByTaskId()).isEmpty();
        ReviewFinding pending = fixed.pendingReassessment(NOW.plusSeconds(12));
        assertThat(pending.outdated()).isTrue();
        ReviewFinding carried = pending.carried(Optional.empty(), NEXT_HEAD_SHA, NOW.plusSeconds(13));
        assertThat(carried.revision()).as("carrying identical changes is not a new assessment").isEqualTo(fixed.revision());
        assertThat(carried.outdated()).isFalse();
        ReviewFinding revised = store.updateFinding(carried.revised("Updated title", "Updated evidence", Optional.of("P2"), Optional.empty(),
                ImmutableList.of(CONTEXT_EVIDENCE), NEXT_HEAD_SHA, NOW.plusSeconds(14)));
        entityManager.clear();
        assertThat(store.findFinding(fixed.id())).contains(revised);
        assertThat(revised.revision()).isEqualTo(fixed.revision() + 1);
        assertThat(revised.version()).isEqualTo(fixed.version() + 1);
        assertThat(revised.state()).isEqualTo(ReviewFinding.State.RESOLVED);
        assertThat(revised.resolvedByTaskId()).as("reassessment preserves the developer's resolution").contains("developer-task");
        assertThat(revised.memories()).isEqualTo(fixed.memories());
        assertThatThrownBy(() -> new ReviewFinding("finding-open", "review-1", "run-1", 3, "Title", "Body", Optional.empty(), Optional.empty(), ImmutableList.of(), 1,
                ReviewFinding.State.OPEN, Optional.empty(), finding.validatedHeadSha(), false, 0, NOW, NOW, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC,
                Optional.of("developer-task")))
                .isInstanceOf(IllegalArgumentException.class);

        // A replay has no pull request number, so the pull request's own review lookup never finds it.
        AgentReview replay = new AgentReview("review-replay", "workspace-a", "bytequay/sample", OptionalInt.empty(), OptionalLong.empty(), "task-replay",
                Optional.empty(), AgentReview.State.OPEN, false, Optional.empty(), Optional.of(HEAD_SHA), Optional.of(HEAD_SHA), 0, NOW, NOW, Optional.empty(),
                Optional.of(new AgentReview.Replay("replay-1", 7, true, NOW.minusSeconds(60))));
        store.insertReview(replay);
        entityManager.clear();
        assertThat(store.findReview("review-replay")).contains(replay);
        assertThat(store.findReviewByTask("task-replay")).contains(replay);
        assertThat(store.findOpenReview("workspace-a", "bytequay/sample", 7)).map(AgentReview::id).contains("review-1");
    }

    @Test
    void roundTripsTheSealedEvidenceRequestResponseAndFinalSelection()
    {
        AgentReview review = newReview("review-loop", "workspace-a", "bytequay/sample", 7, "task-loop", NOW);
        ReviewInvestigation initial = new ReviewInvestigation("Caller lifetime", ImmutableList.of(REVIEW_TOOLS), ImmutableList.of("ev-1"),
                ImmutableList.of(candidate("The resource may leak")), ImmutableList.of(), ImmutableList.of(),
                Optional.of(new ReviewVerification("Run the focused check using the recorded temporary directory", ImmutableList.of("ev-1"))), perspectives(), Optional.empty());
        ReviewCandidateValidation request = new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE, "Ownership is unknown",
                ImmutableList.of("ev-1"), "The caller may close the resource", Optional.empty(), Optional.empty(),
                Optional.of(new ReviewEvidenceRequest("Who closes the resource?", "Read the caller finally block", "Guaranteed cleanup rejects the leak")));
        ReviewValidation validation = new ReviewValidation(1, ImmutableList.of(request), COVERAGE, ImmutableList.of(), ImmutableList.of(), "Awaiting evidence", ImmutableList.of());
        ReviewInvestigation response = new ReviewInvestigation(initial.changeMap(), initial.reviewedFiles(), ImmutableList.of("ev-1", "ev-2"), initial.candidates(),
                ImmutableList.of(), ImmutableList.of(new ReviewEvidenceAnswer(1, "Read all callers", ImmutableList.of("ev-2"), Optional.of("Integration fixture unavailable"))),
                initial.verification(), initial.perspectives(), Optional.of(new ReviewTestCheck(ReviewTestCheck.Outcome.DOES_NOT_PROTECT,
                        "The new test passes with the cleanup removed", Optional.of("ev-1"), Optional.of("ev-2"), Optional.of(1))));
        ReviewRun run = newRun("run-loop", review.id())
                .replaceCurrentStep(ReviewStep.initial().withInvestigation(initial), NOW)
                .withStep(new ReviewStep(2, ReviewStep.Kind.VALIDATE, false, Optional.empty(), Optional.of(validation)), NOW)
                .withStep(new ReviewStep(3, ReviewStep.Kind.COLLECT_EVIDENCE, true, Optional.of(response), Optional.empty()), NOW)
                .withInvestigation(response, NOW)
                .withStep(new ReviewStep(4, ReviewStep.Kind.VALIDATE, false, Optional.empty(), Optional.of(new ReviewValidation(3,
                        ImmutableList.of(new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.SUPPORTED, "One caller misses cleanup", ImmutableList.of("ev-2"),
                                "Other callers guarantee cleanup", Optional.of("finding-1"), Optional.of(2), Optional.empty())),
                        COVERAGE, ImmutableList.of(), ImmutableList.of("finding-1"), "Select the missing cleanup",
                        ImmutableList.of(new ReviewThreadVerdict("51", ReviewThreadVerdict.Verdict.PARTLY_ADDRESSED, "One caller still misses the cleanup",
                                ImmutableList.of("ev-2"), REVIEW_TOOLS, Optional.of(12), "Please close the resource", true))))), NOW)
                .withCompletionRepairUsed(NOW);
        store.insertReview(review);
        store.insertRun(run);
        entityManager.clear();

        ReviewRun restored = store.findRun(run.id()).orElseThrow();
        assertThat(restored).isEqualTo(run);
        assertThat(restored.steps().get(1).validation()).contains(validation);
        assertThat(restored.steps().get(2).investigation()).contains(response);
        assertThat(restored.steps().get(2).key(run.id())).isEqualTo("review-step:run-loop:3:repair");
        assertThat(restored.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.VALIDATE);
        assertThat(restored.currentStep().orElseThrow().validation().orElseThrow().selectedFindingIds()).containsExactly("finding-1");
    }

    @Test
    void aValidationStoredBeforeThreadVerdictsExistedReadsWithNone()
            throws JsonProcessingException
    {
        ReviewValidation current = new ReviewValidation(1, ImmutableList.of(), COVERAGE, ImmutableList.of(), ImmutableList.of(), "Nothing to select", ImmutableList.of());
        ObjectNode stored = JsonColumns.mapper().valueToTree(current);
        assertThat(stored.has("threadVerdicts")).isTrue();
        stored.remove("threadVerdicts");

        assertThat(JsonColumns.mapper().readValue(stored.toString(), ReviewValidation.class)).isEqualTo(current);
    }

    @Test
    void anInvestigationStoredBeforeTheTestCheckExistedReadsWithoutOne()
            throws JsonProcessingException
    {
        ReviewInvestigation current = new ReviewInvestigation("Caller lifetime", ImmutableList.of(REVIEW_TOOLS), ImmutableList.of("ev-1"),
                ImmutableList.of(candidate("The resource may leak")), ImmutableList.of(), ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
        ObjectNode stored = JsonColumns.mapper().valueToTree(current);
        assertThat(stored.has("testCheck")).isTrue();
        stored.remove("testCheck");

        assertThat(JsonColumns.mapper().readValue(stored.toString(), ReviewInvestigation.class)).isEqualTo(current);
    }

    @Test
    void changeMapCheckpointRoundTripsWithoutBecomingASealedReport()
    {
        AgentReview review = newReview("review-draft", "workspace-a", "bytequay/sample", 7, "task-draft", NOW);
        ReviewInvestigation draft = new ReviewInvestigation("Trace the publication counter from initialization to its callers", ImmutableList.of(), ImmutableList.of("ev-1"),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty(), ImmutableList.of(), Optional.empty());
        ReviewRun run = newRun("run-draft", review.id()).withInvestigation(draft, NOW);
        store.insertReview(review);
        store.insertRun(run);
        entityManager.clear();
        ReviewRun restored = store.findRun(run.id()).orElseThrow();
        assertThat(restored.investigation()).contains(draft);
        assertThat(restored.evidence()).containsEntry("ev-1", CONTEXT_EVIDENCE);
        assertThat(restored.latestSealedInvestigation()).isEmpty();
        assertThat(restored.currentStep().orElseThrow().sealed()).isFalse();
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
                Optional.empty(),
                Optional.of("a".repeat(40)), OptionalLong.empty());
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
                Optional.of(publication),
                Optional.of("a".repeat(40)), OptionalLong.empty());
        ReviewDraftItem reply = new ReviewDraftItem("reply", ReviewDraftItem.Source.MANUAL, Optional.empty(), OptionalInt.empty(), REVIEW_TOOLS,
                "RIGHT", 42, OptionalInt.empty(), "Reply to the existing discussion", true, Optional.empty(), Optional.empty(), OptionalLong.of(61));
        reply = reply.withPublication(Optional.of(new ReviewPublication(
                "attempt-2", ReviewPublication.State.UNCERTAIN, OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), NOW.plusSeconds(11))));
        ReviewDraft draft = ReviewDraft.empty("ByteQuay/Sample", 7, NOW)
                .withReview("Close, but the counter is wrong.", "REQUEST_CHANGES", NOW.plusSeconds(1))
                .withItems(ImmutableList.of(manual, promoted, reply), NOW.plusSeconds(2))
                .withSummaryFindings(ImmutableList.of("finding-pending"), ImmutableList.of("finding-posted"), NOW.plusSeconds(3))
                .withReviewPublication(Optional.of(new ReviewPublication(
                        "attempt-2", ReviewPublication.State.UNCERTAIN, OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), NOW.plusSeconds(11))), NOW.plusSeconds(4));

        ReviewDraft saved = draftStore.save(draft);
        entityManager.clear();

        assertThat(saved).isEqualTo(draft);
        assertThat(draftStore.find("bytequay/sample", 7)).contains(draft);
        ReviewDraft stored = draftStore.find("bytequay/sample", 7).orElseThrow();
        assertThat(stored.items()).containsExactly(manual, promoted, reply);
        assertThat(stored.items().get(0).published()).isFalse();
        assertThat(stored.items().get(1).published()).isTrue();
        assertThat(stored.items().get(2).published()).isFalse();

        draftStore.save(stored.withReviewPublication(Optional.empty(), NOW.plusSeconds(5)));
        entityManager.clear();
        assertThat(draftStore.find("bytequay/sample", 7).orElseThrow().reviewPublication()).isEmpty();
    }

    @Test
    void readsRetainedDraftItemsWithoutACommitWithoutLosingUserText()
    {
        ReviewDraftItem item = new ReviewDraftItem("item-retained", ReviewDraftItem.Source.MANUAL, Optional.empty(), OptionalInt.empty(), REVIEW_TOOLS,
                "RIGHT", 42, OptionalInt.empty(), "User-edited wording", true, Optional.empty(), Optional.of(HEAD_SHA), OptionalLong.empty());
        ReviewDraft saved = draftStore.save(ReviewDraft.empty("bytequay/sample", 7, NOW)
                .withReview("Retained summary", "REQUEST_CHANGES", NOW).withItems(ImmutableList.of(item), NOW));
        entityManager.createNativeQuery("UPDATE pr_review_draft SET items_json = json_remove(items_json, '$[0].commitId') WHERE id = :id")
                .setParameter("id", saved.id()).executeUpdate();
        entityManager.clear();

        ReviewDraft retained = draftStore.find("bytequay/sample", 7).orElseThrow();

        assertThat(retained.body()).isEqualTo("Retained summary");
        assertThat(retained.event()).isEqualTo("REQUEST_CHANGES");
        assertThat(retained.items()).singleElement().satisfies(value -> {
            assertThat(value.body()).isEqualTo("User-edited wording");
            assertThat(value.line()).isEqualTo(42);
            assertThat(value.commitId()).isEmpty();
        });
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
    void listsOpenAndClosedWorkspaceReviewsInStableUpdateOrder()
    {
        AgentReview older = store.insertReview(newReview("review-old", "workspace-a", "bytequay/sample", 7, "task-old", NOW));
        AgentReview ended = store.insertReview(newReview("review-ended", "workspace-a", "bytequay/sample", 7, "task-ended", NOW.minusSeconds(10))
                .closed(NOW.plusSeconds(5)));
        AgentReview tied = store.insertReview(newReview("review-tied", "workspace-a", "bytequay/sample", 9, "task-tied", NOW.plusSeconds(5)));
        store.insertReview(newReview("review-other", "workspace-b", "bytequay/sample", 7, "task-other", NOW.plusSeconds(10)));
        entityManager.clear();

        assertThat(store.listReviews("workspace-a")).containsExactly(ended, tied, older);
        assertThat(store.listReviews("workspace-missing")).isEmpty();
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void publicationAndFindingResolutionCommitTogetherOrBothRollBack()
    {
        store.insertReview(newReview("review-published", "workspace-published", "bytequay/published", 7, "task-published", NOW).closed(NOW));
        store.insertRun(newRun("run-published", "review-published"));
        ReviewFinding finding = store.insertFinding(newFinding("finding-published", "review-published", "run-published"));
        ReviewDraft pending = draftStore.save(ReviewDraft.empty("bytequay/published", 7, NOW).withSummaryFindings(ImmutableList.of(finding.id()), ImmutableList.of(), NOW));
        ReviewDraft published = pending.withSummaryFindings(ImmutableList.of(), ImmutableList.of(finding.id()), NOW.plusSeconds(1));
        ReviewFinding concurrent = store.updateFinding(finding.withMemories(ImmutableList.of(new ReviewFinding.MemoryCitation("m1", "Keep the guard"))));

        assertThatThrownBy(() -> draftStore.savePublished(published,
                ImmutableList.of(finding.withState(ReviewFinding.State.RESOLVED, Optional.empty(), NOW.plusSeconds(1)))))
                .isInstanceOf(ReviewConflictException.class);

        assertThat(draftStore.find(pending.repo(), pending.number())).contains(pending);
        assertThat(store.findFinding(finding.id())).contains(concurrent);

        ReviewFinding resolved = concurrent.withState(ReviewFinding.State.RESOLVED, Optional.of("Accepted and posted to GitHub"), NOW.plusSeconds(2));
        ReviewDraft saved = draftStore.savePublished(published, ImmutableList.of(resolved));

        assertThat(draftStore.find(pending.repo(), pending.number())).contains(saved);
        assertThat(saved.postedSummaryFindingIds()).containsExactly(finding.id());
        assertThat(store.findFinding(finding.id())).contains(resolved.withVersion(concurrent.version() + 1));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void carryingARunCommitsItsFindingsAndTheReviewCursorTogetherOrRollsBack()
    {
        AgentReview review = store.insertReview(newReview("review-carried", "workspace-carried", "bytequay/carried", 7, "task-carried", NOW));
        store.insertRun(newRun("run-reviewed", "review-carried"));
        ReviewFinding finding = store.insertFinding(newFinding("finding-carried", "review-carried", "run-reviewed"));
        ReviewFinding concurrent = store.updateFinding(finding.withMemories(ImmutableList.of(new ReviewFinding.MemoryCitation("m1", "Keep the guard"))));
        ReviewRun carried = newRun("run-carried", "review-carried").ended(ReviewRun.State.ACCEPTED, Optional.of("carried"), NOW.plusSeconds(1));
        AgentReview cursor = review.withLatestCompletedRun("run-carried", NOW.plusSeconds(1));
        List<ReviewFinding> moved = ImmutableList.of(finding.withMemories(ImmutableList.of(new ReviewFinding.MemoryCitation("m2", "Carry the guard"))));

        assertThatThrownBy(() -> store.carryRun(cursor, carried, moved)).isInstanceOf(ReviewConflictException.class);

        assertThat(store.findRun("run-carried")).isEmpty();
        assertThat(store.findReview("review-carried").orElseThrow().latestCompletedRunId()).isEmpty();

        store.carryRun(cursor, carried, ImmutableList.of(concurrent.withMemories(ImmutableList.of(new ReviewFinding.MemoryCitation("m2", "Carry the guard")))));

        assertThat(store.findRun("run-carried").orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(store.findReview("review-carried").orElseThrow().latestCompletedRunId()).contains("run-carried");
        assertThat(store.findFinding(finding.id()).orElseThrow().memories()).extracting(ReviewFinding.MemoryCitation::id).containsExactly("m2");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void withdrawalCommitsTheFindingItsMessageAndTheRunTogetherOrRollsBack()
    {
        store.insertReview(newReview("review-withdrawn", "workspace-withdrawn", "bytequay/withdrawn", 7, "task-withdrawn", NOW));
        ReviewRun run = store.insertRun(newRun("run-withdrawn", "review-withdrawn"));
        ReviewFinding finding = store.insertFinding(newFinding("finding-withdrawn", "review-withdrawn", "run-withdrawn"));
        ReviewFinding withdrawn = finding.withState(ReviewFinding.State.WITHDRAWN, Optional.of("Unreachable"), NOW.plusSeconds(1));
        ReviewMessage message = new ReviewMessage("message-withdrawn", "review-withdrawn", Optional.of(finding.id()), Optional.empty(), ReviewMessage.Author.AGENT,
                "Withdrawn: Unreachable", 2, "run-withdrawn", Optional.empty(), Optional.empty(), 0, NOW.plusSeconds(1), Optional.empty());
        ReviewRun concurrent = store.updateRun(run.withCoverage(COVERAGE, NOW.plusSeconds(1)));
        ReviewOperation operation = new ReviewOperation("sha256:22cd", "accepted");

        assertThatThrownBy(() -> store.withdrawFinding(withdrawn, message, run.withOperation("review_withdraw_finding:1", operation, NOW.plusSeconds(1))))
                .isInstanceOf(ReviewConflictException.class);

        assertThat(store.findFinding(finding.id())).contains(finding);
        assertThat(store.listMessages("review-withdrawn")).isEmpty();

        store.withdrawFinding(withdrawn, message, concurrent.withOperation("review_withdraw_finding:1", operation, NOW.plusSeconds(1)));

        assertThat(store.findFinding(finding.id()).orElseThrow().state()).isEqualTo(ReviewFinding.State.WITHDRAWN);
        assertThat(store.listMessages("review-withdrawn")).extracting(ReviewMessage::id).containsExactly("message-withdrawn");
        assertThat(store.findRun("run-withdrawn").orElseThrow().operations()).containsEntry("review_withdraw_finding:1", operation);
    }

    private static AgentReview newReview(String id, String workspaceId, String repo, int number, String taskId, Instant createdAt)
    {
        return new AgentReview(
                id,
                workspaceId,
                repo,
                OptionalInt.of(number),
                OptionalLong.of(4242),
                taskId,
                Optional.empty(),
                AgentReview.State.OPEN,
                false,
                Optional.empty(),
                Optional.of(HEAD_SHA),
                Optional.of(HEAD_SHA),
                0,
                createdAt,
                createdAt,
                Optional.empty(),
                Optional.empty());
    }

    private static ReviewRun newRun(String id, String reviewId)
    {
        return newRun(id, reviewId, 1, ReviewRun.Scope.INCREMENTAL,
                ImmutableList.of(new ReviewCommit(PRIOR_HEAD_SHA, "Add the review tool surface", "octocat"), new ReviewCommit(HEAD_SHA, "Freeze the publication diff", "octocat")),
                ImmutableList.of(
                        new DiffFile(REVIEW_TOOLS, "modified", 41, 3, "@@ -115,4 +115,6 @@\n-int published;\n+int published = 0;\n"),
                        new DiffFile("docs/design/review.png", "added", 0, 0, null)));
    }

    private static ReviewRun newRun(String id, String reviewId, int ordinal, ReviewRun.Scope scope, List<ReviewCommit> commits, List<DiffFile> diff)
    {
        return new ReviewRun(
                id,
                reviewId,
                ordinal,
                scope,
                "main",
                BASE_SHA,
                MERGE_BASE_SHA,
                HEAD_SHA, Optional.of("d".repeat(40)),
                Optional.of(PRIOR_HEAD_SHA),
                commits,
                diff,
                ImmutableList.of("turn-1"),
                ReviewRun.State.RUNNING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableList.of(ReviewStep.initial()),
                false,
                ImmutableMap.of("review_context:ev-1", new ReviewOperation("sha256:11ab", "read")),
                ImmutableMap.of("ev-1", CONTEXT_EVIDENCE),
                Optional.empty(),
                0,
                NOW,
                NOW,
                Optional.empty());
    }

    private static ReviewCandidate candidate(String hypothesis)
    {
        return new ReviewCandidate(1, ReviewPerspectiveAssessment.Perspective.INTERACTIONS, "Resource lifetime", hypothesis, ImmutableList.of("ev-1"),
                "Trace caller cleanup", Optional.empty(), 1, "turn-1", Optional.of("lifetime explorer"));
    }

    private static List<ReviewPerspectiveAssessment> perspectives()
    {
        return java.util.Arrays.stream(ReviewPerspectiveAssessment.Perspective.values())
                .map(perspective -> new ReviewPerspectiveAssessment(perspective, "Is the resource closed?", ImmutableList.of("Cleanup"), ImmutableList.of(REVIEW_TOOLS),
                        ImmutableList.of("ev-1"), ReviewPerspectiveAssessment.Status.CHECKED, "Read the cleanup path"))
                .collect(com.google.common.collect.ImmutableList.toImmutableList());
    }

    private static ReviewFinding newFinding(String id, String reviewId, String runId)
    {
        ReviewAnchor anchor = new ReviewAnchor(
                REVIEW_TOOLS,
                "RIGHT",
                118,
                121,
                ImmutableList.of("int published = 0;", "for (ReviewFinding finding : findings) {", "    publish(finding);", "}"));
        return new ReviewFinding(
                id,
                reviewId,
                runId,
                1,
                "Published counter never increments",
                "The loop publishes every finding but leaves `published` at zero, so the caller reports nothing was sent.",
                Optional.of("critical"),
                Optional.of(anchor),
                ImmutableList.of(CONTEXT_EVIDENCE, COMMAND_EVIDENCE),
                2,
                ReviewFinding.State.OPEN,
                Optional.empty(),
                HEAD_SHA,
                false,
                0,
                NOW,
                NOW.plusSeconds(4),
                ImmutableList.of(),
                ReviewFinding.Origin.AUTOMATIC, Optional.empty());
    }

    private static ReviewMessage newMessage(String id, Optional<String> findingId, ReviewMessage.Author author, String body, Instant createdAt)
    {
        return new ReviewMessage(
                id,
                "review-1",
                findingId,
                findingId.isEmpty() ? Optional.of("run-1:purpose") : Optional.empty(),
                author,
                body,
                2,
                "run-1",
                Optional.of("turn-2"),
                Optional.of("queued-1"),
                0,
                createdAt, Optional.empty());
    }
}
