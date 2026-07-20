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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestDeepSeekBalanceService
{
    private static final Instant NOW = Instant.parse("2026-07-20T08:00:00Z");

    @Test
    void readsLiveBalanceWithTheStoredApiKey()
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.getSecret(CredentialType.AI, "deepseek"))
                .thenReturn(Optional.of("sk-test-secret"));
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.test/user/balance"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test-secret"))
                .andRespond(withSuccess("""
                        {
                          "is_available": true,
                          "balance_infos": [{
                            "currency": "USD",
                            "total_balance": "12.34000000",
                            "granted_balance": "2.00000000",
                            "topped_up_balance": "10.34000000"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        DeepSeekBalanceService service = new DeepSeekBalanceService(
                credentials, builder.build(), Clock.fixed(NOW, ZoneOffset.UTC));

        DeepSeekBalanceService.DeepSeekBalance balance = service.current();

        assertThat(balance.configured()).isTrue();
        assertThat(balance.available()).isTrue();
        assertThat(balance.updatedAt()).isEqualTo(NOW.toEpochMilli());
        assertThat(balance.message()).isNull();
        assertThat(balance.balances()).singleElement().satisfies(info -> {
            assertThat(info.currency()).isEqualTo("USD");
            assertThat(info.totalBalance()).isEqualTo("12.34000000");
            assertThat(info.grantedBalance()).isEqualTo("2.00000000");
            assertThat(info.toppedUpBalance()).isEqualTo("10.34000000");
        });
        server.verify();
    }

    @Test
    void doesNotCallDeepSeekWithoutAKey()
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.getSecret(CredentialType.AI, "deepseek")).thenReturn(Optional.empty());
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepSeekBalanceService service = new DeepSeekBalanceService(
                credentials, builder.build(), Clock.fixed(NOW, ZoneOffset.UTC));

        DeepSeekBalanceService.DeepSeekBalance balance = service.current();

        assertThat(balance.configured()).isFalse();
        assertThat(balance.available()).isNull();
        assertThat(balance.message()).contains("not configured");
        assertThat(balance.balances()).isEmpty();
        server.verify();
    }

    @Test
    void sanitizesRejectedKeyResponses()
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.getSecret(CredentialType.AI, "deepseek"))
                .thenReturn(Optional.of("sk-test-secret"));
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.test/user/balance"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("sensitive upstream response"));
        DeepSeekBalanceService service = new DeepSeekBalanceService(
                credentials, builder.build(), Clock.fixed(NOW, ZoneOffset.UTC));

        DeepSeekBalanceService.DeepSeekBalance balance = service.current();

        assertThat(balance.configured()).isTrue();
        assertThat(balance.available()).isNull();
        assertThat(balance.message()).isEqualTo("DeepSeek API key was rejected.")
                .doesNotContain("sk-test-secret", "sensitive upstream response");
        assertThat(balance.balances()).isEmpty();
        server.verify();
    }
}
