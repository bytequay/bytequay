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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.domain.ReviewVerdict;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.web.PatResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewPassService
{
    private ThreadStore threadStore;
    private ReviewStore reviewStore;
    private PullRequestRepository pullRequests;
    private PatResolver patResolver;
    private LlmReviewerRegistry registry;
    private LlmReviewer reviewer;
    private ReviewPassService service;
    private RecordingReviewStore recording;

    @BeforeEach
    void setUp()
    {
        threadStore = mock(ThreadStore.class);
        pullRequests = mock(PullRequestRepository.class);
        patResolver = mock(PatResolver.class);
        registry = mock(LlmReviewerRegistry.class);
        reviewer = mock(LlmReviewer.class);
        recording = new RecordingReviewStore();
        reviewStore = recording;

        when(registry.active()).thenReturn(reviewer);
        when(reviewer.providerId()).thenReturn("claude");
        when(reviewer.displayName()).thenReturn("Claude (Anthropic)");
        when(reviewer.isConfigured()).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.fetchPrDetail(eq("ghp_secret"), any(PullRequestRef.class)))
                .thenReturn(rawDetail());
        when(pullRequests.fetchPrDiff(eq("ghp_secret"), any(PullRequestRef.class)))
                .thenReturn("diff --git a/x b/x\n");

        service = new ReviewPassService(
                threadStore, reviewStore, pullRequests, patResolver, registry);
    }

    @Test
    void startReviewOnPrPersistsTheFullPassAndReturnsDetail()
    {
        ReviewOutput output = new ReviewOutput(
                "Mostly fine — one nit on the helper.",
                List.of(
                        new ReviewOutput.LineComment("src/foo.ts", 12, "Inline the helper.", "nit"),
                        new ReviewOutput.LineComment("src/bar.ts", 0, "Whole-file note.", "question")),
                "claude", "claude-sonnet-4.6");
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(output);

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        // 1. Thread saved exactly once, flow = REVIEW.
        ArgumentCaptor<Thread> threadCaptor = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(threadCaptor.capture());
        assertThat(threadCaptor.getValue().flow()).isEqualTo(ThreadFlow.REVIEW);
        assertThat(threadCaptor.getValue().title()).contains("acme/widget#42");

        // 2. Pass walks KICKOFF → INDEPENDENT → TERMINATE in order.
        //    The savePass calls capture the intermediate state.
        List<ReviewPhase> phaseHistory = recording.passHistory.stream()
                .map(ReviewPass::phase)
                .toList();
        assertThat(phaseHistory).containsExactly(
                ReviewPhase.KICKOFF, ReviewPhase.INDEPENDENT, ReviewPhase.TERMINATE);

        // 3. Three participants seated: Moderator, Reviewer, Human.
        List<ReviewParticipantKind> participantKinds = recording.participants.stream()
                .map(ReviewParticipant::kind)
                .toList();
        assertThat(participantKinds).containsExactly(
                ReviewParticipantKind.MODERATOR,
                ReviewParticipantKind.REVIEWER,
                ReviewParticipantKind.HUMAN);
        ReviewParticipant reviewerSeat = recording.participants.get(1);
        assertThat(reviewerSeat.credentialId()).isEqualTo("claude");
        assertThat(reviewerSeat.personaLabel()).isEqualTo("Claude (Anthropic)");

        // 4. Two messages — moderator kickoff + reviewer summary.
        assertThat(recording.messages).hasSize(2);
        assertThat(recording.messages.get(0).phase()).isEqualTo(ReviewPhase.KICKOFF);
        assertThat(recording.messages.get(0).participantId()).isEqualTo(recording.participants.get(0).id());
        assertThat(recording.messages.get(1).phase()).isEqualTo(ReviewPhase.INDEPENDENT);
        assertThat(recording.messages.get(1).body()).isEqualTo(output.summary());

        // 5. One finding per LineComment, severities mapped, all AGREED.
        assertThat(recording.findings).hasSize(2);
        assertThat(recording.findings).extracting(ReviewFinding::severity).containsExactly(
                ReviewFindingSeverity.NIT, ReviewFindingSeverity.QUESTION);
        assertThat(recording.findings).allMatch(f -> f.status() == ReviewFindingStatus.AGREED);
        // line 0 from the LineComment normalises to null on the finding
        // (a "whole-file" comment) so the publish gate doesn't anchor
        // at a nonexistent line.
        assertThat(recording.findings.get(1).line()).isNull();

        // 6. Suggested verdict — no blocker, but at least one finding
        //    → COMMENT.
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.TERMINATE);
        assertThat(detail.pass().verdict()).isEqualTo(ReviewVerdict.COMMENT);
        assertThat(detail.findings()).hasSize(2);
        assertThat(detail.participants()).hasSize(3);
    }

    @Test
    void blockerSeverityFlipsTheSuggestedVerdictToRequestChanges()
    {
        ReviewOutput output = new ReviewOutput(
                "Found a real problem.",
                List.of(
                        new ReviewOutput.LineComment("src/x.ts", 1, "Null deref.", "blocker"),
                        new ReviewOutput.LineComment("src/y.ts", 2, "Style.", "nit")),
                "claude", "claude-sonnet-4.6");
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(output);

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 7);

        assertThat(detail.pass().verdict()).isEqualTo(ReviewVerdict.REQUEST_CHANGES);
    }

    @Test
    void emptyFindingsListSuggestsApprove()
    {
        ReviewOutput output = new ReviewOutput(
                "Looks good to me.", List.of(),
                "claude", "claude-sonnet-4.6");
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(output);

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 8);

        assertThat(detail.pass().verdict()).isEqualTo(ReviewVerdict.APPROVE);
        assertThat(detail.findings()).isEmpty();
    }

    @Test
    void reviewerExceptionTerminatesThePassAndSurfacesA502()
    {
        when(reviewer.review(any(ReviewRequest.class)))
                .thenThrow(new RuntimeException("Anthropic returned 529"));

        assertThatThrownBy(() -> service.startReviewOnPr("acme/widget", 99))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("LLM reviewer call failed: Anthropic returned 529");

        // Pass is persisted terminated so the UI shows "review failed"
        // rather than "review running forever".
        ReviewPhase terminalPhase = recording.passHistory.get(recording.passHistory.size() - 1).phase();
        assertThat(terminalPhase).isEqualTo(ReviewPhase.TERMINATE);
    }

    @Test
    void refusesWith412WhenTheActiveReviewerHasNoApiKey()
    {
        when(reviewer.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.startReviewOnPr("acme/widget", 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no API key configured");

        verify(threadStore, never()).saveThread(any());
        assertThat(recording.passHistory).isEmpty();
    }

    @Test
    void refusesWith400WhenPrNumberIsZeroOrNegative()
    {
        assertThatThrownBy(() -> service.startReviewOnPr("acme/widget", 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("prNumber must be a positive integer");
        assertThatThrownBy(() -> service.startReviewOnPr("acme/widget", -1))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void publishPassPostsTheSelectedFindingsAsAGitHubReviewAndTransitionsThePass()
    {
        // Set up a terminated pass with two findings — one line-anchored,
        // one whole-PR — so we can verify routing: inline comments for
        // file:line, body fold-in for whole-PR.
        ReviewOutput output = new ReviewOutput(
                "Mostly fine — one nit and one whole-PR note.",
                List.of(
                        new ReviewOutput.LineComment("src/foo.ts", 12, "Inline.", "nit"),
                        new ReviewOutput.LineComment(null, 0, "Whole PR.", "question")),
                "claude", "claude-sonnet-4.6");
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(output);
        ReviewPassDetail kicked = service.startReviewOnPr("acme/widget", 42);
        // Include both findings; verdict = COMMENT.
        List<String> findingIds = kicked.findings().stream()
                .map(ReviewFinding::id)
                .toList();

        ReviewPassDetail published = service.publishPass(
                kicked.pass().id(), ReviewVerdict.COMMENT, findingIds);

        // 1. Single GitHub createReview call composed correctly: COMMENT
        //    event, body contains summary + whole-PR fold-in, inline
        //    comment for the line-anchored finding.
        ArgumentCaptor<CreateReviewCommand> commandCaptor =
                ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp_secret"), any(PullRequestRef.class), commandCaptor.capture());
        CreateReviewCommand command = commandCaptor.getValue();
        assertThat(command.event()).isEqualTo("COMMENT");
        assertThat(command.body()).isPresent();
        assertThat(command.body().get()).contains("Mostly fine");
        assertThat(command.body().get()).contains("Whole-PR notes");
        assertThat(command.body().get()).contains("Whole PR");
        assertThat(command.comments()).hasSize(1);
        CreateReviewCommand.ReviewLineComment inline = command.comments().get(0);
        assertThat(inline.path()).isEqualTo("src/foo.ts");
        assertThat(inline.line()).contains(12);
        assertThat(inline.body()).contains("Inline");

        // 2. Pass is PUBLISHED with the chosen verdict + endedAt.
        assertThat(published.pass().phase()).isEqualTo(ReviewPhase.PUBLISHED);
        assertThat(published.pass().verdict()).isEqualTo(ReviewVerdict.COMMENT);
        assertThat(published.pass().endedAt()).isNotNull();

        // 3. Both selected findings flipped to POSTED.
        assertThat(published.findings())
                .allMatch(f -> f.status() == ReviewFindingStatus.POSTED);
    }

    @Test
    void publishPassDropsUnselectedFindingsFromThePayloadButLeavesThemAgreedOnTheRow()
    {
        ReviewOutput output = new ReviewOutput(
                "Two nits.",
                List.of(
                        new ReviewOutput.LineComment("src/a.ts", 1, "Keep.", "nit"),
                        new ReviewOutput.LineComment("src/b.ts", 2, "Drop.", "nit")),
                "claude", "claude-sonnet-4.6");
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(output);
        ReviewPassDetail kicked = service.startReviewOnPr("acme/widget", 7);
        String keepId = kicked.findings().get(0).id();
        String dropId = kicked.findings().get(1).id();

        ReviewPassDetail published = service.publishPass(
                kicked.pass().id(), ReviewVerdict.COMMENT, List.of(keepId));

        ArgumentCaptor<CreateReviewCommand> commandCaptor =
                ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp_secret"), any(PullRequestRef.class), commandCaptor.capture());
        assertThat(commandCaptor.getValue().comments()).hasSize(1);
        assertThat(commandCaptor.getValue().comments().get(0).body()).contains("Keep");

        // The dropped finding stays on the row at AGREED (not POSTED),
        // so the user can re-publish later if they change their mind.
        ReviewFinding keep = published.findings().stream()
                .filter(f -> f.id().equals(keepId)).findFirst().orElseThrow();
        ReviewFinding drop = published.findings().stream()
                .filter(f -> f.id().equals(dropId)).findFirst().orElseThrow();
        assertThat(keep.status()).isEqualTo(ReviewFindingStatus.POSTED);
        assertThat(drop.status()).isEqualTo(ReviewFindingStatus.AGREED);
    }

    @Test
    void publishPassRefusesWithA409WhenThePassIsAlreadyPublished()
    {
        ReviewOutput output = new ReviewOutput(
                "Done.", List.of(), "claude", "claude-sonnet-4.6");
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(output);
        ReviewPassDetail kicked = service.startReviewOnPr("acme/widget", 11);
        service.publishPass(kicked.pass().id(), ReviewVerdict.APPROVE, List.of());

        // Second publish on the same pass — must be refused so we
        // don't post the same review twice to GitHub.
        assertThatThrownBy(() -> service.publishPass(
                kicked.pass().id(), ReviewVerdict.APPROVE, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already published");
    }

    @Test
    void publishPassSurfacesA502WhenGitHubRejectsTheReview()
    {
        ReviewOutput output = new ReviewOutput(
                "Looks reasonable.", List.of(), "claude", "claude-sonnet-4.6");
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(output);
        ReviewPassDetail kicked = service.startReviewOnPr("acme/widget", 13);
        doThrow(new RuntimeException("422 — head_sha out of date"))
                .when(pullRequests).createReview(
                        anyString(), any(PullRequestRef.class), any(CreateReviewCommand.class));

        assertThatThrownBy(() -> service.publishPass(
                kicked.pass().id(), ReviewVerdict.APPROVE, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GitHub rejected the review");

        // The pass stays at TERMINATE so the user can retry once the
        // upstream issue is fixed — it would be wrong to mark it
        // PUBLISHED when nothing actually landed on GitHub.
        ReviewPhase phase = recording.passes.get(kicked.pass().id()).phase();
        assertThat(phase).isEqualTo(ReviewPhase.TERMINATE);
    }

    private static PrRawDetail rawDetail()
    {
        return new PrRawDetail(
                /* body */ "Description.", List.of(),
                /* draft */ false, /* mergeable */ null, /* mergeableState */ null,
                /* additions */ 10, /* deletions */ 5, /* changedFiles */ 2,
                /* requestedReviewerCount */ 0, /* requestedReviewers */ List.of(),
                /* headSha */ "abc123", /* headRef */ "feature/x", /* headRepo */ "acme/widget",
                /* baseRef */ "main", /* baseRepo */ "acme/widget");
    }

    /** In-memory ReviewStore that records every save so the test can
     *  assert on the order of pass-phase transitions and the exact
     *  participant / message / finding rows that get persisted. */
    private static final class RecordingReviewStore
            implements ReviewStore
    {
        final List<ReviewPass> passHistory = new ArrayList<>();
        final Map<String, ReviewPass> passes = new HashMap<>();
        final List<ReviewParticipant> participants = new ArrayList<>();
        final List<ReviewMessage> messages = new ArrayList<>();
        final List<ReviewFinding> findings = new ArrayList<>();

        @Override
        public void savePass(ReviewPass pass)
        {
            passHistory.add(pass);
            passes.put(pass.id(), pass);
        }

        @Override
        public Optional<ReviewPass> findPassById(String id)
        {
            return Optional.ofNullable(passes.get(id));
        }

        @Override
        public List<ReviewPass> listPassesByThread(String threadId)
        {
            return passes.values().stream()
                    .filter(p -> p.threadId().equals(threadId))
                    .toList();
        }

        @Override
        public List<ReviewPass> listPassesForPr(String repoFullName, int prNumber)
        {
            return passes.values().stream()
                    .filter(p -> p.repoFullName().equals(repoFullName) && p.prNumber() == prNumber)
                    .toList();
        }

        @Override public void deletePass(String id) { passes.remove(id); }

        @Override
        public void saveParticipant(ReviewParticipant participant)
        {
            // Upsert — match production SqliteReviewStore.saveParticipant
            // semantics so tests that update an existing row don't see
            // duplicate entries.
            participants.removeIf(p -> p.id().equals(participant.id()));
            participants.add(participant);
        }

        @Override
        public Optional<ReviewParticipant> findParticipantById(String id)
        {
            return participants.stream().filter(p -> p.id().equals(id)).findFirst();
        }

        @Override
        public List<ReviewParticipant> listParticipantsForPass(String reviewPassId)
        {
            return participants.stream()
                    .filter(p -> p.reviewPassId().equals(reviewPassId))
                    .toList();
        }

        @Override
        public void saveMessage(ReviewMessage message)
        {
            messages.removeIf(m -> m.id().equals(message.id()));
            messages.add(message);
        }

        @Override
        public Optional<ReviewMessage> findMessageById(String id)
        {
            return messages.stream().filter(m -> m.id().equals(id)).findFirst();
        }

        @Override
        public List<ReviewMessage> listMessagesForPass(String reviewPassId)
        {
            return messages.stream()
                    .filter(m -> m.reviewPassId().equals(reviewPassId))
                    .toList();
        }

        @Override
        public void saveFinding(ReviewFinding finding)
        {
            findings.removeIf(f -> f.id().equals(finding.id()));
            findings.add(finding);
        }

        @Override
        public Optional<ReviewFinding> findFindingById(String id)
        {
            return findings.stream().filter(f -> f.id().equals(id)).findFirst();
        }

        @Override
        public List<ReviewFinding> listFindingsForPass(String reviewPassId)
        {
            return findings.stream()
                    .filter(f -> f.reviewPassId().equals(reviewPassId))
                    .toList();
        }
    }
}
