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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.TurnSpec;
import org.springframework.stereotype.Component;

import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Resolves a review-roster provider id ({@code claude} / {@code openai}
 * / {@code deepseek}) into the wire-level config a
 * {@link com.bytequay.app.service.agents.TurnRunner} turn needs:
 * transport dialect, endpoint URL, API key, and model id. Mirrors the
 * per-provider credential names and model defaults the
 * {@code LlmReviewer} implementations use, so a seat's runner turn
 * bills the same account and model the one-shot review calls did.
 */
@Component
public class ReviewProviderEndpoints
{
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";

    private final CredentialService credentials;
    private final AppSettingsStore appSettings;

    public ReviewProviderEndpoints(CredentialService credentials, AppSettingsStore appSettings)
    {
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    /** Wire config for one provider's turn. */
    public record Endpoint(
            TurnSpec.Transport transport,
            String url,
            String authToken,
            String modelId)
    {
    }

    public Endpoint resolve(String providerId)
    {
        requireNonNull(providerId, "providerId is null");
        String normalised = providerId.toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "claude", "anthropic" -> new Endpoint(
                    TurnSpec.Transport.ANTHROPIC, ANTHROPIC_URL,
                    secret("anthropic", "Anthropic"),
                    modelFor("claude-", "claude-opus-4-7"));
            case "openai" -> new Endpoint(
                    TurnSpec.Transport.OPENAI_COMPAT, OPENAI_URL,
                    secret("openai", "OpenAI"),
                    modelFor("gpt-", "gpt-4o-mini"));
            case "deepseek" -> new Endpoint(
                    TurnSpec.Transport.OPENAI_COMPAT, DEEPSEEK_URL,
                    secret("deepseek", "DeepSeek"),
                    modelFor("deepseek-", "deepseek-chat"));
            default -> throw new IllegalStateException(
                    "Provider '" + providerId + "' has no review turn endpoint. "
                            + "Supported providers: claude, openai, deepseek.");
        };
    }

    private String secret(String credentialName, String displayName)
    {
        return credentials.getSecret(CredentialType.AI, credentialName)
                .orElseThrow(() -> new IllegalStateException(
                        "No " + displayName + " API key on file. Add one in Settings → Credentials."));
    }

    /** Honour the global model setting only when it belongs to this
     *  provider's family — the panel runs several providers at once,
     *  so a {@code claude-*} setting must not leak into a DeepSeek
     *  request. */
    private String modelFor(String familyPrefix, String fallback)
    {
        return appSettings.get(Key.LLM_MODEL)
                .filter(m -> !m.isBlank())
                .filter(m -> m.toLowerCase(Locale.ROOT).startsWith(familyPrefix))
                .orElse(fallback);
    }
}
