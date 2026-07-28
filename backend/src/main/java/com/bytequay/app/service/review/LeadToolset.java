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
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * The Lead's tool surface: the agenda verbs ({@code set_agenda},
 * {@code mark_phase_*}), reviewer dispatch, consensus + dissent
 * recording, and the same read-only code tools the seats get. No
 * write tool reaches code or GitHub — the Lead orchestrates the panel
 * and the findings table, nothing else.
 *
 * <p>When one Lead turn carries several {@code dispatch_to_reviewer}
 * calls, the round's batch is fanned out concurrently through shared
 * review admission, and the
 * results come back in dispatch order — that is what makes a
 * five-reviewer panel cost one Lead round of wall-clock, not five.
 */
@Component
public class LeadToolset
{
    private static final Logger log = LoggerFactory.getLogger(LeadToolset.class);

    /** Per-finding debate spend ceiling in milli-USD ($0.10) — a
     *  single finding's debate dispatches never spend past this. */
    static final long DEBATE_COST_CAP_MILLI = 100L;

    private final ReviewStore reviewStore;
    private final SeatToolset readTools;
    private final ReviewerSeat reviewerSeat;
    private final LegacyReviewAdmission reviewAdmission;
    private final ObjectMapper mapper;

    public LeadToolset(
            ReviewStore reviewStore,
            SeatToolset readTools,
            ReviewerSeat reviewerSeat,
            LegacyReviewAdmission reviewAdmission,
            ObjectMapper mapper)
    {
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.readTools = requireNonNull(readTools, "readTools is null");
        this.reviewerSeat = requireNonNull(reviewerSeat, "reviewerSeat is null");
        this.reviewAdmission = requireNonNull(reviewAdmission, "reviewAdmission is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** The Lead catalog rendered for the Lead seat's transport. */
    public ArrayNode toolsArray(TurnSpec.Transport transport)
    {
        return ReviewToolSchemas.render(mapper, transport, List.of(
                ReviewToolSchemas.SET_AGENDA,
                ReviewToolSchemas.MARK_PHASE_IN_PROGRESS,
                ReviewToolSchemas.MARK_PHASE_DONE,
                ReviewToolSchemas.DISPATCH_TO_REVIEWER,
                ReviewToolSchemas.MARK_CONSENSUS,
                ReviewToolSchemas.RECORD_DISSENT,
                ReviewToolSchemas.GET_PR_DIFF,
                ReviewToolSchemas.GET_FILE_CONTENT,
                ReviewToolSchemas.SEARCH_CODE));
    }

    /** One pass run's Lead tool session. Holds the per-finding debate
     *  ledger (in-memory working state for the run) and the roster. */
    public Session sessionFor(String passId, PanelSeatConfig roster, String leadParticipantId)
    {
        return new Session(passId, roster, leadParticipantId);
    }

    public final class Session
    {
        private final String passId;
        private final PanelSeatConfig roster;
        private final String leadParticipantId;
        /** Per-finding debate spend, milli-USD. Working state for one
         *  pass run; intentionally not persisted. */
        private final ConcurrentHashMap<String, Long> debateLedger = new ConcurrentHashMap<>();

        private Session(String passId, PanelSeatConfig roster, String leadParticipantId)
        {
            this.passId = requireNonNull(passId, "passId is null");
            this.roster = requireNonNull(roster, "roster is null");
            this.leadParticipantId = requireNonNull(leadParticipantId, "leadParticipantId is null");
        }

        /** Executor for one Lead round. {@code prefetch} (wired to
         *  {@code TurnHooks.onToolCallsParsed}) fans multi-dispatch
         *  rounds out in parallel; {@code execute} then serves the
         *  prefetched results in order. */
        public RoundExecutor roundExecutor(ReviewPhase phase, int round)
        {
            return new RoundExecutor(this, phase, round);
        }

        private ReviewPass freshPass()
        {
            return reviewStore.findPassById(passId)
                    .orElseThrow(() -> new IllegalStateException("no review pass: " + passId));
        }
    }

    /** Per-round executor: dispatch prefetching + the verb handlers. */
    public final class RoundExecutor
            implements ToolExecutor
    {
        private final Session session;
        private final ReviewPhase phase;
        private final int round;
        private final Map<String, ToolCallResult> prefetched = new ConcurrentHashMap<>();

        private RoundExecutor(Session session, ReviewPhase phase, int round)
        {
            this.session = session;
            this.phase = phase;
            this.round = round;
        }

        /** Fan out this round's reviewer dispatches concurrently
         *  through the scheduler's API lane. Lead messages persist
         *  sequentially first (stable transcript order), then the
         *  seat turns run in parallel; results land keyed by call id
         *  so {@link #execute} returns them in dispatch order. */
        public void prefetch(List<ToolCall> calls)
        {
            List<ToolCall> dispatches = calls.stream()
                    .filter(c -> "dispatch_to_reviewer".equals(c.name()))
                    .toList();
            if (dispatches.size() < 2) {
                return;
            }
            List<LegacyReviewAdmission.Work<ToolCallResult>> work = new ArrayList<>();
            List<ToolCall> accepted = new ArrayList<>();
            for (ToolCall call : dispatches) {
                Dispatch dispatch;
                try {
                    dispatch = prepareDispatch(session, phase, round, call);
                }
                catch (DispatchRejected rejected) {
                    prefetched.put(call.id(), rejected.result);
                    continue;
                }
                accepted.add(call);
                ReviewPass pass = session.freshPass();
                PanelSeatConfig.Seat seat = session.roster
                        .byParticipantId(dispatch.participantId())
                        .orElseThrow(() -> new IllegalStateException(
                                "dispatched reviewer is missing from the roster"));
                LegacyReviewAdmission.ProviderLane lane =
                        CliReviewRunner.Provider.isCliProvider(seat.providerId())
                                ? LegacyReviewAdmission.ProviderLane.CLI
                                : LegacyReviewAdmission.ProviderLane.API;
                String attemptId = ReviewerSeat.attemptId(
                        dispatch.participantId(), dispatch.body(), phase, round,
                        dispatch.leadMessageId());
                work.add(new LegacyReviewAdmission.Work<>(
                        pass, lane, attemptId,
                        () -> runDispatch(
                                session, phase, round, dispatch,
                                /* capacityHeld */ true)));
            }
            if (work.isEmpty()) {
                return;
            }
            List<ToolCallResult> results;
            try {
                results = reviewAdmission.invokeAll(work);
            }
            catch (LegacyReviewAdmission.ReviewCapacityUnavailableException unavailable) {
                accepted.forEach(call -> prefetched.put(
                        call.id(),
                        ToolCallResult.error(
                                "Review capacity is busy; retry this reviewer dispatch.")));
                return;
            }
            for (int i = 0; i < accepted.size(); i++) {
                prefetched.put(accepted.get(i).id(), results.get(i));
            }
        }

        @Override
        public ToolCallResult execute(ToolCall call)
        {
            ToolCallResult ready = prefetched.remove(call.id());
            if (ready != null) {
                return ready;
            }
            return dispatchTool(session, phase, round, call);
        }
    }

    private ToolExecutor.ToolCallResult dispatchTool(
            Session session, ReviewPhase phase, int round, ToolCall call)
    {
        try {
            return switch (call.name()) {
                case "set_agenda" -> setAgenda(session, call);
                case "mark_phase_in_progress" -> markPhase(session, call, AgendaPhaseStatus.IN_PROGRESS);
                case "mark_phase_done" -> markPhase(session, call, AgendaPhaseStatus.DONE);
                case "dispatch_to_reviewer" -> dispatchToReviewer(session, phase, round, call);
                case "mark_consensus" -> markConsensus(session, call);
                case "record_dissent" -> recordDissent(session, phase, round, call);
                case "get_pr_diff" -> readTools.getPrDiff(session.freshPass(), call);
                case "get_file_content" -> readTools.getFileContent(session.freshPass(), call);
                case "search_code" -> readTools.searchCode(session.freshPass(), call);
                default -> error(422, "tool_not_available_to_lead", "tool", call.name());
            };
        }
        catch (RuntimeException e) {
            log.warn("Lead tool {} failed on pass {}: {}", call.name(), session.passId, e.getMessage());
            return ToolExecutor.ToolCallResult.error(
                    "Tool '" + call.name() + "' failed: " + e.getMessage());
        }
    }

    // ── Agenda verbs ──────────────────────────────────────────────────

    private ToolExecutor.ToolCallResult setAgenda(Session session, ToolCall call)
    {
        ReviewPass pass = session.freshPass();
        if (pass.agendaJson() != null && !AgendaJsonCodec.parse(pass.agendaJson()).isEmpty()) {
            // Once set, the agenda is an artifact, not a scratchpad.
            return error(409, "agenda_already_set",
                    "hint", "The agenda can only be set once, at kickoff.");
        }
        JsonNode phases = call.input().path("phases");
        if (!phases.isArray() || phases.isEmpty()) {
            return ToolExecutor.ToolCallResult.error("'phases' must be a non-empty array.");
        }
        List<AgendaPhase> agenda = new ArrayList<>();
        for (JsonNode node : phases) {
            String id = node.path("id").asText("");
            String title = node.path("title").asText("");
            if (id.isBlank() || title.isBlank()) {
                return ToolExecutor.ToolCallResult.error(
                        "Every agenda phase needs a non-blank 'id' and 'title'.");
            }
            agenda.add(new AgendaPhase(id, title, AgendaPhaseStatus.OPEN));
        }
        reviewStore.savePass(pass.withAgendaJson(AgendaJsonCodec.write(agenda)));
        ObjectNode ok = mapper.createObjectNode();
        ok.put("status", "agenda_set");
        ok.put("phases", agenda.size());
        return ToolExecutor.ToolCallResult.ok(ok.toString());
    }

    private ToolExecutor.ToolCallResult markPhase(
            Session session, ToolCall call, AgendaPhaseStatus status)
    {
        String phaseId = call.input().path("phase_id").asText("");
        if (phaseId.isBlank()) {
            return ToolExecutor.ToolCallResult.error("'phase_id' is required.");
        }
        ReviewPass pass = session.freshPass();
        List<AgendaPhase> agenda = AgendaJsonCodec.parse(pass.agendaJson());
        if (agenda.stream().noneMatch(p -> p.id().equals(phaseId))) {
            return error(404, "unknown_agenda_phase", "phase_id", phaseId);
        }
        reviewStore.savePass(pass.withAgendaJson(
                AgendaJsonCodec.write(AgendaJsonCodec.withStatus(agenda, phaseId, status))));
        ObjectNode ok = mapper.createObjectNode();
        ok.put("phase_id", phaseId);
        ok.put("status", status.jsonValue());
        return ToolExecutor.ToolCallResult.ok(ok.toString());
    }

    // ── Dispatch ──────────────────────────────────────────────────────

    /** Validated, ready-to-run dispatch: the lead's mention message is
     *  already persisted (sequentially, so transcript order is the
     *  dispatch order even when the seat turns run in parallel). */
    private record Dispatch(String participantId, String body, String findingId, String leadMessageId)
    {
    }

    private static final class DispatchRejected
            extends RuntimeException
    {
        private final transient ToolExecutor.ToolCallResult result;

        private DispatchRejected(ToolExecutor.ToolCallResult result)
        {
            this.result = result;
        }
    }

    private Dispatch prepareDispatch(Session session, ReviewPhase phase, int round, ToolCall call)
    {
        String participantId = call.input().path("participant_id").asText("");
        String body = call.input().path("body").asText("");
        String findingId = call.input().path("finding_id").asText("");
        PanelSeatConfig.Seat seat = session.roster.byParticipantId(participantId)
                .filter(s -> !s.lead())
                .orElse(null);
        if (seat == null) {
            throw new DispatchRejected(error(404, "unknown_reviewer_seat",
                    "participant_id", participantId));
        }
        if (!body.contains("@" + seat.displayLabel())) {
            throw new DispatchRejected(error(422, "missing_reviewer_mention",
                    "hint", "The body must @-mention @" + seat.displayLabel() + "."));
        }
        if (!findingId.isBlank()) {
            long spent = session.debateLedger.getOrDefault(findingId, 0L);
            if (spent >= DEBATE_COST_CAP_MILLI) {
                throw new DispatchRejected(error(429, "finding_debate_budget_exhausted",
                        "finding_id", findingId));
            }
        }
        ReviewMessage leadMessage = new ReviewMessage(
                UUID.randomUUID().toString(),
                session.passId,
                session.leadParticipantId,
                phase,
                round,
                body,
                List.of(participantId),
                /* refs */ findingId.isBlank() ? List.of() : List.of("finding:" + findingId),
                /* costUsdMilli */ 0L,
                Instant.now());
        reviewStore.saveMessage(leadMessage);
        return new Dispatch(participantId, body,
                findingId.isBlank() ? null : findingId, leadMessage.id());
    }

    private ToolExecutor.ToolCallResult runDispatch(
            Session session, ReviewPhase phase, int round, Dispatch dispatch)
    {
        return runDispatch(session, phase, round, dispatch, false);
    }

    private ToolExecutor.ToolCallResult runDispatch(
            Session session,
            ReviewPhase phase,
            int round,
            Dispatch dispatch,
            boolean capacityHeld)
    {
        try {
            ReviewMessage reply = capacityHeld
                    ? reviewerSeat.runDispatchedTurnAlreadyAdmitted(
                            session.freshPass(), session.roster, dispatch.participantId(),
                            dispatch.body(), phase, round, dispatch.leadMessageId(), true)
                    : reviewerSeat.runDispatchedTurn(
                            session.freshPass(), session.roster, dispatch.participantId(),
                            dispatch.body(), phase, round, dispatch.leadMessageId());
            if (dispatch.findingId() != null) {
                session.debateLedger.merge(dispatch.findingId(), reply.costUsdMilli(), Long::sum);
            }
            return ToolExecutor.ToolCallResult.ok(reply.body());
        }
        catch (ReviewerSeat.SeatBudgetExhaustedException e) {
            return error(429, "seat_budget_exhausted",
                    "participant_id", dispatch.participantId());
        }
        catch (RuntimeException e) {
            return ToolExecutor.ToolCallResult.error(
                    "Dispatch to " + dispatch.participantId() + " failed: " + e.getMessage());
        }
    }

    private ToolExecutor.ToolCallResult dispatchToReviewer(
            Session session, ReviewPhase phase, int round, ToolCall call)
    {
        Dispatch dispatch;
        try {
            dispatch = prepareDispatch(session, phase, round, call);
        }
        catch (DispatchRejected rejected) {
            return rejected.result;
        }
        return runDispatch(session, phase, round, dispatch);
    }

    // ── Consensus / dissent ───────────────────────────────────────────

    private ToolExecutor.ToolCallResult markConsensus(Session session, ToolCall call)
    {
        String findingId = call.input().path("finding_id").asText("");
        ReviewFinding finding = reviewStore.findFindingById(findingId).orElse(null);
        if (finding == null || !finding.reviewPassId().equals(session.passId)) {
            return error(404, "unknown_finding", "finding_id", findingId);
        }
        ReviewFindingStatus status = switch (call.input().path("status").asText("")
                .toLowerCase(Locale.ROOT)) {
            case "agreed" -> ReviewFindingStatus.AGREED;
            case "disputed" -> ReviewFindingStatus.DISPUTED;
            case "dropped" -> ReviewFindingStatus.DROPPED;
            default -> null;
        };
        if (status == null) {
            return ToolExecutor.ToolCallResult.error(
                    "'status' must be one of agreed, disputed, dropped.");
        }
        String severityRaw = call.input().path("severity").asText("");
        reviewStore.saveFinding(new ReviewFinding(
                finding.id(), finding.reviewPassId(), finding.path(), finding.line(),
                severityRaw.isBlank() ? finding.severity() : SeatToolset.severityFrom(severityRaw),
                status,
                finding.body(),
                finding.resolution(), finding.postedCommentId(), finding.createdAt(),
                finding.debateStatus(), finding.debateRounds()));
        ObjectNode ok = mapper.createObjectNode();
        ok.put("finding_id", findingId);
        ok.put("status", status.dbValue());
        return ToolExecutor.ToolCallResult.ok(ok.toString());
    }

    private ToolExecutor.ToolCallResult recordDissent(
            Session session, ReviewPhase phase, int round, ToolCall call)
    {
        String findingId = call.input().path("finding_id").asText("");
        String reviewerId = call.input().path("reviewer_id").asText("");
        String body = call.input().path("body").asText("");
        ReviewFinding finding = reviewStore.findFindingById(findingId).orElse(null);
        if (finding == null || !finding.reviewPassId().equals(session.passId)) {
            return error(404, "unknown_finding", "finding_id", findingId);
        }
        if (session.roster.byParticipantId(reviewerId).isEmpty()) {
            return error(404, "unknown_reviewer_seat", "participant_id", reviewerId);
        }
        if (body.isBlank()) {
            return ToolExecutor.ToolCallResult.error("'body' is required.");
        }
        // Authored by the Lead (it is the one recording), mentioning
        // the dissenting reviewer and #ref-ing the finding — the
        // finding's status is deliberately untouched.
        reviewStore.saveMessage(new ReviewMessage(
                UUID.randomUUID().toString(),
                session.passId,
                session.leadParticipantId,
                phase,
                round,
                body,
                List.of(reviewerId),
                List.of("finding:" + findingId),
                "dissent",
                /* payloadJson */ null,
                /* costUsdMilli */ 0L,
                Instant.now()));
        ObjectNode ok = mapper.createObjectNode();
        ok.put("finding_id", findingId);
        ok.put("status", "dissent_recorded");
        return ToolExecutor.ToolCallResult.ok(ok.toString());
    }

    private ToolExecutor.ToolCallResult error(int status, String code, String key, String value)
    {
        ObjectNode err = mapper.createObjectNode();
        err.put("error", code);
        err.put("status", status);
        err.put(key, value);
        return ToolExecutor.ToolCallResult.error(err.toString());
    }
}
