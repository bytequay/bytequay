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
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.ProviderModel;
import com.bytequay.agent.spi.ProviderQuotaSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Adds authenticated DeepSeek model readiness and account balance to the isolated Codex App Server driver. */
final class DeepSeekAgentDriver
        implements AgentDriver
{
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final String DEFAULT_MODEL = "deepseek-flash";
    private static final Set<String> SUPPORTED_MODELS = ImmutableSet.of(DEFAULT_MODEL, "deepseek-v4-pro");

    private final AgentDriver codex;
    private final Supplier<Optional<String>> secret;
    private final URI modelsEndpoint;
    private final HttpClient client;
    private final ObjectMapper mapper;

    DeepSeekAgentDriver(AgentDriver codex, Supplier<Optional<String>> secret, ObjectMapper mapper)
    {
        this(codex, secret, URI.create("https://api.deepseek.com/models"), HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), mapper);
    }

    DeepSeekAgentDriver(
            AgentDriver codex,
            Supplier<Optional<String>> secret,
            URI modelsEndpoint,
            HttpClient client,
            ObjectMapper mapper)
    {
        this.codex = requireNonNull(codex, "codex is null");
        this.secret = requireNonNull(secret, "secret is null");
        this.modelsEndpoint = requireNonNull(modelsEndpoint, "modelsEndpoint is null");
        this.client = requireNonNull(client, "client is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public AgentLaneId id()
    {
        return codex.id();
    }

    @Override
    public String label()
    {
        return codex.label();
    }

    @Override
    public AgentCapabilities capabilities()
    {
        return codex.capabilities();
    }

    @Override
    public AgentProviderStatus probe()
    {
        return probeModels(Optional.empty());
    }

    @Override
    public AgentProviderStatus probe(Optional<String> model)
    {
        return probeModels(Optional.of(model.orElse(DEFAULT_MODEL)));
    }

    private AgentProviderStatus probeModels(Optional<String> model)
    {
        AgentProviderStatus local = codex.probe();
        if (!local.available()) {
            return local;
        }
        Optional<String> currentSecret = secret.get().filter(value -> !value.isBlank());
        if (currentSecret.isEmpty()) {
            return status(local, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED, "DeepSeek key removed or unavailable");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(modelsEndpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + currentSecret.orElseThrow())
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return status(local, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                        "DeepSeek rejected this API key. Replace it in Models settings.");
            }
            if (response.statusCode() == 402) {
                return status(local, AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                        "DeepSeek account has insufficient balance. Check the account balance before retrying.");
            }
            if (response.statusCode() != 200) {
                return status(local, AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                        "DeepSeek model check failed (HTTP " + response.statusCode() + "). Try again later.");
            }
            JsonNode data = mapper.readTree(response.body()).path("data");
            if (!data.isArray()) {
                return status(local, AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                        "DeepSeek returned an unreadable model list. Try again later.");
            }
            Set<String> listed = StreamSupport.stream(data.spliterator(), false)
                    .map(entry -> entry.path("id").asText(""))
                    .filter(SUPPORTED_MODELS::contains)
                    .collect(java.util.stream.Collectors.toSet());
            if (listed.isEmpty()) {
                return status(local, AgentProviderStatus.Readiness.UNSUPPORTED,
                        "This DeepSeek key lists none of ByteQuay's supported models.");
            }
            if (model.isPresent() && !listed.contains(model.orElseThrow())) {
                return status(local, AgentProviderStatus.Readiness.UNSUPPORTED,
                        "This DeepSeek key does not list the selected model " + model.orElseThrow() + ".");
            }
            return status(local, AgentProviderStatus.Readiness.READY, "DeepSeek API key and model are available");
        }
        catch (IOException failure) {
            return status(local, AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                    "Cannot reach DeepSeek to check the API key. Try again later.");
        }
        catch (IllegalArgumentException failure) {
            return status(local, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                    "The saved DeepSeek API key is malformed. Replace it in Models settings.");
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return status(local, AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE,
                    "DeepSeek model check was interrupted. Try again.");
        }
    }

    private AgentProviderStatus status(AgentProviderStatus local, AgentProviderStatus.Readiness readiness, String diagnostic)
    {
        return new AgentProviderStatus(id(), readiness, local.version(), diagnostic, capabilities());
    }

    @Override
    public ProviderQuotaSnapshot quota()
    {
        try {
            Optional<String> currentSecret = secret.get().filter(value -> !value.isBlank());
            if (currentSecret.isEmpty()) {
                return ProviderQuotaSnapshot.unavailable("DeepSeek key removed or unavailable. Check Models settings.");
            }
            HttpRequest request = HttpRequest.newBuilder(modelsEndpoint.resolve("/user/balance"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + currentSecret.orElseThrow())
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return ProviderQuotaSnapshot.unavailable("DeepSeek rejected this API key. Replace it in Models settings.");
            }
            checkArgument(response.statusCode() == 200, "Balance request failed");
            JsonNode body = mapper.readTree(response.body());
            JsonNode balances = body.path("balance_infos");
            checkArgument(body.path("is_available").isBoolean() && balances.isArray() && !balances.isEmpty(), "Invalid balance response");
            StringJoiner amounts = new StringJoiner(", ");
            for (JsonNode balance : balances) {
                String currency = balance.path("currency").asText();
                checkArgument(currency.equals("USD") || currency.equals("CNY"), "Invalid balance currency");
                String total = balance.path("total_balance").asText();
                checkArgument(total.matches("-?\\d{1,16}(\\.\\d{1,16})?"), "Invalid balance amount");
                amounts.add(currency + " " + new BigDecimal(total).toPlainString());
            }
            String message = "Available balance: " + amounts + "."
                    + (body.path("is_available").booleanValue() ? "" : " Insufficient balance for API calls. Top up your DeepSeek account.");
            return new ProviderQuotaSnapshot(Optional.empty(), ImmutableList.of(), Optional.of(message));
        }
        catch (IOException | RuntimeException failure) {
            return ProviderQuotaSnapshot.unavailable("DeepSeek balance unavailable. Check this account in Settings and try again.");
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return ProviderQuotaSnapshot.unavailable("DeepSeek balance check interrupted. Try again.");
        }
    }

    @Override
    public List<ProviderModel> models()
    {
        return codex.models().stream().filter(model -> SUPPORTED_MODELS.contains(model.id())).toList();
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        return codex.open(context, events);
    }
}
