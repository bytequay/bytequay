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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end round-trip of the four review-panel tables against the
 * real Flyway-migrated SQLite schema. Catches JPA-mapping drift,
 * FK-cascade misconfig, and the mentions/refs JSON serialisation
 * before any service-layer code is layered on top.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteReviewStore
{
    @Autowired
    private ReviewStore reviews;
    @Autowired
    private ThreadStore threads;

    @Test
    void roundTripsAReviewPassWithItsPanelTranscriptAndFindings()
    {
        Thread reviewThread = newReviewThread();
        threads.saveThread(reviewThread);

        ReviewPass pass = newPass(reviewThread.id(), "acme/widget", 42);
        reviews.savePass(pass);
        ReviewPass loaded = reviews.findPassById(pass.id()).orElseThrow();
        assertThat(loaded.threadId()).isEqualTo(reviewThread.id());
        assertThat(loaded.repoFullName()).isEqualTo("acme/widget");
        assertThat(loaded.prNumber()).isEqualTo(42);
        assertThat(loaded.phase()).isEqualTo(ReviewPhase.INDEPENDENT);
        assertThat(loaded.roundCap()).isEqualTo(3);
        assertThat(loaded.costCapMilli()).isEqualTo(500L);
        // verdict is null until the panel decides — must round-trip
        // as null, not as some default enum value.
        assertThat(loaded.verdict()).isNull();

        ReviewParticipant moderator = newParticipant(
                pass.id(), ReviewParticipantKind.LEAD, null, "Moderator", null);
        ReviewParticipant reviewer = newParticipant(
                pass.id(), ReviewParticipantKind.REVIEWER, "cred-claude", "Claude", "claude-sonnet-4.6");
        reviews.saveParticipant(moderator);
        reviews.saveParticipant(reviewer);
        List<ReviewParticipant> panel = reviews.listParticipantsForPass(pass.id());
        assertThat(panel).extracting(ReviewParticipant::personaLabel)
                .containsExactly("Moderator", "Claude");
        assertThat(panel.get(1).credentialId()).isEqualTo("cred-claude");
        assertThat(panel.get(1).model()).isEqualTo("claude-sonnet-4.6");

        // mentions + refs are stored as JSON arrays; assert they
        // survive serialise → load → deserialise verbatim.
        ReviewMessage msg = new ReviewMessage(
                UUID.randomUUID().toString(),
                pass.id(),
                reviewer.id(),
                ReviewPhase.INDEPENDENT,
                /* round */ 0,
                "## Initial review\n- Found a stale null check in foo.ts.",
                /* mentions */ List.of(moderator.id()),
                /* refs */ List.of(),
                /* costUsdMilli */ 12L,
                Instant.parse("2026-05-22T12:01:00Z"));
        reviews.saveMessage(msg);
        List<ReviewMessage> transcript = reviews.listMessagesForPass(pass.id());
        assertThat(transcript).hasSize(1);
        assertThat(transcript.get(0).mentions()).containsExactly(moderator.id());
        assertThat(transcript.get(0).refs()).isEmpty();
        assertThat(transcript.get(0).body()).contains("stale null check");

        ReviewFinding finding = new ReviewFinding(
                UUID.randomUUID().toString(),
                pass.id(),
                "src/foo.ts",
                /* line */ 42,
                ReviewFindingSeverity.BLOCKER,
                ReviewFindingStatus.AGREED,
                "Null check is reachable on the happy path.",
                /* resolution */ null,
                /* postedCommentId */ null,
                Instant.parse("2026-05-22T12:02:00Z"));
        reviews.saveFinding(finding);
        List<ReviewFinding> findings = reviews.listFindingsForPass(pass.id());
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(ReviewFindingSeverity.BLOCKER);
        assertThat(findings.get(0).status()).isEqualTo(ReviewFindingStatus.AGREED);
    }

    @Test
    void deletingAPassCascadesItsParticipantsMessagesAndFindings()
    {
        Thread reviewThread = newReviewThread();
        threads.saveThread(reviewThread);
        ReviewPass pass = newPass(reviewThread.id(), "acme/widget", 7);
        reviews.savePass(pass);
        ReviewParticipant moderator = newParticipant(
                pass.id(), ReviewParticipantKind.LEAD, null, "Moderator", null);
        reviews.saveParticipant(moderator);
        reviews.saveMessage(message(pass.id(), moderator.id()));
        reviews.saveFinding(finding(pass.id()));

        reviews.deletePass(pass.id());

        // Pass-scoped FK ON DELETE CASCADE clears the children — a
        // future re-run on the same thread shouldn't drag the prior
        // pass's transcript along.
        assertThat(reviews.findPassById(pass.id())).isEmpty();
        assertThat(reviews.listParticipantsForPass(pass.id())).isEmpty();
        assertThat(reviews.listMessagesForPass(pass.id())).isEmpty();
        assertThat(reviews.listFindingsForPass(pass.id())).isEmpty();
    }

    @Test
    void deletingTheThreadCascadesEveryReviewPassUnderIt()
    {
        Thread reviewThread = newReviewThread();
        threads.saveThread(reviewThread);
        ReviewPass first = newPass(reviewThread.id(), "acme/widget", 11);
        ReviewPass second = newPass(reviewThread.id(), "acme/widget", 11);
        reviews.savePass(first);
        reviews.savePass(second);

        threads.deleteThread(reviewThread.id());

        // The thread FK cascades through review_passes and on into
        // their participants/messages/findings — so deleting the
        // thread really does drop the whole panel transcript.
        assertThat(reviews.findPassById(first.id())).isEmpty();
        assertThat(reviews.findPassById(second.id())).isEmpty();
        assertThat(reviews.listPassesByThread(reviewThread.id())).isEmpty();
    }

    @Test
    void listPassesForPrSortsByCreatedAtDesc()
    {
        Thread t1 = newReviewThread();
        Thread t2 = newReviewThread();
        threads.saveThread(t1);
        threads.saveThread(t2);
        Instant base = Instant.parse("2026-05-22T12:00:00Z");
        ReviewPass older = passAt(t1.id(), "acme/widget", 99, base);
        ReviewPass newer = passAt(t2.id(), "acme/widget", 99, base.plusSeconds(60));
        reviews.savePass(older);
        reviews.savePass(newer);

        List<ReviewPass> passes = reviews.listPassesForPr("acme/widget", 99);

        // Newest first — the PR detail page surfaces the freshest
        // review pass on top.
        assertThat(passes).extracting(ReviewPass::id).containsExactly(newer.id(), older.id());
    }

    private static Thread newReviewThread()
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.LOGIC_LOOP,
                "claude-code",
                /* agentSessionId */ null,
                "Review PR #42",
                ThreadStatus.RUNNING,
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.REVIEW,
                "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
    }

    private static ReviewPass newPass(String threadId, String repoFullName, int prNumber)
    {
        return passAt(threadId, repoFullName, prNumber,
                Instant.parse("2026-05-22T12:00:00Z"));
    }

    private static ReviewPass passAt(
            String threadId, String repoFullName, int prNumber, Instant createdAt)
    {
        return new ReviewPass(
                UUID.randomUUID().toString(),
                threadId,
                repoFullName,
                prNumber,
                /* headSha */ "abc123",
                ReviewPhase.INDEPENDENT,
                /* round */ 0,
                /* roundCap */ 3,
                /* costCapMilli */ 500L,
                /* costUsdMilli */ 0L,
                /* verdict */ null,
                createdAt,
                /* endedAt */ null);
    }

    private static ReviewParticipant newParticipant(
            String passId,
            ReviewParticipantKind kind,
            String credentialId,
            String personaLabel,
            String model)
    {
        return new ReviewParticipant(
                UUID.randomUUID().toString(),
                passId,
                kind,
                credentialId,
                personaLabel,
                model,
                /* color */ null,
                Instant.parse("2026-05-22T12:00:00Z"));
    }

    private static ReviewMessage message(String passId, String participantId)
    {
        return new ReviewMessage(
                UUID.randomUUID().toString(),
                passId,
                participantId,
                ReviewPhase.KICKOFF,
                /* round */ 0,
                "Panel announced.",
                /* mentions */ List.of(),
                /* refs */ List.of(),
                /* costUsdMilli */ 0L,
                Instant.parse("2026-05-22T12:00:00Z"));
    }

    private static ReviewFinding finding(String passId)
    {
        return new ReviewFinding(
                UUID.randomUUID().toString(),
                passId,
                /* path */ "src/x.ts",
                /* line */ 1,
                ReviewFindingSeverity.NIT,
                ReviewFindingStatus.AGREED,
                "Trailing whitespace.",
                /* resolution */ null,
                /* postedCommentId */ null,
                Instant.parse("2026-05-22T12:00:00Z"));
    }
}
