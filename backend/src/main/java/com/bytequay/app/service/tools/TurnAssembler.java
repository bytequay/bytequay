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
package com.bytequay.app.service.tools;

import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles a {@link TurnRequest} in the order the spec mandates.
 * Pure — no I/O, no caching — so the assembler itself doesn't drift
 * across turns.
 *
 * <p>The lane that actually issues the HTTP call is expected to:
 * <ul>
 *   <li>serialise the {@code tools} list as the {@code tools} array
 *       (Anthropic) or {@code tools} OpenAI-shape array (DeepSeek);</li>
 *   <li>serialise {@code systemBlocks} as the {@code system} field
 *       (Anthropic's content-block array, OpenAI's leading "system"
 *       message);</li>
 *   <li>tag the last tool block and the last system block with
 *       {@code cache_control: {type: "ephemeral"}} for Anthropic so
 *       the prefix is hashed up through them;</li>
 *   <li>append {@code historyMessages} verbatim, then the
 *       {@code newTurn} as the final user message.</li>
 * </ul>
 *
 * <p>Each input list is treated as immutable; the assembled lists are
 * defensive copies so a downstream lane can hand them to a serialiser
 * without worrying about concurrent mutation by another caller.
 */
@Component
public class TurnAssembler
{
    /**
     * @param actionTools  provider-shaped JSON definitions for every
     *                     action tool the lane wants to expose this
     *                     turn (read / edit / run …). Order matters:
     *                     turn. The caller must pass the exact bounded
     *                     set selected by ByteQuay.
     * @param providerShape retained for source compatibility; tools are
     *                     already provider-shaped at this boundary
     * @param roleSkillBody the role skill body (system "block 1") —
     *                      frozen for the lifetime of a task so it
     *                      doesn't drift the prefix mid-turn
     * @param brainBody     the workspace brain body (system "block
     *                      2") — also frozen; resolved at workspace
     *                      load
     * @param history       prior messages in append-only order. Must
     *                      be byte-stable across turns
     * @param newTurn       the new user turn JSON
     */
    public TurnRequest assemble(
            List<String> actionTools,
            ProviderShape providerShape,
            String roleSkillBody,
            String brainBody,
            List<String> history,
            String newTurn)
    {
        return assemble(
                actionTools, providerShape,
                roleSkillBody, brainBody,
                null, null, null,
                history, newTurn);
    }

    /**
     * Extended overload that carries the three additional system
     * blocks the prompt-context spec calls out (concept preamble,
     * skill manifest, rendered memory items). The classic 6-arg
     * overload delegates here with the new blocks set to
     * {@code null} so existing callers stay byte-identical until
     * they opt into the wider shape.
     *
     * <p>Block order in the assembled {@code systemBlocks} array
     * matches the spec's serialised order: role skill → brain →
     * concept preamble → skill manifest → memory items. Blanks /
     * nulls are skipped so an empty axis doesn't show up as an
     * empty system message on the wire.
     */
    public TurnRequest assemble(
            List<String> actionTools,
            ProviderShape providerShape,
            String roleSkillBody,
            String brainBody,
            String conceptPreamble,
            String skillManifest,
            String memoryRendered,
            List<String> history,
            String newTurn)
    {
        ImmutableList<String> tools = actionTools == null
                ? ImmutableList.of()
                : ImmutableList.copyOf(actionTools);
        ImmutableList.Builder<String> systemBlocks = ImmutableList.builder();
        addIfPresent(systemBlocks, roleSkillBody);
        addIfPresent(systemBlocks, brainBody);
        addIfPresent(systemBlocks, conceptPreamble);
        addIfPresent(systemBlocks, skillManifest);
        addIfPresent(systemBlocks, memoryRendered);
        ImmutableList<String> historyCopy = history == null
                ? ImmutableList.of()
                : ImmutableList.copyOf(history);
        return new TurnRequest(
                tools,
                systemBlocks.build(),
                historyCopy,
                newTurn == null ? "" : newTurn);
    }

    private static void addIfPresent(ImmutableList.Builder<String> target, String body)
    {
        if (body != null && !body.isBlank()) {
            target.add(body);
        }
    }

    public enum ProviderShape
    {
        ANTHROPIC,
        OPENAI,
    }
}
