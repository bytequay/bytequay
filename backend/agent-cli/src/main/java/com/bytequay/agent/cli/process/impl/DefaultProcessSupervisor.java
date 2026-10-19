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

import com.bytequay.agent.cli.process.LaunchIntentRecorder;
import com.bytequay.agent.cli.process.LaunchPhase;
import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessIdentity;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;
import com.google.common.primitives.Longs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Default JDK process implementation used by the Codex and Claude CLI lanes.
 *
 * <p>Every payload is spawned behind a gated launcher so a crash between the durable launch intent
 * and the operating-system process can never leave an unrecorded writer running. The launcher makes
 * the payload its own session leader for bounded cleanup across job-control process groups.
 * Observations retain known identities, including children seen leaving that session. This is
 * lifecycle ownership, not containment of code deliberately creating an unobserved new session.
 */
public final class DefaultProcessSupervisor
        implements ProcessSupervisor
{
    private static final Logger log = LoggerFactory.getLogger(DefaultProcessSupervisor.class);

    /** Gated launcher script; see {@code launcher.sh} for what it does and why. */
    private static final String LAUNCHER_SCRIPT = readLauncherScript();
    /** Bash supplies the gated read timeout without launching polling helpers. */
    private static final String LAUNCHER_SHELL = "/bin/bash";
    /** Exit status the launcher script uses when the gate closed and the payload never ran. */
    private static final int GATE_CLOSED_EXIT_CODE = 70;
    private static final Duration IDENTITY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LAUNCHER_EXIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TREE_VERIFICATION_TIMEOUT = Duration.ofSeconds(2);
    private static final Optional<String> BOOT_SESSION_ID = readBootSessionId();

    private final LaunchMarkerStore store;
    private final LaunchIntentRecorder intents;
    private final Optional<String> bootSessionId;
    private final LongFunction<Optional<List<Long>>> sessionMembers;
    private final String backendId = UUID.randomUUID().toString();

    public DefaultProcessSupervisor(Path launchDirectory, LaunchIntentRecorder intents)
    {
        this(launchDirectory, intents, BOOT_SESSION_ID);
    }

    DefaultProcessSupervisor(Path launchDirectory, LaunchIntentRecorder intents, Optional<String> bootSessionId)
    {
        this(launchDirectory, intents, bootSessionId, ProcessSessions::members);
    }

    DefaultProcessSupervisor(Path launchDirectory, LaunchIntentRecorder intents, Optional<String> bootSessionId, LongFunction<Optional<List<Long>>> sessionMembers)
    {
        this.store = new LaunchMarkerStore(launchDirectory);
        this.intents = requireNonNull(intents, "intents is null");
        this.bootSessionId = requireNonNull(bootSessionId, "bootSessionId is null").flatMap(DefaultProcessSupervisor::validBootSessionId);
        this.sessionMembers = requireNonNull(sessionMembers, "sessionMembers is null");
    }

    @Override
    public PreparedProcess prepare(ProcessSpec spec)
    {
        requireNonNull(spec, "spec is null");
        String executable = resolveExecutable(spec);
        LaunchMarker marker = LaunchMarker.prepared(UUID.randomUUID().toString(), backendId, spec.owner(), executable, argvDigest(spec.argv()));
        if (bootSessionId.isPresent()) {
            marker = marker.withRecoveryBootSessionId(bootSessionId.orElseThrow());
        }
        store.write(marker);
        intents.prepared(marker.launchId(), spec.owner(), executable, marker.argvDigest());
        return new GatedLaunch(spec, executable, marker);
    }

    @Override
    public List<OwnedProcessRecovery> recoverOwnedProcesses()
    {
        Map<String, Boolean> committed = intents.committedLaunches();
        Set<String> launches = ImmutableSet.<String>builder()
                .addAll(store.list())
                .addAll(committed.keySet())
                .build();
        return launches.stream()
                .sorted()
                .map(launchId -> recover(launchId, Optional.ofNullable(committed.get(launchId)), false))
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow)
                .collect(toImmutableList());
    }

    @Override
    public OwnedProcessRecovery recoverOwnedProcess(String launchId)
    {
        requireNonNull(launchId, "launchId is null");
        Optional<Boolean> attempted = Optional.ofNullable(intents.committedLaunches().get(launchId));
        return recover(launchId, attempted, true)
                .orElseGet(() -> new OwnedProcessRecovery(
                        launchId,
                        Optional.empty(),
                        Optional.empty(),
                        OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                        "Launch belongs to the current backend instance"));
    }

    @Override
    public void acknowledge(String launchId)
    {
        requireNonNull(launchId, "launchId is null");
        intents.forget(launchId);
        store.delete(launchId);
    }

    private Optional<OwnedProcessRecovery> recover(String launchId, Optional<Boolean> attempted, boolean retryUnverifiable)
    {
        Optional<LaunchMarker> found = store.read(launchId);
        if (found.isEmpty()) {
            // A committed launch that never reached the spawn is provably unlaunched. Any attempted
            // launch without its marker is an unexplained loss and must block rather than be
            // assumed harmless.
            boolean unlaunched = attempted.filter(value -> !value).isPresent();
            return Optional.of(new OwnedProcessRecovery(
                    launchId,
                    Optional.empty(),
                    Optional.empty(),
                    unlaunched
                            ? OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED
                            : OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                    unlaunched
                            ? "Committed launch was never attempted and has no marker"
                            : "Launch marker is missing or undecodable"));
        }
        LaunchMarker marker = found.orElseThrow();
        if (!marker.launchId().equals(launchId)) {
            return Optional.of(new OwnedProcessRecovery(
                    launchId, Optional.empty(), Optional.empty(), OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Launch marker identity does not match its filename"));
        }
        if (marker.backendId().equals(backendId)) {
            return Optional.empty();
        }
        return Optional.of(reconcile(marker, attempted, retryUnverifiable));
    }

    /**
     * Rejects a marker whose recorded process facts cannot have followed its committed intent.
     *
     * <p>The owner always commits {@code ATTEMPTING} before a launcher can exist, so a marker that
     * observed a launcher under a merely prepared or absent intent is an impossible ordering.
     */
    private static boolean impossibleOrdering(LaunchMarker marker, Optional<Boolean> attempted)
    {
        if (marker.phase() == LaunchPhase.PREPARED) {
            return false;
        }
        return !attempted.orElse(false);
    }

    private OwnedProcessRecovery reconcile(LaunchMarker marker, Optional<Boolean> attempted, boolean retryUnverifiable)
    {
        String launchId = marker.launchId();
        boolean released = store.gateReleased(launchId);
        LaunchPhase phase = marker.phase();
        if (phase == LaunchPhase.TERMINAL && verifiedTerminalReceipt(marker)) {
            return result(marker, OwnedProcessRecovery.Outcome.VERIFIED_STOPPED, "Replayed a durable terminal receipt");
        }
        if (impossibleOrdering(marker, attempted)) {
            return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Recorded process facts do not follow the committed launch intent");
        }
        if (attempted.orElse(false) && bootSessionId.isPresent()) {
            if (marker.recoveryBootSessionId().isPresent()) {
                if (!marker.recoveryBootSessionId().equals(bootSessionId)) {
                    return result(marker, OwnedProcessRecovery.Outcome.VERIFIED_STOPPED, "Recorded launch belongs to an earlier operating-system boot session");
                }
            }
            else {
                try {
                    marker = marker.withRecoveryBootSessionId(bootSessionId.orElseThrow());
                    store.write(marker);
                }
                catch (AgentUnavailableException failure) {
                    return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Unable to record the recovery boot session");
                }
            }
        }
        if (phase == LaunchPhase.TERMINAL) {
            if (retryUnverifiable || marker.workFinished()) {
                return stopRecordedTree(marker);
            }
            return unresolved(marker, "Replayed a durable terminal receipt");
        }
        if (phase == LaunchPhase.PREPARED && !released) {
            // Release requires a durable GO gate, and it was never written. STOP also prevents
            // release of an unrecorded launcher, whose read ends on the old backend's pipe EOF
            // or the gate timeout.
            store.writeGate(launchId, LaunchMarkerStore.CANCEL);
            return result(marker, OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED, "Gate was never released");
        }
        if (phase == LaunchPhase.GATED && !released) {
            return closeGate(marker);
        }
        return stopRecordedTree(marker);
    }

    private static boolean verifiedTerminalReceipt(LaunchMarker marker)
    {
        return marker.format() == LaunchMarker.CURRENT_FORMAT
                && marker.descendants().isPresent()
                && (!marker.descendantTrackingIncomplete() || marker.workFinished())
                && marker.treeTermination().orElse(ProcessExit.TreeTermination.UNVERIFIABLE) == ProcessExit.TreeTermination.VERIFIED_STOPPED;
    }

    private OwnedProcessRecovery unresolved(LaunchMarker marker, String detail)
    {
        if (marker.descendantTrackingIncomplete() && !marker.workFinished()
                && bootSessionId.isPresent() && marker.recoveryBootSessionId().equals(bootSessionId)) {
            detail += ". Recovery is recorded for this boot. Restart your Mac, then reopen ByteQuay to finish recovery. Your task and worktree are kept";
        }
        return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, detail);
    }

    private static Optional<String> readBootSessionId()
    {
        return ShortCommand.run(ImmutableList.of("/usr/sbin/sysctl", "-n", "kern.bootsessionuuid"), Duration.ofSeconds(2))
                .map(String::strip).flatMap(DefaultProcessSupervisor::validBootSessionId);
    }

    private static Optional<String> validBootSessionId(String value)
    {
        try {
            String canonical = UUID.fromString(value).toString();
            return canonical.equalsIgnoreCase(value) ? Optional.of(canonical) : Optional.empty();
        }
        catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private OwnedProcessRecovery closeGate(LaunchMarker marker)
    {
        Optional<ProcessIdentity> identity = marker.identity();
        if (identity.isEmpty()) {
            return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Gated marker has no usable identity");
        }
        store.writeGate(marker.launchId(), LaunchMarkerStore.CANCEL);
        ProcessIdentity gated = identity.orElseThrow();
        switch (gated.liveness()) {
            case GONE -> {
                return result(marker, OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED, "Gated launcher already exited");
            }
            case UNKNOWN -> {
                return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Gated launcher exists but its start token cannot be read");
            }
            case MATCHES -> ProcessHandle.of(gated.pid()).ifPresent(ProcessHandle::destroy);
        }
        if (await(() -> gated.liveness() == ProcessIdentity.Liveness.GONE, LAUNCHER_EXIT_TIMEOUT, 20)) {
            return result(marker, OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED, "Gated launcher was stopped");
        }
        return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Gated launcher did not stop");
    }

    private OwnedProcessRecovery stopRecordedTree(LaunchMarker marker)
    {
        Optional<ProcessIdentity> identity = marker.identity();
        Optional<Long> group = marker.processSession();
        Optional<List<ProcessIdentity>> descendants = marker.descendants();
        if (identity.isEmpty() || group.isEmpty()) {
            return unresolved(marker, "Released marker has no process receipt");
        }
        ProcessIdentity leader = identity.orElseThrow();
        ProcessIdentity.Liveness leaderLiveness = leader.liveness();
        if (leaderLiveness == ProcessIdentity.Liveness.UNKNOWN) {
            return unresolved(marker, "Recorded PID exists but its start token cannot be read");
        }
        boolean liveLeader = leaderLiveness == ProcessIdentity.Liveness.MATCHES;
        if (liveLeader) {
            ProcessHandle handle = ProcessHandle.of(leader.pid()).orElseThrow();
            if (!matchesRecordedExecutable(marker, handle.info())) {
                return result(marker, OwnedProcessRecovery.Outcome.UNVERIFIABLE, "Recorded PID runs an unrecognized executable");
            }
            if (group.orElseThrow() != leader.pid() || ProcessSessions.sessionOf(leader.pid()).orElse(-1) != leader.pid()) {
                return unresolved(marker, "Recorded PID no longer leads its owned process session");
            }
        }
        ProcessExit.TreeTermination descendantTermination = descendants
                .map(identities -> terminateIdentities(identities, Duration.ofSeconds(1)))
                .orElse(ProcessExit.TreeTermination.UNVERIFIABLE);
        ProcessExit.TreeTermination groupTermination = liveLeader ? terminateSession(group.orElseThrow(), Duration.ofSeconds(1)) : verifySessionEmpty(group.orElseThrow());
        boolean verified = groupTermination == ProcessExit.TreeTermination.VERIFIED_STOPPED
                        && descendantTermination == ProcessExit.TreeTermination.VERIFIED_STOPPED
                        && (!marker.descendantTrackingIncomplete() || marker.workFinished());
        String detail = "Reconciled the recorded process session and descendants";
        return verified ? result(marker, OwnedProcessRecovery.Outcome.VERIFIED_STOPPED, detail) : unresolved(marker, detail);
    }

    private static boolean matchesRecordedExecutable(LaunchMarker marker, ProcessHandle.Info info)
    {
        if (info.command().filter(marker.executable()::equals).isPresent()) {
            return true;
        }
        // An interpreted provider CLI reports its interpreter as the command, so the resolved
        // executable path is only visible in the full command line.
        if (info.commandLine().filter(line -> line.contains(marker.executable())).isPresent()) {
            return true;
        }
        // Until the gate is released the recorded PID is still the gated launcher shell.
        return info.command()
                .filter(observed -> marker.launcherCommand().filter(observed::equals).isPresent())
                .isPresent();
    }

    private static OwnedProcessRecovery result(LaunchMarker marker, OwnedProcessRecovery.Outcome outcome, String detail)
    {
        return new OwnedProcessRecovery(marker.launchId(), Optional.of(marker.owner()), Optional.of(marker.phase()), outcome, detail);
    }

    /**
     * Terminates every live member of the owned session, including reparented process groups.
     *
     * <p>A session identifier is not reused while it still has members. A failed scan is
     * unverifiable. Descendants observed in another session are terminated by their start tokens.
     */
    private ProcessExit.TreeTermination terminateSession(long processSession, Duration grace)
    {
        Optional<List<Long>> initial = sessionMembers.apply(processSession);
        if (initial.isEmpty()) {
            return ProcessExit.TreeTermination.UNVERIFIABLE;
        }
        initial.orElseThrow().forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy));
        if (!await(() -> sessionMembers.apply(processSession).filter(List::isEmpty).isPresent(), grace, 50)) {
            // A member spawned during the grace period is still owned work, so the forced pass
            // uses current membership rather than the first snapshot.
            sessionMembers.apply(processSession)
                    .ifPresent(current -> current.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly)));
        }
        return await(
                () -> sessionMembers.apply(processSession).filter(List::isEmpty).isPresent(),
                TREE_VERIFICATION_TIMEOUT,
                50) ? ProcessExit.TreeTermination.VERIFIED_STOPPED : ProcessExit.TreeTermination.UNVERIFIABLE;
    }

    private ProcessExit.TreeTermination verifySessionEmpty(long processSession)
    {
        return sessionMembers.apply(processSession).filter(List::isEmpty).isPresent() ? ProcessExit.TreeTermination.VERIFIED_STOPPED : ProcessExit.TreeTermination.UNVERIFIABLE;
    }

    private static ProcessExit.TreeTermination terminateIdentities(List<ProcessIdentity> identities, Duration grace)
    {
        identities.stream()
                .filter(identity -> identity.liveness() == ProcessIdentity.Liveness.MATCHES)
                .map(ProcessIdentity::pid)
                .map(ProcessHandle::of)
                .flatMap(Optional::stream)
                .forEach(ProcessHandle::destroy);
        if (!await(() -> identities.stream().allMatch(identity -> identity.liveness() == ProcessIdentity.Liveness.GONE), grace, 50)) {
            identities.stream()
                    .filter(identity -> identity.liveness() == ProcessIdentity.Liveness.MATCHES)
                    .map(ProcessIdentity::pid)
                    .map(ProcessHandle::of)
                    .flatMap(Optional::stream)
                    .forEach(ProcessHandle::destroyForcibly);
        }
        return await(
                () -> identities.stream().allMatch(identity -> identity.liveness() == ProcessIdentity.Liveness.GONE),
                TREE_VERIFICATION_TIMEOUT,
                50) ? ProcessExit.TreeTermination.VERIFIED_STOPPED : ProcessExit.TreeTermination.UNVERIFIABLE;
    }

    private static boolean await(BooleanSupplier condition, Duration timeout, long pollMillis)
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline || !sleep(pollMillis)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sleep(long millis)
    {
        try {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String readLauncherScript()
    {
        try {
            return Resources.toString(Resources.getResource(DefaultProcessSupervisor.class, "launcher.sh"), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException("Unable to read the process launcher script", e);
        }
    }

    private static String resolveExecutable(ProcessSpec spec)
    {
        String command = spec.argv().getFirst();
        Optional<Path> resolved = command.contains("/") ? Optional.of(Path.of(command)) : searchPath(command, spec.environment().get("PATH"));
        return resolved
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isExecutable)
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .orElseThrow(() -> new AgentUnavailableException("Unable to start process: " + command, new NoSuchFileException(command)));
    }

    private static Optional<Path> searchPath(String command, String path)
    {
        if (path == null) {
            return Optional.empty();
        }
        return Arrays.stream(path.split(":"))
                .filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).resolve(command))
                .filter(Files::isExecutable)
                .findFirst();
    }

    private static String argvDigest(List<String> argv)
    {
        return Hashing.sha256().hashString(String.join("\n", argv), StandardCharsets.UTF_8).toString();
    }

    private static Optional<Long> readIdentity(Path identity)
    {
        try {
            return Optional.ofNullable(Longs.tryParse(Files.readString(identity, StandardCharsets.UTF_8).trim()));
        }
        catch (IOException e) {
            return Optional.empty();
        }
    }

    private final class GatedLaunch
            implements PreparedProcess
    {
        private final ProcessSpec spec;
        private final String executable;
        private final LaunchMarker prepared;
        private final AtomicBoolean used = new AtomicBoolean();

        private GatedLaunch(ProcessSpec spec, String executable, LaunchMarker prepared)
        {
            this.spec = spec;
            this.executable = executable;
            this.prepared = prepared;
        }

        @Override
        public String launchId()
        {
            return prepared.launchId();
        }

        @Override
        public ManagedProcess start(ProcessOutputSink output)
        {
            requireNonNull(output, "output is null");
            checkState(used.compareAndSet(false, true), "Prepared launch was already used");
            intents.attempting(launchId());
            Process launcher = spawnLauncher();
            LaunchMarker recorded = prepared;
            boolean released = false;
            try {
                ProcessIdentity identity = awaitIdentity(launcher);
                ProcessHandle.Info info = ProcessHandle.of(identity.pid())
                        .orElseThrow(() -> new AgentUnavailableException("Gated launcher exited before it could be recorded"))
                        .info();
                long group = ProcessSessions.sessionOf(identity.pid())
                        .orElseThrow(() -> new AgentUnavailableException("Unable to read the launcher process session"));
                checkState(group == identity.pid(), "Gated launcher is not its own session leader");
                recorded = prepared.gated(identity, info.command().orElse(LAUNCHER_SHELL), group);
                store.write(recorded);
                recorded = recorded.toBuilder().phase(LaunchPhase.RELEASE_PENDING).build();
                store.write(recorded);
                released = true;
                store.writeGate(launchId(), LaunchMarkerStore.RELEASE);
                launcher.getOutputStream().write((LaunchMarkerStore.RELEASE + "\n").getBytes(StandardCharsets.UTF_8));
                launcher.getOutputStream().flush();
                recorded = recorded.toBuilder().phase(LaunchPhase.RUNNING).build();
                store.write(recorded);
                return new DefaultManagedProcess(recorded, launcher, identity.pid(), group, spec, output);
            }
            catch (IOException | RuntimeException failure) {
                abort(launcher, recorded, released);
                throw failure instanceof AgentUnavailableException unavailable ? unavailable : new AgentUnavailableException("Unable to start process: " + executable, failure);
            }
        }

        private void abort(Process launcher, LaunchMarker recorded, boolean released)
        {
            if (released) {
                // The gate may already have executed the payload, so the launch keeps a durable
                // terminal receipt instead of being deleted as known unsent.
                ProcessExit.TreeTermination termination = recorded.processSession()
                        .map(group -> terminateSession(group, spec.cancellationGrace()))
                        .orElse(ProcessExit.TreeTermination.UNVERIFIABLE);
                launcher.destroyForcibly();
                store.write(recorded.terminal(new ProcessExit(-1, ProcessExit.Reason.CANCELLED, termination, Duration.ZERO, false, false)));
                return;
            }
            store.writeGate(launchId(), LaunchMarkerStore.CANCEL);
            try {
                launcher.getOutputStream().close();
                if (!launcher.waitFor(LAUNCHER_EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    launcher.destroyForcibly();
                }
                else if (launcher.exitValue() != GATE_CLOSED_EXIT_CODE) {
                    log.warn("Cancelled launcher {} exited with {} instead of the gate-closed status", launchId(), launcher.exitValue());
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                launcher.destroyForcibly();
            }
            catch (IOException e) {
                launcher.destroyForcibly();
            }
            intents.forget(launchId());
            store.delete(launchId());
        }

        private Process spawnLauncher()
        {
            try {
                ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                        .add(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "--enable-native-access=ALL-UNNAMED",
                                "-cp",
                                store.sessionLauncher(launchId()).toString(),
                                ProcessSessionLauncher.class.getName(),
                                LAUNCHER_SHELL,
                                "-p",
                                "-c",
                                LAUNCHER_SCRIPT,
                                "bytequay-launch",
                                store.gateFile(launchId()).toString(),
                                store.identityFile(launchId()).toString(),
                                executable);
                spec.argv().stream().skip(1).forEach(argv::add);
                ProcessBuilder builder = new ProcessBuilder(argv.build())
                        .directory(spec.workingDirectory().toFile())
                        .redirectErrorStream(false);
                builder.environment().clear();
                builder.environment().putAll(spec.environment());
                // Bootstrap JVM options must not load code before the durable gate exists.
                ImmutableList.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
                        .forEach(builder.environment()::remove);
                return builder.start();
            }
            catch (IOException | RuntimeException e) {
                // ProcessBuilder failed before a launcher existed, so the ATTEMPTING intent is
                // known unlaunched and must not survive as an unexplained recovery claim.
                intents.forget(launchId());
                store.delete(launchId());
                throw new AgentUnavailableException("Unable to start process: " + executable, e);
            }
        }

        private ProcessIdentity awaitIdentity(Process launcher)
        {
            Path identity = store.identityFile(launchId());
            long deadline = System.nanoTime() + IDENTITY_TIMEOUT.toNanos();
            while (true) {
                Optional<Long> reported = readIdentity(identity);
                if (reported.isPresent()) {
                    long pid = reported.orElseThrow();
                    Instant startToken = ProcessHandle.of(pid)
                            .flatMap(handle -> handle.info().startInstant())
                            .orElseThrow(() -> new AgentUnavailableException("Gated launcher exited before its start token could be read"));
                    return new ProcessIdentity(pid, startToken);
                }
                if (!launcher.isAlive()) {
                    throw new AgentUnavailableException("Gated launcher exited with code " + launcher.exitValue() + " before reporting its identity");
                }
                if (System.nanoTime() >= deadline || !sleep(5)) {
                    throw new AgentUnavailableException("Gated launcher did not report its identity");
                }
            }
        }
    }

    private final class DefaultManagedProcess
            implements ManagedProcess
    {
        private static final int BUFFER_BYTES = 8 * 1024;
        private static final int OUTPUT_QUEUE_CAPACITY = 256;

        private volatile LaunchMarker marker;
        private final Process launcher;
        private final long payloadPid;
        private final long processSession;
        private final ProcessSpec spec;
        private final ProcessOutputSink output;
        private final Instant startedAt = Instant.now();
        private final Object inputLock = new Object();
        private final Object outputLock = new Object();
        private final Object terminationLock = new Object();
        private final Object markerLock = new Object();
        private final ExecutorService inputExecutor;
        private final BlockingQueue<OutputChunk> outputQueue = new LinkedBlockingQueue<>(OUTPUT_QUEUE_CAPACITY);
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final CompletableFuture<Void> stdoutDone = new CompletableFuture<>();
        private final CompletableFuture<Void> stderrDone = new CompletableFuture<>();
        private final AtomicInteger stdoutBytes = new AtomicInteger();
        private final AtomicInteger stderrBytes = new AtomicInteger();
        private final AtomicBoolean stdoutTruncated = new AtomicBoolean();
        private final AtomicBoolean stderrTruncated = new AtomicBoolean();
        private final AtomicBoolean stdoutTruncationReported = new AtomicBoolean();
        private final AtomicBoolean stderrTruncationReported = new AtomicBoolean();
        private final AtomicBoolean inputClosed = new AtomicBoolean();
        private final AtomicBoolean outputClosed = new AtomicBoolean();
        private final AtomicBoolean terminating = new AtomicBoolean();
        private final AtomicBoolean verifiedStopped = new AtomicBoolean();
        private final AtomicBoolean observingDescendants = new AtomicBoolean(true);
        private final AtomicReference<ProcessExit.Reason> reason = new AtomicReference<>(ProcessExit.Reason.EXITED);
        private final Thread outputDispatcher;
        private final Thread descendantObserver;

        private DefaultManagedProcess(LaunchMarker marker, Process launcher, long payloadPid, long processSession, ProcessSpec spec, ProcessOutputSink output)
        {
            this.marker = marker;
            this.launcher = launcher;
            this.payloadPid = payloadPid;
            this.processSession = processSession;
            this.spec = spec;
            this.output = output;
            this.inputExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("bytequay-process-stdin-" + payloadPid).factory());
            this.outputDispatcher = Thread.ofVirtual()
                    .name("bytequay-process-output-" + payloadPid)
                    .start(this::dispatchOutput);
            this.descendantObserver = Thread.ofVirtual()
                    .name("bytequay-process-descendants-" + payloadPid)
                    .start(this::observeDescendants);
            Thread.ofVirtual().name("bytequay-process-stdout-" + payloadPid).start(() -> drain(launcher.getInputStream(), ProcessStream.STDOUT, stdoutDone));
            Thread.ofVirtual().name("bytequay-process-stderr-" + payloadPid).start(() -> drain(launcher.getErrorStream(), ProcessStream.STDERR, stderrDone));
            launcher.onExit().whenComplete((ignored, failure) -> finish());
            if (!spec.maximumRuntime().isZero()) {
                CompletableFuture.delayedExecutor(spec.maximumRuntime().toMillis(), TimeUnit.MILLISECONDS)
                        .execute(() -> {
                            // The launcher can exit while a payload descendant keeps running, so the
                            // runtime bound follows the owned process session rather than the root.
                            if (!sessionEmpty()
                                    && reason.compareAndSet(ProcessExit.Reason.EXITED, ProcessExit.Reason.TIMED_OUT)) {
                                terminate();
                            }
                        });
            }
        }

        @Override
        public String launchId()
        {
            return marker.launchId();
        }

        @Override
        public long pid()
        {
            return payloadPid;
        }

        @Override
        public Optional<List<ProcessIdentity>> observedDescendants()
        {
            if (terminating.get() || marker.identity().map(ProcessIdentity::liveness).orElse(ProcessIdentity.Liveness.UNKNOWN) != ProcessIdentity.Liveness.MATCHES) {
                return Optional.empty();
            }
            sampleDescendants();
            sampleSession();
            synchronized (markerLock) {
                return marker.descendantTrackingIncomplete() ? Optional.empty() : marker.descendants();
            }
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            requireNonNull(bytes, "bytes is null");
            byte[] copy = new byte[bytes.remaining()];
            bytes.duplicate().get(copy);
            return CompletableFuture.runAsync(() -> {
                synchronized (inputLock) {
                    if (inputClosed.get() || terminating.get() || marker.workFinished() || !launcher.isAlive()) {
                        throw new AgentUnavailableException("Process input is closed");
                    }
                    try {
                        OutputStream input = launcher.getOutputStream();
                        input.write(copy);
                        input.flush();
                    }
                    catch (IOException e) {
                        throw new CompletionException(new AgentUnavailableException("Unable to write process input", e));
                    }
                }
            }, inputExecutor);
        }

        @Override
        public void closeInput()
        {
            if (!inputClosed.compareAndSet(false, true)) {
                return;
            }
            synchronized (inputLock) {
                try {
                    launcher.getOutputStream().close();
                }
                catch (IOException ignored) {
                    // Closing an already-exited process is complete for the caller.
                }
            }
        }

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            // Root exit is not quiescence: a caller may still need to stop session members that
            // outlived the launcher, so cancellation stays available after the root is reaped.
            if (verifiedStopped.get()) {
                return;
            }
            reason.compareAndSet(ProcessExit.Reason.EXITED, ProcessExit.Reason.CANCELLED);
            terminate();
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            if (verifiedStopped.get()) {
                return CompletableFuture.completedFuture(ProcessExit.TreeTermination.VERIFIED_STOPPED);
            }
            reason.compareAndSet(ProcessExit.Reason.EXITED, ProcessExit.Reason.CANCELLED);
            terminating.set(true);
            CompletableFuture<ProcessExit.TreeTermination> verification = new CompletableFuture<>();
            Thread.ofVirtual().name("bytequay-process-verify-" + payloadPid).start(() -> {
                try {
                    verification.complete(stopTreeAndVerify());
                }
                catch (RuntimeException failure) {
                    verification.completeExceptionally(failure);
                }
            });
            return verification;
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAfterWorkFinished()
        {
            // Serializing with input prevents a queued provider request from following the proof.
            // Publish it in memory only after fsync; a failed receipt must leave the strict path.
            synchronized (inputLock) {
                synchronized (markerLock) {
                    if (!marker.workFinished()) {
                        LaunchMarker finished = marker.toBuilder().workFinished(true).build();
                        store.write(finished);
                        marker = finished;
                    }
                }
            }
            return stopAndVerify();
        }

        @Override
        public void close()
        {
            cancel();
            closeInput();
        }

        private void drain(InputStream stream, ProcessStream source, CompletableFuture<Void> done)
        {
            try (stream) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    offer(source, buffer, count);
                }
                done.complete(null);
            }
            catch (IOException e) {
                done.completeExceptionally(e);
            }
        }

        private void offer(ProcessStream source, byte[] buffer, int count)
        {
            AtomicBoolean truncated = truncated(source);
            if (truncated.get()) {
                return;
            }
            if (source == ProcessStream.STDOUT && spec.stdoutCaptureLimit().isEmpty()) {
                byte[] copy = new byte[count];
                System.arraycopy(buffer, 0, copy, 0, count);
                offerProtocol(new OutputChunk(source, copy));
                return;
            }
            AtomicInteger observed = observed(source);
            int accepted = Math.min(count, limit(source) - observed.get());
            if (accepted <= 0) {
                truncated.set(true);
                return;
            }
            byte[] copy = new byte[accepted];
            System.arraycopy(buffer, 0, copy, 0, accepted);
            synchronized (outputLock) {
                if (!outputQueue.offer(new OutputChunk(source, copy))) {
                    truncated.set(true);
                    return;
                }
                observed.addAndGet(accepted);
            }
            if (accepted < count) {
                truncated.set(true);
            }
        }

        private void offerProtocol(OutputChunk chunk)
        {
            try {
                // Wait outside outputLock so diagnostics can still be drained independently.
                while (!stdoutTruncated.get() && !outputClosed.get()) {
                    if (outputQueue.offer(chunk, 100, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stdoutTruncated.set(true);
                cancel();
            }
        }

        private void dispatchOutput()
        {
            try {
                while (!drainsFinished() || !outputQueue.isEmpty() || hasUnreportedTruncation()) {
                    OutputChunk chunk = outputQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        output.onOutput(chunk.stream(), ByteBuffer.wrap(chunk.bytes()).asReadOnlyBuffer());
                    }
                    if (outputQueue.isEmpty()) {
                        reportTruncation(ProcessStream.STDOUT);
                        reportTruncation(ProcessStream.STDERR);
                    }
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stdoutTruncated.set(true);
                stderrTruncated.set(true);
                cancel();
            }
            catch (RuntimeException failure) {
                stdoutTruncated.set(true);
                stderrTruncated.set(true);
                log.warn("Process output consumer failed for launch {}: {}", launchId(), failure.getClass().getSimpleName());
                cancel();
            }
        }

        private void reportTruncation(ProcessStream stream)
        {
            AtomicBoolean truncated = truncated(stream);
            AtomicBoolean reported = reported(stream);
            if (truncated.get() && reported.compareAndSet(false, true)) {
                output.onTruncated(stream);
            }
        }

        private void observeDescendants()
        {
            long nextSessionScan = 0;
            while (observingDescendants.get()) {
                sampleDescendants();
                if (System.nanoTime() >= nextSessionScan) {
                    sampleSession();
                    nextSessionScan = System.nanoTime() + Duration.ofMillis(250).toNanos();
                }
                if (!sleep(10)) {
                    return;
                }
            }
        }

        private void sampleDescendants()
        {
            try {
                launcher.descendants().forEach(this::rememberDescendant);
            }
            catch (RuntimeException failure) {
                markDescendantTrackingIncomplete("Unable to enumerate descendants: " + failure.getClass().getSimpleName());
            }
        }

        private void sampleSession()
        {
            try {
                Optional<List<Long>> members = sessionMembers.apply(processSession);
                if (members.isEmpty()) {
                    markDescendantTrackingIncomplete("Unable to enumerate the process session");
                    return;
                }
                members.orElseThrow().stream().map(ProcessHandle::of).flatMap(Optional::stream)
                        .forEach(this::rememberDescendant);
            }
            catch (RuntimeException failure) {
                markDescendantTrackingIncomplete("Unable to enumerate the process session: " + failure.getClass().getSimpleName());
            }
        }

        private void rememberDescendant(ProcessHandle descendant)
        {
            if (descendant.pid() == payloadPid) {
                return;
            }
            Optional<Instant> startToken = descendant.info().startInstant();
            if (startToken.isEmpty()) {
                // A child can exit between enumeration and the identity read. Its confirmed death
                // needs no later signal; an unreadable live identity still leaves an ownership gap.
                if (descendant.isAlive()) {
                    markDescendantTrackingIncomplete("Unable to read the start token for descendant " + descendant.pid());
                }
                return;
            }
            ProcessIdentity identity = new ProcessIdentity(descendant.pid(), startToken.orElseThrow());
            synchronized (markerLock) {
                if (identity.startToken().toString().equals(marker.descendantStartTokens().get(identity.pid()))) {
                    return;
                }
                marker = marker.withDescendant(identity);
                store.write(marker);
            }
        }

        private void markDescendantTrackingIncomplete(String detail)
        {
            synchronized (markerLock) {
                if (marker.descendantTrackingIncomplete()) {
                    return;
                }
                log.warn("Process launch {} for {}/{} has incomplete descendant tracking: {}", marker.launchId(), spec.owner().kind(), spec.owner().id(), detail);
                marker = marker.toBuilder().descendantTrackingIncomplete(true).build();
                store.write(marker);
            }
        }

        private void stopObservingDescendants()
        {
            observingDescendants.set(false);
            try {
                descendantObserver.join();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markDescendantTrackingIncomplete("Interrupted while waiting for the descendant observer");
            }
        }

        private void finish()
        {
            stopObservingDescendants();
            sampleSession();
            CompletableFuture.allOf(stdoutDone, stderrDone).whenComplete((ignored, failure) -> {
                try {
                    outputDispatcher.join();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                closeInput();
                // Queued writes must run their closed-input guard so their completion stages settle.
                inputExecutor.shutdown();
                exit.complete(record(new ProcessExit(
                        launcher.exitValue(),
                        reason.get(),
                        verifyTreeStopped(),
                        Duration.between(startedAt, Instant.now()),
                        stdoutTruncated.get(),
                        stderrTruncated.get())));
            });
        }

        /**
         * Fsyncs the terminal receipt so an owner that crashes before acknowledging replays it.
         *
         * <p>A receipt that cannot be written must not hide the observed result from the caller.
         */
        private ProcessExit record(ProcessExit result)
        {
            synchronized (markerLock) {
                try {
                    marker = marker.terminal(result);
                    store.write(marker);
                }
                catch (RuntimeException ignored) {
                    // The in-process result still stands; recovery treats a missing marker as
                    // unverifiable, which is the conservative outcome.
                }
            }
            return result;
        }

        private ProcessExit.TreeTermination verifyTreeStopped()
        {
            if (verifiedStopped.get()) {
                return ProcessExit.TreeTermination.VERIFIED_STOPPED;
            }
            if (!await(() -> sessionMembers.apply(processSession).filter(List::isEmpty).isPresent(), TREE_VERIFICATION_TIMEOUT, 50)) {
                return ProcessExit.TreeTermination.UNVERIFIABLE;
            }
            Optional<List<ProcessIdentity>> descendants = marker.descendants();
            if ((!marker.workFinished() && marker.descendantTrackingIncomplete())
                    || descendants.isEmpty()
                    || !await(() -> descendants.orElseThrow().stream().allMatch(identity -> identity.liveness() == ProcessIdentity.Liveness.GONE), TREE_VERIFICATION_TIMEOUT, 50)) {
                return ProcessExit.TreeTermination.UNVERIFIABLE;
            }
            verifiedStopped.set(true);
            return ProcessExit.TreeTermination.VERIFIED_STOPPED;
        }

        private boolean sessionEmpty()
        {
            return sessionMembers.apply(processSession).filter(List::isEmpty).isPresent();
        }

        /** Requests idempotent tree termination without blocking the caller for the grace period. */
        private void terminate()
        {
            if (!terminating.compareAndSet(false, true)) {
                return;
            }
            Thread.ofVirtual().name("bytequay-process-stop-" + payloadPid).start(this::stopTreeAndVerify);
        }

        private ProcessExit.TreeTermination stopTreeAndVerify()
        {
            synchronized (terminationLock) {
                if (verifiedStopped.get()) {
                    return ProcessExit.TreeTermination.VERIFIED_STOPPED;
                }
                // A blocked stdin write owns inputLock; stop the readers before closing their pipe.
                sampleSession();
                terminateSession(processSession, spec.cancellationGrace());
                launcher.descendants().forEach(ProcessHandle::destroyForcibly);
                stopObservingDescendants();
                marker.descendants().ifPresent(identities -> terminateIdentities(identities, spec.cancellationGrace()));
                launcher.destroyForcibly();
                closeInput();
                closeOutputStreams();
                ProcessExit.TreeTermination termination = verifyTreeStopped();
                ProcessExit terminal = exit.getNow(null);
                if (termination == ProcessExit.TreeTermination.VERIFIED_STOPPED
                        && terminal != null
                        && terminal.treeTermination() != ProcessExit.TreeTermination.VERIFIED_STOPPED) {
                    record(new ProcessExit(terminal.exitCode(), terminal.reason(), termination, terminal.duration(), terminal.stdoutTruncated(), terminal.stderrTruncated()));
                }
                return termination;
            }
        }

        private void closeOutputStreams()
        {
            outputClosed.set(true);
            try {
                launcher.getInputStream().close();
            }
            catch (IOException ignored) {
                // The process already closed stdout.
            }
            try {
                launcher.getErrorStream().close();
            }
            catch (IOException ignored) {
                // The process already closed stderr.
            }
        }

        private boolean drainsFinished()
        {
            return stdoutDone.isDone() && stderrDone.isDone();
        }

        private boolean hasUnreportedTruncation()
        {
            return stdoutTruncated.get() && !stdoutTruncationReported.get()
                    || stderrTruncated.get() && !stderrTruncationReported.get();
        }

        private int limit(ProcessStream stream)
        {
            return stream == ProcessStream.STDOUT ? spec.stdoutCaptureLimit().orElseThrow() : spec.maximumStderrBytes();
        }

        private AtomicInteger observed(ProcessStream stream)
        {
            return stream == ProcessStream.STDOUT ? stdoutBytes : stderrBytes;
        }

        private AtomicBoolean truncated(ProcessStream stream)
        {
            return stream == ProcessStream.STDOUT ? stdoutTruncated : stderrTruncated;
        }

        private AtomicBoolean reported(ProcessStream stream)
        {
            return stream == ProcessStream.STDOUT ? stdoutTruncationReported : stderrTruncationReported;
        }
    }

    private record OutputChunk(ProcessStream stream, byte[] bytes)
    {
        private OutputChunk
        {
            requireNonNull(stream, "stream is null");
            requireNonNull(bytes, "bytes is null");
        }
    }
}
