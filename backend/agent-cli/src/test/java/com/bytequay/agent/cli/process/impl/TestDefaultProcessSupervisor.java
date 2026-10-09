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
package com.bytequay.agent.cli.process.impl;

import com.bytequay.agent.cli.process.LaunchPhase;
import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessIdentity;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDefaultProcessSupervisor
{
    private static final ProcessOwner PROBE = new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "test-probe");

    @TempDir
    private Path worktree;

    @TempDir
    private Path launches;

    private final InMemoryLaunchIntentRecorder intents = new InMemoryLaunchIntentRecorder();

    private DefaultProcessSupervisor supervisor;

    @BeforeEach
    void createSupervisor()
    {
        supervisor = new DefaultProcessSupervisor(launches, intents);
    }

    @Test
    void writesStdinAndKeepsStdoutAndStderrSeparate()
            throws Exception
    {
        Map<ProcessStream, StringBuilder> output = new EnumMap<>(ProcessStream.class);
        output.put(ProcessStream.STDOUT, new StringBuilder());
        output.put(ProcessStream.STDERR, new StringBuilder());
        ManagedProcess process = supervisor
                .prepare(spec("read value; printf 'out:%s' \"$value\"; printf 'err:%s' \"$value\" >&2"))
                .start((stream, bytes) -> output.get(stream).append(StandardCharsets.UTF_8.decode(bytes)));

        process.write(ByteBuffer.wrap("hello\n".getBytes(StandardCharsets.UTF_8)))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        ProcessExit exit = process.exit().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isZero();
        assertThat(exit.reason()).isEqualTo(ProcessExit.Reason.EXITED);
        assertThat(exit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        assertThat(exit.stdoutTruncated()).isFalse();
        assertThat(exit.stderrTruncated()).isFalse();
        assertThat(output.get(ProcessStream.STDOUT)).hasToString("out:hello");
        assertThat(output.get(ProcessStream.STDERR)).hasToString("err:hello");
    }

    @Test
    void truncatesBoundedOutputWithoutBlockingTheChild()
            throws Exception
    {
        StringBuilder output = new StringBuilder();
        AtomicBoolean truncated = new AtomicBoolean();
        ProcessSpec spec = new ProcessSpec(
                PROBE,
                ImmutableList.of("/bin/sh", "-c", "i=0; while [ $i -lt 1000 ]; do printf 1234567890; i=$((i+1)); done"),
                worktree,
                ImmutableMap.of(),
                OptionalInt.of(64),
                64,
                Duration.ofSeconds(5),
                Duration.ofMillis(100));
        ManagedProcess process = supervisor.prepare(spec).start(new ProcessOutputSink()
        {
            @Override
            public void onOutput(ProcessStream stream, ByteBuffer bytes)
            {
                output.append(StandardCharsets.UTF_8.decode(bytes));
            }

            @Override
            public void onTruncated(ProcessStream stream)
            {
                truncated.set(true);
            }
        });

        ProcessExit exit = process.exit().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isZero();
        assertThat(exit.stdoutTruncated()).isTrue();
        assertThat(output).hasSize(64);
        assertThat(truncated).isTrue();
    }

    @Test
    void drainsTheChildWhenTheOutputConsumerIsSlow()
            throws Exception
    {
        CountDownLatch enteredSink = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        ProcessSpec spec = new ProcessSpec(
                PROBE,
                ImmutableList.of("/bin/sh", "-c", "dd if=/dev/zero bs=4096 count=1024 2>/dev/null"),
                worktree,
                ImmutableMap.of(),
                OptionalInt.of(4 * 1024 * 1024),
                64,
                Duration.ofSeconds(10),
                Duration.ofMillis(100));
        ManagedProcess process = supervisor.prepare(spec).start((stream, bytes) -> {
            enteredSink.countDown();
            try {
                releaseSink.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(enteredSink.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitDead(process.pid(), Duration.ofSeconds(5))).isTrue();
        releaseSink.countDown();
        ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isZero();
    }

    @Test
    void streamsBeyondTheCaptureQuotaWithoutDroppingABurstOrItsFinalRecord()
            throws Exception
    {
        CountDownLatch enteredSink = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        AtomicLong received = new AtomicLong();
        StringBuilder finalRecord = new StringBuilder();
        ProcessSpec spec = new ProcessSpec(
                PROBE,
                ImmutableList.of("/bin/sh", "-c", "dd if=/dev/zero bs=8192 count=8448 2>/dev/null; printf 'FINAL\\n'"),
                worktree,
                ImmutableMap.of(),
                OptionalInt.empty(),
                64,
                Duration.ofSeconds(20),
                Duration.ofMillis(100));
        ManagedProcess process = supervisor.prepare(spec).start((stream, bytes) -> {
            enteredSink.countDown();
            try {
                releaseSink.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            received.addAndGet(bytes.remaining());
            while (bytes.hasRemaining()) {
                byte value = bytes.get();
                if (value != 0) {
                    finalRecord.append((char) value);
                }
            }
        });
        try {
            assertThat(enteredSink.await(5, TimeUnit.SECONDS)).isTrue();
            // Hold the consumer while the child produces more than the fixed output queue can hold.
            Thread.sleep(200);
        }
        finally {
            releaseSink.countDown();
        }
        ProcessExit result = process.exit().toCompletableFuture().get(25, TimeUnit.SECONDS);

        assertThat(received).hasValue(8192L * 8448 + 6);
        assertThat(finalRecord).hasToString("FINAL\n");
        assertThat(result.stdoutTruncated()).isFalse();
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void stopsAProtocolProducerWhenItsBackpressuredConsumerFails()
            throws Exception
    {
        CountDownLatch enteredSink = new CountDownLatch(1);
        CountDownLatch failSink = new CountDownLatch(1);
        ProcessSpec spec = new ProcessSpec(
                PROBE,
                ImmutableList.of("/usr/bin/yes"),
                worktree,
                ImmutableMap.of(),
                OptionalInt.empty(),
                64,
                Duration.ofSeconds(10),
                Duration.ofMillis(100));
        ManagedProcess process = supervisor.prepare(spec).start((stream, bytes) -> {
            enteredSink.countDown();
            try {
                failSink.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("consumer failed");
        });
        try {
            assertThat(enteredSink.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
        }
        finally {
            failSink.countDown();
        }
        ProcessExit result = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(result.reason()).isEqualTo(ProcessExit.Reason.CANCELLED);
        assertThat(awaitDead(process.pid(), Duration.ofSeconds(2))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelsAProtocolProducerWhileStdinAndStdoutAreBlocked(boolean verifyStop)
            throws Exception
    {
        CountDownLatch enteredSink = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        ProcessSpec spec = new ProcessSpec(
                PROBE,
                ImmutableList.of("/usr/bin/yes"),
                worktree,
                ImmutableMap.of(),
                OptionalInt.empty(),
                64,
                Duration.ofSeconds(10),
                Duration.ofMillis(100));
        ManagedProcess process = supervisor.prepare(spec).start((stream, bytes) -> {
            enteredSink.countDown();
            try {
                releaseSink.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        try {
            assertThat(enteredSink.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> blockedInput = process.write(ByteBuffer.wrap(new byte[1024 * 1024])).toCompletableFuture();
            CompletableFuture<Void> queuedInput = process.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture();
            assertThatThrownBy(() -> blockedInput.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            if (verifyStop) {
                assertThat(process.stopAndVerify().toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
            }
            else {
                CompletableFuture.runAsync(process::cancel).get(2, TimeUnit.SECONDS);
                assertThat(awaitDead(process.pid(), Duration.ofSeconds(5))).isTrue();
            }
            assertThatThrownBy(() -> blockedInput.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(() -> queuedInput.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(AgentUnavailableException.class);
        }
        finally {
            // Keep the regression bounded even if cancellation waits on the blocked stdin writer.
            ProcessHandle.of(process.pid()).ifPresent(handle -> {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                handle.destroyForcibly();
            });
            releaseSink.countDown();
        }
        assertThat(process.exit().toCompletableFuture().get(10, TimeUnit.SECONDS).reason()).isEqualTo(ProcessExit.Reason.CANCELLED);
    }

    @Test
    void cancelsTheProcessTreeAndTimesOutLongRuns()
            throws Exception
    {
        StringBuilder stdout = new StringBuilder();
        CountDownLatch childReported = new CountDownLatch(1);
        ManagedProcess cancelled = supervisor
                .prepare(spec("sleep 30 & child=$!; printf '%s\\n' \"$child\"; wait"))
                .start((stream, bytes) -> {
                    stdout.append(StandardCharsets.UTF_8.decode(bytes));
                    if (stdout.indexOf("\n") >= 0) {
                        childReported.countDown();
                    }
                });
        assertThat(childReported.await(5, TimeUnit.SECONDS)).isTrue();
        long childPid = Long.parseLong(stdout.toString().trim());

        cancelled.cancel();
        ProcessExit cancelledExit = cancelled.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(cancelledExit.reason()).isEqualTo(ProcessExit.Reason.CANCELLED);
        assertThat(cancelledExit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        assertThat(awaitDead(cancelled.pid(), Duration.ofSeconds(2))).isTrue();
        assertThat(awaitDead(childPid, Duration.ofSeconds(2))).isTrue();

        ProcessSpec timeoutSpec = new ProcessSpec(
                PROBE,
                ImmutableList.of("/bin/sh", "-c", "sleep 30"),
                worktree,
                ImmutableMap.of(),
                OptionalInt.of(64),
                64,
                Duration.ofMillis(100),
                Duration.ofMillis(100));
        ManagedProcess timedOut = supervisor.prepare(timeoutSpec).start((stream, bytes) -> {});
        ProcessExit timedOutExit = timedOut.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(timedOutExit.reason()).isEqualTo(ProcessExit.Reason.TIMED_OUT);
        assertThat(awaitDead(timedOut.pid(), Duration.ofSeconds(2))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unlimitedProcessRunsUntilCompletionOrCancellation(boolean cancel)
            throws Exception
    {
        ProcessSpec spec = new ProcessSpec(
                PROBE, ImmutableList.of("/bin/sh", "-c", "read input"), worktree, ImmutableMap.of(), OptionalInt.empty(), 64,
                Duration.ZERO, Duration.ofMillis(100));
        ManagedProcess process = supervisor.prepare(spec).start((stream, bytes) -> {});
        try {
            assertThatThrownBy(() -> process.exit().toCompletableFuture().get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            if (cancel) {
                process.cancel();
            }
            else {
                process.write(StandardCharsets.UTF_8.encode("done\n")).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertThat(exit.reason()).isEqualTo(cancel ? ProcessExit.Reason.CANCELLED : ProcessExit.Reason.EXITED);
            assertThat(exit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        }
        finally {
            process.cancel();
        }
    }

    @Test
    void terminatesADescendantThatOutlivesItsDirectParent()
            throws Exception
    {
        StringBuilder stdout = new StringBuilder();
        CountDownLatch reported = new CountDownLatch(1);
        // The middle shell exits immediately, so only owned process-group membership can still
        // prove that the re-parented descendant stopped.
        ManagedProcess process = supervisor
                .prepare(spec("/bin/sh -c 'sleep 45 & printf \"%s\\n\" \"$!\"'; sleep 45"))
                .start((stream, bytes) -> {
                    stdout.append(StandardCharsets.UTF_8.decode(bytes));
                    if (stdout.indexOf("\n") >= 0) {
                        reported.countDown();
                    }
                });
        assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue();
        long descendantPid = Long.parseLong(stdout.toString().trim());

        process.cancel();
        ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(exit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        assertThat(awaitDead(descendantPid, Duration.ofSeconds(2))).isTrue();
    }

    @Test
    void reportsAndStillCancelsGroupMembersThatOutliveTheLauncher()
            throws Exception
    {
        // The payload exits immediately, leaving a detached group member with redirected streams,
        // so nothing but process-group membership can observe that work is still running.
        ManagedProcess process = supervisor
                .prepare(spec("sleep 45 >/dev/null 2>&1 & exit 0"))
                .start((stream, bytes) -> {});
        ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isZero();
        assertThat(exit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.UNVERIFIABLE);

        long survivor = ProcessGroups.members(process.pid()).orElseThrow().getFirst();
        assertThat(ProcessHandle.of(survivor)).isPresent();

        ProcessExit.TreeTermination retried = process.stopAndVerify().toCompletableFuture()
                .get(15, TimeUnit.SECONDS);

        assertThat(retried).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        assertThat(awaitDead(survivor, Duration.ofSeconds(5))).isTrue();
        assertThat(new LaunchMarkerStore(launches).read(process.launchId()).orElseThrow().treeTermination())
                .contains(ProcessExit.TreeTermination.VERIFIED_STOPPED);
    }

    @Test
    void recordsAndStopsADescendantThatLeavesTheProcessGroup()
            throws Exception
    {
        StringBuilder stdout = new StringBuilder();
        CountDownLatch reported = new CountDownLatch(1);
        ManagedProcess process = supervisor
                .prepare(spec("/bin/bash -c 'set -m; sleep 45 >/dev/null 2>&1 & printf \"%s\\n\" \"$!\"; sleep 1'"))
                .start((stream, bytes) -> {
                    stdout.append(StandardCharsets.UTF_8.decode(bytes));
                    if (stdout.indexOf("\n") >= 0) {
                        reported.countDown();
                    }
                });
        assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue();
        long descendantPid = Long.parseLong(stdout.toString().trim());

        ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(exit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.UNVERIFIABLE);
        assertThat(ProcessHandle.of(descendantPid)).isPresent();
        assertThat(new LaunchMarkerStore(launches).read(process.launchId()).orElseThrow().descendants().orElseThrow())
                .extracting(ProcessIdentity::pid)
                .contains(descendantPid);

        assertThat(process.stopAndVerify().toCompletableFuture().get(15, TimeUnit.SECONDS))
                .isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        assertThat(awaitDead(descendantPid, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void cliProbeShortLivedDescendantsDoNotInvalidateTreeVerification()
            throws Exception
    {
        ManagedProcess process = supervisor
                .prepare(spec("i=0; while [ $i -lt 256 ]; do /usr/bin/true & i=$((i + 1)); done; wait"))
                .start((stream, bytes) -> {});

        ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(exit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        assertThat(new LaunchMarkerStore(launches).read(process.launchId()).orElseThrow().descendants()).isPresent();
    }

    @Test
    void modelCatalogShortLivedDescendantsDoNotInvalidateTreeVerification()
            throws Exception
    {
        ManagedProcess process = supervisor
                .prepare(spec(
                        new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "codex-model-catalog"),
                        "i=0; while [ $i -lt 256 ]; do /usr/bin/true & i=$((i + 1)); done; wait"))
                .start((stream, bytes) -> {});

        ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(exit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        assertThat(new LaunchMarkerStore(launches).read(process.launchId()).orElseThrow().descendants()).isPresent();
    }

    @Test
    void scratchHelperShortLivedDescendantsDoNotInvalidateTreeVerification()
            throws Exception
    {
        ManagedProcess process = supervisor
                .prepare(spec(
                        new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "task-title"),
                        "i=0; while [ $i -lt 256 ]; do /usr/bin/true & i=$((i + 1)); done; wait"))
                .start((stream, bytes) -> {});

        ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(exit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        assertThat(new LaunchMarkerStore(launches).read(process.launchId()).orElseThrow().descendants()).isPresent();
    }

    @Test
    void tracksPayloadDescendantsWithoutTheLaunchersIdentityPublisher()
            throws Exception
    {
        Path publisher = worktree.resolve("mv");
        Files.writeString(publisher, """
                #!/bin/sh
                printf '%s' "$$" > "$0.pid"
                /bin/mv "$@"
                /bin/sleep 30
                """);
        assertThat(publisher.toFile().setExecutable(true)).isTrue();
        StringBuilder output = new StringBuilder();
        CountDownLatch childReported = new CountDownLatch(1);
        ProcessSpec spec = new ProcessSpec(
                new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "test-task"),
                ImmutableList.of("/bin/sh", "-c", "sleep 30 & printf '%s\\n' \"$!\"; read value"),
                worktree,
                ImmutableMap.of("PATH", worktree + ":/usr/bin:/bin"),
                OptionalInt.of(1024),
                1024,
                Duration.ofSeconds(20),
                Duration.ofMillis(100));
        ManagedProcess process = supervisor.prepare(spec).start((stream, bytes) -> {
            output.append(StandardCharsets.UTF_8.decode(bytes));
            if (output.indexOf("\n") >= 0) {
                childReported.countDown();
            }
        });
        try {
            assertThat(childReported.await(5, TimeUnit.SECONDS)).isTrue();
            long childPid = Long.parseLong(output.toString().trim());
            LaunchMarkerStore store = new LaunchMarkerStore(launches);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (store.read(process.launchId()).orElseThrow().descendants().orElseThrow().stream().noneMatch(identity -> identity.pid() == childPid)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            long publisherPid = Long.parseLong(Files.readString(worktree.resolve("mv.pid")));
            assertThat(store.read(process.launchId()).orElseThrow().descendants().orElseThrow())
                    .extracting(ProcessIdentity::pid)
                    .contains(childPid)
                    .doesNotContain(publisherPid);
        }
        finally {
            process.stopAndVerify().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
        assertThat(process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS).treeTermination())
                .isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
    }

    @Test
    void distinguishesPersistentChildrenFromAnExecutionsNewProcesses()
            throws Exception
    {
        CountDownLatch serviceReady = new CountDownLatch(1);
        CountDownLatch runReady = new CountDownLatch(1);
        AtomicLong service = new AtomicLong();
        AtomicLong run = new AtomicLong();
        StringBuilder output = new StringBuilder();
        ManagedProcess process = supervisor.prepare(spec(new ProcessOwner(ProcessOwner.Kind.IDE, "test-task"),
                "sleep 30 & printf 'service:%s\\n' \"$!\"; read begin; sleep 30 & job=$!; printf 'run:%s\\n' \"$job\"; read finish; kill \"$job\"; wait \"$job\"; read close"))
                .start((stream, bytes) -> {
                    output.append(StandardCharsets.UTF_8.decode(bytes));
                    for (String line : output.toString().split("\\n")) {
                        if (line.startsWith("service:")) {
                            service.set(Long.parseLong(line.substring(8).strip()));
                            serviceReady.countDown();
                        }
                        if (line.startsWith("run:")) {
                            run.set(Long.parseLong(line.substring(4).strip()));
                            runReady.countDown();
                        }
                    }
                });
        try {
            assertThat(serviceReady.await(5, TimeUnit.SECONDS)).isTrue();
            List<ProcessIdentity> baseline = process.observedDescendants().orElseThrow();
            assertThat(baseline).extracting(ProcessIdentity::pid).contains(service.get());
            process.write(ByteBuffer.wrap("begin\n".getBytes(StandardCharsets.UTF_8))).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(runReady.await(5, TimeUnit.SECONDS)).isTrue();
            List<ProcessIdentity> added = process.observedDescendants().orElseThrow().stream().filter(identity -> !baseline.contains(identity)).toList();
            assertThat(added).extracting(ProcessIdentity::pid).contains(run.get());
            assertThat(added.stream().anyMatch(identity -> identity.liveness() != ProcessIdentity.Liveness.GONE)).isTrue();
            process.write(ByteBuffer.wrap("finish\n".getBytes(StandardCharsets.UTF_8))).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(awaitDead(run.get(), Duration.ofSeconds(5))).isTrue();
            assertThat(process.observedDescendants().orElseThrow()).containsAll(added);
            assertThat(added).allMatch(identity -> identity.liveness() == ProcessIdentity.Liveness.GONE);
            assertThat(baseline.stream().filter(identity -> identity.pid() == service.get()).findFirst().orElseThrow().liveness()).isEqualTo(ProcessIdentity.Liveness.MATCHES);
        }
        finally {
            assertThat(process.stopAndVerify().toCompletableFuture().get(15, TimeUnit.SECONDS)).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        }
        assertThat(process.observedDescendants()).isEmpty();
    }

    @Test
    void verifiesTextOnlyTaskProcesses()
            throws Exception
    {
        for (int index = 0; index < 10; index++) {
            ManagedProcess process = supervisor
                    .prepare(spec(new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "test-task"), "read value; printf '%s' \"$value\""))
                    .start((stream, bytes) -> {});
            process.write(ByteBuffer.wrap("hello\n".getBytes(StandardCharsets.UTF_8))).toCompletableFuture().get(5, TimeUnit.SECONDS);
            ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertThat(exit.treeTermination()).as("task launch %s", index).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        }
    }

    @Test
    void interruptedTaskWithAnAlreadyDeadTokenlessChildCanStopWithoutFinishedWorkProof()
            throws Exception
    {
        ManagedProcess process = supervisor.prepare(spec(new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "interrupted-task"), "read value"))
                .start((stream, bytes) -> {});
        try {
            observeTokenlessDescendant(process, false);
            assertThat(process.stopAndVerify().toCompletableFuture().get(15, TimeUnit.SECONDS)).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
            LaunchMarker marker = new LaunchMarkerStore(launches).read(process.launchId()).orElseThrow();
            assertThat(marker.descendantTrackingIncomplete()).isFalse();
            assertThat(marker.workFinished()).isFalse();
        }
        finally {
            process.close();
        }
    }

    @Test
    void interruptedTaskWithAnUnreadableLiveChildStillRetainsTheOwnershipGap()
            throws Exception
    {
        ManagedProcess process = supervisor.prepare(spec(new ProcessOwner(ProcessOwner.Kind.CLAUDE_TURN, "interrupted-task"), "read value"))
                .start((stream, bytes) -> {});
        try {
            assertThat(process.observedDescendants()).isPresent();
            observeTokenlessDescendant(process, true);
            assertThat(process.observedDescendants()).isEmpty();
            assertThat(process.stopAndVerify().toCompletableFuture().get(15, TimeUnit.SECONDS)).isEqualTo(ProcessExit.TreeTermination.UNVERIFIABLE);
            LaunchMarker marker = new LaunchMarkerStore(launches).read(process.launchId()).orElseThrow();
            assertThat(marker.descendantTrackingIncomplete()).isTrue();
            assertThat(marker.workFinished()).isFalse();
            observeTokenlessDescendant(process, false);
            assertThat(process.stopAndVerify().toCompletableFuture().get(15, TimeUnit.SECONDS)).isEqualTo(ProcessExit.TreeTermination.UNVERIFIABLE);
        }
        finally {
            process.close();
        }
    }

    private static void observeTokenlessDescendant(ManagedProcess process, boolean alive)
            throws Exception
    {
        ProcessHandle.Info info = (ProcessHandle.Info) Proxy.newProxyInstance(
                ProcessHandle.Info.class.getClassLoader(), new Class<?>[] {ProcessHandle.Info.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("startInstant")) {
                        return Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ProcessHandle descendant = (ProcessHandle) Proxy.newProxyInstance(
                ProcessHandle.class.getClassLoader(), new Class<?>[] {ProcessHandle.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "pid" -> 123_456_789L;
                    case "info" -> info;
                    case "isAlive" -> alive;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Method observe = process.getClass().getDeclaredMethod("rememberDescendant", ProcessHandle.class);
        observe.setAccessible(true);
        observe.invoke(process, descendant);
    }

    @Test
    void finishedTaskWorkResolvesStartupChurnWithoutForgettingItsReceipt()
            throws Exception
    {
        CountDownLatch ready = new CountDownLatch(1);
        ManagedProcess process = supervisor.prepare(spec(
                        new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "test-task"),
                        "i=0; while [ $i -lt 256 ]; do /usr/bin/true & i=$((i + 1)); done; wait; printf ready; read value"))
                .start((stream, bytes) -> ready.countDown());
        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(process.stopAfterWorkFinished().toCompletableFuture().get(15, TimeUnit.SECONDS))
                    .isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
            process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertThat(process.stopAndVerify().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
            assertThat(new LaunchMarkerStore(launches).read(process.launchId()).orElseThrow().workFinished()).isTrue();
            assertThatThrownBy(() -> process.write(ByteBuffer.wrap("new work\n".getBytes(StandardCharsets.UTF_8))).toCompletableFuture().join())
                    .isInstanceOf(RuntimeException.class);
            assertThat(new DefaultProcessSupervisor(launches, intents).recoverOwnedProcesses())
                    .singleElement()
                    .satisfies(recovery -> assertThat(recovery.outcome()).isEqualTo(OwnedProcessRecovery.Outcome.VERIFIED_STOPPED));
        }
        finally {
            process.close();
        }
    }

    @Test
    void finishedWorkStillStopsAKnownChildOutsideTheProcessGroup()
            throws Exception
    {
        StringBuilder output = new StringBuilder();
        CountDownLatch reported = new CountDownLatch(1);
        ManagedProcess process = supervisor.prepare(spec(
                        new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "test-task"),
                        "/bin/bash -c 'set -m; sleep 45 >/dev/null 2>&1 & printf \"%s\\n\" \"$!\"; read value'"))
                .start((stream, bytes) -> {
                    output.append(StandardCharsets.UTF_8.decode(bytes));
                    if (output.indexOf("\n") >= 0) {
                        reported.countDown();
                    }
                });
        try {
            assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue();
            long childPid = Long.parseLong(output.toString().trim());
            LaunchMarkerStore store = new LaunchMarkerStore(launches);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (store.read(process.launchId()).orElseThrow().descendants().orElseThrow().stream().noneMatch(identity -> identity.pid() == childPid)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(store.read(process.launchId()).orElseThrow().descendants().orElseThrow())
                    .extracting(ProcessIdentity::pid)
                    .contains(childPid);

            assertThat(process.stopAfterWorkFinished().toCompletableFuture().get(15, TimeUnit.SECONDS))
                    .isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
            process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertThat(awaitDead(childPid, Duration.ofSeconds(5))).isTrue();
        }
        finally {
            process.close();
        }
    }

    @Test
    void refusesFinishedWorkAuthorityWhenItsReceiptCannotBeWritten()
            throws Exception
    {
        ManagedProcess process = supervisor.prepare(spec(new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "test-task"), "read value"))
                .start((stream, bytes) -> {});
        Path blockedWrite = launches.resolve(process.launchId() + ".json.part");
        Files.createDirectory(blockedWrite);
        try {
            assertThatThrownBy(process::stopAfterWorkFinished).isInstanceOf(AgentUnavailableException.class);
            assertThat(new LaunchMarkerStore(launches).read(process.launchId()).orElseThrow().workFinished()).isFalse();
            assertThat(ProcessHandle.of(process.pid()).orElseThrow().isAlive()).isTrue();
        }
        finally {
            Files.delete(blockedWrite);
            process.stopAndVerify().toCompletableFuture().get(15, TimeUnit.SECONDS);
            process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void waitsForReleaseWithoutDescendantsAndExitsWhenInputCloses()
            throws Exception
    {
        String script;
        try (InputStream resource = DefaultProcessSupervisor.class.getResourceAsStream("launcher.sh")) {
            script = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path identity = worktree.resolve("identity");
        Path sentinel = worktree.resolve("payload-ran");
        ProcessBuilder builder = new ProcessBuilder(
                "/bin/bash", "-c", script, "bytequay-launch", worktree.resolve("gate").toString(), identity.toString(), "/usr/bin/touch", sentinel.toString());
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        Process launcher = builder.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(identity) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            long payloadPid = Long.parseLong(Files.readString(identity).trim());
            assertThat(ProcessHandle.of(payloadPid).orElseThrow().children()).isEmpty();
            launcher.getOutputStream().close();

            assertThat(launcher.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(launcher.exitValue()).isEqualTo(70);
            assertThat(awaitDead(payloadPid, Duration.ofSeconds(2))).isTrue();
            assertThat(sentinel).doesNotExist();
        }
        finally {
            launcher.descendants().forEach(ProcessHandle::destroyForcibly);
            launcher.destroyForcibly();
        }
    }

    @Test
    void runsALocalRunBehindANonExecLeaderThatKeepsTheWrapperExitCode()
            throws Exception
    {
        Path wrapper = worktree.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s|' \"$@\" > args\nexec /bin/sh -c 'exit 3'\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
        ProcessOwner owner = new ProcessOwner(ProcessOwner.Kind.LOCAL_RUN, "task-1/run-1");
        PreparedProcess prepared = supervisor.prepare(new ProcessSpec(
                owner,
                ImmutableList.of("/bin/bash", "-c", "\"$0\" \"$@\"; exit $?", wrapper.toString(), "-B", "-Dtest=demo.TestCode#fails", "test"),
                worktree,
                ImmutableMap.of("PATH", "/usr/bin:/bin"),
                OptionalInt.empty(),
                64 * 1024,
                Duration.ofSeconds(10),
                Duration.ofMillis(100)));

        ProcessExit exit = prepared.start((stream, bytes) -> {}).exit().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isEqualTo(3);
        assertThat(exit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        assertThat(Files.readString(worktree.resolve("args"))).isEqualTo("-B|-Dtest=demo.TestCode#fails|test|");
        LaunchMarker marker = new LaunchMarkerStore(launches).read(prepared.launchId()).orElseThrow();
        assertThat(marker.owner()).isEqualTo(owner);
        assertThat(marker.executable()).isEqualTo("/bin/bash");
    }

    @Test
    void preparesADurableMarkerBeforeAnyProcessExists()
    {
        PreparedProcess prepared = supervisor.prepare(spec("exit 0"));
        LaunchMarkerStore store = new LaunchMarkerStore(launches);
        LaunchMarker marker = store.read(prepared.launchId()).orElseThrow();

        assertThat(marker.phase()).isEqualTo(LaunchPhase.PREPARED);
        assertThat(marker.owner()).isEqualTo(PROBE);
        assertThat(marker.executable()).isEqualTo("/bin/sh");
        assertThat(marker.argvDigest()).isNotBlank();
        assertThat(marker.pid()).isEmpty();
        assertThat(store.gateReleased(prepared.launchId())).isFalse();
        assertThat(intents.attempted(prepared.launchId())).contains(false);

        prepared.abandon();

        assertThat(store.read(prepared.launchId())).isEmpty();
        assertThat(intents.attempted(prepared.launchId())).isEmpty();
    }

    @Test
    void recordsPayloadIdentityAndAnIsolatedProcessGroupThenAcknowledgesTheReceipt()
            throws Exception
    {
        ManagedProcess process = supervisor.prepare(spec("printf ready; sleep 30")).start((stream, bytes) -> {});
        LaunchMarkerStore store = new LaunchMarkerStore(launches);
        LaunchMarker running = store.read(process.launchId()).orElseThrow();

        assertThat(running.phase()).isEqualTo(LaunchPhase.RUNNING);
        assertThat(running.pid()).contains(process.pid());
        assertThat(running.processGroup()).contains(process.pid());
        assertThat(running.startToken()).isPresent();
        assertThat(running.launcherCommand()).isPresent();
        assertThat(running.identity().orElseThrow().liveness()).isEqualTo(ProcessIdentity.Liveness.MATCHES);
        assertThat(store.gateReleased(process.launchId())).isTrue();

        process.cancel();
        ProcessExit exit = process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
        LaunchMarker terminal = store.read(process.launchId()).orElseThrow();

        assertThat(terminal.phase()).isEqualTo(LaunchPhase.TERMINAL);
        assertThat(terminal.treeTermination()).contains(exit.treeTermination());
        // Ownership is committed before the launcher exists, and nothing else is written on the
        // launch path: every later fact already lives in the marker.
        assertThat(intents.transitions()).containsExactly("prepared", "attempting");
        assertThat(intents.attempted(process.launchId())).contains(true);

        supervisor.acknowledge(process.launchId());

        assertThat(store.read(process.launchId())).isEmpty();
        assertThat(intents.attempted(process.launchId())).isEmpty();
    }

    @Test
    void neverExecutesThePayloadWhenTheGatedLaunchCannotBeRecorded()
            throws Exception
    {
        Path sentinel = worktree.resolve("payload-ran");
        PreparedProcess prepared = supervisor.prepare(spec("touch '" + sentinel + "'; sleep 30"));
        // Blocking the staged marker write reproduces a crash between the launcher spawn and the
        // durable identity record, which is the window the gate exists to close.
        Files.createDirectory(launches.resolve(prepared.launchId() + ".json.part"));

        assertThatThrownBy(() -> prepared.start((stream, bytes) -> {}))
                .isInstanceOf(AgentUnavailableException.class);

        LaunchMarkerStore store = new LaunchMarkerStore(launches);
        assertThat(store.read(prepared.launchId())).isEmpty();
        assertThat(intents.attempted(prepared.launchId())).isEmpty();
        assertThat(store.gateReleased(prepared.launchId())).isFalse();
        assertThat(supervisor.recoverOwnedProcesses()).isEmpty();
        Thread.sleep(500);
        assertThat(sentinel).doesNotExist();
    }

    @Test
    void forgetsTheAttemptWhenTheLauncherCannotSpawn()
            throws Exception
    {
        PreparedProcess prepared = supervisor.prepare(spec("exit 0"));
        Files.delete(worktree);

        assertThatThrownBy(() -> prepared.start((stream, bytes) -> {}))
                .isInstanceOf(AgentUnavailableException.class);

        assertThat(new LaunchMarkerStore(launches).read(prepared.launchId())).isEmpty();
        assertThat(intents.attempted(prepared.launchId())).isEmpty();
        assertThat(supervisor.recoverOwnedProcesses()).isEmpty();
    }

    @Test
    void rejectsAnExecutableThatCannotBeResolved()
    {
        ProcessSpec spec = new ProcessSpec(
                PROBE,
                ImmutableList.of("bytequay-missing-binary"),
                worktree,
                ImmutableMap.of("PATH", "/usr/bin:/bin"),
                OptionalInt.of(64),
                64,
                Duration.ofSeconds(5),
                Duration.ofMillis(100));

        assertThatThrownBy(() -> supervisor.prepare(spec))
                .isInstanceOf(AgentUnavailableException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(supervisor.recoverOwnedProcesses()).isEmpty();
    }

    @Test
    void refusesToStartAPreparedLaunchTwice()
            throws Exception
    {
        PreparedProcess prepared = supervisor.prepare(spec("exit 0"));
        ManagedProcess process = prepared.start((stream, bytes) -> {});
        process.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThatThrownBy(() -> prepared.start((stream, bytes) -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    private ProcessSpec spec(String script)
    {
        return spec(PROBE, script);
    }

    private ProcessSpec spec(ProcessOwner owner, String script)
    {
        return new ProcessSpec(
                owner,
                ImmutableList.of("/bin/sh", "-c", script),
                worktree,
                ImmutableMap.of("PATH", "/usr/bin:/bin"),
                OptionalInt.of(1024),
                1024,
                Duration.ofSeconds(20),
                Duration.ofMillis(100));
    }

    private static boolean awaitDead(long pid, Duration timeout)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(10);
            }
            else {
                return true;
            }
        }
        return false;
    }
}
