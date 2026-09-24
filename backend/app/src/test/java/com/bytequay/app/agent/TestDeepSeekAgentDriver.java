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
package com.bytequay.app.agent;

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDeepSeekAgentDriver
{
    private static final AgentLaneId LANE = new AgentLaneId("deepseek-key-123e4567-e89b-12d3-a456-426614174000");
    private static final String SECRET = "test-deepseek-secret";

    @Test
    void authenticatesAndChecksTheSelectedModel()
            throws Exception
    {
        Harness harness = harness(200, "{\"data\":[{\"id\":\"deepseek-flash\"},{\"id\":\"deepseek-v4-pro\"}]}", () -> Optional.of(SECRET));

        AgentProviderStatus status = harness.driver().probe(Optional.of("deepseek-v4-pro"));

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        assertThat(status.laneId()).isEqualTo(LANE);
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(harness.client()).send(request.capture(), any());
        assertThat(request.getValue().uri()).isEqualTo(URI.create("https://deepseek.test/models"));
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer " + SECRET);
        assertThat(status.diagnostic()).doesNotContain(SECRET);
    }

    @Test
    void rejectsAModelAbsentFromAnOtherwiseValidList()
    {
        Harness harness = harness(200, "{\"data\":[{\"id\":\"deepseek-flash\"}]}", () -> Optional.of(SECRET));

        AgentProviderStatus status = harness.driver().probe(Optional.of("deepseek-v4-pro"));

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
        assertThat(status.diagnostic()).contains("deepseek-v4-pro").doesNotContain(SECRET);
    }

    @Test
    void classifiesInvalidKeyWithoutEchoingTheResponseBody()
    {
        Harness harness = harness(401, "secret=" + SECRET, () -> Optional.of(SECRET));

        AgentProviderStatus status = harness.driver().probe(Optional.of("deepseek-flash"));

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThat(status.diagnostic()).contains("DeepSeek rejected").doesNotContain(SECRET);
    }

    @Test
    void classifiesMalformedSavedKeyWithoutSendingIt()
    {
        Harness harness = harness(200, "{}", () -> Optional.of("bad\nkey"));

        AgentProviderStatus status = harness.driver().probe(Optional.of("deepseek-flash"));

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThat(status.diagnostic()).contains("malformed").doesNotContain("bad\nkey");
        verifyNoInteractions(harness.client());
    }

    @Test
    void classifiesServiceFailureAsTemporary()
    {
        Harness harness = harness(503, "upstream secret=" + SECRET, () -> Optional.of(SECRET));

        AgentProviderStatus status = harness.driver().probe(Optional.of("deepseek-flash"));

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        assertThat(status.diagnostic()).contains("HTTP 503").doesNotContain(SECRET);
    }

    @Test
    void namesInsufficientBalanceWithoutEchoingTheResponseBody()
    {
        Harness harness = harness(402, "secret=" + SECRET, () -> Optional.of(SECRET));

        AgentProviderStatus status = harness.driver().probe(Optional.of("deepseek-flash"));

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        assertThat(status.diagnostic()).contains("insufficient balance").doesNotContain(SECRET);
    }

    @Test
    void classifiesMalformedModelResponseAsTemporary()
    {
        Harness harness = harness(200, "{not json", () -> Optional.of(SECRET));

        AgentProviderStatus status = harness.driver().probe(Optional.of("deepseek-flash"));

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        assertThat(status.diagnostic()).doesNotContain(SECRET);
    }

    @Test
    void checksLocalCodexReadinessAndKeyBeforeContactingDeepSeek()
    {
        Harness harness = harness(200, "{\"data\":[]}", Optional::empty);

        AgentProviderStatus missingKey = harness.driver().probe();
        assertThat(missingKey.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(harness.client());

        when(harness.codex().probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.CLI_MISSING,
                Optional.empty(), "Codex missing", AgentCapabilities.none()));
        AgentProviderStatus missingCodex = harness.driver().probe();
        assertThat(missingCodex.readiness()).isEqualTo(AgentProviderStatus.Readiness.CLI_MISSING);
        verifyNoInteractions(harness.client());
    }

    private static Harness harness(int responseStatus, String body, Supplier<Optional<String>> secret)
    {
        AgentDriver codex = mock(AgentDriver.class);
        when(codex.id()).thenReturn(LANE);
        when(codex.capabilities()).thenReturn(AgentCapabilities.none());
        when(codex.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY,
                Optional.of("0.156.1"), "Codex ready", AgentCapabilities.none()));

        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(responseStatus);
        when(response.body()).thenReturn(body);
        try {
            doReturn(response).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
        catch (Exception failure) {
            throw new AssertionError(failure);
        }
        return new Harness(new DeepSeekAgentDriver(codex, secret, URI.create("https://deepseek.test/models"), client, new ObjectMapper()), client, codex);
    }

    private record Harness(DeepSeekAgentDriver driver, HttpClient client, AgentDriver codex) {}
}
