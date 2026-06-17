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

import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    /** Bound on one seat turn's tool-use loop. */
    private static final int MAX_TOOL_ITERATIONS = 8;
    private static final int MAX_OUTPUT_TOKENS = 4_096;
    /** Diff slice inlined into the seat's first user message. The
     *  seat can pull more via get_pr_diff / get_file_content. */
    private static final int MAX_INLINE_DIFF_CHARS = 60_000;

    private final TurnRunner runner;
    private final SeatContextAssembler contextAssembler;
    private final SeatToolset toolset;
    private final ReviewProviderEndpoints endpoints;
    private final ReviewBudgetMeter budget;
    private final ReviewDiffCache diffCache;
    private final ReviewStore reviewStore;
    private final ObjectMapper mapper;

    public ReviewerSeat(
            TurnRunner runner,
            SeatContextAssembler contextAssembler,
            SeatToolset toolset,
            ReviewProviderEndpoints endpoints,
            ReviewBudgetMeter budget,
            ReviewDiffCache diffCache,
            ReviewStore reviewStore,
            ObjectMapper mapper)
    {
        this.runner = requireNonNull(runner, "runner is null");
        this.contextAssembler = requireNonNull(contextAssembler, "contextAssembler is null");
        this.toolset = requireNonNull(toolset, "toolset is null");
        this.endpoints = requireNonNull(endpoints, "endpoints is null");
        this.budget = requireNonNull(budget, "budget is null");
        this.diffCache = requireNonNull(diffCache, "diffCache is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
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

        ReviewProviderEndpoints.Endpoint endpoint = endpoints.resolve(seat.providerId());
        String system = systemPrompt(seat);
        ArrayNode messages = providerMessages(endpoint.transport(), system,
                pass, participantId, directive, excludeMessageId);

        ToolExecutor executor = toolset.executorFor(pass, participantId, seat.displayLabel());
        TurnHooks hooks = enforceBudget
                ? budgetHooks(pass.id(), participantId)
                : new TurnHooks() { };

        TurnResult result = runner.runTurn(
                new TurnSpec(
                        endpoint.transport(), endpoint.url(), endpoint.authToken(),
                        endpoint.modelId(),
                        endpoint.transport() == TurnSpec.Transport.ANTHROPIC ? system : null,
                        messages, toolset.toolsArray(endpoint.transport()),
                        MAX_OUTPUT_TOKENS, MAX_TOOL_ITERATIONS),
                executor, hooks);

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
                claims, and record every concrete issue with report_finding. \
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
        String diff = diffCache.diffFor(pass);
        if (diff.length() > MAX_INLINE_DIFF_CHARS) {
            diff = diff.substring(0, MAX_INLINE_DIFF_CHARS)
                    + "\n… [diff truncated — use get_pr_diff(path) for specific files]";
        }
        return "Reviewing " + pass.repoFullName() + "#" + pass.prNumber()
                + " at " + (pass.headSha() == null ? "(unknown sha)" : pass.headSha())
                + ".\n\nUnified diff:\n```diff\n" + diff + "\n```";
    }
}
