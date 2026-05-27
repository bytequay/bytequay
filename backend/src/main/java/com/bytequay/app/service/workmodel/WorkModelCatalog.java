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
 * Curated source-of-truth for the work-model axis: the CLI agents and
 * API providers ByteQuay knows about, plus a baseline model list for
 * each one with a friendly display name and a marked default. The
 * upstream list-models APIs don't carry ordering / cost / friendly-name
 * hints, so we keep this in code and evolve it as new models ship.
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
     *  exactly one default model render the "Default" tag on that row. */
    public record CatalogEntry(String id, String displayName, boolean isDefault) {}

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
                    new CatalogEntry("claude-opus-4-7", "Claude Opus 4.7", false),
                    new CatalogEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", true),
                    new CatalogEntry("claude-haiku-4-5", "Claude Haiku 4.5", false))),
            new CatalogAgent("codex", "Codex", ImmutableList.of(
                    new CatalogEntry("gpt-5", "GPT-5", true),
                    new CatalogEntry("gpt-5-mini", "GPT-5 Mini", false))));

    /** API providers, ordered for the picker. */
    public static final List<CatalogProvider> API_PROVIDERS = ImmutableList.of(
            new CatalogProvider("anthropic", "Anthropic", ImmutableList.of(
                    new CatalogEntry("claude-opus-4-7", "Claude Opus 4.7", false),
                    new CatalogEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", true),
                    new CatalogEntry("claude-haiku-4-5", "Claude Haiku 4.5", false))),
            new CatalogProvider("openai", "OpenAI", ImmutableList.of(
                    new CatalogEntry("gpt-5", "GPT-5", true),
                    new CatalogEntry("gpt-5-mini", "GPT-5 Mini", false),
                    new CatalogEntry("gpt-4o", "GPT-4o", false),
                    new CatalogEntry("gpt-4o-mini", "GPT-4o Mini", false))),
            new CatalogProvider("deepseek", "DeepSeek", ImmutableList.of(
                    new CatalogEntry("deepseek-chat", "DeepSeek Chat", true),
                    new CatalogEntry("deepseek-reasoner", "DeepSeek Reasoner", false))),
            new CatalogProvider("local", "Local", ImmutableList.of(
                    new CatalogEntry("llama3.1:8b", "Llama 3.1 8B", true),
                    new CatalogEntry("gpt-oss-20b", "GPT-OSS 20B", false))));

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
