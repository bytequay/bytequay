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

import java.util.ArrayList;
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
     *                     they go after the three skill tools and
     *                     before the system blocks. Empty list when
     *                     the lane only offers skill tools.
     * @param providerShape whether {@code actionTools} are Anthropic-
     *                     shape or OpenAI-shape — picks the matching
     *                     skill-tools definitions so the bytes line up
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
        ImmutableList.Builder<String> tools = ImmutableList.builder();
        tools.addAll(skillToolsFor(providerShape));
        if (actionTools != null) {
            tools.addAll(actionTools);
        }
        ImmutableList.Builder<String> systemBlocks = ImmutableList.builder();
        if (roleSkillBody != null && !roleSkillBody.isBlank()) {
            systemBlocks.add(roleSkillBody);
        }
        if (brainBody != null && !brainBody.isBlank()) {
            systemBlocks.add(brainBody);
        }
        ImmutableList<String> historyCopy = history == null
                ? ImmutableList.of()
                : ImmutableList.copyOf(history);
        return new TurnRequest(
                tools.build(),
                systemBlocks.build(),
                historyCopy,
                newTurn == null ? "" : newTurn);
    }

    private static List<String> skillToolsFor(ProviderShape providerShape)
    {
        return switch (providerShape) {
            // Single-string array containing each skill tool's frozen
            // JSON definition. Wrap in unmodifiableList to keep the
            // assembled list immutable end-to-end.
            case ANTHROPIC -> List.of(unwrapJsonArray(SkillTools.ANTHROPIC_DEFINITIONS_JSON));
            case OPENAI -> List.of(unwrapJsonArray(SkillTools.OPENAI_DEFINITIONS_JSON));
        };
    }

    /** The constants are JSON arrays; the request envelope wants each
     *  tool as its own list entry so providers can splice them. */
    private static String[] unwrapJsonArray(String jsonArray)
    {
        String body = jsonArray.strip();
        if (body.length() < 2 || body.charAt(0) != '[' || body.charAt(body.length() - 1) != ']') {
            throw new IllegalStateException("expected a JSON array, got: " + body);
        }
        body = body.substring(1, body.length() - 1);
        return splitTopLevelJson(body);
    }

    /** Splits a comma-separated JSON-object list at top-level commas
     *  only (commas inside {} or [] are skipped). The input is trusted
     *  — the source strings come from constants in {@link SkillTools}
     *  — so we don't bother with full parser validation. */
    private static String[] splitTopLevelJson(String body)
    {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{' || c == '[') {
                depth++;
            }
            else if (c == '}' || c == ']') {
                depth--;
            }
            else if (c == ',' && depth == 0) {
                out.add(body.substring(start, i));
                start = i + 1;
            }
        }
        out.add(body.substring(start));
        return out.toArray(new String[0]);
    }

    public enum ProviderShape
    {
        ANTHROPIC,
        OPENAI,
    }
}
