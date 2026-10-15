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
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.spi.ProviderQuotaWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestClaudeQuota
{
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    private Path home;

    @Test
    void readsPersonalAndModelWindowsWithoutDuplicatingLegacyModelLimits()
            throws Exception
    {
        var quota = ClaudeQuota.parse(mapper.readTree("""
                {"subscription_type":"max","rate_limits_available":true,"rate_limits":{
                  "five_hour":{"utilization":37,"resets_at":"2026-10-08T07:20:00.082166+00:00"},
                  "seven_day":{"utilization":100,"resets_at":"2026-10-09T00:00:00Z"},
                  "seven_day_opus":{"utilization":90},
                  "model_scoped":[{"display_name":"Fable","utilization":0,"resets_at":"2026-10-09T00:00:00Z"}]}}
                """));
        assertThat(quota.windows()).extracting(ProviderQuotaWindow::usedPercent).containsExactly(37.0, 100.0, 0.0);
        var session = quota.windows().getFirst();
        assertThat(Duration.between(session.startsAt().orElseThrow(), session.resetsAt().orElseThrow())).isEqualTo(Duration.ofHours(5));
        assertThat(quota.windows().getLast().label()).isEqualTo("Weekly · Fable");
    }

    @Test
    void missingOrMalformedDataIsNotZeroAndMonthlyPaceIsNotInvented()
            throws Exception
    {
        assertThat(ClaudeQuota.parse(mapper.readTree("{\"rate_limits_available\":false}")).windows()).isEmpty();
        var quota = ClaudeQuota.parse(mapper.readTree("""
                {"rate_limits_available":true,"rate_limits":{
                 "five_hour":{"utilization":null},"seven_day":{"utilization":-1},
                 "model_scoped":[{"display_name":"Fable","utilization":12,"resets_at":"invalid"}],
                 "extra_usage":{"is_enabled":true,"monthly_limit":50000,"used_credits":12000,"utilization":24,"currency":"USD","decimal_places":2}}}
                """));
        assertThat(quota.windows()).hasSize(2).allSatisfy(window -> assertThat(window.startsAt()).isEmpty());
        assertThat(quota.windows().getLast().amount().orElseThrow().used()).isEqualByComparingTo("120");
        assertThat(quota.windows().getLast().amount().orElseThrow().total()).isEqualByComparingTo("500");
    }

    @Test
    void querySendsOnlyControlMessagesAndCleansUpTheProcess()
            throws Exception
    {
        ProbeProcess process = new ProbeProcess();
        assertThat(ClaudeQuota.read(process, spec(), mapper).windows()).isEmpty();
        assertThat(process.requests).containsExactly("initialize", "get_usage");
        process.acknowledged.get(3, TimeUnit.SECONDS);
        assertThat(process.exit.join().reason()).isEqualTo(ProcessExit.Reason.EXITED);
        assertThat(process.cancelled).isFalse();
    }

    @Test
    void cachedUsageDoesNotCloseInputBeforeTheRefreshedCredentialIsSaved()
            throws Exception
    {
        ProbeProcess process = new ProbeProcess();
        process.credentials = "{\"claudeAiOauth\":{\"refreshToken\":\"old-fabricated-token\",\"expiresAt\":0}}";
        assertThat(ClaudeQuota.read(process, spec(), mapper).windows()).isEmpty();
        assertThat(process.credentialRead.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exit.isDone()).isFalse();
        assertThat(process.cancelled).isFalse();
        process.credentials = "{\"claudeAiOauth\":{\"refreshToken\":\"new-fabricated-token\",\"expiresAt\":" + Instant.now().plus(Duration.ofHours(8)).toEpochMilli() + "}}";
        process.acknowledged.get(3, TimeUnit.SECONDS);
        assertThat(process.exit.join().reason()).isEqualTo(ProcessExit.Reason.EXITED);
        assertThat(process.cancelled).isFalse();
    }

    @Test
    void unreadableCredentialStatusNeverCancelsARefreshInFlight()
            throws Exception
    {
        ProbeProcess process = new ProbeProcess();
        process.credentials = "unreadable";
        ClaudeQuota.read(process, spec(), mapper);
        assertThat(process.credentialRead.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exit.isDone()).isFalse();
        assertThat(process.cancelled).isFalse();
        // The supervisor still owns the finite runtime deadline and verifies its eventual cleanup.
        process.exit.complete(new ProcessExit(143, ProcessExit.Reason.TIMED_OUT, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));
        process.acknowledged.get(3, TimeUnit.SECONDS);
    }

    @Test
    void waitsForTheCliFallbackFileAndActiveRefreshLock()
            throws Exception
    {
        ProbeProcess process = new ProbeProcess();
        process.credentialExitCode = 44;
        Path credentials = home.resolve(".credentials.json");
        Files.writeString(credentials, "{\"claudeAiOauth\":{\"refreshToken\":\"fabricated-token\",\"expiresAt\":0}}");
        ClaudeQuota.read(process, spec(), mapper);
        assertThat(process.credentialRead.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exit.isDone()).isFalse();
        Files.createDirectory(home.resolve(".oauth_refresh.lock"));
        Files.writeString(credentials, "{\"claudeAiOauth\":{\"refreshToken\":\"fabricated-token\",\"expiresAt\":" + Instant.now().plus(Duration.ofHours(8)).toEpochMilli() + "}}");
        assertThat(ClaudeCredentialRefresh.pending(process, spec(), mapper)).isTrue();
        Files.delete(home.resolve(".oauth_refresh.lock"));
        process.acknowledged.get(3, TimeUnit.SECONDS);
        assertThat(process.cancelled).isFalse();
    }

    private ProcessSpec spec()
    {
        return new ProcessSpec(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "usage"), ImmutableList.of("claude"), home,
                ImmutableMap.of("CLAUDE_CONFIG_DIR", home.toString(), "USER", "test-user"),
                OptionalInt.empty(), 1024, Duration.ofMinutes(2), Duration.ZERO);
    }

    private final class ProbeProcess
            implements ProcessSupervisor, PreparedProcess, ManagedProcess
    {
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final List<String> requests = new ArrayList<>();
        private ProcessOutputSink sink;
        private final CompletableFuture<Void> acknowledged = new CompletableFuture<>();
        private final CountDownLatch credentialRead = new CountDownLatch(1);
        private volatile String credentials = "{}";
        private int credentialExitCode;
        private volatile boolean cancelled;
        private ProbeProcess credentialOwner;

        @Override
        public PreparedProcess prepare(ProcessSpec spec)
        {
            if (spec.argv().getFirst().equals("/usr/bin/security")) {
                assertThat(spec.argv()).containsSequence("-a", "test-user", "-w", "-s", ClaudeCredentialRefresh.service(home));
                ProbeProcess read = new ProbeProcess();
                read.credentialOwner = this;
                return read;
            }
            return this;
        }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses()
        {
            return ImmutableList.of();
        }

        @Override
        public void acknowledge(String launchId)
        {
            if (launchId.equals("usage-probe")) {
                acknowledged.complete(null);
            }
        }

        @Override
        public String launchId()
        {
            return credentialOwner == null ? "usage-probe" : "credential-read";
        }

        @Override
        public ManagedProcess start(ProcessOutputSink output)
        {
            sink = output;
            if (credentialOwner != null) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(credentialOwner.credentials));
                exit.complete(new ProcessExit(credentialOwner.credentialExitCode, ProcessExit.Reason.EXITED,
                        ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));
                credentialOwner.credentialRead.countDown();
            }
            return this;
        }

        @Override
        public long pid()
        {
            return 1;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            try {
                var request = mapper.readTree(StandardCharsets.UTF_8.decode(bytes).toString());
                requests.add(request.path("request").path("subtype").asText());
                assertThat(request.path("type").asText()).isEqualTo("control_request");
                if (request.path("request_id").asText().equals("usage")) {
                    assertThat(request.path("request").path("skip_behaviors").asBoolean()).isTrue();
                }
                String reply = "{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\""
                        + request.path("request_id").asText() + "\",\"response\":{\"rate_limits_available\":false}}}\n";
                sink.onOutput(ProcessStream.STDOUT, ByteBuffer.wrap(reply.getBytes(StandardCharsets.UTF_8)));
                return CompletableFuture.completedFuture(null);
            }
            catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public void closeInput()
        {
            exit.complete(new ProcessExit(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));
        }

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            cancelled = true;
            exit.complete(new ProcessExit(0, ProcessExit.Reason.CANCELLED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));
        }

        @Override
        public void close()
        {
            cancel();
        }
    }
}
