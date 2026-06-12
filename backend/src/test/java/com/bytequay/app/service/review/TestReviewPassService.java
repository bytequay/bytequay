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
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.ReviewerPersonaStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.ai.LlmCompletion;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.service.credentials.PatResolver;
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
import static org.mockito.Mockito.times;
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
    private AppSettingsStore appSettings;
    private ReviewerPersonaStore personas;
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
        appSettings = mock(AppSettingsStore.class);
        personas = mock(ReviewerPersonaStore.class);
        recording = new RecordingReviewStore();
        reviewStore = recording;

        when(registry.all()).thenReturn(List.of(reviewer));
        when(reviewer.providerId()).thenReturn("claude");
        when(reviewer.displayName()).thenReturn("Claude (Anthropic)");
        when(reviewer.isConfigured()).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.fetchPrDetail(eq("ghp_secret"), any(PullRequestRef.class)))
                .thenReturn(rawDetail());
        when(pullRequests.fetchPrDiff(eq("ghp_secret"), any(PullRequestRef.class)))
                .thenReturn("diff --git a/x b/x\n");
        when(appSettings.get(anyString())).thenReturn(Optional.empty());

        // Same-thread executor: the async 3-arg overload runs its body
        // inline so tests stay deterministic.
        service = new ReviewPassService(
                threadStore, reviewStore, pullRequests, patResolver, registry, appSettings, personas,
                Runnable::run);
    }

    @Test
    void startReviewWithOptionsSeatsThePassThenRunsTheBodyOnTheExecutor()
    {
        ReviewOutput output = new ReviewOutput(
                "Looks good.",
                List.of(new ReviewOutput.LineComment("src/foo.ts", 3, "Tidy this.", "nit")),
                "claude", "claude-sonnet-4.6");
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(output);

        ReviewPassDetail seated = service.startReviewOnPr(
                "acme/widget", 42, ReviewPassService.StartOptions.DEFAULT);

        // The pass is seated with a thread id, and the body — dispatched
        // to the (same-thread, in this test) review executor — persisted
        // the reviewer's finding. A real executor would run it off-thread
        // so the caller returns before the model fan-out completes.
        assertThat(seated.pass().threadId()).isNotBlank();
        assertThat(seated.findings()).hasSize(1);
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
                .hasMessageContaining("API key configured");

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

    // ── Multi-reviewer orchestration test helpers ───────────────────

    private static final String CROSS_REVIEW_ENVELOPE =
            "{\"agree\":[],\"dispute\":[],\"open_questions\":[]}";
    /** Default debate stance: reviewers stay unconvinced, so disputed
     *  findings never converge and the pass parks at arbitration. */
    private static final String DEBATE_HOLD =
            "{\"stance\":\"hold\",\"comment\":\"not convinced\"}";

    /** Stub each panel reviewer's structured complete() call with a
     *  benign cross-review envelope, the given consensus JSON, and a
     *  "hold" debate stance — distinguished by each prompt's marker. */
    private void stubPanelOrchestration(String consensusJson, LlmReviewer... panel)
    {
        stubPanelOrchestration(consensusJson, DEBATE_HOLD, panel);
    }

    /** As above but with a caller-chosen debate-turn JSON, so a test can
     *  drive convergence ("agree") or a stall ("hold"). */
    private void stubPanelOrchestration(String consensusJson, String debateTurnJson, LlmReviewer... panel)
    {
        for (LlmReviewer r : panel) {
            when(r.complete(anyString(), anyString())).thenAnswer(inv -> {
                String user = inv.getArgument(1);
                String json;
                if (user.contains("Produce the resolved finding set")) {
                    json = consensusJson;
                }
                else if (user.contains("Respond directly")) {
                    json = debateTurnJson;
                }
                else {
                    json = CROSS_REVIEW_ENVELOPE;
                }
                return new LlmCompletion(json, 120, 80, "claude-sonnet-4.6");
            });
        }
    }

    private static String consensus(String... findings)
    {
        return "{\"findings\":[" + String.join(",", findings) + "]}";
    }

    private static String finding(
            String path, Integer line, String severity, String body, String status, String reporter)
    {
        return String.format(
                "{\"path\":%s,\"line\":%s,\"severity\":\"%s\",\"body\":\"%s\",\"status\":\"%s\",\"reporter\":\"%s\"}",
                path == null ? "null" : "\"" + path + "\"",
                line == null ? "null" : line.toString(),
                severity, body, status, reporter);
    }

    // ── Phase 2: multi-reviewer panel ───────────────────────────────

    @Test
    void multiReviewerPanelRunsBothInParallelAndDedupsFindings()
    {
        // Two configured reviewers; both flag the same blocker at
        // foo.ts:12 (→ AGREED with the more severe reading), and each
        // flags a distinct nit at a unique anchor (→ DISPUTED, one
        // per reporter).
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));

        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Found a real issue.",
                List.of(
                        new ReviewOutput.LineComment("src/foo.ts", 12, "Null deref.", "blocker"),
                        new ReviewOutput.LineComment("src/bar.ts", 3, "Claude-only nit.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Same problem, different wording.",
                List.of(
                        new ReviewOutput.LineComment("src/foo.ts", 12, "Crashes on null.", "major"),
                        new ReviewOutput.LineComment("src/baz.ts", 4, "GPT-only nit.", "nit")),
                "openai", "gpt-5"));
        // The lead consensus collapses the shared foo.ts:12 finding to
        // one AGREED row (blocker reading) and keeps each solo nit as a
        // DISPUTED row.
        stubPanelOrchestration(consensus(
                finding("src/foo.ts", 12, "blocker", "Null deref.", "agreed", "Claude"),
                finding("src/bar.ts", 3, "nit", "Claude-only nit.", "disputed", "Claude"),
                finding("src/baz.ts", 4, "nit", "GPT-only nit.", "disputed", "GPT-5")),
                claude, openai);

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        // 1. Both reviewers ran — exactly two INDEPENDENT messages.
        long independentMessages = recording.messages.stream()
                .filter(m -> m.phase() == ReviewPhase.INDEPENDENT)
                .count();
        assertThat(independentMessages).isEqualTo(2);

        // 2. Phase machine walked through CROSS_REVIEW. This test
        //    produces disputed findings (each reviewer's solo nit) so
        //    the pass parks at ARBITRATE for the ballot rather than
        //    going straight to TERMINATE.
        List<ReviewPhase> phaseHistory = recording.passHistory.stream()
                .map(ReviewPass::phase).toList();
        assertThat(phaseHistory).containsSubsequence(
                ReviewPhase.KICKOFF,
                ReviewPhase.INDEPENDENT,
                ReviewPhase.CROSS_REVIEW,
                ReviewPhase.ARBITRATE);
        // The lead emits a CONSENSUS message summarising the resolved
        // split (1 agreed, 2 disputed).
        boolean consensusMessage = recording.messages.stream()
                .anyMatch(m -> m.phase() == ReviewPhase.CONSENSUS
                        && m.body().contains("1 agreed")
                        && m.body().contains("2 disputed"));
        assertThat(consensusMessage).isTrue();

        // 3. Findings: one AGREED at foo.ts:12 (the BLOCKER wins), two
        //    DISPUTED rows (one from each reviewer for their unique
        //    anchors).
        List<ReviewFinding> findings = detail.findings();
        assertThat(findings).hasSize(3);
        ReviewFinding agreed = findings.stream()
                .filter(f -> f.status() == ReviewFindingStatus.AGREED)
                .findFirst().orElseThrow();
        assertThat(agreed.path()).isEqualTo("src/foo.ts");
        assertThat(agreed.line()).isEqualTo(12);
        // The blocker reading wins over the major reading.
        assertThat(agreed.severity()).isEqualTo(ReviewFindingSeverity.BLOCKER);

        List<ReviewFinding> disputed = findings.stream()
                .filter(f -> f.status() == ReviewFindingStatus.DISPUTED)
                .toList();
        assertThat(disputed).hasSize(2);
        // Reviewer attribution surfaces in the body so the publish UI
        // can render "who flagged this".
        assertThat(disputed).anySatisfy(f -> {
            assertThat(f.body()).contains("[Claude]");
            assertThat(f.body()).contains("Claude-only nit.");
        });
        assertThat(disputed).anySatisfy(f -> {
            assertThat(f.body()).contains("[GPT-5]");
            assertThat(f.body()).contains("GPT-only nit.");
        });

        // 4. Verdict suggestion: AGREED blocker → REQUEST_CHANGES.
        //    Phase parks at ARBITRATE for the ballot since two
        //    DISPUTED findings remain unresolved.
        assertThat(detail.pass().verdict()).isEqualTo(ReviewVerdict.REQUEST_CHANGES);
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.ARBITRATE);

        // 5. Three reviewer participants seated (two reviewers + the
        //    moderator + the human; assert reviewers specifically).
        long reviewerSeats = detail.participants().stream()
                .filter(p -> p.kind() == ReviewParticipantKind.REVIEWER)
                .count();
        assertThat(reviewerSeats).isEqualTo(2);
    }

    @Test
    void multiReviewerPanelParksAtArbitrateWhenDisputedFindingsRemain()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));

        // Two distinct nits at unique anchors → both DISPUTED, no
        // AGREED. The pass should park at ARBITRATE so the ballot
        // surfaces the contest to the user instead of silently
        // publishing one reviewer's call.
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Claude take.", List.of(
                        new ReviewOutput.LineComment("src/a.ts", 1, "Claude's pick.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "GPT take.", List.of(
                        new ReviewOutput.LineComment("src/b.ts", 2, "GPT's pick.", "nit")),
                "openai", "gpt-5"));
        stubPanelOrchestration(consensus(
                finding("src/a.ts", 1, "nit", "Claude's pick.", "disputed", "Claude"),
                finding("src/b.ts", 2, "nit", "GPT's pick.", "disputed", "GPT-5")),
                claude, openai);

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        // Disputed findings exist → pass parks at ARBITRATE, not
        // TERMINATE. endedAt stays null until the user resolves.
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.ARBITRATE);
        assertThat(detail.pass().endedAt()).isNull();
    }

    @Test
    void arbitrateFindingFlipsDisputedToArbitratedOrDroppedAndTerminatesWhenAllResolved()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));

        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Claude take.", List.of(
                        new ReviewOutput.LineComment("src/a.ts", 1, "Claude pick.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "GPT take.", List.of(
                        new ReviewOutput.LineComment("src/b.ts", 2, "GPT pick.", "nit")),
                "openai", "gpt-5"));
        stubPanelOrchestration(consensus(
                finding("src/a.ts", 1, "nit", "Claude pick.", "disputed", "Claude"),
                finding("src/b.ts", 2, "nit", "GPT pick.", "disputed", "GPT-5")),
                claude, openai);
        ReviewPassDetail parked = service.startReviewOnPr("acme/widget", 42);
        assertThat(parked.pass().phase()).isEqualTo(ReviewPhase.ARBITRATE);
        List<ReviewFinding> disputed = parked.findings().stream()
                .filter(f -> f.status() == ReviewFindingStatus.DISPUTED)
                .toList();
        assertThat(disputed).hasSize(2);
        String includeId = disputed.get(0).id();
        String dropId = disputed.get(1).id();

        // Include the first → ARBITRATED. Pass stays at ARBITRATE
        // because the second is still pending.
        ReviewPassDetail afterFirst = service.arbitrateFinding(parked.pass().id(), includeId, "include");
        assertThat(afterFirst.pass().phase()).isEqualTo(ReviewPhase.ARBITRATE);
        ReviewFinding includedNow = afterFirst.findings().stream()
                .filter(f -> f.id().equals(includeId)).findFirst().orElseThrow();
        assertThat(includedNow.status()).isEqualTo(ReviewFindingStatus.ARBITRATED);
        assertThat(includedNow.resolution()).isEqualTo("include");

        // Drop the second → DROPPED. No DISPUTED left → pass moves
        // to TERMINATE and endedAt stamps.
        ReviewPassDetail afterSecond = service.arbitrateFinding(parked.pass().id(), dropId, "drop");
        assertThat(afterSecond.pass().phase()).isEqualTo(ReviewPhase.TERMINATE);
        assertThat(afterSecond.pass().endedAt()).isNotNull();
        ReviewFinding droppedNow = afterSecond.findings().stream()
                .filter(f -> f.id().equals(dropId)).findFirst().orElseThrow();
        assertThat(droppedNow.status()).isEqualTo(ReviewFindingStatus.DROPPED);
    }

    @Test
    void arbitrateFindingRefusesWhenPassIsNotInArbitratePhase()
    {
        // Single-reviewer pass → no ARBITRATE phase. Arbitrating
        // anything on it must 409 so a stale frontend can't poke at
        // findings on a terminated pass.
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Done.", List.of(), "claude", "claude-sonnet-4.6"));
        ReviewPassDetail kicked = service.startReviewOnPr("acme/widget", 11);

        assertThatThrownBy(() -> service.arbitrateFinding(
                kicked.pass().id(), "nonexistent", "include"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not in ARBITRATE");
    }

    @Test
    void publishPassRefusesWhenPassIsAtArbitrate()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Claude.", List.of(
                        new ReviewOutput.LineComment("src/a.ts", 1, "C.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "GPT.", List.of(
                        new ReviewOutput.LineComment("src/b.ts", 2, "G.", "nit")),
                "openai", "gpt-5"));
        stubPanelOrchestration(consensus(
                finding("src/a.ts", 1, "nit", "C.", "disputed", "Claude"),
                finding("src/b.ts", 2, "nit", "G.", "disputed", "GPT-5")),
                claude, openai);
        ReviewPassDetail parked = service.startReviewOnPr("acme/widget", 9);
        assertThat(parked.pass().phase()).isEqualTo(ReviewPhase.ARBITRATE);

        assertThatThrownBy(() -> service.publishPass(
                parked.pass().id(), ReviewVerdict.COMMENT, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ARBITRATE");
    }

    @Test
    void debateConvergesDisputedFindingsToAgreedWhenReviewersReaffirm()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "C.", List.of(new ReviewOutput.LineComment("src/a.ts", 1, "A.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "G.", List.of(new ReviewOutput.LineComment("src/b.ts", 2, "B.", "nit")),
                "openai", "gpt-5"));
        // Both reviewers reaffirm every disputed finding, so each debate
        // converges after a single round.
        stubPanelOrchestration(
                consensus(
                        finding("src/a.ts", 1, "nit", "A.", "disputed", "Claude"),
                        finding("src/b.ts", 2, "nit", "B.", "disputed", "GPT-5")),
                "{\"stance\":\"agree\",\"comment\":\"valid\"}",
                claude, openai);

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        // Debate ran and both disputed findings collapsed to AGREED, so
        // the pass terminates with nothing left to arbitrate.
        assertThat(recording.passHistory).extracting(ReviewPass::phase)
                .contains(ReviewPhase.DEBATE);
        assertThat(detail.findings()).isNotEmpty();
        assertThat(detail.findings()).allSatisfy(f -> {
            assertThat(f.status()).isEqualTo(ReviewFindingStatus.AGREED);
            assertThat(f.debateStatus()).isEqualTo("converged");
        });
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.TERMINATE);
    }

    @Test
    void debateLeavesAFindingDisputedAndStalledWhenReviewersNeverAgree()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "C.", List.of(new ReviewOutput.LineComment("src/a.ts", 1, "A.", "major")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "G.", List.of(new ReviewOutput.LineComment("src/b.ts", 2, "B.", "nit")),
                "openai", "gpt-5"));
        // Reviewers hold (default stance) → no convergence; the debate
        // burns the full round cap and the finding stays disputed.
        stubPanelOrchestration(consensus(
                finding("src/a.ts", 1, "major", "A.", "disputed", "Claude")),
                claude, openai);

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        ReviewFinding disputed = detail.findings().stream()
                .filter(f -> f.status() == ReviewFindingStatus.DISPUTED)
                .findFirst().orElseThrow();
        assertThat(disputed.debateStatus()).isEqualTo("stalled_rounds");
        assertThat(disputed.debateRounds()).isEqualTo(3); // StartOptions.DEFAULT roundCap
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.ARBITRATE);
    }

    @Test
    void debateTurnsCarryTheFindingIdAndKeepContextBounded()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "C.", List.of(new ReviewOutput.LineComment("src/a.ts", 1, "Alpha finding.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "G.", List.of(new ReviewOutput.LineComment("src/b.ts", 2, "Beta finding.", "nit")),
                "openai", "gpt-5"));
        // Consensus keeps exactly one disputed finding; the other
        // independent finding's text must not leak into the debate.
        List<String> debatePrompts = new ArrayList<>();
        String consensusJson = consensus(
                finding("src/a.ts", 1, "nit", "Alpha finding.", "disputed", "Claude"));
        for (LlmReviewer r : List.of(claude, openai)) {
            when(r.complete(anyString(), anyString())).thenAnswer(inv -> {
                String user = inv.getArgument(1);
                if (user.contains("Produce the resolved finding set")) {
                    return new LlmCompletion(consensusJson, 1, 1, "claude-sonnet-4.6");
                }
                if (user.contains("Respond directly")) {
                    debatePrompts.add(user);
                    return new LlmCompletion(DEBATE_HOLD, 1, 1, "claude-sonnet-4.6");
                }
                return new LlmCompletion(CROSS_REVIEW_ENVELOPE, 1, 1, "claude-sonnet-4.6");
            });
        }

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);
        String disputedId = detail.findings().stream()
                .filter(f -> f.status() == ReviewFindingStatus.DISPUTED)
                .findFirst().orElseThrow().id();

        // Every debate prompt references the finding under debate ...
        assertThat(debatePrompts).isNotEmpty();
        assertThat(debatePrompts).allMatch(p -> p.contains(disputedId));
        // ... and none drags in the other finding's text.
        assertThat(debatePrompts).noneMatch(p -> p.contains("Beta finding."));
        // The debate_turn message payloads carry the finding id too.
        assertThat(recording.messages)
                .filteredOn(m -> "debate_turn".equals(m.payloadKind()))
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.payloadJson()).contains(disputedId));
    }

    @Test
    void perFindingDebateCostCapStopsTheRoundRobin()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "C.", List.of(new ReviewOutput.LineComment("src/a.ts", 1, "A.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "G.", List.of(new ReviewOutput.LineComment("src/b.ts", 2, "B.", "nit")),
                "openai", "gpt-5"));
        // A single debate turn reports a huge token bill (~$18), blowing
        // the per-finding $0.10 cap after the first turn.
        String consensusJson = consensus(
                finding("src/a.ts", 1, "nit", "A.", "disputed", "Claude"));
        for (LlmReviewer r : List.of(claude, openai)) {
            when(r.complete(anyString(), anyString())).thenAnswer(inv -> {
                String user = inv.getArgument(1);
                if (user.contains("Produce the resolved finding set")) {
                    return new LlmCompletion(consensusJson, 1, 1, "claude-sonnet-4.6");
                }
                if (user.contains("Respond directly")) {
                    return new LlmCompletion(DEBATE_HOLD, 1_000_000, 1_000_000, "claude-sonnet-4.6");
                }
                return new LlmCompletion(CROSS_REVIEW_ENVELOPE, 1, 1, "claude-sonnet-4.6");
            });
        }

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        ReviewFinding disputed = detail.findings().stream()
                .filter(f -> f.status() == ReviewFindingStatus.DISPUTED)
                .findFirst().orElseThrow();
        assertThat(disputed.debateStatus()).isEqualTo("stalled_cost");
    }

    @Test
    void multiReviewerPanelPicksCommentVerdictWhenOnlyDisputedFindingsExist()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));

        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Just nits.", List.of(
                        new ReviewOutput.LineComment("src/a.ts", 1, "Claude's nit.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Just nits.", List.of(
                        new ReviewOutput.LineComment("src/b.ts", 2, "GPT's nit.", "nit")),
                "openai", "gpt-5"));
        stubPanelOrchestration(consensus(
                finding("src/a.ts", 1, "nit", "Claude's nit.", "disputed", "Claude"),
                finding("src/b.ts", 2, "nit", "GPT's nit.", "disputed", "GPT-5")),
                claude, openai);

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        // No agreed findings, just two disputed nits — the suggested
        // verdict is COMMENT (we surface findings the user might want
        // to address) rather than escalating to REQUEST_CHANGES on a
        // single reviewer's unconfirmed call.
        assertThat(detail.findings()).allMatch(f -> f.status() == ReviewFindingStatus.DISPUTED);
        assertThat(detail.pass().verdict()).isEqualTo(ReviewVerdict.COMMENT);
    }

    @Test
    void crossReviewPersistsPerReviewerEnvelopesThenAConsensusMessage()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "C.", List.of(new ReviewOutput.LineComment("src/x.ts", 5, "Shared.", "major")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "G.", List.of(new ReviewOutput.LineComment("src/x.ts", 5, "Shared.", "blocker")),
                "openai", "gpt-5"));
        // Lead folds the shared finding into one AGREED row at the
        // blocker (severity-max) reading.
        stubPanelOrchestration(consensus(
                finding("src/x.ts", 5, "blocker", "Shared.", "agreed", "panel")),
                claude, openai);

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        // One structured CROSS_REVIEW envelope per reviewer.
        long crossReview = recording.messages.stream()
                .filter(m -> m.phase() == ReviewPhase.CROSS_REVIEW
                        && "cross_review".equals(m.payloadKind())
                        && m.payloadJson() != null)
                .count();
        assertThat(crossReview).isEqualTo(2);
        // Exactly one CONSENSUS message, tagged with the structured kind.
        long consensusMsgs = recording.messages.stream()
                .filter(m -> m.phase() == ReviewPhase.CONSENSUS
                        && "consensus".equals(m.payloadKind()))
                .count();
        assertThat(consensusMsgs).isEqualTo(1);
        // Single AGREED row at the severe reading → straight to TERMINATE.
        assertThat(detail.findings()).singleElement().satisfies(f -> {
            assertThat(f.status()).isEqualTo(ReviewFindingStatus.AGREED);
            assertThat(f.severity()).isEqualTo(ReviewFindingSeverity.BLOCKER);
        });
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.TERMINATE);
    }

    @Test
    void crossReviewSkipsAReviewerWhoseCallFailsButStillReachesConsensus()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "C.", List.of(new ReviewOutput.LineComment("src/a.ts", 1, "Claude's pick.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "G.", List.of(new ReviewOutput.LineComment("src/b.ts", 2, "GPT's pick.", "nit")),
                "openai", "gpt-5"));
        // openai's cross-review call fails → it abstains. claude is the
        // lead (first member) and runs both its cross-review + consensus.
        when(openai.complete(anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider down"));
        when(claude.complete(anyString(), anyString())).thenAnswer(inv -> {
            String user = inv.getArgument(1);
            String json = user.contains("Produce the resolved finding set")
                    ? consensus(
                            finding("src/a.ts", 1, "nit", "Claude's pick.", "disputed", "Claude"),
                            finding("src/b.ts", 2, "nit", "GPT's pick.", "disputed", "GPT-5"))
                    : CROSS_REVIEW_ENVELOPE;
            return new LlmCompletion(json, 50, 50, "claude-sonnet-4.6");
        });

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        // Only the lead's cross-review envelope persisted; the failed
        // reviewer left none.
        long crossReview = recording.messages.stream()
                .filter(m -> m.phase() == ReviewPhase.CROSS_REVIEW)
                .count();
        assertThat(crossReview).isEqualTo(1);
        // Consensus still ran and produced both disputed findings → the
        // pass parks at ARBITRATE rather than failing.
        assertThat(detail.findings()).hasSize(2);
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.ARBITRATE);
    }

    @Test
    void costCapStopsTheCrossReviewRoundAndEscalatesToArbitration()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "C.", List.of(new ReviewOutput.LineComment("src/a.ts", 1, "A.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "G.", List.of(new ReviewOutput.LineComment("src/b.ts", 2, "B.", "nit")),
                "openai", "gpt-5"));
        // Each cross-review call costs ~2 milli-USD, so a 1-milli cap
        // trips after the first reviewer and the consensus call is
        // skipped entirely.
        stubPanelOrchestration(consensus(
                finding("src/a.ts", 1, "nit", "A.", "agreed", "Claude")),
                claude, openai);

        ReviewPassService.StartOptions opts = new ReviewPassService.StartOptions(
                List.of(), 3, /* costCapMilli */ 1L, true);
        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42, opts);

        // A budget message landed; only one cross-review envelope ran;
        // no debate; every finding escalated to arbitration.
        assertThat(recording.messages).anySatisfy(m ->
                assertThat(m.body()).contains("Budget cap reached"));
        long crossReview = recording.messages.stream()
                .filter(m -> m.phase() == ReviewPhase.CROSS_REVIEW)
                .count();
        assertThat(crossReview).isEqualTo(1);
        long debate = recording.messages.stream()
                .filter(m -> m.phase() == ReviewPhase.DEBATE)
                .count();
        assertThat(debate).isZero();
        assertThat(detail.findings()).isNotEmpty();
        assertThat(detail.findings()).allMatch(f -> f.status() == ReviewFindingStatus.DISPUTED);
    }

    @Test
    void debateTurnCommentsPopulateMentionAndRefColumns()
    {
        LlmReviewer claude = mock(LlmReviewer.class);
        LlmReviewer openai = mock(LlmReviewer.class);
        when(claude.providerId()).thenReturn("claude");
        when(claude.displayName()).thenReturn("Claude");
        when(claude.isConfigured()).thenReturn(true);
        when(openai.providerId()).thenReturn("openai");
        when(openai.displayName()).thenReturn("GPT-5");
        when(openai.isConfigured()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(claude, openai));
        when(claude.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "C.", List.of(new ReviewOutput.LineComment("src/a.ts", 1, "A.", "nit")),
                "claude", "claude-sonnet-4.6"));
        when(openai.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "G.", List.of(new ReviewOutput.LineComment("src/b.ts", 2, "B.", "nit")),
                "openai", "gpt-5"));
        // Reviewers address @claude and quote #finding-known-id in their
        // debate comments — both must land on the message's columns.
        stubPanelOrchestration(
                consensus(finding("src/a.ts", 1, "nit", "A.", "disputed", "Claude")),
                "{\"stance\":\"hold\",\"comment\":\"@claude not yet, compare #finding-known-id\"}",
                claude, openai);

        service.startReviewOnPr("acme/widget", 42);

        ReviewMessage debateMsg = recording.messages.stream()
                .filter(m -> "debate_turn".equals(m.payloadKind()))
                .findFirst().orElseThrow();
        // @claude resolved to the Claude reviewer seat.
        assertThat(debateMsg.mentions()).isNotEmpty();
        // #finding-known-id encoded as a stored ref.
        assertThat(debateMsg.refs()).contains("finding:known-id");
    }

    @Test
    void assembleReferencedContextInlinesOnlyTheReferencedBodies()
    {
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Summary here.",
                List.of(new ReviewOutput.LineComment("src/x.ts", 1, "Body of finding.", "nit")),
                "claude", "claude-sonnet-4.6"));
        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);
        String findingId = detail.findings().get(0).id();
        ReviewMessage anyMessage = recording.messages.get(0);

        String ctx = service.assembleReferencedContext(List.of(
                "finding:" + findingId,
                "msg:" + anyMessage.id(),
                "msg:does-not-exist",
                "garbage-without-separator"));

        // Both live refs are inlined; the dangling and malformed refs are
        // skipped rather than throwing.
        assertThat(ctx).contains("Body of finding.");
        assertThat(ctx).contains(anyMessage.body());
        assertThat(ctx).doesNotContain("does-not-exist");
        assertThat(ctx).doesNotContain("garbage-without-separator");
    }

    // ── Phase 8 inner-5: per-file fan-out for big PRs ────────────────

    @Test
    void splitDiffByFileBoundariesIsHeadCanonical()
    {
        // Empty / blank input returns nothing — the fan-out caller
        // falls through to the single-shot path on empty.
        assertThat(ReviewPassService.splitDiffByFile("")).isEmpty();
        assertThat(ReviewPassService.splitDiffByFile("   \n\n  ")).isEmpty();

        // Single-file diff stays one chunk; the chunk starts with the
        // marker so per-file calls each get a self-contained diff.
        String oneFile = "diff --git a/x b/x\nindex 1..2\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n";
        List<String> oneOnly = ReviewPassService.splitDiffByFile(oneFile);
        assertThat(oneOnly).hasSize(1);
        assertThat(oneOnly.get(0)).startsWith("diff --git ");

        // Multi-file: split on `diff --git ` line starts, marker kept
        // at the head of each chunk.
        String twoFiles = "diff --git a/x b/x\nbody-x\ndiff --git a/y b/y\nbody-y\n";
        List<String> two = ReviewPassService.splitDiffByFile(twoFiles);
        assertThat(two).hasSize(2);
        assertThat(two.get(0)).startsWith("diff --git a/x");
        assertThat(two.get(0)).contains("body-x");
        assertThat(two.get(1)).startsWith("diff --git a/y");
        assertThat(two.get(1)).contains("body-y");
    }

    @Test
    void smallDiffStaysASingleCallEvenWhenFanOutLooksLikeItCouldFire()
    {
        // Regression guard: a small two-file diff should still go in
        // one call so we don't multiply LLM cost on small PRs.
        when(pullRequests.fetchPrDiff(anyString(), any(PullRequestRef.class)))
                .thenReturn("diff --git a/x b/x\nshort\ndiff --git a/y b/y\nshort\n");
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Fine.", List.of(), "claude", "claude-sonnet-4.6"));

        service.startReviewOnPr("acme/widget", 42);

        // Independent phase: exactly one call (no debate fires because
        // there are no findings → no disputed).
        verify(reviewer, times(1)).review(any(ReviewRequest.class));
    }

    @Test
    void largeMultiFileDiffFansOutOneCallPerFileAndMergesTheOutputs()
    {
        // Build a 3-file diff with each file's body padded over the
        // per-call threshold so we exercise the fan-out path.
        String filler = "x".repeat(ReviewPassService.MAX_DIFF_CHARS_PER_CALL);
        String bigDiff = "diff --git a/foo.ts b/foo.ts\n" + filler + "\n"
                + "diff --git a/bar.ts b/bar.ts\n" + filler + "\n"
                + "diff --git a/baz.ts b/baz.ts\n" + filler + "\n";
        when(pullRequests.fetchPrDiff(anyString(), any(PullRequestRef.class)))
                .thenReturn(bigDiff);

        // Each chunk produces a finding tagged with its file name so
        // we can verify all three made it into the final merged
        // output. The reviewer mock answers each call based on which
        // file path appears in the chunk's diff.
        when(reviewer.review(any(ReviewRequest.class))).thenAnswer(invocation -> {
            ReviewRequest req = invocation.getArgument(0);
            String diff = req.diff();
            String file = diff.contains("foo.ts") ? "src/foo.ts"
                    : diff.contains("bar.ts") ? "src/bar.ts"
                    : "src/baz.ts";
            return new ReviewOutput(
                    "Per-file summary for " + file,
                    List.of(new ReviewOutput.LineComment(file, 1, "found at " + file, "nit")),
                    "claude", "claude-sonnet-4.6");
        });

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 99);

        // 3 fan-out calls during INDEPENDENT — the panel-of-1 path
        // doesn't enter CROSS_REVIEW / DEBATE, so this count is
        // exactly the file count.
        verify(reviewer, times(3)).review(any(ReviewRequest.class));

        // All three files surface as findings on the pass.
        assertThat(detail.findings()).hasSize(3);
        assertThat(detail.findings()).extracting(ReviewFinding::path)
                .containsExactlyInAnyOrder("src/foo.ts", "src/bar.ts", "src/baz.ts");

        // The merged summary preserves each chunk's contribution.
        ReviewMessage independent = recording.messages.stream()
                .filter(m -> m.phase() == ReviewPhase.INDEPENDENT)
                .findFirst().orElseThrow();
        assertThat(independent.body()).contains("Per-file summary for src/foo.ts");
        assertThat(independent.body()).contains("Per-file summary for src/bar.ts");
        assertThat(independent.body()).contains("Per-file summary for src/baz.ts");
    }

    @Test
    void singleMegaFileFallsThroughToOneCallWithTheFullDiff()
    {
        // One file bigger than the threshold can't be sliced further
        // — the existing ReviewPrompt truncation is the safety net.
        // We must NOT spuriously call review() N times on the same
        // chunk; one call only.
        String filler = "x".repeat(ReviewPassService.MAX_DIFF_CHARS_PER_CALL + 1_000);
        String monolithic = "diff --git a/giant.ts b/giant.ts\n" + filler + "\n";
        when(pullRequests.fetchPrDiff(anyString(), any(PullRequestRef.class)))
                .thenReturn(monolithic);
        when(reviewer.review(any(ReviewRequest.class))).thenReturn(new ReviewOutput(
                "Big file done.", List.of(), "claude", "claude-sonnet-4.6"));

        service.startReviewOnPr("acme/widget", 12);

        verify(reviewer, times(1)).review(any(ReviewRequest.class));
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
