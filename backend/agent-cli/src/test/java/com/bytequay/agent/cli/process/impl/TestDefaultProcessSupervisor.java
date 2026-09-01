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

import com.bytequay.agent.cli.process.LaunchIntent;
import com.bytequay.agent.cli.process.LaunchPhase;
import com.bytequay.agent.cli.process.ManagedProcess;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
                64,
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
                4 * 1024 * 1024,
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
                64,
                64,
                Duration.ofMillis(100),
                Duration.ofMillis(100));
        ManagedProcess timedOut = supervisor.prepare(timeoutSpec).start((stream, bytes) -> {});
        ProcessExit timedOutExit = timedOut.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertThat(timedOutExit.reason()).isEqualTo(ProcessExit.Reason.TIMED_OUT);
        assertThat(awaitDead(timedOut.pid(), Duration.ofSeconds(2))).isTrue();
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

        process.cancel();

        assertThat(awaitDead(survivor, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void preparesADurableMarkerBeforeAnyProcessExists()
    {
        PreparedProcess prepared = supervisor.prepare(spec("exit 0"));
        LaunchMarkerStore store = new LaunchMarkerStore(launches);
        LaunchMarker marker = store.read(prepared.launchId()).orElseThrow();

        assertThat(marker.phase()).isEqualTo(LaunchPhase.PREPARED);
        assertThat(marker.owner()).isEqualTo(PROBE);
        assertThat(marker.profile()).isEqualTo(PROBE.profile());
        assertThat(marker.executable()).isEqualTo("/bin/sh");
        assertThat(marker.argvDigest()).isNotBlank();
        assertThat(marker.pid()).isEmpty();
        assertThat(store.gateReleased(prepared.launchId())).isFalse();
        assertThat(intents.find(prepared.launchId()).orElseThrow().state())
                .isEqualTo(LaunchIntent.State.PREPARED);

        prepared.abandon();

        assertThat(store.read(prepared.launchId())).isEmpty();
        assertThat(intents.find(prepared.launchId())).isEmpty();
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
        // The intent must be committed before the launcher exists and advanced only after the gate.
        assertThat(intents.transitions()).containsExactly("prepared", "attempting", "running", "terminal");
        assertThat(intents.verifiedStopped(process.launchId())).contains(true);

        supervisor.acknowledge(process.launchId());

        assertThat(store.read(process.launchId())).isEmpty();
        assertThat(intents.find(process.launchId())).isEmpty();
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
        assertThat(intents.find(prepared.launchId())).isEmpty();
        assertThat(store.gateReleased(prepared.launchId())).isFalse();
        assertThat(supervisor.recoverOwnedProcesses()).isEmpty();
        Thread.sleep(500);
        assertThat(sentinel).doesNotExist();
    }

    @Test
    void rejectsAnExecutableThatCannotBeResolved()
    {
        ProcessSpec spec = new ProcessSpec(
                PROBE,
                ImmutableList.of("bytequay-missing-binary"),
                worktree,
                ImmutableMap.of("PATH", "/usr/bin:/bin"),
                64,
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
        return new ProcessSpec(
                PROBE,
                ImmutableList.of("/bin/sh", "-c", script),
                worktree,
                ImmutableMap.of("PATH", "/usr/bin:/bin"),
                1024,
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
