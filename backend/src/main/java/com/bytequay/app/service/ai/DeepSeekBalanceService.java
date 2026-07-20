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
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Reads the live credit balance exposed by DeepSeek's account API. */
@Service
public class DeepSeekBalanceService
{
    private static final Logger log = LoggerFactory.getLogger(DeepSeekBalanceService.class);

    private final CredentialService credentials;
    private final RestClient client;
    private final Clock clock;

    @Autowired
    public DeepSeekBalanceService(
            CredentialService credentials,
            @Qualifier("deepseekRestClient") RestClient client)
    {
        this(credentials, client, Clock.systemUTC());
    }

    DeepSeekBalanceService(CredentialService credentials, RestClient client, Clock clock)
    {
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.client = requireNonNull(client, "client is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public DeepSeekBalance current()
    {
        String apiKey = credentials.getSecret(CredentialType.AI, "deepseek")
                .filter(value -> !value.isBlank())
                .orElse(null);
        if (apiKey == null) {
            return new DeepSeekBalance(
                    false, null, 0, "DeepSeek API key is not configured.", List.of());
        }

        long updatedAt = clock.millis();
        try {
            UpstreamBalance response = client.get()
                    .uri("/user/balance")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(UpstreamBalance.class);
            if (response == null || response.balanceInfos() == null) {
                return unavailable(updatedAt, "DeepSeek balance is temporarily unavailable.");
            }
            List<BalanceInfo> balances = response.balanceInfos().stream()
                    .filter(balance -> present(balance.currency()) && present(balance.totalBalance()))
                    .map(balance -> new BalanceInfo(
                            balance.currency(),
                            balance.totalBalance(),
                            balance.grantedBalance(),
                            balance.toppedUpBalance()))
                    .toList();
            if (response.available() && balances.isEmpty()) {
                return unavailable(updatedAt, "DeepSeek balance is temporarily unavailable.");
            }
            String message = response.available()
                    ? null
                    : "DeepSeek reports that API credits are unavailable.";
            return new DeepSeekBalance(true, response.available(), updatedAt, message, balances);
        }
        catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("DeepSeek balance request failed with HTTP {}", status);
            if (status == 401 || status == 403) {
                return unavailable(updatedAt, "DeepSeek API key was rejected.");
            }
            return unavailable(updatedAt, "DeepSeek balance is temporarily unavailable.");
        }
        catch (RestClientException e) {
            log.warn("DeepSeek balance request failed: {}", e.getClass().getSimpleName());
            return unavailable(updatedAt, "DeepSeek balance is temporarily unavailable.");
        }
    }

    private DeepSeekBalance unavailable(long updatedAt, String message)
    {
        return new DeepSeekBalance(true, null, updatedAt, message, List.of());
    }

    private static boolean present(String value)
    {
        return value != null && !value.isBlank();
    }

    public record DeepSeekBalance(
            boolean configured,
            Boolean available,
            long updatedAt,
            String message,
            List<BalanceInfo> balances)
    {
        public DeepSeekBalance
        {
            balances = List.copyOf(balances);
        }
    }

    public record BalanceInfo(
            String currency,
            String totalBalance,
            String grantedBalance,
            String toppedUpBalance) {}

    private record UpstreamBalance(
            @JsonProperty("is_available") boolean available,
            @JsonProperty("balance_infos") List<UpstreamBalanceInfo> balanceInfos) {}

    private record UpstreamBalanceInfo(
            String currency,
            @JsonProperty("total_balance") String totalBalance,
            @JsonProperty("granted_balance") String grantedBalance,
            @JsonProperty("topped_up_balance") String toppedUpBalance) {}
}
