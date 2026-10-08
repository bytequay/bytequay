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
package com.bytequay.agent.cli.claude;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.Utf8LineBuffer;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderQuotaSnapshot;
import com.bytequay.agent.spi.ProviderQuotaWindow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ClaudeQuota
{
    private ClaudeQuota() {}

    static ProviderQuotaSnapshot read(ProcessSupervisor supervisor, ProcessSpec spec, ObjectMapper mapper)
    {
        CompletableFuture<JsonNode> initialized = new CompletableFuture<>();
        CompletableFuture<JsonNode> usage = new CompletableFuture<>();
        Utf8LineBuffer lines = new Utf8LineBuffer(4 * 1024 * 1024, true, line -> {
            try {
                JsonNode message = mapper.readTree(line);
                if (!message.path("type").asText().equals("control_response")) {
                    return;
                }
                JsonNode response = message.path("response");
                CompletableFuture<JsonNode> pending = switch (response.path("request_id").asText()) {
                    case "initialize" -> initialized;
                    case "usage" -> usage;
                    default -> null;
                };
                if (pending != null) {
                    if (response.path("subtype").asText().equals("success")) {
                        pending.complete(response.path("response"));
                    }
                    else {
                        pending.completeExceptionally(new AgentUnavailableException("Claude usage query is unavailable"));
                    }
                }
            }
            catch (IOException | RuntimeException failure) {
                initialized.completeExceptionally(failure);
                usage.completeExceptionally(failure);
            }
        });
        ManagedProcess process = supervisor.prepare(spec).start((stream, bytes) -> {
            if (stream == ProcessStream.STDOUT) {
                try {
                    lines.accept(bytes);
                }
                catch (RuntimeException failure) {
                    initialized.completeExceptionally(failure);
                    usage.completeExceptionally(failure);
                }
            }
        });
        process.exit().whenComplete((exit, failure) -> {
            AgentUnavailableException stopped = new AgentUnavailableException("Claude usage query ended before replying");
            initialized.completeExceptionally(stopped);
            usage.completeExceptionally(stopped);
        });
        try {
            send(process, "{\"type\":\"control_request\",\"request_id\":\"initialize\",\"request\":{\"subtype\":\"initialize\"}}");
            initialized.get(5, TimeUnit.SECONDS);
            // No user message is ever sent. Skip transcript-based suggestions, which are unrelated to account quotas.
            send(process, "{\"type\":\"control_request\",\"request_id\":\"usage\",\"request\":{\"subtype\":\"get_usage\",\"skip_behaviors\":true}}");
            return parse(usage.get(20, TimeUnit.SECONDS));
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Claude usage query interrupted", failure);
        }
        catch (ExecutionException | TimeoutException failure) {
            throw new AgentUnavailableException("Claude usage query unavailable", failure);
        }
        finally {
            process.cancel();
            process.exit().whenComplete((exit, failure) -> {
                if (failure == null && exit.treeTermination() == ProcessExit.TreeTermination.VERIFIED_STOPPED) {
                    supervisor.acknowledge(process.launchId());
                }
            });
        }
    }

    private static void send(ManagedProcess process, String request)
            throws InterruptedException, ExecutionException, TimeoutException
    {
        process.write(ByteBuffer.wrap((request + "\n").getBytes(StandardCharsets.UTF_8))).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    static ProviderQuotaSnapshot parse(JsonNode response)
    {
        Optional<String> plan = Optional.ofNullable(response.get("subscription_type")).filter(JsonNode::isTextual).map(JsonNode::textValue);
        if (!response.path("rate_limits_available").asBoolean(false)) {
            return new ProviderQuotaSnapshot(plan, ImmutableList.of(), Optional.of("This account does not expose usage limits."));
        }
        JsonNode limits = response.path("rate_limits");
        ImmutableList.Builder<ProviderQuotaWindow> windows = ImmutableList.builder();
        add(windows, "five_hour", "5-hour", limits.path("five_hour"), Duration.ofHours(5));
        add(windows, "seven_day", "Weekly · all models", limits.path("seven_day"), Duration.ofDays(7));
        JsonNode scoped = limits.path("model_scoped");
        if (scoped.isArray()) {
            for (int i = 0; i < scoped.size(); i++) {
                JsonNode window = scoped.get(i);
                add(windows, "model-" + i, "Weekly · " + window.path("display_name").asText("Model"), window, Duration.ofDays(7));
            }
        }
        else {
            add(windows, "seven_day_opus", "Weekly · Opus", limits.path("seven_day_opus"), Duration.ofDays(7));
            add(windows, "seven_day_sonnet", "Weekly · Sonnet", limits.path("seven_day_sonnet"), Duration.ofDays(7));
        }
        // A spending cap is separate from included subscription limits. Its billing boundaries must be supplied, never guessed as 30 days.
        JsonNode extra = limits.path("extra_usage");
        if (extra.path("is_enabled").asBoolean() && extra.path("monthly_limit").isNumber() && extra.path("monthly_limit").decimalValue().signum() > 0
                && extra.path("used_credits").isNumber() && extra.path("used_credits").decimalValue().signum() >= 0
                && extra.path("currency").asText().matches("[A-Z]{3}") && extra.path("decimal_places").isIntegralNumber()) {
            int scale = extra.path("decimal_places").intValue();
            if (scale >= 0 && scale <= 4 && validUsage(extra.path("utilization"))) {
                Optional<Instant> reset = instant(extra.path("resets_at"));
                Optional<Instant> start = instant(extra.path("starts_at")).filter(value -> reset.filter(end -> end.isAfter(value)).isPresent());
                windows.add(new ProviderQuotaWindow("extra_usage", "Monthly · extra usage", extra.path("utilization").doubleValue(), start, reset,
                        Optional.of(new ProviderQuotaWindow.Amount(extra.path("used_credits").decimalValue().movePointLeft(scale),
                                extra.path("monthly_limit").decimalValue().movePointLeft(scale), extra.path("currency").textValue()))));
            }
        }
        return new ProviderQuotaSnapshot(plan, windows.build(), Optional.empty());
    }

    private static void add(ImmutableList.Builder<ProviderQuotaWindow> windows, String id, String label, JsonNode window, Duration duration)
    {
        if (!validUsage(window.path("utilization"))) {
            return;
        }
        Optional<Instant> reset = instant(window.path("resets_at"));
        windows.add(new ProviderQuotaWindow(id, label, window.path("utilization").doubleValue(), reset.map(value -> value.minus(duration)), reset, Optional.empty()));
    }

    private static boolean validUsage(JsonNode value)
    {
        return value.isNumber() && Double.isFinite(value.doubleValue()) && value.doubleValue() >= 0;
    }

    private static Optional<Instant> instant(JsonNode value)
    {
        try {
            return value.isTextual() ? Optional.of(Instant.parse(value.textValue())) : Optional.empty();
        }
        catch (DateTimeException ignored) {
            return Optional.empty();
        }
    }
}
