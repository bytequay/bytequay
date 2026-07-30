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
package com.bytequay.app.service.agents;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * One turn's provider-call configuration for {@link TurnRunner}.
 *
 * <p>The {@code messages} array is provider-shaped and OWNED BY THE
 * RUNNER for the duration of the turn: the runner appends the
 * assistant tool-call echo and the tool-result messages to it between
 * rounds, exactly the way the provider's follow-up-turn contract
 * requires. Callers build the initial history (system placement
 * differs per transport: Anthropic takes {@code system} as a
 * top-level field, OpenAI-compatible APIs expect it as
 * {@code messages[0]} with {@code role=system} — in which case pass
 * {@code system} as null here).
 *
 * @param transport      wire dialect to speak.
 * @param url            full endpoint URL (messages / chat-completions).
 * @param authToken      Anthropic {@code x-api-key} or OpenAI-style
 *                       bearer token, depending on transport.
 * @param modelId        provider model id, stamped on the request.
 * @param reasoningEffort provider-native reasoning effort for this request;
 *                       null omits the field.
 * @param system         Anthropic top-level system prompt; null when
 *                       absent or when the transport carries it in
 *                       {@code messages[0]}.
 * @param messages       provider-shaped message history including the
 *                       new user turn.
 * @param tools          provider-shaped tools array; null or empty
 *                       omits the field.
 * @param maxOutputTokens    per-round completion budget.
 * @param maxToolIterations  bound on the tool-use ↔ result loop.
 */
public record TurnSpec(
        Transport transport,
        String url,
        String authToken,
        String modelId,
        String reasoningEffort,
        String system,
        ArrayNode messages,
        ArrayNode tools,
        int maxOutputTokens,
        int maxToolIterations)
{
    /** Compatibility constructor for callers that do not select effort. */
    public TurnSpec(
            Transport transport,
            String url,
            String authToken,
            String modelId,
            String system,
            ArrayNode messages,
            ArrayNode tools,
            int maxOutputTokens,
            int maxToolIterations)
    {
        this(transport, url, authToken, modelId, null, system, messages,
                tools, maxOutputTokens, maxToolIterations);
    }

    public enum Transport
    {
        ANTHROPIC,
        OPENAI_COMPAT,
    }
}
