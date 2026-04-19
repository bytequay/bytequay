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
package com.bytequay.app.service.ai;

import com.bytequay.app.repository.AppSettingsStore;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Chooses which {@link LlmReviewer} to use based on
 * {@code app_settings.llm.provider}. All registered reviewers are available;
 * the UI decides which one to display as the active choice.
 */
@Component
public class LlmReviewerRegistry
{
    private static final String DEFAULT_PROVIDER = "claude";

    private final Map<String, LlmReviewer> byProviderId;
    private final AppSettingsStore appSettings;

    public LlmReviewerRegistry(List<LlmReviewer> reviewers, AppSettingsStore appSettings)
    {
        requireNonNull(reviewers, "reviewers is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        ImmutableMap.Builder<String, LlmReviewer> builder = ImmutableMap.builder();
        for (LlmReviewer r : reviewers) {
            builder.put(r.providerId(), r);
        }
        this.byProviderId = builder.build();
    }

    public Collection<LlmReviewer> all()
    {
        return byProviderId.values();
    }

    /** Lookup a reviewer by its {@code providerId()}. Used when a per-repo
     *  review skill locks the run to a specific provider. Returns empty
     *  when the id isn't registered (typically a stale config value). */
    public Optional<LlmReviewer> byId(String providerId)
    {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byProviderId.get(providerId));
    }

    /**
     * Returns the reviewer matching {@code app_settings.llm.provider}, falling
     * back to Claude if the configured provider is unknown or missing.
     */
    public LlmReviewer active()
    {
        String selected = appSettings.get(AppSettingsStore.Key.LLM_PROVIDER)
                .filter(s -> !s.isBlank())
                .orElse(DEFAULT_PROVIDER);
        LlmReviewer reviewer = byProviderId.get(selected);
        if (reviewer != null) {
            return reviewer;
        }
        return byProviderId.get(DEFAULT_PROVIDER);
    }
}
