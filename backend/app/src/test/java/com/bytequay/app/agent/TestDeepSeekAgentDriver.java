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
import com.bytequay.agent.spi.ProviderQuotaSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
    void modelLessReadinessRequiresTheCatalogDefault()
    {
        Harness onlyV4 = harness(200, "{\"data\":[{\"id\":\"deepseek-v4-pro\"}]}", () -> Optional.of(SECRET));
        AgentProviderStatus unsupported = onlyV4.driver().probe(Optional.empty());
        assertThat(unsupported.readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
        assertThat(unsupported.diagnostic()).contains("deepseek-flash").doesNotContain(SECRET);
        assertThat(onlyV4.driver().probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        assertThat(onlyV4.driver().probe(Optional.of("deepseek-v4-pro")).readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);

        Harness flash = harness(200, "{\"data\":[{\"id\":\"deepseek-flash\"}]}", () -> Optional.of(SECRET));
        assertThat(flash.driver().probe(Optional.empty()).readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
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

    @Test
    void readsAccountBalanceWithoutUsingCodexOrSubscriptionLimits()
            throws Exception
    {
        Harness harness = harness(200, """
                {"is_available":true,"balance_infos":[
                  {"currency":"USD","total_balance":"12.3456","granted_balance":"2.00","topped_up_balance":"10.3456"},
                  {"currency":"CNY","total_balance":"0.00","granted_balance":"0.00","topped_up_balance":"0.00"}]}
                """, () -> Optional.of(SECRET));
        ProviderQuotaSnapshot quota = harness.driver().quota();
        assertThat(quota.windows()).isEmpty();
        assertThat(quota.plan()).isEmpty();
        assertThat(quota.message()).contains("Available balance: USD 12.3456, CNY 0.00.");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(harness.client()).send(request.capture(), any());
        assertThat(request.getValue().uri()).isEqualTo(URI.create("https://deepseek.test/user/balance"));
        assertThat(request.getValue().method()).isEqualTo("GET");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(6));
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer " + SECRET);
        verifyNoInteractions(harness.codex());
    }

    @Test
    void showsInsufficientBalanceIncludingNegativeCredit()
    {
        Harness harness = harness(200, """
                {"is_available":false,"balance_infos":[{"currency":"USD","total_balance":"-0.01"}]}
                """, () -> Optional.of(SECRET));
        assertThat(harness.driver().quota().message().orElseThrow()).contains("USD -0.01", "Insufficient balance");
    }

    @Test
    void rejectsInvalidBalanceResponsesWithoutEchoingDiagnostics()
    {
        for (String body : new String[] {"not json " + SECRET, "null", "{}", "{\"is_available\":true,\"balance_infos\":[]}",
                "{\"is_available\":true,\"balance_infos\":[{\"currency\":\"USD\",\"total_balance\":\"" + SECRET + "\"}]}",
                "{\"is_available\":true,\"balance_infos\":[{\"currency\":\"" + SECRET + "\",\"total_balance\":\"1.00\"}]}"}) {
            assertThat(harness(200, body, () -> Optional.of(SECRET)).driver().quota().message().orElseThrow())
                    .contains("balance unavailable").doesNotContain(SECRET);
        }
        for (int status : new int[] {401, 403, 429, 500}) {
            assertThat(harness(status, SECRET, () -> Optional.of(SECRET)).driver().quota().message().orElseThrow())
                    .doesNotContain(SECRET).contains(status == 401 || status == 403 ? "rejected this API key" : "balance unavailable");
        }
    }

    @Test
    void missingOrMalformedBalanceKeyNeverReachesTheNetwork()
    {
        for (Optional<String> secret : ImmutableList.of(Optional.<String>empty(), Optional.of("bad\nkey"))) {
            Harness harness = harness(200, "{}", () -> secret);
            assertThat(harness.driver().quota().message().orElseThrow()).contains("unavailable");
            verifyNoInteractions(harness.client(), harness.codex());
        }
    }

    @Test
    void balanceNetworkFailuresAreSafeAndInterruptionIsPreserved()
            throws Exception
    {
        Harness harness = harness(200, "{}", () -> Optional.of(SECRET));
        when(harness.client().send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException(SECRET));
        assertThat(harness.driver().quota().message().orElseThrow()).contains("balance unavailable").doesNotContain(SECRET);
        doThrow(new InterruptedException(SECRET)).when(harness.client()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try {
            assertThat(harness.driver().quota().message().orElseThrow()).contains("interrupted").doesNotContain(SECRET);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally {
            Thread.interrupted();
        }
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
