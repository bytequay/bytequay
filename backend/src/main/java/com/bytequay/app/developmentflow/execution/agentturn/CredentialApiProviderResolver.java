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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.local.ds4.Ds4State;
import com.bytequay.app.service.local.ds4.Ds4Status;

import java.util.Locale;

import static java.util.Objects.requireNonNull;

/** Resolves one frozen API provider/account without falling back to a new default. */
public final class CredentialApiProviderResolver
        implements ApiAgentTurnProviderSession.ProviderResolver
{
    private static final String LOCAL_DS4_MODEL = "deepseek-v4-flash";
    private static final String LOCAL_DS4_TOKEN = "dsv4-local";

    private final CredentialService credentials;
    private final Ds4LifecycleService ds4;

    public CredentialApiProviderResolver(
            CredentialService credentials,
            Ds4LifecycleService ds4)
    {
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.ds4 = requireNonNull(ds4, "ds4 is null");
    }

    @Override
    public ApiAgentTurnProviderSession.ResolvedProvider resolve(
            AgentTurnProviderSession.Request request)
    {
        requireNonNull(request, "request is null");
        String provider = request.provider().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(provider) && LOCAL_DS4_MODEL.equals(request.model())) {
            if (request.credentialAccount() != null) {
                throw new IllegalArgumentException(
                        "local ds4 Turn cannot carry a cloud credential account");
            }
            Ds4Status status = ds4.status();
            if (status.state() != Ds4State.RUNNING) {
                throw new IllegalStateException("local ds4 provider is not running");
            }
            return new ApiAgentTurnProviderSession.ResolvedProvider(
                    TurnSpec.Transport.OPENAI_COMPAT,
                    status.endpoint() + "/v1/chat/completions",
                    LOCAL_DS4_TOKEN);
        }

        String account = request.credentialAccount();
        if (account == null) {
            throw new IllegalArgumentException(
                    "cloud API Turn requires its frozen credential account");
        }
        String token = credentials.getSecret(CredentialType.AI, provider, account)
                .orElseThrow(() -> new IllegalStateException(
                        "frozen " + provider + " credential account is unavailable"));
        return switch (provider) {
            case "anthropic" -> new ApiAgentTurnProviderSession.ResolvedProvider(
                    TurnSpec.Transport.ANTHROPIC,
                    "https://api.anthropic.com/v1/messages",
                    token);
            case "openai" -> new ApiAgentTurnProviderSession.ResolvedProvider(
                    TurnSpec.Transport.OPENAI_COMPAT,
                    "https://api.openai.com/v1/chat/completions",
                    token);
            case "deepseek" -> new ApiAgentTurnProviderSession.ResolvedProvider(
                    TurnSpec.Transport.OPENAI_COMPAT,
                    "https://api.deepseek.com/chat/completions",
                    token);
            default -> throw new IllegalArgumentException(
                    "unsupported API provider: " + request.provider());
        };
    }
}
