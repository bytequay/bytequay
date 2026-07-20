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

import java.util.List;

/**
 * The two-level option tree the work-model picker walks: a flat list
 * of agents/providers (top level) with their available models nested
 * inside. Computed by merging the curated catalog with the user's
 * credentials (gates API providers) and the local CLI detection
 * (readiness flags on CLI agents).
 */
public record WorkModelOptions(
        List<WorkModelAgentOption> cliAgents,
        List<WorkModelProviderOption> apiProviders)
{
    public record WorkModelAgentOption(
            String id,
            String displayName,
            /** Binary present on PATH + responded to {@code --version}. */
            boolean installed,
            /** Agent reports an active auth session (best-effort probe). */
            boolean authed,
            /** Catalog default model id for this agent, never null. */
            String defaultModel,
            List<WorkModelEntry> models) {}

    public record WorkModelProviderOption(
            String id,
            String displayName,
            /** Catalog default model id for this provider, never null. */
            String defaultModel,
            List<WorkModelEntry> models,
            /** Stored credential instances for this provider. Empty
             *  when no credential exists yet (in which case the picker
             *  surfaces a "set up" hint). */
            List<WorkModelAccount> accounts) {}

    public record WorkModelEntry(
            String id,
            String displayName,
            /** True iff this is the agent/provider's default model. */
            boolean isDefault,
            /** CLI-supplied explanation of the model's intended use. */
            String description,
            /** CLI-supplied default effort for this model, if supported. */
            String defaultReasoningEffort,
            /** Effort choices accepted by this specific model. */
            List<WorkModelReasoningEffort> supportedReasoningEfforts)
    {
        public WorkModelEntry(String id, String displayName, boolean isDefault)
        {
            this(id, displayName, isDefault, null, null, List.of());
        }
    }

    public record WorkModelReasoningEffort(String id, String description) {}

    public record WorkModelAccount(
            String name,
            /** Resolved ★ default for this (type, provider) group. */
            boolean isDefault,
            /** Last cached probe outcome: {@code true} = reachable,
             *  {@code false} = failed, {@code null} = never probed. */
            Boolean valid) {}
}
