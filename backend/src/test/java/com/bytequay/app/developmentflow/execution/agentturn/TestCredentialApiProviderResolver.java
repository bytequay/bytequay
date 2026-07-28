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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK_TURN;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCredentialApiProviderResolver
{
    @Test
    void resolvesOnlyTheFrozenCloudCredentialAccount()
    {
        CredentialService credentials = mock(CredentialService.class);
        Ds4LifecycleService ds4 = mock(Ds4LifecycleService.class);
        when(credentials.getSecret(CredentialType.AI, "anthropic", "account-2"))
                .thenReturn(Optional.of("exact-secret"));
        CredentialApiProviderResolver resolver =
                new CredentialApiProviderResolver(credentials, ds4);

        ApiAgentTurnProviderSession.ResolvedProvider provider =
                resolver.resolve(request("Anthropic", "account-2", "claude-sonnet"));

        assertThat(provider.transport()).isEqualTo(TurnSpec.Transport.ANTHROPIC);
        assertThat(provider.url()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(provider.authToken()).isEqualTo("exact-secret");
        verify(credentials).getSecret(CredentialType.AI, "anthropic", "account-2");
        verifyNoInteractions(ds4);
    }

    @Test
    void resolvesLocalDs4OnlyFromALiveFrozenLocalChoice()
    {
        CredentialService credentials = mock(CredentialService.class);
        Ds4LifecycleService ds4 = mock(Ds4LifecycleService.class);
        when(ds4.status()).thenReturn(new Ds4Status(
                Ds4State.RUNNING,
                "http://127.0.0.1:9429",
                42,
                Instant.EPOCH,
                true,
                0,
                null));
        CredentialApiProviderResolver resolver =
                new CredentialApiProviderResolver(credentials, ds4);

        ApiAgentTurnProviderSession.ResolvedProvider provider = resolver.resolve(
                request("deepseek", null, "deepseek-v4-flash"));

        assertThat(provider.transport()).isEqualTo(TurnSpec.Transport.OPENAI_COMPAT);
        assertThat(provider.url())
                .isEqualTo("http://127.0.0.1:9429/v1/chat/completions");
        verifyNoInteractions(credentials);
    }

    @Test
    void failsClosedWhenFrozenCredentialOrLocalRuntimeIsUnavailable()
    {
        CredentialService credentials = mock(CredentialService.class);
        Ds4LifecycleService ds4 = mock(Ds4LifecycleService.class);
        when(ds4.status()).thenReturn(Ds4Status.stopped("http://127.0.0.1:9429"));
        CredentialApiProviderResolver resolver =
                new CredentialApiProviderResolver(credentials, ds4);

        assertThatThrownBy(() -> resolver.resolve(
                request("openai", null, "gpt-5.6")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frozen credential account");
        assertThatThrownBy(() -> resolver.resolve(
                request("deepseek", null, "deepseek-v4-flash")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    private static AgentTurnProviderSession.Request request(
            String provider, String account, String model)
    {
        return new AgentTurnProviderSession.Request(
                API,
                provider,
                account,
                model,
                null,
                Path.of("/tmp/api-provider-worktree"),
                null,
                "prompt",
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay",
                        "http://127.0.0.1:53123/api/v2/task-turns/task-turn-1/"
                                + "operations/operation-1/mcp",
                        TASK_TURN,
                        "task-turn-1",
                        "operation-1",
                        TASK_BRAIN_READ_ONLY,
                        "mcp__bytequay__approval_prompt"),
                READ_ONLY);
    }
}
