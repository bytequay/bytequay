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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexAppServerClient
{
    @TempDir
    private Path worktree;

    @Test
    void closeRetriesProcessTreeVerificationAfterAnUnverifiableExit()
    {
        ObjectMapper objectMapper = new ObjectMapper();
        RetryingProcess process = new RetryingProcess(objectMapper);
        ProcessSupervisor supervisor = new ProcessSupervisor()
        {
            @Override
            public PreparedProcess prepare(ProcessSpec spec)
            {
                return new PreparedProcess()
                {
                    @Override
                    public String launchId()
                    {
                        return process.launchId();
                    }

                    @Override
                    public ManagedProcess start(ProcessOutputSink output)
                    {
                        process.output(output);
                        return process;
                    }

                    @Override
                    public void abandon() {}
                };
            }

            @Override
            public List<OwnedProcessRecovery> recoverOwnedProcesses()
            {
                return ImmutableList.of();
            }

            @Override
            public void acknowledge(String launchId) {}
        };
        ProcessSpec spec = new ProcessSpec(
                new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "task-1"),
                ImmutableList.of("/fake/codex", "app-server"),
                worktree,
                ImmutableMap.of(),
                1024,
                1024,
                Duration.ofMinutes(1),
                Duration.ZERO);
        CodexAppServerClient client = new CodexAppServerClient(
                supervisor,
                spec,
                objectMapper,
                SecretRedactor.fromEnvironment(ImmutableMap.of()));
        client.start();

        client.close();

        assertThat(process.stopAttempts()).isOne();
        assertThat(client.processTreeVerifiedStopped()).contains(true);
    }

    private static final class RetryingProcess
            implements ManagedProcess
    {
        private final ObjectMapper objectMapper;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final AtomicInteger stopAttempts = new AtomicInteger();
        private ProcessOutputSink output;

        private RetryingProcess(ObjectMapper objectMapper)
        {
            this.objectMapper = objectMapper;
        }

        private void output(ProcessOutputSink output)
        {
            this.output = output;
        }

        private int stopAttempts()
        {
            return stopAttempts.get();
        }

        @Override
        public String launchId()
        {
            return "retrying";
        }

        @Override
        public long pid()
        {
            return 42;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            try {
                JsonNode message = objectMapper.readTree(StandardCharsets.UTF_8.decode(bytes.duplicate()).toString());
                if (message.path("method").asText().equals("initialize")) {
                    long id = message.path("id").asLong();
                    output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(
                            "{\"id\":" + id + ",\"result\":{\"codexHome\":\"/codex-home\"}}\n"));
                }
                return CompletableFuture.completedFuture(null);
            }
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public void closeInput() {}

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            exit.complete(new ProcessExit(
                    0,
                    ProcessExit.Reason.EXITED,
                    ProcessExit.TreeTermination.UNVERIFIABLE,
                    Duration.ZERO,
                    false,
                    false));
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            stopAttempts.incrementAndGet();
            return CompletableFuture.completedFuture(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        }

        @Override
        public void close()
        {
            cancel();
        }
    }
}
