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
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.SkillStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.service.credentials.PatResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Panel-of-5 round-trip through the REAL lead + seat compositions
 * (only the provider wire is faked): KICKOFF → INDEPENDENT (parallel
 * fan-out through the real scheduler) → CROSS_REVIEW (one Lead round
 * dispatching all five seats) → CONSENSUS → DEBATE → TERMINATE.
 *
 * <p>Also the SAFETY WALL: across the whole run, the GitHub repository
 * mock must see reads only — no Lead or Seat code path can produce a
 * write to GitHub.
 */
class TestReviewPanelIntegration
{
    private static final List<String> PROVIDERS =
            List.of("claude", "openai", "deepseek", "claude", "openai");
    private static final List<String> LABELS =
            List.of("R1", "R2", "R3", "R4", "R5");

    private final ObjectMapper mapper = new ObjectMapper();

    private InMemoryReviewStore reviewStore;
    private PullRequestRepository pullRequests;
    private TurnRunner turnRunner;
    private ReviewPassService service;
    private AtomicInteger seatTurnPeak;
    private AtomicInteger seatTurnsRunning;

    @BeforeEach
    void setUp()
    {
        reviewStore = new InMemoryReviewStore();
        pullRequests = mock(PullRequestRepository.class);
        turnRunner = mock(TurnRunner.class);
        seatTurnPeak = new AtomicInteger();
        seatTurnsRunning = new AtomicInteger();

        PatResolver patResolver = mock(PatResolver.class);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.fetchPrDetail(anyString(), any(PullRequestRef.class)))
                .thenReturn(rawDetail());
        when(pullRequests.fetchPrDiff(anyString(), any(PullRequestRef.class)))
                .thenReturn("diff --git a/src/A.java b/src/A.java\n+int x = 1;\n");

        AppSettingsStore appSettings = mock(AppSettingsStore.class);
        when(appSettings.get(anyString())).thenReturn(Optional.empty());

        // Five-seat roster via explicit seats — labels are distinct so
        // attribution and @-mentions stay unambiguous.
        LlmReviewerRegistry registry = mock(LlmReviewerRegistry.class);
        List<LlmReviewer> reviewers = new ArrayList<>();
        for (int i = 0; i < PROVIDERS.size(); i++) {
            LlmReviewer reviewer = mock(LlmReviewer.class);
            when(reviewer.providerId()).thenReturn(PROVIDERS.get(i));
            when(reviewer.displayName()).thenReturn(LABELS.get(i));
            when(reviewer.isConfigured()).thenReturn(true);
            reviewers.add(reviewer);
        }
        when(registry.byId(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return reviewers.stream().filter(r -> r.providerId().equals(id)).findFirst();
        });
        when(registry.all()).thenReturn(List.copyOf(reviewers));

        LegacyReviewAdmission admission = mock(LegacyReviewAdmission.class);
        when(admission.invoke(any(), any(), any(), any())).thenAnswer(invocation ->
                invocation.<Callable<Object>>getArgument(3).call());
        when(admission.invokeAll(any())).thenAnswer(invocation -> {
            List<LegacyReviewAdmission.Work<Object>> work = invocation.getArgument(0);
            return work.stream()
                    .map(item -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return item.work().call();
                        }
                        catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }))
                    .toList().stream()
                    .map(CompletableFuture::join)
                    .toList();
        });

        // Endpoint resolution is faked (no credentials in tests); the
        // wire itself is the scripted TurnRunner below.
        ReviewProviderEndpoints endpoints = mock(ReviewProviderEndpoints.class);
        when(endpoints.resolve(anyString())).thenReturn(new ReviewProviderEndpoints.Endpoint(
                TurnSpec.Transport.OPENAI_COMPAT, "http://127.0.0.1:1/x", "k", "test-model"));

        ReviewDiffCache diffCache = new ReviewDiffCache(pullRequests, patResolver);
        ReviewBudgetMeter budget = new ReviewBudgetMeter(reviewStore);
        SeatToolset seatToolset = new SeatToolset(
                reviewStore, diffCache, pullRequests, patResolver, mapper);
        ReviewerSeat reviewerSeat = new ReviewerSeat(
                turnRunner, new SeatContextAssembler(reviewStore), seatToolset,
                endpoints, budget, diffCache, reviewStore, mapper,
                new CliReviewRunner(mapper), new CliReviewSessionRegistry(), admission);
        LeadToolset leadToolset = new LeadToolset(
                reviewStore, seatToolset, reviewerSeat, admission, mapper);
        LeadOrchestrator leadOrchestrator = new LeadOrchestrator(
                turnRunner, new LeadContextAssembler(reviewStore, diffCache), leadToolset,
                endpoints, budget, reviewStore, admission, mapper);

        scriptTurnRunner();

        service = new ReviewPassService(
                mock(ThreadStore.class), reviewStore, pullRequests,
                mock(PullRequestStore.class), patResolver, registry,
                appSettings,
                Runnable::run,
                leadOrchestrator, reviewerSeat, leadToolset, budget, diffCache, admission,
                mock(SkillStore.class),
                event -> {});
    }

    @Test
    void panelOfFiveRoundTripsToTerminateWithBudgetsAndAttributionIntact()
    {
        ReviewPassDetail seated = service.startReviewOnPr(
                "acme/widget", 42, new ReviewPassService.StartOptions(
                        List.of(), 3, 500L, true, "ws-test", null, List.of()));
        ReviewPassDetail detail = service.findPassWithDetail(seated.pass().id()).orElseThrow();

        // Terminated cleanly with the agenda frozen all-DONE.
        assertThat(detail.pass().phase()).isEqualTo(ReviewPhase.TERMINATE);
        List<AgendaPhase> agenda = AgendaJsonCodec.parse(detail.pass().agendaJson());
        assertThat(agenda).hasSize(4);
        assertThat(agenda).allMatch(p -> p.status() == AgendaPhaseStatus.DONE);

        // Every reviewer seat reported exactly one finding, attributed
        // by label, and the Lead classified them all AGREED.
        List<ReviewFinding> findings = detail.findings();
        assertThat(findings).hasSize(5);
        assertThat(findings).allMatch(f -> f.status() == ReviewFindingStatus.AGREED);
        for (String label : LABELS) {
            assertThat(findings).anyMatch(f -> f.body().startsWith("[" + label + "] "));
        }

        // The Lead's cross-review round dispatched every seat with a
        // persisted @-mention message.
        List<ReviewParticipant> participants = detail.participants();
        ReviewParticipant lead = participants.get(0);
        assertThat(lead.kind()).isEqualTo(ReviewParticipantKind.LEAD);
        List<ReviewMessage> dispatches = detail.messages().stream()
                .filter(m -> m.participantId().equals(lead.id()))
                .filter(m -> m.phase() == ReviewPhase.CROSS_REVIEW)
                .filter(m -> !m.mentions().isEmpty())
                .toList();
        assertThat(dispatches).hasSize(5);

        // The cross-review fan-out genuinely overlapped (parallel via
        // the scheduler's API lane, capped at 6).
        assertThat(seatTurnPeak.get()).isGreaterThan(1);

        // Per-seat costs: every reviewer seat spent within its slice
        // and the pass total is within the cap.
        List<ReviewParticipant> seats = participants.stream()
                .filter(p -> p.kind() == ReviewParticipantKind.REVIEWER)
                .toList();
        assertThat(seats).hasSize(5);
        long seatSpend = 0;
        for (ReviewParticipant seat : seats) {
            assertThat(seat.budgetMilliUsdSpent()).isPositive();
            assertThat(seat.budgetMilliUsdSpent()).isLessThanOrEqualTo(seat.budgetMilliUsdCap());
            seatSpend += seat.budgetMilliUsdSpent();
        }
        assertThat(seatSpend).isLessThanOrEqualTo(detail.pass().costCapMilli());
        assertThat(detail.pass().costUsdMilli()).isPositive();
        assertThat(detail.pass().costUsdMilli()).isLessThanOrEqualTo(detail.pass().costCapMilli());

        // ── SAFETY WALL ──────────────────────────────────────────────
        // Across the entire panel run, the GitHub repository saw READS
        // ONLY. No Lead or Seat code path can write to GitHub; the only
        // write path is the user-gated publish, which never ran here.
        Collection<Invocation> invocations = mockingDetails(pullRequests).getInvocations();
        assertThat(invocations).isNotEmpty();
        assertThat(invocations)
                .allMatch(inv -> inv.getMethod().getName().startsWith("fetch"),
                        "the panel run must only read from GitHub");
    }

    // ── Scripted provider wire ──────────────────────────────────────

    /** The fake provider: the Lead's turns follow the directive (set
     *  the agenda at kickoff, dispatch all five seats in one
     *  cross-review round, classify every finding at consensus, close
     *  debate immediately); seat turns report one finding on the
     *  independent pass and just answer on dispatched ones. */
    private void scriptTurnRunner()
    {
        when(turnRunner.runTurn(any(), any(), any())).thenAnswer(inv -> {
            TurnSpec spec = inv.getArgument(0);
            ToolExecutor executor = inv.getArgument(1);
            TurnHooks hooks = inv.getArgument(2);
            String system = systemOf(spec);
            if (system.contains("LEAD of a multi-reviewer")) {
                return leadTurn(spec, executor, hooks);
            }
            return seatTurn(spec, executor);
        });
    }

    private TurnResult leadTurn(TurnSpec spec, ToolExecutor executor, TurnHooks hooks)
    {
        String directive = lastUserText(spec);
        if (directive.contains("Kick off")) {
            executor.execute(call("set_agenda", agendaArgs()));
            return new TurnResult("Agenda set.", 100, 20, 5L, 1, TurnResult.End.COMPLETED);
        }
        if (directive.contains("'" + ReviewPassService.AGENDA_CROSS_REVIEW + "'")) {
            // One round, five parallel dispatches — the real runner
            // announces the batch first, which prefetches through the
            // scheduler; execute() then drains the results.
            List<ToolCall> batch = new ArrayList<>();
            for (int i = 0; i < LABELS.size(); i++) {
                String participantId = reviewerSeatIds().get(i);
                batch.add(call("dispatch_to_reviewer", "{\"participant_id\":\"" + participantId
                        + "\",\"body\":\"@" + LABELS.get(i) + " react to the panel's claims\"}"));
            }
            hooks.onToolCallsParsed(batch);
            for (ToolCall callItem : batch) {
                executor.execute(callItem);
            }
            executor.execute(markDone(ReviewPassService.AGENDA_CROSS_REVIEW));
            return new TurnResult("Cross-review complete.", 200, 30, 5L, 1,
                    TurnResult.End.COMPLETED);
        }
        if (directive.contains("'" + ReviewPassService.AGENDA_CONSENSUS + "'")) {
            for (ReviewFinding f : reviewStore.listFindingsForPass(passId())) {
                executor.execute(call("mark_consensus",
                        "{\"finding_id\":\"" + f.id() + "\",\"status\":\"agreed\"}"));
            }
            executor.execute(markDone(ReviewPassService.AGENDA_CONSENSUS));
            return new TurnResult("All findings agreed.", 150, 25, 5L, 1,
                    TurnResult.End.COMPLETED);
        }
        if (directive.contains("'" + ReviewPassService.AGENDA_DEBATE + "'")) {
            executor.execute(markDone(ReviewPassService.AGENDA_DEBATE));
            return new TurnResult("Nothing left to debate.", 80, 10, 5L, 1,
                    TurnResult.End.COMPLETED);
        }
        return new TurnResult("", 0, 0, 0L, 1, TurnResult.End.COMPLETED);
    }

    private TurnResult seatTurn(TurnSpec spec, ToolExecutor executor)
    {
        int now = seatTurnsRunning.incrementAndGet();
        seatTurnPeak.accumulateAndGet(now, Math::max);
        try {
            // Tiny pause so genuinely parallel dispatches overlap and
            // the peak-concurrency assertion means something.
            Thread.sleep(15);
            String directive = lastUserText(spec);
            if (directive.contains("independent review")) {
                String label = labelOf(spec);
                executor.execute(call("report_finding",
                        "{\"path\":\"src/A.java\",\"line\":1,\"severity\":\"nit\","
                                + "\"summary\":\"finding from " + label + "\"}"));
                return new TurnResult("Reviewed independently.", 120, 40, 7L, 2,
                        TurnResult.End.COMPLETED);
            }
            return new TurnResult("I agree with the claim as quoted.", 90, 15, 4L, 1,
                    TurnResult.End.COMPLETED);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        finally {
            seatTurnsRunning.decrementAndGet();
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────

    private String passId()
    {
        return reviewStore.passHistory.get(0).id();
    }

    private List<String> reviewerSeatIds()
    {
        return reviewStore.listParticipantsForPass(passId()).stream()
                .filter(p -> p.kind() == ReviewParticipantKind.REVIEWER)
                .map(ReviewParticipant::id)
                .toList();
    }

    private static String agendaArgs()
    {
        return "{\"phases\":["
                + "{\"id\":\"" + ReviewPassService.AGENDA_INDEPENDENT + "\",\"title\":\"Run 5 parallel reviews\"},"
                + "{\"id\":\"" + ReviewPassService.AGENDA_CROSS_REVIEW + "\",\"title\":\"Cross-examine\"},"
                + "{\"id\":\"" + ReviewPassService.AGENDA_CONSENSUS + "\",\"title\":\"Classify consensus\"},"
                + "{\"id\":\"" + ReviewPassService.AGENDA_DEBATE + "\",\"title\":\"Debate disputes\"}]}";
    }

    private static ToolCall markDone(String phaseId)
    {
        return callStatic("mark_phase_done", "{\"phase_id\":\"" + phaseId + "\"}");
    }

    private ToolCall call(String name, String argsJson)
    {
        return callStatic(name, argsJson);
    }

    private static ToolCall callStatic(String name, String argsJson)
    {
        try {
            JsonNode parsed = new ObjectMapper().readTree(argsJson);
            return new ToolCall("call-" + System.nanoTime(), name, argsJson, parsed);
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String systemOf(TurnSpec spec)
    {
        if (spec.system() != null) {
            return spec.system();
        }
        JsonNode first = spec.messages().get(0);
        return first != null && "system".equals(first.path("role").asText())
                ? first.path("content").asText("")
                : "";
    }

    private static String lastUserText(TurnSpec spec)
    {
        for (int i = spec.messages().size() - 1; i >= 0; i--) {
            JsonNode node = spec.messages().get(i);
            if ("user".equals(node.path("role").asText())) {
                return node.path("content").asText("");
            }
        }
        return "";
    }

    /** Which seat is this turn for? The seat system prompt names the
     *  reviewer label. */
    private static String labelOf(TurnSpec spec)
    {
        String system = systemOf(spec);
        for (String label : LABELS) {
            if (system.contains("\"" + label + "\"")) {
                return label;
            }
        }
        return "unknown";
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
