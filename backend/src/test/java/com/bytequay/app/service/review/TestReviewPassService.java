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

import com.bytequay.app.domain.AgendaPhase;
import com.bytequay.app.domain.AgendaPhaseStatus;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewPassHostKind;
import com.bytequay.app.domain.ReviewPassKind;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.ReviewVerdict;
import com.bytequay.app.domain.Skill;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.SkillStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.threads.AgentScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deterministic review-pass spine over the Lead + Seats panel:
 * seating, the phase walk, the agenda artifact, arbitration, and the
 * gated publish. Lead and seat CONTENT is mocked — their own
 * machinery has dedicated suites.
 */
class TestReviewPassService
{
    private static final Pattern AGENDA_ID_IN_DIRECTIVE =
            Pattern.compile("Agenda phase '([^']+)'");

    private ThreadStore threadStore;
    private InMemoryReviewStore reviewStore;
    private PullRequestRepository pullRequests;
    private PullRequestStore pullRequestStore;
    private PatResolver patResolver;
    private LlmReviewerRegistry registry;
    private LlmReviewer reviewer;
    private AppSettingsStore appSettings;
    private SkillStore skillStore;
    private LeadOrchestrator leadOrchestrator;
    private ReviewerSeat reviewerSeat;
    private LeadToolset leadToolset;
    private AgentScheduler scheduler;
    private ReviewBudgetMeter budgetMeter;
    private ReviewPassService service;
    private final List<Object> publishedEvents = new ArrayList<>();

    @BeforeEach
    void setUp()
    {
        publishedEvents.clear();
        threadStore = mock(ThreadStore.class);
        pullRequests = mock(PullRequestRepository.class);
        pullRequestStore = mock(PullRequestStore.class);
        patResolver = mock(PatResolver.class);
        registry = mock(LlmReviewerRegistry.class);
        reviewer = mock(LlmReviewer.class);
        appSettings = mock(AppSettingsStore.class);
        skillStore = mock(SkillStore.class);
        reviewStore = new InMemoryReviewStore();
        leadOrchestrator = mock(LeadOrchestrator.class);
        reviewerSeat = mock(ReviewerSeat.class);
        leadToolset = mock(LeadToolset.class);
        scheduler = mock(AgentScheduler.class);

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
        when(leadToolset.sessionFor(anyString(), any(), anyString()))
                .thenReturn(mock(LeadToolset.Session.class));

        // Scheduler runs fan-out batches inline so tests stay
        // deterministic and single-threaded.
        when(scheduler.invokeAll(any())).thenAnswer(inv -> {
            List<Callable<Object>> work = inv.getArgument(0);
            List<Object> results = new ArrayList<>();
            for (Callable<Object> item : work) {
                results.add(item.call());
            }
            return results;
        });

        // Default seat behaviour: persist a short reply, no findings.
        when(reviewerSeat.runDispatchedTurn(
                any(), any(), anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(inv -> seatMessage(inv.getArgument(0), inv.getArgument(2),
                        "Looked at the diff; nothing to flag."));

        // Default Lead behaviour: every phase round marks its agenda
        // phase done immediately (the directive names it); the kickoff
        // round sets no agenda so the spine installs the default.
        when(leadOrchestrator.runRound(any(), any(), any(), any(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    markDirectivePhaseDone(inv.getArgument(0), inv.getArgument(5));
                    return new TurnResult("", 0, 0, 0L, 1, TurnResult.End.COMPLETED);
                });

        // Same-thread executor: the async 3-arg overload runs its body
        // inline so tests stay deterministic.
        budgetMeter = new ReviewBudgetMeter(reviewStore);
        service = new ReviewPassService(
                threadStore, reviewStore, pullRequests, pullRequestStore, patResolver, registry,
                appSettings,
                Runnable::run,
                leadOrchestrator, reviewerSeat, leadToolset,
                budgetMeter,
                mock(ReviewDiffCache.class),
                scheduler,
                skillStore,
                publishedEvents::add);
    }

    // ── Seating + the phase walk ─────────────────────────────────────

    @Test
    void startReviewWithOptionsSeatsThePassThenRunsTheBodyOnTheExecutor()
    {
        seatReportsFinding("src/foo.ts", 3, "nit", "Tidy this.");

        ReviewPassDetail seated = service.startReviewOnPr(
                "acme/widget", 42, ReviewPassService.StartOptions.DEFAULT);

        // The pass is seated with a thread id, and the body — dispatched
        // to the (same-thread, in this test) review executor — persisted
        // the seat's finding. A real executor runs it off-thread.
        assertThat(seated.pass().threadId()).isNotBlank();
        assertThat(seated.findings()).hasSize(1);
    }

    @Test
    void standaloneReviewIsThreadHostedAndFresh()
    {
        service.startReviewOnPr("acme/widget", 42);

        ReviewPass pass = reviewStore.findPassById(passId()).orElseThrow();
        assertThat(pass.hostKind()).isEqualTo(ReviewPassHostKind.THREAD);
        assertThat(pass.hostId()).isEqualTo(pass.threadId());
        assertThat(pass.kind()).isEqualTo(ReviewPassKind.FRESH);
    }

    @Test
    void taskPhaseReviewIsHostedByTheTaskWithItsKind()
    {
        service.startTaskPhaseReview(
                "task-7", "acme/widget", 42, ReviewPassKind.RE_REVIEW,
                ReviewPassService.StartOptions.DEFAULT);

        ReviewPass pass = reviewStore.findPassById(passId()).orElseThrow();
        assertThat(pass.hostKind()).isEqualTo(ReviewPassHostKind.TASK_PHASE);
        assertThat(pass.hostId()).isEqualTo("task-7");
        assertThat(pass.kind()).isEqualTo(ReviewPassKind.RE_REVIEW);
    }

    @Test
    void stageLinkedPassFiresTerminatedEventOnTerminate()
    {
        service.startTaskPhaseReview(
                "task-7", "acme/widget", 42, ReviewPassKind.FRESH,
                ReviewPassService.StartOptions.DEFAULT, "stage-abc");

        // The link is stamped during seating, before the (inline) body
        // settles to TERMINATE, so the terminate hook sees it and fires.
        ReviewPass pass = reviewStore.findPassById(passId()).orElseThrow();
        assertThat(pass.phase()).isEqualTo(ReviewPhase.TERMINATE);
        assertThat(pass.taskStageId()).isEqualTo("stage-abc");
        assertThat(publishedEvents).filteredOn(e -> e instanceof ReviewPassTerminatedEvent)
                .singleElement()
                .isEqualTo(new ReviewPassTerminatedEvent(pass.id(), "stage-abc"));
    }

    @Test
    void standaloneReviewFiresNoTerminatedEvent()
    {
        service.startReviewOnPr("acme/widget", 42);

        // A THREAD-hosted pass carries no stage link, so nothing closes.
        assertThat(publishedEvents).noneMatch(e -> e instanceof ReviewPassTerminatedEvent);
    }

    @Test
    void seatsLeadReviewersAndHumanWithBudgetSlices()
    {
        LlmReviewer openai = configuredReviewer("openai", "GPT-5");
        when(registry.all()).thenReturn(List.of(reviewer, openai));

        service.startReviewOnPr("acme/widget", 42);

        ArgumentCaptor<Thread> threadCaptor = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(threadCaptor.capture());
        assertThat(threadCaptor.getValue().flow()).isEqualTo(ThreadFlow.REVIEW);

        List<ReviewParticipant> participants =
                reviewStore.listParticipantsForPass(passId());
        assertThat(participants).extracting(ReviewParticipant::kind).containsExactly(
                ReviewParticipantKind.LEAD,
                ReviewParticipantKind.REVIEWER,
                ReviewParticipantKind.REVIEWER,
                ReviewParticipantKind.HUMAN);
        // The Lead runs on the lead member's provider (first member
        // when nothing was picked).
        assertThat(participants.get(0).credentialId()).isEqualTo("claude");
        assertThat(participants.get(0).personaLabel()).isEqualTo("Lead");
        // Reviewer slices: the pass cap split evenly.
        long cap = reviewStore.findPassById(passId()).orElseThrow().costCapMilli();
        assertThat(participants.get(1).budgetMilliUsdCap()).isEqualTo(cap / 2);
        assertThat(participants.get(2).budgetMilliUsdCap()).isEqualTo(cap / 2);
        assertThat(participants.get(3).budgetMilliUsdCap()).isZero();

        // Kickoff message mentions every reviewer seat.
        ReviewMessage kickoff = reviewStore.listMessagesForPass(passId()).get(0);
        assertThat(kickoff.phase()).isEqualTo(ReviewPhase.KICKOFF);
        assertThat(kickoff.mentions()).containsExactly(
                participants.get(1).id(), participants.get(2).id());
    }

    @Test
    void reviewerSeatRoleSkillResolvesItsBodyAsThePersonaPrompt()
    {
        when(skillStore.byId(7L)).thenReturn(Optional.of(new Skill(
                7L, "global", null, null, "Trino style reviewer",
                "Reviews in the Trino voice.", "Be strict about Trino conventions.",
                "persona", "review", "reviewer", /* enabled */ true, false,
                "authored", null, "hash", Instant.now(), Instant.now())));

        // A model-only lead + a reviewer carrying the skill: only the
        // reviewer's role resolves; the lead never takes a voice.
        service.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, null, true),
                        new ReviewPassService.PanelSeat("claude", null, 7L, false))));

        // The reviewer seat carries the skill's name as its label; the
        // lead seat stays the fixed "Lead" with no persona.
        List<ReviewParticipant> roster = reviewStore.listParticipantsForPass(passId());
        assertThat(roster).anyMatch(p -> p.kind() == ReviewParticipantKind.REVIEWER
                && "Trino style reviewer".equals(p.personaLabel()));
        ReviewParticipant lead = roster.stream()
                .filter(p -> p.kind() == ReviewParticipantKind.LEAD)
                .findFirst()
                .orElseThrow();
        assertThat(lead.personaLabel()).isEqualTo("Lead");
    }

    @Test
    void rosterListsTheCliAgentsAlongsideApiReviewers()
    {
        List<ReviewPassService.RosterEntry> roster = service.roster();

        assertThat(roster).anyMatch(e ->
                "claude-cli".equals(e.providerId()) && "Claude CLI".equals(e.displayName()));
        assertThat(roster).anyMatch(e ->
                "codex-cli".equals(e.providerId()) && "Codex CLI".equals(e.displayName()));
    }

    @Test
    void aCliAgentCannotBeThePanelLead()
    {
        assertThatThrownBy(() -> service.startReviewOnPr("acme/widget", 42,
                new ReviewPassService.StartOptions(
                        List.of(), 3, 500L, true, null, null,
                        List.of(
                                new ReviewPassService.PanelSeat("claude-cli", null, null, true),
                                new ReviewPassService.PanelSeat("claude", null, null, false)))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("can't be the panel lead");
    }

    @Test
    void leadSeatDropsAnyAttachedRoleSkill()
    {
        // Even if a caller pins a review skill on the lead seat, the
        // lead's job is fixed and code-driven — the skill is ignored
        // (not resolved, not validated) and the lead seat carries no
        // persona prompt.
        when(skillStore.byId(7L)).thenReturn(Optional.of(new Skill(
                7L, "global", null, null, "Trino style reviewer",
                "Reviews in the Trino voice.", "Be strict about Trino conventions.",
                "persona", "review", "reviewer", /* enabled */ true, false,
                "authored", null, "hash", Instant.now(), Instant.now())));

        service.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, 7L, true),
                        new ReviewPassService.PanelSeat("claude", null, null, false))));

        ArgumentCaptor<PanelSeatConfig> roster = ArgumentCaptor.forClass(PanelSeatConfig.class);
        verify(leadOrchestrator, atLeastOnce()).runRound(
                any(), any(), roster.capture(), any(), anyInt(), anyString());
        PanelSeatConfig.Seat leadSeat = roster.getValue().seats().stream()
                .filter(PanelSeatConfig.Seat::lead)
                .findFirst()
                .orElseThrow();
        assertThat(leadSeat.personaPrompt()).isNull();
        // The skill body never reached any seat as the lead's voice.
        assertThat(reviewStore.listParticipantsForPass(passId()))
                .filteredOn(p -> p.kind() == ReviewParticipantKind.LEAD)
                .allMatch(p -> "Lead".equals(p.personaLabel()));
    }

    @Test
    void aFlaggedLeadIsNotDoubleSeatedAsAReviewer()
    {
        // A lead seat + one reviewer seat must yield exactly one LEAD and
        // one REVIEWER — the lead coordinates, it is never also seated as a
        // reviewer (which produced the phantom extra reviewer).
        service.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, null, true),
                        new ReviewPassService.PanelSeat("claude", null, null, false))));

        List<ReviewParticipant> roster = reviewStore.listParticipantsForPass(passId());
        assertThat(roster).filteredOn(p -> p.kind() == ReviewParticipantKind.LEAD).hasSize(1);
        assertThat(roster).filteredOn(p -> p.kind() == ReviewParticipantKind.REVIEWER).hasSize(1);
    }

    @Test
    void reviewerSeatPromptRendersThePrSummaryPlaceholder()
    {
        when(skillStore.byId(11L)).thenReturn(Optional.of(new Skill(
                11L, "global", null, null, "Summarising reviewer",
                "Reviews with the PR summary inline.",
                "Focus on the change described here:\n{{pr_summary}}",
                "rubric", "review", null, /* enabled */ true, false,
                "authored", null, "hash", Instant.now(), Instant.now())));

        service.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, null, true),
                        new ReviewPassService.PanelSeat("claude", null, 11L, false))));

        // The {{pr_summary}} token is replaced by the PR summary (the
        // fetched PR body) before the prompt reaches the seat roster.
        ArgumentCaptor<PanelSeatConfig> roster = ArgumentCaptor.forClass(PanelSeatConfig.class);
        verify(leadOrchestrator, atLeastOnce()).runRound(
                any(), any(), roster.capture(), any(), anyInt(), anyString());
        String rendered = roster.getValue().seats().stream()
                .map(PanelSeatConfig.Seat::personaPrompt)
                .filter(p -> p != null && p.contains("Focus on the change"))
                .findFirst()
                .orElseThrow();
        assertThat(rendered).contains("Description.");
        assertThat(rendered).doesNotContain("{{pr_summary}}");
    }

    @Test
    void disabledRoleSkillReviewerSeatIsSkipped()
    {
        when(skillStore.byId(8L)).thenReturn(Optional.of(new Skill(
                8L, "global", null, null, "Muted persona",
                "", "...", "persona", "review", "reviewer", /* enabled */ false, false,
                "authored", null, "hash", Instant.now(), Instant.now())));

        // The disabled-skill reviewer seat falls out; with no seat left
        // the start is refused rather than running an empty panel.
        assertThatThrownBy(() -> service.startReviewOnPr(
                "acme/widget", 42, new ReviewPassService.StartOptions(
                        List.of(), 3, 500L, true, null, null,
                        List.of(new ReviewPassService.PanelSeat("claude", null, 8L, false)))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No reviewers selected");
    }

    @Test
    void buildSurfaceSkillIsRefusedAsAReviewerRole()
    {
        when(skillStore.byId(9L)).thenReturn(Optional.of(new Skill(
                9L, "global", null, null, "Compact Skill",
                "", "context chunk", "library", "build", null, true, false,
                "authored", null, "hash", Instant.now(), Instant.now())));

        assertThatThrownBy(() -> service.startReviewOnPr(
                "acme/widget", 42, new ReviewPassService.StartOptions(
                        List.of(), 3, 500L, true, null, null,
                        List.of(new ReviewPassService.PanelSeat("claude", null, 9L, false)))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("build-surface skill");
    }

    @Test
    void dialogLeadPickPutsThatProviderOnTheLeadSeat()
    {
        LlmReviewer openai = configuredReviewer("openai", "GPT-5");
        when(registry.all()).thenReturn(List.of(reviewer, openai));

        service.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, "openai", List.of()));

        ReviewParticipant lead = reviewStore.listParticipantsForPass(passId()).get(0);
        assertThat(lead.kind()).isEqualTo(ReviewParticipantKind.LEAD);
        assertThat(lead.credentialId()).isEqualTo("openai");
    }

    @Test
    void spineWalksThePhasesAndFreezesTheAgendaDone()
    {
        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        // Deterministic phase walk, persisted in order.
        assertThat(reviewStore.passHistory.stream().map(ReviewPass::phase).distinct())
                .containsExactly(
                        ReviewPhase.KICKOFF, ReviewPhase.INDEPENDENT,
                        ReviewPhase.CROSS_REVIEW, ReviewPhase.CONSENSUS,
                        ReviewPhase.DEBATE, ReviewPhase.TERMINATE);

        // One Lead kickoff round + one round per Lead-driven phase
        // (the stubbed Lead marks each phase done immediately).
        verify(leadOrchestrator).runRound(
                any(), any(), any(), eq(ReviewPhase.KICKOFF), anyInt(), anyString());
        verify(leadOrchestrator).runRound(
                any(), any(), any(), eq(ReviewPhase.CROSS_REVIEW), anyInt(), anyString());
        verify(leadOrchestrator).runRound(
                any(), any(), any(), eq(ReviewPhase.CONSENSUS), anyInt(), anyString());
        verify(leadOrchestrator).runRound(
                any(), any(), any(), eq(ReviewPhase.DEBATE), anyInt(), anyString());
        // A closing wrap-up round records the consolidated result.
        verify(leadOrchestrator).runRound(
                any(), any(), any(), eq(ReviewPhase.TERMINATE), anyInt(), anyString());

        // The independent fan-out went through the scheduler once,
        // dispatching the (single) reviewer seat.
        verify(scheduler).invokeAll(any());
        verify(reviewerSeat).runDispatchedTurn(
                any(), any(), anyString(), anyString(),
                eq(ReviewPhase.INDEPENDENT), eq(0), any());

        // The default agenda was installed and froze fully DONE.
        List<AgendaPhase> agenda = AgendaJsonCodec.parse(detail.pass().agendaJson());
        assertThat(agenda).extracting(AgendaPhase::id).containsExactly(
                ReviewPassService.AGENDA_INDEPENDENT,
                ReviewPassService.AGENDA_CROSS_REVIEW,
                ReviewPassService.AGENDA_CONSENSUS,
                ReviewPassService.AGENDA_DEBATE);
        assertThat(agenda).allMatch(p -> p.status() == AgendaPhaseStatus.DONE);
        assertThat(detail.agenda()).hasSize(4);

        // Clean panel → TERMINATE with APPROVE suggested.
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.TERMINATE);
        assertThat(detail.pass().verdict()).isEqualTo(ReviewVerdict.APPROVE);
        assertThat(detail.pass().endedAt()).isNotNull();
    }

    @Test
    void reportedFindingsParkThePassAtArbitrate()
    {
        seatReportsFinding("src/a.ts", 5, "major", "Suspicious null check.");

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.ARBITRATE);
        assertThat(detail.pass().endedAt()).isNull();
        assertThat(detail.pass().verdict()).isEqualTo(ReviewVerdict.COMMENT);
        assertThat(detail.findings()).singleElement()
                .matches(f -> f.status() == ReviewFindingStatus.REPORTED);
    }

    @Test
    void agreedBlockerSuggestsRequestChanges()
    {
        seatReportsFinding("src/a.ts", 5, "blocker", "Data loss on retry.");
        // The Lead classifies the finding AGREED during consensus.
        doAnswer(inv -> {
            ReviewPass pass = inv.getArgument(0);
            ReviewFinding f = reviewStore.listFindingsForPass(pass.id()).get(0);
            reviewStore.saveFinding(new ReviewFinding(
                    f.id(), f.reviewPassId(), f.path(), f.line(),
                    ReviewFindingSeverity.BLOCKER, ReviewFindingStatus.AGREED,
                    f.body(), null, null, f.createdAt()));
            markDirectivePhaseDone(pass, inv.getArgument(5));
            return new TurnResult("", 0, 0, 0L, 1, TurnResult.End.COMPLETED);
        }).when(leadOrchestrator).runRound(
                any(), any(), any(), eq(ReviewPhase.CONSENSUS), anyInt(), anyString());

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.TERMINATE);
        assertThat(detail.pass().verdict()).isEqualTo(ReviewVerdict.REQUEST_CHANGES);
    }

    @Test
    void watchdogBoundsLeadRoundsPerPhase()
    {
        // A Lead that never marks anything done: each driven phase
        // must stop at the watchdog and the spine forces the agenda
        // phase DONE so the artifact freezes consistent.
        doReturn(new TurnResult("", 0, 0, 0L, 1, TurnResult.End.COMPLETED))
                .when(leadOrchestrator)
                .runRound(any(), any(), any(), any(), anyInt(), anyString());

        ReviewPassDetail detail = service.startReviewOnPr("acme/widget", 42);

        // 1 kickoff + 3 watchdog-bounded driven phases + 1 closing wrap-up.
        verify(leadOrchestrator, times(2 + 3 * ReviewPassService.MAX_LEAD_TURNS_PER_PHASE))
                .runRound(any(), any(), any(), any(), anyInt(), anyString());
        assertThat(AgendaJsonCodec.parse(detail.pass().agendaJson()))
                .allMatch(p -> p.status() == AgendaPhaseStatus.DONE);
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.TERMINATE);
    }

    @Test
    void aBudgetExhaustedPassStillFinalizesWithAnUnbudgetedClosingTurn()
    {
        // Every lead round spends the whole budget, so the pass is
        // exhausted long before the panel converges.
        doAnswer(inv -> {
            ReviewPass p = inv.getArgument(0);
            budgetMeter.chargePass(p.id(), 1_000_000L);
            return new TurnResult("", 0, 0, 0L, 1, TurnResult.End.COMPLETED);
        }).when(leadOrchestrator).runRound(any(), any(), any(), any(), anyInt(), anyString());

        service.startReviewOnPr("acme/widget", 42);

        // The closing summary still runs — but via the UNBUDGETED overload
        // (enforceBudget=false) with the budget-aware directive — so a
        // capped pass gets a finalized result instead of stalling with none.
        verify(leadOrchestrator).runRound(
                any(), any(), any(), eq(ReviewPhase.TERMINATE), anyInt(),
                argThat(d -> d != null && d.contains("budget")), eq(false));
    }

    @Test
    void steeringAReviewerPersistsTheHumanMessageAndRunsAnUnbudgetedReply()
    {
        service.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, null, true),
                        new ReviewPassService.PanelSeat("claude", null, null, false))));
        String passId = passId();
        ReviewParticipant reviewer = reviewStore.listParticipantsForPass(passId).stream()
                .filter(p -> p.kind() == ReviewParticipantKind.REVIEWER)
                .findFirst().orElseThrow();

        service.steerPass(passId, reviewer.id(), "Please double-check the null path.");

        // The human's message landed, addressed to the reviewer.
        assertThat(reviewStore.listMessagesForPass(passId))
                .anyMatch(m -> "Please double-check the null path.".equals(m.body())
                        && m.mentions().contains(reviewer.id()));
        // The reviewer reply ran UNBUDGETED (enforceBudget=false).
        verify(reviewerSeat).runDispatchedTurn(
                any(), any(), eq(reviewer.id()), anyString(), any(), anyInt(), any(), eq(false));
    }

    @Test
    void prSummariesCarryTheReviewersAndBestEffortPrTitleWhenNotCached()
    {
        // The reviewed PR isn't in the local cache (mock store returns
        // empty), so the title falls back to the best-effort GitHub fetch.
        when(pullRequests.fetchPrTitle(eq("ghp_secret"), any(PullRequestRef.class)))
                .thenReturn("Add coordinator retry logic");
        service.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, null, true),
                        new ReviewPassService.PanelSeat("claude", null, null, false))));
        String threadId = reviewStore.passHistory.get(0).threadId();

        List<ReviewPassService.ReviewThreadPrSummary> summaries =
                service.prSummariesForThreads(List.of(threadId));

        assertThat(summaries).hasSize(1);
        ReviewPassService.ReviewThreadPrSummary s = summaries.get(0);
        assertThat(s.prTitle()).isEqualTo("Add coordinator retry logic");
        // The panel seats (lead + reviewer) are listed for the row; the
        // human "You" seat is excluded.
        assertThat(s.reviewers()).isNotEmpty();
    }

    @Test
    void steeringDefersTheReplyToTheExecutorRatherThanTheRequestThread()
    {
        // A capturing executor records the turn instead of running it. The
        // steered reply is unbudgeted and can run for many seconds; running
        // it on the request thread (and, before the fix, under an open
        // transaction holding the single SQLite connection) starved every
        // other request into pool-timeout 500s. steerPass must dispatch it
        // and return immediately.
        List<Runnable> deferred = new ArrayList<>();
        ReviewPassService async = new ReviewPassService(
                threadStore, reviewStore, pullRequests, pullRequestStore, patResolver, registry,
                appSettings, deferred::add,
                leadOrchestrator, reviewerSeat, leadToolset, budgetMeter,
                mock(ReviewDiffCache.class), scheduler, skillStore, publishedEvents::add);
        async.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, null, true),
                        new ReviewPassService.PanelSeat("claude", null, null, false))));
        String passId = passId();
        ReviewParticipant reviewer = reviewStore.listParticipantsForPass(passId).stream()
                .filter(p -> p.kind() == ReviewParticipantKind.REVIEWER)
                .findFirst().orElseThrow();
        deferred.clear();   // drop the initial review body; assert only on the steer

        async.steerPass(passId, reviewer.id(), "Please double-check the null path.");

        // The human message lands synchronously...
        assertThat(reviewStore.listMessagesForPass(passId))
                .anyMatch(m -> "Please double-check the null path.".equals(m.body()));
        // ...but the reply is deferred to the executor, not run inline.
        assertThat(deferred).hasSize(1);
        verify(reviewerSeat, never()).runDispatchedTurn(
                any(), any(), eq(reviewer.id()), anyString(), any(), anyInt(), any(), eq(false));

        // Draining the executor runs the unbudgeted reply.
        deferred.get(0).run();
        verify(reviewerSeat).runDispatchedTurn(
                any(), any(), eq(reviewer.id()), anyString(), any(), anyInt(), any(), eq(false));
    }

    @Test
    void steeringRefetchesThePrDiffSoTheSeatReviewsActualChangesNotEmpty()
    {
        // The PR diff isn't persisted after the initial run, so a steered
        // turn must re-fetch it; seeding empty made the continued reviewer
        // report "no files changed".
        ReviewDiffCache diffCache = mock(ReviewDiffCache.class);
        List<Runnable> deferred = new ArrayList<>();
        ReviewPassService svc = new ReviewPassService(
                threadStore, reviewStore, pullRequests, pullRequestStore, patResolver, registry,
                appSettings, deferred::add,
                leadOrchestrator, reviewerSeat, leadToolset, budgetMeter,
                diffCache, scheduler, skillStore, publishedEvents::add);
        svc.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, null, true),
                        new ReviewPassService.PanelSeat("claude", null, null, false))));
        String passId = passId();
        ReviewParticipant reviewer = reviewStore.listParticipantsForPass(passId).stream()
                .filter(p -> p.kind() == ReviewParticipantKind.REVIEWER)
                .findFirst().orElseThrow();
        deferred.clear();
        when(pullRequests.fetchPrDiff(eq("ghp_secret"), any(PullRequestRef.class)))
                .thenReturn("diff --git a/F b/F\n+changed");

        svc.steerPass(passId, reviewer.id(), "continue the review");
        deferred.get(0).run();

        // The steered turn seeds the re-fetched diff, not an empty placeholder.
        verify(diffCache).seed(passId, "diff --git a/F b/F\n+changed");
    }

    @Test
    void resumingReRunsTheReviewerSeatsNotJustOneLeadTurn()
    {
        when(pullRequests.fetchPrDiff(eq("ghp_secret"), any(PullRequestRef.class)))
                .thenReturn("diff --git a/F b/F\n+x");
        service.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, null, true),
                        new ReviewPassService.PanelSeat("claude", null, null, false))));
        String passId = passId();
        clearInvocations(reviewerSeat);   // ignore the initial run's turns

        service.resumePass(passId);

        // Resume re-runs the reviewer seat's INDEPENDENT turn — the thing a
        // single-turn lead steer never does — so the panel actually keeps
        // reviewing instead of stalling after the lead speaks.
        verify(reviewerSeat, atLeastOnce()).runDispatchedTurn(
                any(), any(), anyString(), anyString(),
                eq(ReviewPhase.INDEPENDENT), anyInt(), any());
    }

    @Test
    void editingAFindingReplacesItsBodyAndLeavesEverythingElse()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);
        ReviewFinding f = seedFinding(pass, "src/A.java", 12,
                ReviewFindingSeverity.MAJOR, ReviewFindingStatus.AGREED, "[DeepSeek] original text");

        service.editFindingBody(pass.id(), f.id(), "Polished comment for GitHub.");

        ReviewFinding edited = reviewStore.findFindingById(f.id()).orElseThrow();
        assertThat(edited.body()).isEqualTo("Polished comment for GitHub.");
        // Only the body changes — severity, status, and line stay put.
        assertThat(edited.severity()).isEqualTo(ReviewFindingSeverity.MAJOR);
        assertThat(edited.status()).isEqualTo(ReviewFindingStatus.AGREED);
        assertThat(edited.line()).isEqualTo(12);
    }

    @Test
    void droppingAFindingSoftRemovesItAsDropped()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);
        ReviewFinding f = seedFinding(pass, "src/A.java", 12,
                ReviewFindingSeverity.MAJOR, ReviewFindingStatus.AGREED, "[DeepSeek] body");

        service.dropFinding(pass.id(), f.id());

        assertThat(reviewStore.findFindingById(f.id()).orElseThrow().status())
                .isEqualTo(ReviewFindingStatus.DROPPED);
    }

    @Test
    void addingAFindingByHandCreatesItAgreed()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);

        service.addFinding(pass.id(), "BLOCKER", "src/A.java", 7, "Manually captured finding.");

        assertThat(reviewStore.listFindingsForPass(pass.id())).anyMatch(f ->
                f.status() == ReviewFindingStatus.AGREED
                        && f.severity() == ReviewFindingSeverity.BLOCKER
                        && "Manually captured finding.".equals(f.body())
                        && "src/A.java".equals(f.path())
                        && Integer.valueOf(7).equals(f.line()));
    }

    @Test
    void aBudgetExhaustedRunStillTransitionsToATerminalPhase()
    {
        // The lead's first round blows past the cost cap and does NOT mark
        // the phase done, so the lead-driven phases (cross-review, consensus,
        // debate) all rely on the budget break rather than convergence.
        when(leadOrchestrator.runRound(any(), any(), any(), any(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    ReviewPass p = inv.getArgument(0);
                    budgetMeter.chargePass(p.id(), 100_000);   // far over the 500 cap
                    return new TurnResult("", 0, 0, 100_000L, 1, TurnResult.End.ABORTED);
                });

        service.startReviewOnPr("acme/widget", 42, new ReviewPassService.StartOptions(
                List.of(), 3, 500L, true, null, null,
                List.of(
                        new ReviewPassService.PanelSeat("claude", null, null, true),
                        new ReviewPassService.PanelSeat("claude", null, null, false))));

        // Despite hitting the cap, the spine walked every phase to its break
        // and finalized — the pass lands on a terminal phase, never stalling
        // mid-flow.
        ReviewPass pass = reviewStore.findPassById(passId()).orElseThrow();
        assertThat(pass.phase()).isIn(ReviewPhase.TERMINATE, ReviewPhase.ARBITRATE);
        // And every work-phase agenda item is marked done, not left in
        // progress.
        assertThat(reviewStore.findPassById(passId()).orElseThrow().agendaJson())
                .doesNotContain("IN_PROGRESS");
    }

    @Test
    void everySeatFailingParksThePassAndSurfacesA502()
    {
        doThrow(new RuntimeException("Anthropic returned 529"))
                .when(reviewerSeat).runDispatchedTurn(
                        any(), any(), anyString(), anyString(), any(), anyInt(), any());

        assertThatThrownBy(() -> service.startReviewOnPr("acme/widget", 99))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Review panel run failed");

        // Pass is persisted terminated so the UI shows "review failed"
        // rather than "review running forever".
        ReviewPass last = reviewStore.passHistory.get(reviewStore.passHistory.size() - 1);
        assertThat(last.phase()).isEqualTo(ReviewPhase.TERMINATE);
    }

    @Test
    void refusesWith412WhenTheActiveReviewerHasNoApiKey()
    {
        when(reviewer.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.startReviewOnPr("acme/widget", 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("API key configured");

        verify(threadStore, never()).saveThread(any());
        assertThat(reviewStore.passHistory).isEmpty();
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

    // ── Arbitration ─────────────────────────────────────────────────

    @Test
    void arbitrateFindingFlipsOpenFindingsAndTerminatesWhenAllResolved()
    {
        ReviewPass pass = seedPass(ReviewPhase.ARBITRATE);
        ReviewFinding disputed = seedFinding(pass, "src/a.ts", 1,
                ReviewFindingSeverity.NIT, ReviewFindingStatus.DISPUTED, "Claude pick.");
        ReviewFinding reported = seedFinding(pass, "src/b.ts", 2,
                ReviewFindingSeverity.NIT, ReviewFindingStatus.REPORTED, "GPT pick.");

        // Include the disputed one → ARBITRATED; the REPORTED one is
        // still open so the pass stays parked.
        ReviewPassDetail afterFirst = service.arbitrateFinding(pass.id(), disputed.id(), "include");
        assertThat(afterFirst.pass().phase()).isEqualTo(ReviewPhase.ARBITRATE);
        assertThat(findingById(afterFirst, disputed.id()).status())
                .isEqualTo(ReviewFindingStatus.ARBITRATED);
        assertThat(findingById(afterFirst, disputed.id()).resolution()).isEqualTo("include");

        // Drop the REPORTED one → DROPPED; nothing open → TERMINATE.
        ReviewPassDetail afterSecond = service.arbitrateFinding(pass.id(), reported.id(), "drop");
        assertThat(afterSecond.pass().phase()).isEqualTo(ReviewPhase.TERMINATE);
        assertThat(afterSecond.pass().endedAt()).isNotNull();
        assertThat(findingById(afterSecond, reported.id()).status())
                .isEqualTo(ReviewFindingStatus.DROPPED);
    }

    @Test
    void arbitrateFindingRefusesWhenPassIsNotInArbitratePhase()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);

        assertThatThrownBy(() -> service.arbitrateFinding(pass.id(), "nonexistent", "include"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not in ARBITRATE");
    }

    // ── Gated publish ───────────────────────────────────────────────

    @Test
    void publishPassPostsTheSelectedFindingsAsAGitHubReviewAndTransitionsThePass()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);
        seedSummaryMessage(pass, "Mostly fine — one nit and one whole-PR note.");
        ReviewFinding inline = seedFinding(pass, "src/foo.ts", 12,
                ReviewFindingSeverity.NIT, ReviewFindingStatus.AGREED, "Inline.");
        ReviewFinding wholePr = seedFinding(pass, null, null,
                ReviewFindingSeverity.QUESTION, ReviewFindingStatus.AGREED, "Whole PR.");

        ReviewPassDetail published = service.publishPass(
                pass.id(), ReviewVerdict.COMMENT, List.of(inline.id(), wholePr.id()));

        ArgumentCaptor<CreateReviewCommand> commandCaptor =
                ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp_secret"), any(PullRequestRef.class), commandCaptor.capture());
        CreateReviewCommand command = commandCaptor.getValue();
        assertThat(command.event()).isEqualTo("COMMENT");
        assertThat(command.body()).isPresent();
        assertThat(command.body().get()).contains("Mostly fine");
        assertThat(command.body().get()).contains("Whole PR");
        assertThat(command.comments()).hasSize(1);
        assertThat(command.comments().get(0).path()).isEqualTo("src/foo.ts");
        assertThat(command.comments().get(0).line()).contains(12);

        assertThat(published.pass().phase()).isEqualTo(ReviewPhase.PUBLISHED);
        assertThat(published.pass().verdict()).isEqualTo(ReviewVerdict.COMMENT);
        assertThat(published.pass().endedAt()).isNotNull();
        assertThat(published.findings())
                .allMatch(f -> f.status() == ReviewFindingStatus.POSTED);
    }

    @Test
    void publishPassDropsUnselectedFindingsFromThePayloadButLeavesThemAgreedOnTheRow()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);
        seedSummaryMessage(pass, "Two nits.");
        ReviewFinding keep = seedFinding(pass, "src/a.ts", 1,
                ReviewFindingSeverity.NIT, ReviewFindingStatus.AGREED, "Keep.");
        ReviewFinding drop = seedFinding(pass, "src/b.ts", 2,
                ReviewFindingSeverity.NIT, ReviewFindingStatus.AGREED, "Drop.");

        ReviewPassDetail published = service.publishPass(
                pass.id(), ReviewVerdict.COMMENT, List.of(keep.id()));

        ArgumentCaptor<CreateReviewCommand> commandCaptor =
                ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp_secret"), any(PullRequestRef.class), commandCaptor.capture());
        assertThat(commandCaptor.getValue().comments()).hasSize(1);
        assertThat(commandCaptor.getValue().comments().get(0).body()).contains("Keep");

        assertThat(findingById(published, keep.id()).status())
                .isEqualTo(ReviewFindingStatus.POSTED);
        assertThat(findingById(published, drop.id()).status())
                .isEqualTo(ReviewFindingStatus.AGREED);
    }

    @Test
    void publishPassRefusesWithA409WhenThePassIsAlreadyPublished()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);
        service.publishPass(pass.id(), ReviewVerdict.APPROVE, List.of());

        assertThatThrownBy(() -> service.publishPass(
                pass.id(), ReviewVerdict.APPROVE, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already published");
    }

    @Test
    void completePassMarksThePassCompletedWithoutPostingToGitHub()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);
        ReviewFinding agreed = seedFinding(pass, "src/a.ts", 1,
                ReviewFindingSeverity.NIT, ReviewFindingStatus.AGREED, "Nit.");

        ReviewPassDetail completed = service.completePass(pass.id());

        assertThat(completed.pass().phase()).isEqualTo(ReviewPhase.COMPLETED);
        assertThat(completed.pass().endedAt()).isNotNull();
        // Nothing is posted to GitHub, and findings keep their status.
        verify(pullRequests, never()).createReview(
                anyString(), any(PullRequestRef.class), any(CreateReviewCommand.class));
        assertThat(findingById(completed, agreed.id()).status())
                .isEqualTo(ReviewFindingStatus.AGREED);
    }

    @Test
    void completePassRefusesWithA409WhenThePassAlreadyPublished()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);
        service.publishPass(pass.id(), ReviewVerdict.APPROVE, List.of());

        assertThatThrownBy(() -> service.completePass(pass.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already published");
    }

    @Test
    void publishPassSurfacesA502WhenGitHubRejectsTheReview()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);
        doThrow(new RuntimeException("422 — head_sha out of date"))
                .when(pullRequests).createReview(
                        anyString(), any(PullRequestRef.class), any(CreateReviewCommand.class));

        assertThatThrownBy(() -> service.publishPass(
                pass.id(), ReviewVerdict.APPROVE, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GitHub rejected the review");

        // The pass stays at TERMINATE so the user can retry — it would
        // be wrong to mark it PUBLISHED when nothing landed on GitHub.
        assertThat(reviewStore.findPassById(pass.id()).orElseThrow().phase())
                .isEqualTo(ReviewPhase.TERMINATE);
    }

    @Test
    void publishPassRefusesWhenPassIsAtArbitrate()
    {
        ReviewPass pass = seedPass(ReviewPhase.ARBITRATE);
        seedFinding(pass, "src/a.ts", 1,
                ReviewFindingSeverity.NIT, ReviewFindingStatus.DISPUTED, "Open.");

        assertThatThrownBy(() -> service.publishPass(
                pass.id(), ReviewVerdict.COMMENT, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ARBITRATE");
    }

    // ── Static helpers under test ────────────────────────────────────

    @Test
    void assembleReferencedContextInlinesOnlyTheReferencedBodies()
    {
        ReviewPass pass = seedPass(ReviewPhase.TERMINATE);
        ReviewFinding finding = seedFinding(pass, "src/x.ts", 1,
                ReviewFindingSeverity.NIT, ReviewFindingStatus.AGREED, "Body of finding.");
        ReviewMessage message = seedSummaryMessage(pass, "Summary here.");

        String ctx = service.assembleReferencedContext(List.of(
                "finding:" + finding.id(),
                "msg:" + message.id(),
                "msg:does-not-exist",
                "garbage-without-separator"));

        assertThat(ctx).contains("Body of finding.");
        assertThat(ctx).contains(message.body());
        assertThat(ctx).doesNotContain("does-not-exist");
        assertThat(ctx).doesNotContain("garbage-without-separator");
    }

    @Test
    void splitDiffByFileBoundariesIsHeadCanonical()
    {
        assertThat(ReviewPassService.splitDiffByFile("")).isEmpty();
        assertThat(ReviewPassService.splitDiffByFile("   \n\n  ")).isEmpty();

        String oneFile = "diff --git a/x b/x\nindex 1..2\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n";
        List<String> oneOnly = ReviewPassService.splitDiffByFile(oneFile);
        assertThat(oneOnly).hasSize(1);
        assertThat(oneOnly.get(0)).startsWith("diff --git ");

        String twoFiles = "diff --git a/x b/x\nbody-x\ndiff --git a/y b/y\nbody-y\n";
        List<String> two = ReviewPassService.splitDiffByFile(twoFiles);
        assertThat(two).hasSize(2);
        assertThat(two.get(0)).startsWith("diff --git a/x");
        assertThat(two.get(0)).contains("body-x");
        assertThat(two.get(1)).startsWith("diff --git a/y");
        assertThat(two.get(1)).contains("body-y");
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    private static LlmReviewer configuredReviewer(String providerId, String displayName)
    {
        LlmReviewer r = mock(LlmReviewer.class);
        when(r.providerId()).thenReturn(providerId);
        when(r.displayName()).thenReturn(displayName);
        when(r.isConfigured()).thenReturn(true);
        return r;
    }

    /** Make the (mocked) seat persist one REPORTED finding alongside
     *  its reply, like the real seat's report_finding tool does. */
    private void seatReportsFinding(String path, Integer line, String severity, String body)
    {
        doAnswer(inv -> {
            ReviewPass pass = inv.getArgument(0);
            reviewStore.saveFinding(new ReviewFinding(
                    UUID.randomUUID().toString(), pass.id(), path, line,
                    SeatToolset.severityFrom(severity), ReviewFindingStatus.REPORTED,
                    "[Claude (Anthropic)] " + body, null, null, Instant.now()));
            return seatMessage(pass, inv.getArgument(2), "Flagged: " + body);
        }).when(reviewerSeat).runDispatchedTurn(
                any(), any(), anyString(), anyString(), any(), anyInt(), any());
    }

    private ReviewMessage seatMessage(ReviewPass pass, String participantId, String body)
    {
        ReviewMessage message = new ReviewMessage(
                UUID.randomUUID().toString(), pass.id(), participantId,
                ReviewPhase.INDEPENDENT, 0, body, List.of(), List.of(),
                /* costUsdMilli */ 3L, Instant.now());
        reviewStore.saveMessage(message);
        return message;
    }

    /** Implements the stubbed Lead's "mark the directive's agenda
     *  phase done" behaviour against the in-memory store. */
    private void markDirectivePhaseDone(ReviewPass pass, String directive)
    {
        Matcher matcher = AGENDA_ID_IN_DIRECTIVE.matcher(directive);
        if (!matcher.find()) {
            return;
        }
        ReviewPass fresh = reviewStore.findPassById(pass.id()).orElseThrow();
        List<AgendaPhase> agenda = AgendaJsonCodec.parse(fresh.agendaJson());
        reviewStore.savePass(fresh.withAgendaJson(AgendaJsonCodec.write(
                AgendaJsonCodec.withStatus(agenda, matcher.group(1), AgendaPhaseStatus.DONE))));
    }

    private String passId()
    {
        return reviewStore.passHistory.get(0).id();
    }

    private ReviewPass seedPass(ReviewPhase phase)
    {
        ReviewPass pass = new ReviewPass(
                UUID.randomUUID().toString(), "thread-1", "acme/widget", 42, "abc123",
                phase, 0, 3, 500L, 0L, null, Instant.now(), null);
        reviewStore.savePass(pass);
        return pass;
    }

    private ReviewFinding seedFinding(
            ReviewPass pass, String path, Integer line,
            ReviewFindingSeverity severity, ReviewFindingStatus status, String body)
    {
        ReviewFinding finding = new ReviewFinding(
                UUID.randomUUID().toString(), pass.id(), path, line,
                severity, status, body, null, null, Instant.now());
        reviewStore.saveFinding(finding);
        return finding;
    }

    private ReviewMessage seedSummaryMessage(ReviewPass pass, String body)
    {
        ReviewParticipant seat = new ReviewParticipant(
                UUID.randomUUID().toString(), pass.id(), ReviewParticipantKind.REVIEWER,
                "claude", "Claude (Anthropic)", null, null, Instant.now());
        reviewStore.saveParticipant(seat);
        return seatMessage(pass, seat.id(), body);
    }

    private static ReviewFinding findingById(ReviewPassDetail detail, String id)
    {
        return detail.findings().stream()
                .filter(f -> f.id().equals(id))
                .findFirst()
                .orElseThrow();
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
}
