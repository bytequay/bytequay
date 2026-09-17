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

import com.bytequay.core.context.ContextContribution;
import com.bytequay.core.context.ContextDelivery;
import com.bytequay.core.context.ContextDocumentRead;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRelatedItem;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextSelectedEntry;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewAnchor;
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
import com.bytequay.core.review.ReviewPublication;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.review.ReviewValidation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

class TestSqliteReviewStores
        extends AbstractTestSqliteStore
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
            Optional.of("The focused parser tests passed"),
            ImmutableList.of("finding-1"));
    private static final ReviewCoverage COVERAGE = new ReviewCoverage(
            false,
            ImmutableList.of("generated sources", "vendored fixtures"),
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
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void roundTripsEveryReviewRecordThroughSqlite()
    {
        AgentReview review = newReview("review-1", "workspace-a", "bytequay/sample", 7, "task-1", NOW);
        ReviewRun run = newRun("run-1", "review-1")
                .withContext(contextSnapshot(), ImmutableMap.of(), NOW)
                .withTurn("turn-2", NOW.plusSeconds(1))
                .withSummary(SUMMARY, NOW.plusSeconds(2))
                .withCoverage(COVERAGE, NOW.plusSeconds(3))
                .withInvestigation(new ReviewInvestigation("Follow the new publication count", ImmutableList.of(REVIEW_TOOLS), ImmutableList.of("ev-1"),
                        ImmutableList.of("The count might never reach callers"),
                        ImmutableList.of(new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.SUPPORTED, "The caller receives the unchanged value",
                                ImmutableList.of("ev-1"), "No cleanup in caller", Optional.of("finding-1"), Optional.of(1), Optional.empty())),
                        ImmutableList.of()), NOW.plusSeconds(3))
                .withStep(new ReviewStep(2, ReviewStep.Kind.VALIDATE, false, Optional.empty(), Optional.empty()), NOW.plusSeconds(3))
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
    void preservesAbsentAndDisabledContextSeparately()
    {
        store.insertReview(newReview("review-history", "workspace-a", "bytequay/sample", 7, "task-history", NOW));
        store.insertReview(newReview("review-off", "workspace-a", "bytequay/sample", 8, "task-off", NOW));
        ReviewContextSnapshot disabled = new ReviewContextSnapshot("context-off", false, 9, 42, Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        ReviewRun historical = store.insertRun(newRun("run-history", "review-history"));
        ReviewRun off = store.insertRun(newRun("run-off", "review-off").withContext(disabled, ImmutableMap.of(), NOW));
        entityManager.clear();

        assertThat(store.findRun(historical.id()).orElseThrow().context()).isEmpty();
        assertThat(store.findRun(off.id()).orElseThrow().context()).contains(disabled);
        ReviewRun updated = store.updateRun(off.withTurn("turn-2", NOW.plusSeconds(1)));
        entityManager.clear();
        assertThat(store.findRun(updated.id()).orElseThrow().context()).contains(disabled);
    }

    @Test
    void retainsTypedSourceReceiptsAndRejectsDanglingEvidenceAfterReload()
    {
        store.insertReview(newReview("review-reads", "workspace-a", "bytequay/sample", 7, "task-reads", NOW));
        ReviewRun run = newRun("run-reads", "review-reads").withContext(contextSnapshot(), ImmutableMap.of(), NOW);
        ContextReference.RepositoryFile file = new ContextReference.RepositoryFile(42, RepoRef.of("bytequay", "sample"), "README.md", BASE_SHA);
        ContextRepositoryRead repositoryRead = new ContextRepositoryRead(
                "context-read:repository", file, HEAD_SHA, NOW, 1, 1, 1, 6, false, Optional.of("repository-evidence"));
        ContextRepositoryRead emptyFile = new ContextRepositoryRead("context-read:empty-file",
                new ContextReference.RepositoryFile(42, file.repository(), "empty.md", BASE_SHA), BASE_SHA, NOW, 1, 0, 0, 0, false, Optional.empty());
        RegisteredContextSource registration = new RegisteredContextSource(
                "docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/", ContextEntryOrigin.USER, Optional.empty());
        ContextDocumentRead documentRead = new ContextDocumentRead("context-read:document",
                new ContextReference.Document("docs", "https://example.com/docs/page"), registration, 7, UUID.randomUUID().toString(), "a".repeat(64),
                NOW.minusSeconds(60), 1, 2, 2, false, true, true, Optional.of("document-evidence"));
        ContextDocumentRead blankDocument = new ContextDocumentRead("context-read:blank-document", documentRead.reference(), registration, 7,
                documentRead.captureId(), documentRead.contentHash(), documentRead.readAt(), 2, 2, 1, true, false, false, Optional.empty());
        ReviewRun saved = store.insertRun(run
                .withContextSourceRead(repositoryRead, Optional.of(new ReviewEvidence("repository-evidence", "README.md", "Frozen blob", "1  é\n", "")), NOW)
                .withContextSourceRead(emptyFile, Optional.empty(), NOW)
                .withContextSourceRead(documentRead, Optional.of(new ReviewEvidence("document-evidence", "Documentation", "Original capture", "é", "")), NOW)
                .withContextSourceRead(blankDocument, Optional.empty(), NOW));
        entityManager.clear();

        ReviewRun loaded = store.findRun(saved.id()).orElseThrow();
        assertThat(loaded).isEqualTo(saved);
        assertThat(loaded.context().orElseThrow().sourceReads()).containsExactly(repositoryRead, emptyFile, documentRead, blankDocument);
        assertThat(loaded.context().orElseThrow().bootstrap()).isEqualTo(run.context().orElseThrow().bootstrap());
        assertThat(loaded.context().orElseThrow().evidenceRefs()).contains("repository-evidence", "document-evidence");
        entityManager.createNativeQuery("UPDATE agent_review_run SET evidence_json = json_remove(evidence_json, '$.repository-evidence') WHERE id = 'run-reads'")
                .executeUpdate();
        entityManager.clear();
        assertThatThrownBy(() -> store.findRun(saved.id())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing run evidence");
    }

    @Test
    void retainsContextContributionsAndDeliveryPrefixesWithoutProviderAcceptance()
    {
        AgentReview review = newReview("review-context", "workspace-a", "bytequay/sample", 7, "task-context", NOW);
        String preparationRef = "review-step:run-context:1:repository_context_prepare:BEGIN:prepare";
        ReviewInvestigation investigation = new ReviewInvestigation("Counter lifetime", ImmutableList.of(REVIEW_TOOLS), ImmutableList.of("ev-1"),
                ImmutableList.of("The counter may not be incremented"), ImmutableList.of(), ImmutableList.of());
        ContextContribution brief = new ContextContribution(1, "Use the retained counter contract — 资料", ImmutableList.of("ev-1"),
                ImmutableList.of(preparationRef), ImmutableList.of("Source version remains unverified"));
        ReviewRun run = newRun("run-context", review.id())
                .withContext(contextSnapshot(), ImmutableMap.of(), NOW)
                .withOperation(preparationRef, new ReviewOperation("sha256:preparation", "{\"receiptRef\":\"" + preparationRef + "\"}"), NOW)
                .withInvestigation(investigation, NOW)
                .replaceCurrentStep(ReviewStep.initial().withInvestigation(investigation), NOW)
                .withContextContribution(brief, NOW);
        ContextPacket preparedPrefix = run.context().orElseThrow().packetFor(1);
        String input = "Validate the investigation\n" + preparedPrefix.text();
        ContextDelivery delivery = new ContextDelivery("turn-context", preparedPrefix.digest(), preparedPrefix.utf8Bytes(), 1,
                ContextDelivery.digest(input), ContextLimits.bytes(input), Optional.empty(), NOW);
        ContextContribution supplement = new ContextContribution(3, "The focused check confirms the unchanged counter", ImmutableList.of("ev-2"),
                ImmutableList.of(), ImmutableList.of());
        run = run.withContextDelivery(delivery, NOW)
                .withStep(new ReviewStep(2, ReviewStep.Kind.VALIDATE, false, Optional.empty(), Optional.empty()), NOW)
                .withStep(new ReviewStep(3, ReviewStep.Kind.COLLECT_EVIDENCE, false, Optional.of(investigation), Optional.empty()), NOW)
                .withEvidence("ev-2", COMMAND_EVIDENCE, NOW)
                .withContextContribution(supplement, NOW);
        store.insertReview(review);
        ReviewRun saved = store.insertRun(run);
        entityManager.clear();

        ReviewRun loaded = store.findRun(saved.id()).orElseThrow();
        ReviewContextSnapshot snapshot = loaded.context().orElseThrow();
        assertThat(loaded).isEqualTo(saved);
        assertThat(snapshot.contributions()).containsExactly(brief, supplement);
        assertThat(snapshot.deliveries()).containsExactly(delivery);
        assertThat(snapshot.packetFor(delivery.contributionCount())).isEqualTo(preparedPrefix);
        assertThat(snapshot.packetFor(delivery.contributionCount()).digest()).isEqualTo(delivery.packetDigest());
        assertThat(snapshot.packetFor(delivery.contributionCount()).utf8Bytes()).isEqualTo(delivery.utf8Bytes());
        assertThat(snapshot.packetFor(2).digest()).isNotEqualTo(delivery.packetDigest());
        assertThat(snapshot.packetFor(1).text()).contains("Repository baseline unavailable").doesNotContain(supplement.text());
        assertThat(loaded.evidence().keySet()).containsAll(snapshot.evidenceRefs());
        assertThat(loaded.operations()).containsKey(preparationRef);
        String storedContext = (String) entityManager.createNativeQuery("SELECT context_json FROM agent_review_run WHERE id = :id")
                .setParameter("id", saved.id()).getSingleResult();
        assertThat(storedContext).doesNotContain("\"outcome\"", "\"providerTurnReference\"", "\"knownUnsent\"", "CONFIRMED");
        Number providerTurns = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM agent_turn WHERE id = :id")
                .setParameter("id", delivery.turnId()).getSingleResult();
        assertThat(providerTurns.longValue()).isZero();

        entityManager.createNativeQuery("UPDATE agent_review_run SET operations_json = '{}' WHERE id = :id")
                .setParameter("id", saved.id()).executeUpdate();
        entityManager.clear();
        assertThatThrownBy(() -> store.findRun(saved.id())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing preparation receipts");
    }

    @Test
    void readsRecentRunsWithWorkspaceRepositoryAndDatabaseBounds()
    {
        for (int index = 0; index < 12; index++) {
            AgentReview review = newReview("review-" + index, "workspace-a", "bytequay/sample", index + 1, "task-" + index, NOW);
            if (index == 11) {
                review = review.closed(NOW.plusSeconds(1));
            }
            store.insertReview(review);
            store.insertRun(newRun("run-" + index, review.id()).withTurn("history", NOW.plusSeconds(index / 2)));
        }
        store.insertReview(newReview("review-other-workspace", "workspace-b", "bytequay/sample", 1, "task-other-workspace", NOW));
        store.insertReview(newReview("review-other-repo", "workspace-a", "bytequay/other", 1, "task-other-repo", NOW));
        store.insertRun(newRun("run-other-workspace", "review-other-workspace").withTurn("history", NOW.plusSeconds(100)));
        store.insertRun(newRun("run-other-repo", "review-other-repo").withTurn("history", NOW.plusSeconds(100)));
        // An excluded older payload cannot be decoded: applying the bound after loading domain runs would fail.
        entityManager.createNativeQuery("UPDATE agent_review_run SET context_json = 'invalid' WHERE id = 'run-0'").executeUpdate();
        entityManager.clear();

        assertThat(store.listRecentRuns("workspace-a", "ByteQuay/Sample", 3)).extracting(ReviewRun::id).containsExactly("run-10", "run-11", "run-8");
        assertThat(store.listRecentRuns("workspace-a", "bytequay/sample", 10)).hasSize(10);
        assertThat(store.listRecentRuns("workspace-missing", "bytequay/sample", 10)).isEmpty();
        assertThatThrownBy(() -> store.listRecentRuns("workspace-a", "bytequay/sample", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.listRecentRuns("workspace-a", "bytequay/sample", 11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsTheSealedEvidenceRequestAndResponse()
    {
        AgentReview review = newReview("review-loop", "workspace-a", "bytequay/sample", 7, "task-loop", NOW);
        ReviewInvestigation initial = new ReviewInvestigation("Caller lifetime", ImmutableList.of(REVIEW_TOOLS), ImmutableList.of("ev-1"),
                ImmutableList.of("The resource may leak"), ImmutableList.of(), ImmutableList.of());
        ReviewCandidateValidation request = new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE, "Ownership is unknown",
                ImmutableList.of("ev-1"), "The caller may close the resource", Optional.empty(), Optional.empty(),
                Optional.of(new ReviewEvidenceRequest("Who closes the resource?", "Read the caller finally block", "Guaranteed cleanup rejects the leak")));
        ReviewValidation validation = new ReviewValidation(1, ImmutableList.of(request), COVERAGE, ImmutableList.of());
        ReviewInvestigation response = new ReviewInvestigation(initial.changeMap(), initial.reviewedFiles(), ImmutableList.of("ev-1", "ev-2"), initial.candidates(),
                ImmutableList.of(), ImmutableList.of(new ReviewEvidenceAnswer(1, "Read all callers", ImmutableList.of("ev-2"), Optional.of("Integration fixture unavailable"))));
        ReviewRun run = newRun("run-loop", review.id())
                .replaceCurrentStep(ReviewStep.initial().withInvestigation(initial), NOW)
                .withStep(new ReviewStep(2, ReviewStep.Kind.VALIDATE, false, Optional.empty(), Optional.of(validation)), NOW)
                .withStep(new ReviewStep(3, ReviewStep.Kind.COLLECT_EVIDENCE, true, Optional.of(response), Optional.empty()), NOW)
                .withInvestigation(response, NOW)
                .withStep(new ReviewStep(4, ReviewStep.Kind.VALIDATE, false, Optional.empty(), Optional.empty()), NOW)
                .withCompletionRepairUsed(NOW);
        store.insertReview(review);
        store.insertRun(run);
        entityManager.clear();

        ReviewRun restored = store.findRun(run.id()).orElseThrow();
        assertThat(restored).isEqualTo(run);
        assertThat(restored.steps().get(1).validation()).contains(validation);
        assertThat(restored.steps().get(2).investigation()).contains(response);
        assertThat(restored.steps().get(2).key(run.id())).isEqualTo("review-step:run-loop:3:repair");
    }

    @Test
    void retainedReportsRemainReadableWithoutInventingValidationEvidence()
    {
        AgentReview review = newReview("review-retained", "workspace-a", "bytequay/sample", 7, "task-retained", NOW);
        store.insertReview(review);
        store.insertRun(newRun("run-retained", review.id()).ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW));
        String investigation = """
                {"changeMap":"Caller lifetime","reviewedFiles":[],"contextEvidenceRefs":[],"candidates":["Potential leak"],
                 "validations":[{"candidate":1,"conclusion":"Caller does not close the resource","findingId":"finding-1"}]}
                """;
        entityManager.createNativeQuery("UPDATE agent_review_run SET steps_json = NULL, investigation_json = :investigation WHERE id = :id")
                .setParameter("investigation", investigation).setParameter("id", "run-retained").executeUpdate();
        entityManager.clear();

        ReviewRun retained = store.findRun("run-retained").orElseThrow();
        assertThat(retained.steps()).isEmpty();
        assertThat(retained.state()).isEqualTo(ReviewRun.State.ACCEPTED);
        ReviewCandidateValidation candidate = retained.investigation().orElseThrow().validations().getFirst();
        assertThat(candidate.conclusion()).isEqualTo("Caller does not close the resource");
        assertThat(candidate.findingId()).contains("finding-1");
        assertThat(candidate.findingRevision()).isEmpty();
        assertThat(candidate.evidenceRefs()).isEmpty();
        assertThat(candidate.outcome()).isEqualTo(ReviewCandidateValidation.Outcome.UNRESOLVED);
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

    private static ReviewContextSnapshot contextSnapshot()
    {
        ContextSelectedEntry included = new ContextSelectedEntry("knowledge:review", 3, "Review publication", Optional.of("Increment the counter [source-code]"),
                ImmutableMap.of("source-code", "ev-1"), ImmutableList.of("path:backend/app"),
                ContextSelectedEntry.Applicability.MATCHED, ContextSelectedEntry.Inclusion.INCLUDED);
        ContextSelectedEntry deferred = new ContextSelectedEntry("lesson:old-version", 2, "Older release", Optional.empty(), ImmutableMap.of(),
                ImmutableList.of("term:review"), ContextSelectedEntry.Applicability.VERSION_MISMATCH, ContextSelectedEntry.Inclusion.DEFERRED);
        return new ReviewContextSnapshot("context-1", true, 3, 42, Optional.empty(), Optional.of(ContextPacket.create("Increment the counter [source-code]")),
                ImmutableList.of(included, deferred),
                ImmutableList.of(new ContextRelatedItem("ev-1", ImmutableList.of("path:backend/app"), Optional.of(false))),
                ImmutableList.of(
                        ContextInputFingerprint.create("project", ImmutableMap.of("git:pom.xml", "100644 blob " + BASE_SHA, "git:README.md", "ABSENT"), ImmutableList.of()),
                        ContextInputFingerprint.create("path:backend/app/", ImmutableMap.of("scope:configuration", "sha256:scope"), ImmutableList.of("Tree read unavailable"))),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of("Repository baseline unavailable"));
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
                Optional.empty(),
                ImmutableList.of(ReviewStep.initial()),
                false,
                ImmutableMap.of("review_context:ev-1", new ReviewOperation("sha256:11ab", "read")),
                ImmutableMap.of("ev-1", CONTEXT_EVIDENCE),
                Optional.empty(),
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
                findingId.isEmpty() ? Optional.of("run-1:purpose") : Optional.empty(),
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
