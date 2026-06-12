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
import com.bytequay.app.service.agents.ToolCall;
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
 * The panel Lead's turn machinery: the shared {@link TurnRunner}
 * composed with the full-transcript context assembler, the
 * orchestration {@link LeadToolset}, {@code review_messages}
 * persistence, and pass-budget metering. Not a free-running manager
 * AI: the phase spine stays deterministic in ReviewPassService — the
 * Lead drives the CONTENT of a phase (who speaks, what's consensus)
 * one bounded round at a time.
 */
@Component
public class LeadOrchestrator
{
    /** Bound on one Lead round's internal tool loop. One "round" can
     *  still fan out many seat dispatches — this caps chained
     *  tool-call iterations inside the single provider turn. */
    private static final int MAX_TOOL_ITERATIONS = 8;
    private static final int MAX_OUTPUT_TOKENS = 4_096;

    private final TurnRunner runner;
    private final LeadContextAssembler contextAssembler;
    private final LeadToolset toolset;
    private final ReviewProviderEndpoints endpoints;
    private final ReviewBudgetMeter budget;
    private final ReviewStore reviewStore;
    private final ObjectMapper mapper;

    public LeadOrchestrator(
            TurnRunner runner,
            LeadContextAssembler contextAssembler,
            LeadToolset toolset,
            ReviewProviderEndpoints endpoints,
            ReviewBudgetMeter budget,
            ReviewStore reviewStore,
            ObjectMapper mapper)
    {
        this.runner = requireNonNull(runner, "runner is null");
        this.contextAssembler = requireNonNull(contextAssembler, "contextAssembler is null");
        this.toolset = requireNonNull(toolset, "toolset is null");
        this.endpoints = requireNonNull(endpoints, "endpoints is null");
        this.budget = requireNonNull(budget, "budget is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Run one Lead round: full-transcript context + the round
     * directive in, one bounded provider turn (which may fan out
     * reviewer dispatches) out. The Lead's closing text persists as
     * its transcript message; the spend lands on the pass budget.
     */
    public TurnResult runRound(
            ReviewPass pass,
            LeadToolset.Session session,
            PanelSeatConfig roster,
            ReviewPhase phase,
            int round,
            String directive)
    {
        requireNonNull(pass, "pass is null");
        requireNonNull(directive, "directive is null");
        PanelSeatConfig.Seat leadSeat = roster.leadSeat()
                .orElseThrow(() -> new IllegalStateException(
                        "pass " + pass.id() + " has no lead seat on its roster"));

        ReviewProviderEndpoints.Endpoint endpoint = endpoints.resolve(leadSeat.providerId());
        String system = systemPrompt(leadSeat);
        ArrayNode messages = providerMessages(
                endpoint.transport(), system, pass, leadSeat.participantId(), directive);

        LeadToolset.RoundExecutor executor = session.roundExecutor(phase, round);
        TurnHooks hooks = new TurnHooks()
        {
            @Override
            public void onToolCallsParsed(List<ToolCall> calls)
            {
                executor.prefetch(calls);
            }

            @Override
            public boolean abortTurn(long costSoFarMilliUsd)
            {
                return costSoFarMilliUsd >= budget.passRemaining(pass.id());
            }
        };

        TurnResult result = runner.runTurn(
                new TurnSpec(
                        endpoint.transport(), endpoint.url(), endpoint.authToken(),
                        endpoint.modelId(),
                        endpoint.transport() == TurnSpec.Transport.ANTHROPIC ? system : null,
                        messages, toolset.toolsArray(endpoint.transport()),
                        MAX_OUTPUT_TOKENS, MAX_TOOL_ITERATIONS),
                executor, hooks);

        if (result.finalText() != null && !result.finalText().isBlank()) {
            reviewStore.saveMessage(new ReviewMessage(
                    UUID.randomUUID().toString(),
                    pass.id(),
                    leadSeat.participantId(),
                    phase,
                    round,
                    result.finalText().strip(),
                    /* mentions */ List.of(),
                    /* refs */ List.of(),
                    result.costMilliUsd(),
                    Instant.now()));
        }
        budget.chargePass(pass.id(), result.costMilliUsd());
        return result;
    }

    private String systemPrompt(PanelSeatConfig.Seat leadSeat)
    {
        StringBuilder sb = new StringBuilder();
        if (leadSeat.personaPrompt() != null && !leadSeat.personaPrompt().isBlank()) {
            sb.append("Lead voice:\n").append(leadSeat.personaPrompt().strip()).append("\n\n");
        }
        sb.append("""
                You are the LEAD of a multi-reviewer code-review panel. You orchestrate; \
                reviewers verify. Your tools: set_agenda (once, at kickoff), \
                mark_phase_in_progress / mark_phase_done, dispatch_to_reviewer (the body \
                MUST @-mention the reviewer's label; several dispatches in one turn run \
                in parallel), mark_consensus (classify a finding agreed / disputed / \
                dropped after weighing the panel), record_dissent, and the read-only \
                code tools. Reviewers only see what you address to them — quote another \
                reviewer's point explicitly when you want a reaction to it. Work the \
                current agenda phase to completion, then call mark_phase_done. Keep \
                your own messages short: who you asked, what came back, what you \
                concluded.""");
        return sb.toString();
    }

    /** Provider-shaped conversation: pass header + full transcript,
     *  roles merged so they alternate, the round directive last. */
    private ArrayNode providerMessages(
            TurnSpec.Transport transport,
            String system,
            ReviewPass pass,
            String leadParticipantId,
            String directive)
    {
        ArrayNode messages = mapper.createArrayNode();
        if (transport == TurnSpec.Transport.OPENAI_COMPAT && system != null) {
            ObjectNode sys = mapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", system);
            messages.add(sys);
        }
        StringBuilder pendingUser = new StringBuilder(contextAssembler.passHeader(pass));
        for (LeadContextAssembler.LeadMessage m
                : contextAssembler.transcript(pass, leadParticipantId)) {
            if ("assistant".equals(m.role())) {
                if (pendingUser.length() > 0) {
                    addText(messages, "user", pendingUser.toString());
                    pendingUser.setLength(0);
                }
                mergeOrAddAssistant(messages, m.text());
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
        addText(messages, "user", pendingUser.toString());
        return messages;
    }

    private void mergeOrAddAssistant(ArrayNode messages, String text)
    {
        if (!messages.isEmpty()) {
            ObjectNode last = (ObjectNode) messages.get(messages.size() - 1);
            if ("assistant".equals(last.path("role").asText())) {
                last.put("content", last.path("content").asText() + "\n\n" + text);
                return;
            }
        }
        addText(messages, "assistant", text);
    }

    private void addText(ArrayNode messages, String role, String content)
    {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", role);
        msg.put("content", content);
        messages.add(msg);
    }
}
