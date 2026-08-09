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

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.skills.CavemanPrompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * One reviewer seat's turn machinery: composes the shared
 * {@link TurnRunner} with the FILTERED context assembler, the
 * read-only {@link SeatToolset}, {@code review_messages} persistence,
 * and per-seat budget metering. A seat never sees another reviewer's
 * stream (the assembler enforces that), never gets a write tool (the
 * toolset enforces that), and never spends past its slice (the budget
 * hooks enforce that).
 *
 * <p>Adding a sixth reviewer to a panel is one more participant row
 * driven through this same component — there is no per-reviewer agent
 * class.
 */
@Component
public class ReviewerSeat
{
    private static final Logger log = LoggerFactory.getLogger(ReviewerSeat.class);

    /** Bound on one seat turn's tool-use loop. */
    private static final int MAX_TOOL_ITERATIONS = 8;
    private static final int MAX_OUTPUT_TOKENS = 4_096;
    /** Diff slice inlined into the seat's first user message. The
     *  seat can pull more via get_pr_diff / get_file_content. */
    private static final int MAX_INLINE_DIFF_CHARS = 60_000;
    /** Smaller slice for a CLI seat wired to the review MCP server: it has
     *  a real get_pr_diff, so the inline copy only needs to orient it. */
    private static final int MCP_INLINE_DIFF_CHARS = 8_000;

    private final TurnRunner runner;
    private final SeatContextAssembler contextAssembler;
    private final SeatToolset toolset;
    private final ReviewProviderEndpoints endpoints;
    private final ReviewBudgetMeter budget;
    private final ReviewDiffCache diffCache;
    private final ReviewStore reviewStore;
    private final ObjectMapper mapper;
    private final CliReviewRunner cliRunner;
    private final Map<String, String> cliSessions = new ConcurrentHashMap<>();
    private final ReviewCallContext calls;

    public ReviewerSeat(
            TurnRunner runner,
            SeatContextAssembler contextAssembler,
            SeatToolset toolset,
            ReviewProviderEndpoints endpoints,
            ReviewBudgetMeter budget,
            ReviewDiffCache diffCache,
            ReviewStore reviewStore,
            ObjectMapper mapper,
            CliReviewRunner cliRunner,
            ReviewCallContext calls)
    {
        this.runner = requireNonNull(runner, "runner is null");
        this.contextAssembler = requireNonNull(contextAssembler, "contextAssembler is null");
        this.toolset = requireNonNull(toolset, "toolset is null");
        this.endpoints = requireNonNull(endpoints, "endpoints is null");
        this.budget = requireNonNull(budget, "budget is null");
        this.diffCache = requireNonNull(diffCache, "diffCache is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.cliRunner = requireNonNull(cliRunner, "cliRunner is null");
        this.calls = requireNonNull(calls, "calls is null");
    }

    /** Thrown when a dispatch would spend past the seat's slice. The
     *  lead's dispatch tool catches it and returns a structured error
     *  instead of overspending. */
    public static class SeatBudgetExhaustedException
            extends RuntimeException
    {
        public SeatBudgetExhaustedException(String message)
        {
            super(message);
        }
    }

    /**
     * Run one dispatched seat turn: assemble the filtered context,
     * run the bounded tool loop, persist the reply as the seat's
     * {@code review_messages} row, and meter the spend.
     *
     * @param directive the new user-turn body (the lead's dispatch or
     *        the spine's independent-review prompt).
     * @param excludeMessageId the persisted dispatch message to leave
     *        out of history (it rides as {@code directive} instead);
     *        null when the directive was never persisted.
     */
    public ReviewMessage runDispatchedTurn(
            ReviewPass pass,
            PanelSeatConfig roster,
            String participantId,
            String directive,
            ReviewPhase phase,
            int round,
            String excludeMessageId)
    {
        return runDispatchedTurn(pass, roster, participantId, directive, phase, round,
                excludeMessageId, /* enforceBudget */ true);
    }

    /**
     * Variant that can run UNBUDGETED ({@code enforceBudget=false}): the
     * seat-slice gate and the cost-cap abort are skipped so a human-steered
     * turn still answers even after the pass spent its cap. The spend is
     * still metered, so the overage surfaces in the budget meter.
     */
    public ReviewMessage runDispatchedTurn(
            ReviewPass pass,
            PanelSeatConfig roster,
            String participantId,
            String directive,
            ReviewPhase phase,
            int round,
            String excludeMessageId,
            boolean enforceBudget)
    {
        return runDispatchedTurn(
                pass, roster, participantId, directive, phase, round,
                excludeMessageId, enforceBudget, false);
    }

    ReviewMessage runDispatchedTurnAlreadyAdmitted(
            ReviewPass pass,
            PanelSeatConfig roster,
            String participantId,
            String directive,
            ReviewPhase phase,
            int round,
            String excludeMessageId,
            boolean enforceBudget)
    {
        return runDispatchedTurn(
                pass, roster, participantId, directive, phase, round,
                excludeMessageId, enforceBudget, true);
    }

    private ReviewMessage runDispatchedTurn(
            ReviewPass pass,
            PanelSeatConfig roster,
            String participantId,
            String directive,
            ReviewPhase phase,
            int round,
            String excludeMessageId,
            boolean enforceBudget,
            boolean capacityHeld)
    {
        requireNonNull(pass, "pass is null");
        requireNonNull(roster, "roster is null");
        requireNonNull(directive, "directive is null");
        if (enforceBudget && !budget.seatHasBudget(participantId)) {
            throw new SeatBudgetExhaustedException(
                    "Seat " + participantId + " has exhausted its budget slice.");
        }
        PanelSeatConfig.Seat seat = roster.byParticipantId(participantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "participant " + participantId + " is not on the pass roster"));

        // CLI seats (Claude/Codex CLI) run as their own agents through a
        // subprocess, not the API TurnRunner — branch off here.
        if (CliReviewRunner.Provider.isCliProvider(seat.providerId())) {
            return runCliTurn(
                    pass, seat, participantId, directive, phase, round,
                    attemptId(participantId, directive, phase, round, excludeMessageId),
                    capacityHeld);
        }

        ReviewProviderEndpoints.Endpoint endpoint = endpoints.resolve(seat.providerId());
        String system = CavemanPrompt.wrap(systemPrompt(seat));
        ArrayNode messages = providerMessages(endpoint.transport(), system,
                pass, participantId, directive, excludeMessageId);

        ToolExecutor executor = toolset.executorFor(pass, participantId, seat.displayLabel());
        TurnHooks hooks = enforceBudget
                ? budgetHooks(pass.id(), participantId)
                : new TurnHooks() { };

        String attemptId = attemptId(
                participantId, directive, phase, round, excludeMessageId);
        Supplier<TurnResult> launch = () -> runner.runTurn(
                new TurnSpec(
                        endpoint.transport(), endpoint.url(), endpoint.authToken(),
                        endpoint.modelId(),
                        endpoint.transport() == TurnSpec.Transport.ANTHROPIC ? system : null,
                        messages, toolset.toolsArray(endpoint.transport()),
                        MAX_OUTPUT_TOKENS, MAX_TOOL_ITERATIONS),
                executor, hooks);
        TurnResult result;
        if (capacityHeld) {
            calls.requireCurrent(
                    pass, ReviewCallContext.ProviderLane.API, attemptId);
            result = launch.get();
        }
        else {
            result = calls.invoke(
                    pass,
                    ReviewCallContext.ProviderLane.API,
                    attemptId,
                    launch::get);
        }

        String body = result.finalText() == null || result.finalText().isBlank()
                ? (result.end() == TurnResult.End.ABORTED
                        ? "(turn stopped at the seat's budget slice)"
                        : "(no reply)")
                : result.finalText().strip();
        ReviewMessage message = new ReviewMessage(
                UUID.randomUUID().toString(),
                pass.id(),
                participantId,
                phase,
                round,
                body,
                /* mentions */ List.of(),
                /* refs */ List.of(),
                result.costMilliUsd(),
                Instant.now());
        reviewStore.saveMessage(message);
        budget.chargeSeat(pass.id(), participantId, result.costMilliUsd());
        // Diagnostic: a seat that never enters a tool round (rounds <= 1)
        // emitted no tool calls — so during INDEPENDENT it recorded no
        // report_finding and contributes nothing to the structured findings
        // the right rail + publish read. Surfaces a model replying in prose
        // instead of using its tools (the empty-findings failure mode).
        if (result.rounds() <= 1) {
            log.info("Reviewer seat {} ({} via {}) finished {} in one round with no tool "
                            + "calls — no report_finding emitted.",
                    seat.displayLabel(), endpoint.modelId(), endpoint.transport(), phase);
        }
        else {
            log.debug("Reviewer seat {} ({}) {}: {} rounds.",
                    seat.displayLabel(), endpoint.modelId(), phase, result.rounds());
        }
        return message;
    }

    /**
     * Run one turn for a CLI seat (Claude/Codex CLI). Spawns the agent
     * (resuming its session so it keeps prior-phase context), parses the
     * findings block it emits into structured findings, and persists the
     * prose as the seat's review_messages row.
     */
    private ReviewMessage runCliTurn(
            ReviewPass pass,
            PanelSeatConfig.Seat seat,
            String participantId,
            String directive,
            ReviewPhase phase,
            int round,
            String attemptId,
            boolean capacityHeld)
    {
        CliReviewRunner.Provider provider = CliReviewRunner.Provider.of(seat.providerId());
        // The MCP bridge — real review tools incl. report_finding — is wired
        // for Claude only today. Codex falls back to the JSON-block protocol.
        boolean mcp = provider == CliReviewRunner.Provider.CLAUDE;
        String resume = cliSessions.get(participantId);
        boolean resuming = resume != null && !resume.isBlank();
        String prompt = cliPrompt(pass, seat, directive, resuming, mcp);
        CliReviewRunner.McpEndpoint endpoint = mcp
                ? new CliReviewRunner.McpEndpoint(pass.id(), participantId)
                : null;

        Supplier<CliReviewRunner.Result> launch = () -> cliRunner.run(
                provider, prompt, resume,
                Path.of(System.getProperty("java.io.tmpdir")), endpoint);
        CliReviewRunner.Result result;
        try {
            if (capacityHeld) {
                calls.requireCurrent(
                        pass, ReviewCallContext.ProviderLane.CLI, attemptId);
                result = launch.get();
            }
            else {
                result = calls.invoke(
                        pass,
                        ReviewCallContext.ProviderLane.CLI,
                        attemptId,
                        launch::get);
            }
        }
        catch (CliReviewException e) {
            log.warn("CLI reviewer seat {} ({}) {} failed: {}",
                    seat.displayLabel(), seat.providerId(), phase, e.getMessage());
            return persistSeatMessage(pass, participantId, phase, round,
                    "(CLI reviewer failed: " + e.getMessage() + ")", 0);
        }

        if (result.sessionId() != null && !result.sessionId().isBlank()) {
            cliSessions.put(participantId, result.sessionId());
        }

        if (mcp) {
            // Findings were written live through the MCP report_finding tool
            // — nothing to parse out of the prose.
            log.info("CLI reviewer seat {} ({}) {}: ran via MCP review tools.",
                    seat.displayLabel(), seat.providerId(), phase);
            String body = result.text().strip();
            return persistSeatMessage(pass, participantId, phase, round,
                    body.isBlank() ? "(no review text)" : body, result.costUsdMilli());
        }

        // JSON-block fallback (Codex): parse the structured findings out.
        List<CliReviewFindings.Parsed> parsed = CliReviewFindings.parse(result.text(), mapper);
        for (CliReviewFindings.Parsed finding : parsed) {
            reviewStore.saveFinding(new ReviewFinding(
                    UUID.randomUUID().toString(),
                    pass.id(),
                    finding.path(), finding.line(),
                    finding.severity(),
                    ReviewFindingStatus.REPORTED,
                    "[" + seat.displayLabel() + "] " + finding.summary(),
                    /* resolution */ null,
                    /* postedCommentId */ null,
                    Instant.now()));
        }
        log.info("CLI reviewer seat {} ({}) {}: recorded {} finding(s) from the JSON block.",
                seat.displayLabel(), seat.providerId(), phase, parsed.size());

        String body = stripFindingsBlock(result.text());
        return persistSeatMessage(pass, participantId, phase, round,
                body.isBlank() ? "(no review text)" : body, result.costUsdMilli());
    }

    static String attemptId(
            String participantId,
            String directive,
            ReviewPhase phase,
            int round,
            String excludeMessageId)
    {
        String discriminator = excludeMessageId == null || excludeMessageId.isBlank()
                ? directive
                : excludeMessageId;
        return ReviewCallContext.attemptId(
                "seat", participantId, phase, round, discriminator);
    }

    /** Compose the single prompt string a CLI seat gets. On the first turn
     *  it carries the persona + inlined diff; resumed turns rely on the
     *  CLI's own session for that and send only the directive. When the seat
     *  has no MCP tools (Codex) the findings-as-JSON instruction rides along;
     *  with MCP (Claude) the seat calls report_finding directly, so it's
     *  omitted and the persona's tool instructions apply as written. */
    private String cliPrompt(
            ReviewPass pass, PanelSeatConfig.Seat seat, String directive, boolean resuming, boolean mcp)
    {
        StringBuilder sb = new StringBuilder();
        if (!resuming) {
            sb.append(systemPrompt(seat)).append("\n\n");
            // An MCP seat has a real get_pr_diff, so it only needs a small
            // orienting slice; a no-tool seat (Codex) gets the full inline.
            sb.append(mcp ? diffHeader(pass, MCP_INLINE_DIFF_CHARS) : diffHeader(pass)).append("\n\n");
        }
        sb.append(directive);
        if (!mcp) {
            sb.append("\n\n").append(CliReviewFindings.INSTRUCTION);
        }
        return CavemanPrompt.wrap(sb.toString());
    }

    /** Drop the trailing findings block from the prose shown in the
     *  transcript — the structured findings already landed on the rail. */
    private static String stripFindingsBlock(String text)
    {
        if (text == null) {
            return "";
        }
        int marker = text.lastIndexOf(CliReviewFindings.BEGIN);
        return (marker >= 0 ? text.substring(0, marker) : text).strip();
    }

    /** Persist a seat's reply + meter its spend. Shared by the API and CLI
     *  paths so both land a consistent review_messages row. */
    private ReviewMessage persistSeatMessage(
            ReviewPass pass, String participantId, ReviewPhase phase, int round, String body, long costMilliUsd)
    {
        ReviewMessage message = new ReviewMessage(
                UUID.randomUUID().toString(),
                pass.id(),
                participantId,
                phase,
                round,
                body,
                /* mentions */ List.of(),
                /* refs */ List.of(),
                costMilliUsd,
                Instant.now());
        reviewStore.saveMessage(message);
        budget.chargeSeat(pass.id(), participantId, costMilliUsd);
        return message;
    }

    /** Stop the loop at a round boundary once this turn's running
     *  cost reaches whichever is smaller: the seat's remaining slice
     *  or the pass's remaining budget. Never overspends silently. */
    private TurnHooks budgetHooks(String passId, String participantId)
    {
        return new TurnHooks()
        {
            @Override
            public boolean abortTurn(long costSoFarMilliUsd)
            {
                long remaining = Math.min(
                        budget.seatRemaining(participantId),
                        budget.passRemaining(passId));
                return costSoFarMilliUsd >= remaining;
            }
        };
    }

    private String systemPrompt(PanelSeatConfig.Seat seat)
    {
        StringBuilder sb = new StringBuilder();
        if (seat.personaPrompt() != null && !seat.personaPrompt().isBlank()) {
            sb.append("Reviewer voice:\n").append(seat.personaPrompt().strip()).append("\n\n");
        }
        sb.append("""
                You are reviewer "%s" on a multi-reviewer code-review panel. You see only \
                the panel lead's messages addressed to you and your own prior replies — \
                form your own judgement, do not assume what other reviewers said unless \
                the lead quoted them for you. Use the read-only tools (get_pr_diff, \
                get_file_content, search_code) to check the actual code before making \
                claims, and record every concrete issue with report_finding — one \
                report_finding call per issue. Do NOT just describe findings in prose: a \
                finding that isn't recorded with report_finding does not exist to the rest \
                of the panel or the publish step. \
                ANCHOR EVERY CODE-SPECIFIC FINDING TO A LINE: pass both "path" (the diff \
                file) and "line" (the new-file line number from the '+' side of the hunk) \
                so it can post as an inline comment. Omit path/line only for a genuinely \
                PR-wide observation; a code issue reported without a line silently \
                degrades to a whole-PR note. \
                BE TERSE: one short line per finding (claim + where), no preamble, no \
                restating the task, no long explanations or background. Your final reply \
                is a brief bullet list of findings plus a one-line overall stance — a few \
                sentences at most, never multiple paragraphs.\
                """.formatted(seat.displayLabel()));
        return sb.toString();
    }

    /** Build the provider-shaped message history: the inlined diff
     *  header + the seat's filtered transcript, merged so roles
     *  alternate (the Anthropic API rejects consecutive same-role
     *  messages), ending with the directive as the new user turn. */
    private ArrayNode providerMessages(
            TurnSpec.Transport transport,
            String system,
            ReviewPass pass,
            String participantId,
            String directive,
            String excludeMessageId)
    {
        List<SeatContextAssembler.SeatMessage> history =
                contextAssembler.assemble(pass, participantId, excludeMessageId);

        ArrayNode messages = mapper.createArrayNode();
        if (transport == TurnSpec.Transport.OPENAI_COMPAT && system != null) {
            ObjectNode sys = mapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", system);
            messages.add(sys);
        }

        StringBuilder pendingUser = new StringBuilder(diffHeader(pass));
        for (SeatContextAssembler.SeatMessage m : history) {
            if ("assistant".equals(m.role())) {
                if (pendingUser.length() > 0) {
                    messages.add(text("user", pendingUser.toString()));
                    pendingUser.setLength(0);
                }
                appendOrMergeAssistant(messages, m.text());
            }
            else {
                if (pendingUser.length() > 0) {
                    pendingUser.append("\n\n");
                }
                pendingUser.append(m.text());
            }
        }
        if (pendingUser.length() > 0) {
            pendingUser.append("\n\n");
        }
        pendingUser.append(directive);
        messages.add(text("user", pendingUser.toString()));
        return messages;
    }

    private void appendOrMergeAssistant(ArrayNode messages, String text)
    {
        if (!messages.isEmpty()) {
            ObjectNode last = (ObjectNode) messages.get(messages.size() - 1);
            if ("assistant".equals(last.path("role").asText())) {
                last.put("content", last.path("content").asText() + "\n\n" + text);
                return;
            }
        }
        messages.add(text("assistant", text));
    }

    private ObjectNode text(String role, String content)
    {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private String diffHeader(ReviewPass pass)
    {
        return diffHeader(pass, MAX_INLINE_DIFF_CHARS);
    }

    private String diffHeader(ReviewPass pass, int maxChars)
    {
        String diff = diffCache.diffFor(pass);
        if (diff.length() > maxChars) {
            diff = diff.substring(0, maxChars)
                    + "\n… [diff truncated — use get_pr_diff(path) for specific files]";
        }
        return "Reviewing " + pass.repoFullName() + "#" + pass.prNumber()
                + " at " + (pass.headSha() == null ? "(unknown sha)" : pass.headSha())
                + ".\n\nUnified diff:\n```diff\n" + diff + "\n```";
    }
}
