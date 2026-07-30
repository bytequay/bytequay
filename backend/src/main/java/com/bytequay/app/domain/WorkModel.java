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
package com.bytequay.app.domain;

/**
 * One choice on the model & provider axis — the unit a scope (workspace,
 * thread, task, or review seat) carries on the work-model cascade.
 *
 * <p>The five fields:
 * <ul>
 *   <li>{@code kind} — CLI subprocess vs API call. Same surface, two lanes.</li>
 *   <li>{@code agentOrProvider} — id of the CLI agent ({@code "claude-code"},
 *       {@code "codex"}, …) or the API provider ({@code "anthropic"},
 *       {@code "openai"}, …). Acts as the primary key into the catalog.</li>
 *   <li>{@code model} — explicit model override. {@code null} means
 *       "use the agent/provider's default model".</li>
 *   <li>{@code account} — for API kinds only, names the credential
 *       instance to use. {@code null} means "use the ★ default account
 *       for this provider". Ignored for CLI agents because they manage
 *       their own auth outside ByteQuay.</li>
 *   <li>{@code reasoningEffort} — provider-native reasoning level for CLI
 *       and supported API engines. {@code null} leaves the choice to the
 *       selected engine's default.</li>
 * </ul>
 *
 * <p>The cascade itself (workspace → thread → task → seat,
 * most-specific-wins) lives in the resolver; this record is the value
 * stored at each level.
 */
public record WorkModel(
        WorkModelKind kind,
        String agentOrProvider,
        String model,
        String account,
        String reasoningEffort)
{
    public WorkModel(WorkModelKind kind, String agentOrProvider, String model, String account)
    {
        this(kind, agentOrProvider, model, account, null);
    }
}
