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

import com.bytequay.agent.cli.process.ExecutionProfile;
import com.bytequay.agent.cli.process.LaunchPhase;
import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the fail-closed classification of every launch marker an earlier backend can leave behind.
 *
 * <p>A second supervisor over the same launch directory has a different backend identity, which is
 * exactly what a restart looks like to process recovery.
 */
class TestOwnedProcessRecovery
{
    /**
     * Bash, resolved to the path the operating system reports back for a running process.
     * {@code /bin/sh} is bash on macOS but dash on Debian and Ubuntu, so it cannot stand in for a
     * provider CLI here: recovery matches a recorded executable against the reported one, and
     * neither system reports the symlink a process was invoked through.
     */
    private static final String SHELL = resolveShell();
    private static final ProcessOwner OWNER = new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "test-app-server");
    private static final long ABSENT_PROCESS_GROUP = 0x7FFF_0000L;

    @TempDir
    private Path worktree;

    @TempDir
    private Path launches;

    private final List<Process> spawned = new ArrayList<>();
    private final InMemoryLaunchIntentRecorder intents = new InMemoryLaunchIntentRecorder();

    @AfterEach
    void stopSpawnedProcesses()
    {
        spawned.forEach(Process::destroyForcibly);
    }

    @Test
    void restartStopsARunningProcessTreeAndIgnoresItsOwnLaunches()
            throws Exception
    {
        DefaultProcessSupervisor first = new DefaultProcessSupervisor(launches, intents);
        StringBuilder stdout = new StringBuilder();
        CountDownLatch reported = new CountDownLatch(1);
        ManagedProcess process = first
                .prepare(spec("sleep 60 & printf '%s\\n' \"$!\"; wait"))
                .start((stream, bytes) -> {
                    stdout.append(StandardCharsets.UTF_8.decode(bytes));
                    if (stdout.indexOf("\n") >= 0) {
                        reported.countDown();
                    }
                });
        assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue();
        long descendantPid = Long.parseLong(stdout.toString().trim());

        assertThat(first.recoverOwnedProcesses()).isEmpty();

        List<OwnedProcessRecovery> recovered =
                new DefaultProcessSupervisor(launches, intents).recoverOwnedProcesses();

        assertThat(recovered).singleElement().satisfies(result -> {
            assertThat(result.launchId()).isEqualTo(process.launchId());
            assertThat(result.owner()).contains(OWNER);
            assertThat(result.phase()).contains(LaunchPhase.RUNNING);
            assertThat(result.outcome()).isEqualTo(OwnedProcessRecovery.Outcome.VERIFIED_STOPPED);
        });
        assertThat(awaitDead(process.pid())).isTrue();
        assertThat(awaitDead(descendantPid)).isTrue();
    }

    @Test
    void reportsAPreparedLaunchAsKnownUnlaunched()
            throws Exception
    {
        write(marker(LaunchPhase.PREPARED, Optional.empty(), Optional.empty(), Optional.empty(), "/bin/sh"));

        OwnedProcessRecovery result = recover();

        assertThat(result.outcome()).isEqualTo(OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED);
        assertThat(Files.readString(launches.resolve(result.launchId() + ".gate")).trim())
                .isEqualTo(LaunchMarkerStore.CANCEL);
    }

    @Test
    void closesTheGateOfALaunchThatNeverExecutedItsPayload()
            throws Exception
    {
        Process launcher = spawn("sleep 60");
        Instant startToken = ProcessHandle.of(launcher.pid()).orElseThrow().info().startInstant().orElseThrow();
        write(marker(
                LaunchPhase.GATED,
                Optional.of(launcher.pid()),
                Optional.of(startToken),
                Optional.of(launcher.pid()),
                "/bin/sh"));

        OwnedProcessRecovery result = recover();

        assertThat(result.outcome()).isEqualTo(OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED);
        assertThat(awaitDead(launcher.pid())).isTrue();
        assertThat(Files.readString(launches.resolve(result.launchId() + ".gate")).trim())
                .isEqualTo(LaunchMarkerStore.CANCEL);
    }

    @Test
    void neverTreatsAReleasePendingLaunchAsUnsent()
            throws Exception
    {
        long payload = spawnGroupLeader("sleep 60");
        write(marker(
                LaunchPhase.RELEASE_PENDING,
                Optional.of(payload),
                Optional.of(ProcessHandle.of(payload).orElseThrow().info().startInstant().orElseThrow()),
                Optional.of(payload),
                "/bin/sleep"));

        OwnedProcessRecovery result = recover();

        assertThat(result.outcome()).isEqualTo(OwnedProcessRecovery.Outcome.VERIFIED_STOPPED);
        assertThat(awaitDead(payload)).isTrue();
    }

    @Test
    void recognizesAnInterpretedProviderCliThroughItsCommandLine()
            throws Exception
    {
        Path cli = Files.writeString(worktree.resolve("fake-cli"), "#!/bin/sh\nsleep 60\n");
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
        long payload = spawnGroupLeader("'" + cli + "'");
        LaunchMarker marker = marker(
                LaunchPhase.RUNNING,
                Optional.of(payload),
                Optional.of(ProcessHandle.of(payload).orElseThrow().info().startInstant().orElseThrow()),
                Optional.of(payload),
                cli.toString());
        write(new LaunchMarker(
                marker.format(),
                marker.launchId(),
                marker.backendId(),
                marker.ownerKind(),
                marker.ownerId(),
                marker.profile(),
                marker.executable(),
                marker.argvDigest(),
                marker.phase(),
                marker.pid(),
                marker.startToken(),
                Optional.of("/bin/never-the-launcher"),
                marker.processGroup(),
                marker.exitCode(),
                marker.exitReason(),
                marker.treeTermination()));

        assertThat(recover().outcome()).isEqualTo(OwnedProcessRecovery.Outcome.VERIFIED_STOPPED);
        assertThat(awaitDead(payload)).isTrue();
    }

    @Test
    void acceptsARecycledProcessGroupWhoseRecordedLeaderIsGone()
    {
        write(marker(
                LaunchPhase.RUNNING,
                Optional.of(ProcessHandle.current().pid()),
                Optional.of(Instant.EPOCH),
                Optional.of(ABSENT_PROCESS_GROUP),
                "/bin/sleep"));

        assertThat(recover().outcome()).isEqualTo(OwnedProcessRecovery.Outcome.VERIFIED_STOPPED);
    }

    @Test
    void refusesToSignalARecordedPidRunningAnUnrecognizedExecutable()
    {
        ProcessHandle self = ProcessHandle.current();
        write(marker(
                LaunchPhase.RUNNING,
                Optional.of(self.pid()),
                Optional.of(self.info().startInstant().orElseThrow()),
                Optional.of(ABSENT_PROCESS_GROUP),
                "/bin/sleep"));

        assertThat(recover().outcome()).isEqualTo(OwnedProcessRecovery.Outcome.UNVERIFIABLE);
        assertThat(self.isAlive()).isTrue();
    }

    @Test
    void failsClosedForALiveProcessWhoseExecutableCannotBeRead()
    {
        // PID 1 reports a start token but no command, which is exactly the shape of an inspection
        // failure. Recovery must decline rather than read it as death.
        ProcessHandle init = ProcessHandle.of(1).orElseThrow();
        write(marker(
                LaunchPhase.RUNNING,
                Optional.of(1L),
                Optional.of(init.info().startInstant().orElseThrow()),
                Optional.of(ABSENT_PROCESS_GROUP),
                "/bin/sleep"));

        assertThat(recover().outcome()).isEqualTo(OwnedProcessRecovery.Outcome.UNVERIFIABLE);
        assertThat(init.isAlive()).isTrue();
    }

    @Test
    void failsClosedForACommittedIntentWhoseMarkerIsMissing()
    {
        intents.commit("attempted", true);

        assertThat(recover()).satisfies(result -> {
            assertThat(result.launchId()).isEqualTo("attempted");
            assertThat(result.outcome()).isEqualTo(OwnedProcessRecovery.Outcome.UNVERIFIABLE);
            assertThat(result.detail()).contains("missing");
        });
    }

    @Test
    void acceptsACommittedIntentThatWasNeverAttempted()
    {
        intents.commit("never-started", false);

        assertThat(recover().outcome()).isEqualTo(OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED);
    }

    @Test
    void failsClosedWhenProcessFactsPrecedeTheCommittedIntent()
    {
        // The owner always commits ATTEMPTING before a launcher can exist, so a running marker
        // under a merely prepared intent is an ordering that cannot have happened.
        new LaunchMarkerStore(launches).write(marker(
                LaunchPhase.RUNNING,
                Optional.of(1L),
                Optional.of(Instant.EPOCH),
                Optional.of(ABSENT_PROCESS_GROUP),
                "/bin/sleep"));
        intents.commit("launch-1", false);

        assertThat(recover().outcome()).isEqualTo(OwnedProcessRecovery.Outcome.UNVERIFIABLE);
    }

    @Test
    void failsClosedForAnOrphanMarkerWithNoCommittedIntent()
    {
        new LaunchMarkerStore(launches).write(marker(
                LaunchPhase.RUNNING,
                Optional.of(1L),
                Optional.of(Instant.EPOCH),
                Optional.of(ABSENT_PROCESS_GROUP),
                "/bin/sleep"));

        assertThat(recover().outcome()).isEqualTo(OwnedProcessRecovery.Outcome.UNVERIFIABLE);
    }

    @Test
    void failsClosedForACorruptMarker()
            throws Exception
    {
        Files.writeString(launches.resolve("broken.json"), "{\"launchId\":");

        assertThat(recover()).satisfies(result -> {
            assertThat(result.launchId()).isEqualTo("broken");
            assertThat(result.owner()).isEmpty();
            assertThat(result.outcome()).isEqualTo(OwnedProcessRecovery.Outcome.UNVERIFIABLE);
        });
    }

    @Test
    void replaysDurableTerminalReceiptsWithoutReinterpretingThem()
    {
        LaunchMarker stopped = marker(
                LaunchPhase.RUNNING,
                Optional.of(1L),
                Optional.of(Instant.EPOCH),
                Optional.of(ABSENT_PROCESS_GROUP),
                "/bin/sleep");
        write(stopped.terminal(exit(ProcessExit.TreeTermination.VERIFIED_STOPPED)));

        assertThat(recover().outcome()).isEqualTo(OwnedProcessRecovery.Outcome.VERIFIED_STOPPED);

        write(stopped.terminal(exit(ProcessExit.TreeTermination.UNVERIFIABLE)));

        assertThat(recover().outcome()).isEqualTo(OwnedProcessRecovery.Outcome.UNVERIFIABLE);
    }

    private OwnedProcessRecovery recover()
    {
        List<OwnedProcessRecovery> results =
                new DefaultProcessSupervisor(launches, intents).recoverOwnedProcesses();
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    /** Writes a marker together with the committed intent its recorded process facts imply. */
    private void write(LaunchMarker marker)
    {
        new LaunchMarkerStore(launches).write(marker);
        intents.commit(marker.launchId(), marker.phase() != LaunchPhase.PREPARED);
    }

    private static LaunchMarker marker(
            LaunchPhase phase,
            Optional<Long> pid,
            Optional<Instant> startToken,
            Optional<Long> processGroup,
            String executable)
    {
        return new LaunchMarker(
                LaunchMarker.CURRENT_FORMAT,
                "launch-1",
                "an-earlier-backend",
                OWNER.kind(),
                OWNER.id(),
                ExecutionProfile.PROVIDER_CONTROL,
                executable,
                "digest",
                phase,
                pid,
                startToken.map(Instant::toString),
                Optional.of("/bin/sh"),
                processGroup,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static ProcessExit exit(ProcessExit.TreeTermination termination)
    {
        return new ProcessExit(0, ProcessExit.Reason.EXITED, termination, Duration.ZERO, false, false);
    }

    private ProcessSpec spec(String script)
    {
        return new ProcessSpec(
                OWNER,
                ImmutableList.of(SHELL, "-c", script),
                worktree,
                ImmutableMap.of("PATH", "/usr/bin:/bin"),
                1024,
                1024,
                Duration.ofSeconds(30),
                Duration.ofMillis(100));
    }

    private static String resolveShell()
    {
        try {
            return Path.of("/bin/bash").toRealPath().toString();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Bash, for the same reason the gated launcher uses it: {@link #spawnGroupLeader} needs
     * {@code set -m}, and dash, which is {@code /bin/sh} on Debian and Ubuntu, turns job control
     * off without a controlling terminal.
     */
    private Process spawn(String script)
            throws Exception
    {
        Process process = new ProcessBuilder(SHELL, "-c", script).start();
        spawned.add(process);
        return process;
    }

    /** Starts a process that leads its own group, the way the gated launcher does. */
    private long spawnGroupLeader(String payload)
            throws Exception
    {
        Process process = spawn("set -m; " + payload + " & printf '%s\\n' \"$!\"; wait");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return Long.parseLong(reader.readLine().trim());
        }
    }

    private static boolean awaitDead(long pid)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
