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
package com.bytequay.app.service.workmodel;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;

/**
 * Curated fallback for the work-model axis: the CLI agents and API
 * providers ByteQuay knows about, plus a baseline model list for each.
 * Codex models are replaced at runtime by {@code codex app-server}'s
 * {@code model/list} response; its entries here are used only when that
 * machine-readable catalog is unavailable. Providers without such a CLI
 * catalog continue to use these entries directly.
 *
 * <p>An "Other…" free-text affordance on the picker lets a brand-new
 * model id be used immediately without waiting for the catalog to
 * update — see {@link WorkModelService} for how that path is composed.
 */
public final class WorkModelCatalog
{
    private WorkModelCatalog() {}

    /** One row in the catalog model list, before credential / detection
     *  enrichment. The {@code default} flag is sticky: catalogues with
     *  exactly one default model render the "Default" tag on that row.
     *
     *  <p>{@code localServed} flips true for model variants whose
     *  request path goes through a locally-spawned subprocess instead
     *  of the cloud endpoint. v1 has one — {@code deepseek-v4-flash}
     *  served by the ds4 lifecycle service. The flag drives the
     *  reviewer's client + credential selection and the picker's
     *  "[LOCAL · ds4]" sub-label. */
    public record CatalogEntry(String id, String displayName, boolean isDefault, boolean localServed)
    {
        /** Convenience for the common case — non-local entries don't
         *  need to spell {@code false} every line. */
        public CatalogEntry(String id, String displayName, boolean isDefault)
        {
            this(id, displayName, isDefault, /* localServed */ false);
        }
    }

    /** Top-level catalog row for a CLI agent. */
    public record CatalogAgent(String id, String displayName, List<CatalogEntry> models)
    {
        public CatalogEntry defaultModel()
        {
            for (CatalogEntry e : models) {
                if (e.isDefault()) {
                    return e;
                }
            }
            // Fall back to the first listed model — defensive against a
            // catalog typo that leaves no entry marked default.
            return models.get(0);
        }
    }

    /** Top-level catalog row for an API provider. */
    public record CatalogProvider(String id, String displayName, List<CatalogEntry> models)
    {
        public CatalogEntry defaultModel()
        {
            for (CatalogEntry e : models) {
                if (e.isDefault()) {
                    return e;
                }
            }
            return models.get(0);
        }
    }

    /** CLI agents, ordered for the picker. */
    public static final List<CatalogAgent> CLI_AGENTS = ImmutableList.of(
            new CatalogAgent("claude-code", "Claude Code", ImmutableList.of(
                    new CatalogEntry("claude-opus-4-8", "Claude Opus 4.8", true),
                    new CatalogEntry("claude-opus-4-7", "Claude Opus 4.7", false),
                    new CatalogEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", false),
                    new CatalogEntry("claude-haiku-4-5", "Claude Haiku 4.5", false))),
            new CatalogAgent("codex", "Codex", ImmutableList.of(
                    new CatalogEntry("gpt-5", "GPT-5", true),
                    new CatalogEntry("gpt-5-mini", "GPT-5 Mini", false))));

    /** API providers, ordered for the picker. */
    public static final List<CatalogProvider> API_PROVIDERS = ImmutableList.of(
            new CatalogProvider("anthropic", "Anthropic", ImmutableList.of(
                    new CatalogEntry("claude-opus-4-8", "Claude Opus 4.8", true),
                    new CatalogEntry("claude-opus-4-7", "Claude Opus 4.7", false),
                    new CatalogEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", false),
                    new CatalogEntry("claude-haiku-4-5", "Claude Haiku 4.5", false))),
            new CatalogProvider("openai", "OpenAI", ImmutableList.of(
                    new CatalogEntry("gpt-5", "GPT-5", true),
                    new CatalogEntry("gpt-5-mini", "GPT-5 Mini", false),
                    new CatalogEntry("gpt-4o", "GPT-4o", false),
                    new CatalogEntry("gpt-4o-mini", "GPT-4o Mini", false))),
            new CatalogProvider("deepseek", "DeepSeek", ImmutableList.of(
                    new CatalogEntry("deepseek-chat", "DeepSeek Chat", true),
                    new CatalogEntry("deepseek-reasoner", "DeepSeek Reasoner", false),
                    // Locally-served variant routed through the ds4
                    // lifecycle subprocess; readiness gate is "ds4 is
                    // RUNNING", not "API key present". v1 use is via
                    // the AI review path (DeepSeekReviewer); thread-
                    // loop support depends on the multi-provider
                    // transport landing in a later milestone.
                    new CatalogEntry("deepseek-v4-flash", "DeepSeek V4 Flash (local)",
                            /* isDefault */ false, /* localServed */ true))));

    private static final Map<String, CatalogAgent> AGENTS_BY_ID;
    private static final Map<String, CatalogProvider> PROVIDERS_BY_ID;

    static {
        ImmutableMap.Builder<String, CatalogAgent> agents = ImmutableMap.builder();
        for (CatalogAgent a : CLI_AGENTS) {
            agents.put(a.id(), a);
        }
        AGENTS_BY_ID = agents.build();
        ImmutableMap.Builder<String, CatalogProvider> providers = ImmutableMap.builder();
        for (CatalogProvider p : API_PROVIDERS) {
            providers.put(p.id(), p);
        }
        PROVIDERS_BY_ID = providers.build();
    }

    public static CatalogAgent agent(String id)
    {
        return AGENTS_BY_ID.get(id);
    }

    public static CatalogProvider provider(String id)
    {
        return PROVIDERS_BY_ID.get(id);
    }
}
