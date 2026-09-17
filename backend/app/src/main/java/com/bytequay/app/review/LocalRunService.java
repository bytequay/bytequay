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
package com.bytequay.app.review;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.app.review.ReviewCodeService.WorkingCopySource;
import com.bytequay.app.utils.MavenModules;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.LocalRun.Status;
import com.bytequay.core.review.LocalRun.TestClass;
import com.bytequay.core.review.LocalRunStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Runs one test class or method with the Maven wrapper of a review's or a development task's working copy, on the host, as a direct
 * user action. Runs belong to the task, so a review's runs are its task's runs.
 *
 * <p>The run holds the task's {@code LOCAL_RUN} worktree claim until its process tree is verified stopped. Rows are
 * written only at start, finish and stop; live output stays in memory so the single SQLite connection is never held
 * while a build streams.
 */
@Component
public class LocalRunService
        implements SmartLifecycle
{
    static final String RECOVERY_NEEDED = "The working copy needs recovery — see Review & Learning";
    static final String TASK_RECOVERY_NEEDED = "The working copy needs recovery — open the task";
    private static final String AGENT_WORKING = "The review agent is working";
    private static final Logger log = LoggerFactory.getLogger(LocalRunService.class);
    private static final Duration MAXIMUM_RUNTIME = Duration.ofMinutes(60);
    private static final Duration CANCELLATION_GRACE = Duration.ofSeconds(10);
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(1);
    // A start whose completion never arrived, as when its turn was cut short, is forgotten after this.
    private static final Duration AGENT_COMMAND_MEMORY = Duration.ofHours(24);
    private static final int MAXIMUM_STDERR_BYTES = 64 * 1024;
    private static final int LIST_LIMIT = 200;
    private static final Pattern IDENTIFIER = Pattern.compile("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+(\\p{javaJavaIdentifierStart}[\\p{javaJavaIdentifierPart}.]*)\\s*;", Pattern.MULTILINE);
    // Surefire's default includes.
    private static final Pattern TEST_SOURCE = Pattern.compile("Test[^/]*\\.java|[^/]*(?:Test|Tests|TestCase)\\.java");
    private static final Executor FINISHER = command -> Thread.ofVirtual().name("bytequay-local-run-finish").start(command);

    private final AgentReviewStore reviewStore;
    private final AgentReviewService reviews;
    private final AgentTurnService turns;
    private final LocalRunStore runs;
    private final ProcessSupervisor supervisor;
    private final LoginShellEnvironment loginShell;
    private final ManagedWorktreeService worktrees;
    private final ReviewLocks locks;
    private final Clock clock;
    private final ReviewCodeService code;
    private final Map<String, LiveRun> live = new ConcurrentHashMap<>();
    private final Map<String, AgentStart> agentStarts = new ConcurrentHashMap<>();
    private volatile boolean closing;
    private volatile boolean running;

    public LocalRunService(
            AgentReviewStore reviewStore,
            AgentReviewService reviews,
            AgentTurnService turns,
            LocalRunStore runs,
            ProcessSupervisor supervisor,
            LoginShellEnvironment loginShell,
            ManagedWorktreeService worktrees,
            ReviewCodeService code,
            ReviewLocks locks,
            Clock clock)
    {
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.loginShell = requireNonNull(loginShell, "loginShell is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.code = requireNonNull(code, "code is null");
        this.locks = requireNonNull(locks, "locks is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** A review's runs, newest first. */
    public List<LocalRun> list(String reviewId)
    {
        return runs.listByTask(requireReview(reviewId).taskId(), LIST_LIMIT);
    }

    /** A development task's runs, newest first. */
    public List<LocalRun> list(TaskCodeHost host)
    {
        return runs.listByTask(code.task(host).id().toString(), LIST_LIMIT);
    }

    public LocalRun get(String reviewId, String runId)
    {
        return run(requireReview(reviewId).taskId(), runId);
    }

    public LocalRun get(TaskCodeHost host, String runId)
    {
        return run(code.task(host).id().toString(), runId);
    }

    private LocalRun run(String taskId, String runId)
    {
        return runs.find(runId).filter(run -> run.taskId().equals(taskId)).orElseThrow(() -> new ReviewNotFoundException("Run not found: " + runId));
    }

    /**
     * Starts a user run of the test class at {@code path}, or of one of its methods.
     *
     * @throws IllegalArgumentException when the path is not a Surefire test source or the method is not an identifier
     * @throws ReviewConflictException when the review or its working copy is not idle
     * @throws LocalRunRefusedException when the working copy or the login environment cannot run it; no record exists
     */
    public LocalRun start(String reviewId, String path, Optional<String> method)
    {
        checkSelector(path, method);
        String runId = UUID.randomUUID().toString();
        Preflight preflight;
        // Update, Resume, End and follow-up turns run under this lock, and a review step releases its own claim
        // before starting the next turn, so a claim taken outside it could fail that turn.
        Lock lock = locks.lock(reviewId);
        lock.lock();
        try {
            preflight = reviewPreflight(reviewId, path);
            turns.claimLocalRun(preflight.taskId(), runId);
        }
        finally {
            lock.unlock();
        }
        return launch(runId, preflight, path, method);
    }

    /**
     * Starts a user run in a development task's working copy. A development turn and its follow-up hold the task until they finish,
     * so the atomic claim needs no other lock.
     *
     * @throws IllegalArgumentException when the path is not a Surefire test source or the method is not an identifier
     * @throws ReviewConflictException when the task or its working copy is not idle
     * @throws LocalRunRefusedException when the working copy or the login environment cannot run it; no record exists
     */
    public LocalRun start(TaskCodeHost host, String path, Optional<String> method)
    {
        checkSelector(path, method);
        String runId = UUID.randomUUID().toString();
        AgentTask task = code.task(host);
        if (task.state() != TaskState.OPEN) {
            throw new ReviewConflictException("The task has ended");
        }
        requireNotClosing();
        TaskWorktree worktree = worktree(task);
        requireTaskIdle(turns, task);
        Preflight preflight = preflight(task.id(), worktree, path);
        turns.claimLocalRun(preflight.taskId(), runId);
        return launch(runId, preflight, path, method);
    }

    private static void checkSelector(String path, Optional<String> method)
    {
        ReviewCodeService.checkPath(path);
        String fileName = Path.of(path).getFileName().toString();
        // An identifier name also keeps Surefire's pattern characters (",", "*", "!", "%regex[") out of -Dtest.
        checkArgument((path.startsWith("src/test/") || path.contains("/src/test/")) && TEST_SOURCE.matcher(fileName).matches()
                        && IDENTIFIER.matcher(fileName.substring(0, fileName.length() - ".java".length())).matches(),
                "path is not a Surefire test source: %s", path);
        method.ifPresent(name -> checkArgument(IDENTIFIER.matcher(name).matches(), "method is not a Java identifier: %s", name));
    }

    public LocalRun stop(String reviewId, String runId)
    {
        return stop(get(reviewId, runId));
    }

    public LocalRun stop(TaskCodeHost host, String runId)
    {
        return stop(get(host, runId));
    }

    private LocalRun stop(LocalRun stored)
    {
        LiveRun liveRun = live.get(stored.id());
        if (liveRun == null || liveRun.process == null) {
            return stored;
        }
        LocalRun run = liveRun.run();
        if (run.status() != Status.RUNNING && !run.treeUnverified()) {
            return run;
        }
        liveRun.stopRequested.set(true);
        return stopAndSettle(liveRun);
    }

    /**
     * Output after a character offset in the run's full stream.
     *
     * <p>A live or finished run of this backend reads its in-memory buffer, whose dropped middle is reported as skipped.
     * Any other run reads its stored, already bounded output.
     */
    public OutputChunk output(String reviewId, String runId, long after)
    {
        checkArgument(after >= 0, "after must not be negative");
        return output(get(reviewId, runId), after);
    }

    public OutputChunk output(TaskCodeHost host, String runId, long after)
    {
        checkArgument(after >= 0, "after must not be negative");
        return output(get(host, runId), after);
    }

    private OutputChunk output(LocalRun run, long after)
    {
        LiveRun liveRun = live.get(run.id());
        if (liveRun != null) {
            return liveRun.output.read(after);
        }
        String output = runs.findOutput(run.id()).orElse("");
        int from = (int) Math.min(after, output.length());
        return new OutputChunk(output.substring(from), output.length(), 0, run.status() != Status.RUNNING);
    }

    /**
     * Mirrors a Maven command the review agent ran into its task's run history when it completes. Its source facts are read when it
     * starts; a command whose start this backend did not see has none, and without a provider duration it reads no reports.
     * Nothing is caught up after a restart.
     */
    public void recordAgentCommand(String reviewId, AgentEventEnvelope envelope)
    {
        if (!(envelope.payload() instanceof AgentEventPayload.CommandActivity command) || command.background() || envelope.source().providerItemId().isEmpty()) {
            return;
        }
        Optional<MavenCommands.Maven> maven = MavenCommands.detect(command.argv());
        if (maven.isEmpty()) {
            return;
        }
        AgentTask task = reviews.task(reviewId);
        String key = task.id() + "/" + envelope.source().providerItemId().orElseThrow();
        Optional<TaskWorktree> worktree = task.taskWorktree().filter(ignored -> task.worktreeRemovedAt().isEmpty());
        if (worktree.isEmpty()) {
            return;
        }
        Path root = worktrees.ownedCheckout(worktree.orElseThrow());
        switch (command.state()) {
            case STARTED -> {
                agentStarts.values().removeIf(start -> start.occurredAt().isBefore(envelope.occurredAt().minus(AGENT_COMMAND_MEMORY)));
                agentStarts.put(key, new AgentStart(envelope.occurredAt(), code.workingCopySource(root)));
            }
            case COMPLETED, FAILED -> recordAgentFinish(task, worktree.orElseThrow(), root, envelope, command, maven.orElseThrow(), Optional.ofNullable(agentStarts.remove(key)));
            default -> {}
        }
    }

    private void recordAgentFinish(
            AgentTask task,
            TaskWorktree worktree,
            Path root,
            AgentEventEnvelope envelope,
            AgentEventPayload.CommandActivity command,
            MavenCommands.Maven maven,
            Optional<AgentStart> start)
    {
        // Codex reports a declined or interrupted command as failed with no exit code: it never ran. Claude reports no exit code at all.
        if (!task.laneId().equals(AgentLaneId.CLAUDE_CLI) && command.state() == ActivityState.FAILED && command.exitCode().isEmpty()) {
            return;
        }
        Instant endedAt = envelope.occurredAt();
        Optional<Instant> startedAt = command.durationMs().map(endedAt::minusMillis).or(() -> start.map(AgentStart::occurredAt));
        Optional<List<TestClass>> reports = startedAt.isEmpty() ? Optional.of(ImmutableList.of()) : SurefireReports.readWindow(root, startedAt.orElseThrow(), endedAt);
        List<TestClass> results = reports.orElse(ImmutableList.of());
        RunOutput output = new RunOutput();
        output.append(command.output().orElse(""));
        RunOutput.Stored stored = output.finish();
        Optional<WorkingCopySource> source = start.map(AgentStart::source);
        int ordinal = nextOrdinal(task.id());
        LocalRun run = LocalRun.mirrored(
                UUID.randomUUID().toString(),
                task.id().toString(),
                worktree.workspaceId(),
                ordinal,
                maven.testClass(),
                maven.testMethod(),
                maven.script(),
                source.map(WorkingCopySource::head),
                source.map(WorkingCopySource::localPaths).orElse(ImmutableList.of()),
                source.map(WorkingCopySource::fingerprint),
                mirroredStatus(maven, reports, command.exitCode()),
                startedAt.orElse(endedAt),
                endedAt,
                command.exitCode().map(OptionalInt::of).orElse(OptionalInt.empty()),
                results.isEmpty() ? Optional.empty() : Optional.of(SurefireReports.counts(results)),
                results,
                stored.truncated() || command.truncated(),
                // The provider cut the output itself, so how much is missing is unknown.
                command.truncated() ? OptionalLong.empty() : stored.omittedCharacters(),
                envelope.eventId().toString());
        runs.update(runs.insert(run), stored.output());
    }

    /**
     * The status table for user runs, except that only a lone Maven invocation's exit is Maven's: without one, rows that all pass
     * show no aggregate, and a run with no rows is a build failure only for a class or method selector.
     */
    static Status mirroredStatus(MavenCommands.Maven maven, Optional<List<TestClass>> reports, Optional<Integer> exitCode)
    {
        if (reports.isEmpty()) {
            return Status.RESULTS_UNAVAILABLE;
        }
        Optional<Integer> mavenExit = maven.lone() ? exitCode : Optional.empty();
        List<LocalRun.MethodStatus> methods = reports.orElseThrow().stream().flatMap(result -> result.methods().stream()).map(LocalRun.TestMethod::status).toList();
        if (methods.isEmpty()) {
            return maven.testClass().isPresent() && mavenExit.filter(code -> code != 0).isPresent() ? Status.BUILD_FAILED : Status.RESULTS_UNAVAILABLE;
        }
        if (methods.contains(LocalRun.MethodStatus.FAILED) || methods.contains(LocalRun.MethodStatus.ERROR)) {
            return Status.FAILED_ASSERTION;
        }
        if (mavenExit.isEmpty()) {
            return Status.RESULTS_UNAVAILABLE;
        }
        if (mavenExit.orElseThrow() != 0) {
            return Status.BUILD_FAILED;
        }
        return methods.contains(LocalRun.MethodStatus.PASSED) ? Status.PASSED : Status.NO_TESTS_RAN;
    }

    @Override
    public void start()
    {
        // Startup recovery has already classified the previous backend's launches and settled their claims.
        Instant now = Instant.now(clock);
        for (LocalRun run : runs.listUnsettled()) {
            try {
                LocalRun settled = run.status() == Status.RUNNING
                        ? run.finished(Status.INTERRUPTED, now, OptionalInt.empty(), Optional.empty(), ImmutableList.of(), run.outputTruncated(), run.omittedBytes())
                        : run;
                runs.update(settled.withTreeUnverified(false));
            }
            catch (RuntimeException failure) {
                log.warn("Local run {} could not be marked interrupted: {}", run.id(), failure.toString());
            }
        }
        running = true;
    }

    @Override
    public void stop()
    {
        closing = true;
        for (LiveRun liveRun : live.values()) {
            LocalRun run = liveRun.run();
            if (liveRun.process != null && (run.status() == Status.RUNNING || run.treeUnverified())) {
                liveRun.stopRequested.set(true);
                try {
                    stopAndSettle(liveRun);
                }
                catch (RuntimeException failure) {
                    log.warn("Local run {} could not be verified stopped at shutdown; startup recovery will check it: {}", run.id(), failure.toString());
                }
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning()
    {
        return running;
    }

    /** Starts before the web server accepts requests and stops after it drains them, both while the datasource is open. */
    @Override
    public int getPhase()
    {
        return SmartLifecycle.DEFAULT_PHASE - 4096;
    }

    private Preflight reviewPreflight(String reviewId, String path)
    {
        AgentReview review = requireReview(reviewId);
        if (review.state() != AgentReview.State.OPEN) {
            throw new ReviewConflictException("The review has ended");
        }
        requireNotClosing();
        AgentTask task = reviews.task(reviewId);
        TaskWorktree worktree = worktree(task);
        requireIdle(turns, reviewStore.listRuns(reviewId), review, task);
        return preflight(task.id(), worktree, path);
    }

    private void requireNotClosing()
    {
        if (closing) {
            throw new ReviewConflictException("ByteQuay is shutting down");
        }
    }

    private static TaskWorktree worktree(AgentTask task)
    {
        return task.taskWorktree().filter(ignored -> task.worktreeRemovedAt().isEmpty()).orElseThrow(() -> new ReviewConflictException("The working copy is gone"));
    }

    /** The working copy and login environment checks every host shares, once the host is open and idle. */
    private Preflight preflight(TaskId taskId, TaskWorktree worktree, String path)
    {
        Path root = worktrees.ownedCheckout(worktree);
        String testClass = testClass(root, path);
        Path wrapper = root.resolve("mvnw");
        if (!Files.isRegularFile(wrapper) || !Files.isExecutable(wrapper)) {
            throw new LocalRunRefusedException("No Maven wrapper (mvnw) in this working copy", true);
        }
        Optional<String> module = MavenModules.findModule(root, path);
        if (module.isEmpty() && !Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new LocalRunRefusedException("No Maven module for this test", false);
        }
        Map<String, String> environment = loginShell.get();
        if (!loginShell.hasJava(environment)) {
            // Installing a JDK changes the login environment, so the next attempt reads it again.
            loginShell.forget();
            throw new LocalRunRefusedException("No Java runtime in your login shell environment", true);
        }
        return new Preflight(taskId, worktree.workspaceId(), root, testClass, module, environment);
    }

    /** The availability rules the Code and Tests tab mirrors, checked under the review lock by runs and working-copy edits. */
    static void requireIdle(AgentTurnService turns, List<ReviewRun> reviewRuns, AgentReview review, AgentTask task)
    {
        requireIdle(turns, task, AGENT_WORKING, RECOVERY_NEEDED);
        if (!reviewRuns.isEmpty() && reviewRuns.getLast().state() == ReviewRun.State.RUNNING && !review.followUpPaused()) {
            throw new ReviewConflictException(AGENT_WORKING);
        }
    }

    /**
     * A development task's availability rules, with the reasons its tab shows. The core claim refuses the same states atomically; a
     * development turn's follow-up keeps the task unfinished between its steps.
     */
    static void requireTaskIdle(AgentTurnService turns, AgentTask task)
    {
        requireIdle(turns, task, "The task's agent is working", TASK_RECOVERY_NEEDED);
        if (task.developmentContinuationUnfinished()) {
            throw new ReviewConflictException("Development automation is working");
        }
    }

    private static void requireIdle(AgentTurnService turns, AgentTask task, String agentWorking, String recoveryNeeded)
    {
        Optional<WorktreeMutationClaim> claim = task.worktreeMutationClaim();
        if (claim.isPresent()) {
            WorktreeMutationClaim held = claim.orElseThrow();
            throw new ReviewConflictException(switch (held.kind()) {
                case LOCAL_RUN -> "A run is using this working copy";
                case COMPLETION -> "The working copy is being updated";
                case TURN -> turns.get(TurnId.valueOf(held.ownerId())).state().active() ? agentWorking : recoveryNeeded;
                case RECOVERY -> recoveryNeeded;
            });
        }
        if (!turns.queued(task.id()).isEmpty()) {
            throw new ReviewConflictException("Remove or resume the queued question");
        }
    }

    private LocalRun launch(String runId, Preflight preflight, String path, Optional<String> method)
    {
        PreparedProcess prepared;
        LocalRun run;
        try {
            WorkingCopySource source = code.workingCopySource(preflight.root());
            String selector = preflight.testClass() + method.map(name -> "#" + name).orElse("");
            ImmutableList.Builder<String> arguments = ImmutableList.<String>builder().add("-B");
            preflight.module().ifPresent(module -> arguments.add("-pl", module));
            List<String> mavenArguments = arguments.add("-Dtest=" + selector, "-Dsurefire.failIfNoSpecifiedTests=false", "test").build();
            // mvnw execs mvn, which execs java under the same PID; a non-exec bash leader keeps the executable recovery matches.
            List<String> argv = ImmutableList.<String>builder()
                    .add("/bin/bash", "-c", "\"$0\" \"$@\"; exit $?", preflight.root().resolve("mvnw").toString())
                    .addAll(mavenArguments)
                    .build();
            Instant startedAt = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
            prepared = supervisor.prepare(new ProcessSpec(
                    new ProcessOwner(ProcessOwner.Kind.LOCAL_RUN, preflight.taskId() + "/" + runId),
                    argv,
                    preflight.root(),
                    preflight.environment(),
                    OptionalInt.empty(),
                    MAXIMUM_STDERR_BYTES,
                    MAXIMUM_RUNTIME,
                    CANCELLATION_GRACE));
            try {
                run = runs.insert(LocalRun.started(runId, preflight.taskId().toString(), preflight.workspaceId(), nextOrdinal(preflight.taskId()), path,
                        preflight.testClass(), method, preflight.module(), "./mvnw " + String.join(" ", mavenArguments), source.head(), source.localPaths(),
                        source.fingerprint(), prepared.launchId(), startedAt));
            }
            catch (RuntimeException failure) {
                prepared.abandon();
                throw failure;
            }
        }
        catch (RuntimeException failure) {
            releaseAfterFailure(preflight.taskId(), runId, failure);
            throw failure;
        }
        String taskId = run.taskId();
        // Only the newest run of a task keeps its buffer; earlier settled runs read their stored output.
        live.values().removeIf(other -> other.run().taskId().equals(taskId) && other.finished.isDone() && !other.run().treeUnverified());
        LiveRun liveRun = new LiveRun(run, preflight.root().resolve(preflight.module().orElse("")).resolve("target/surefire-reports"));
        live.put(runId, liveRun);
        ManagedProcess process;
        try {
            process = prepared.start(liveRun.output);
        }
        catch (RuntimeException failure) {
            live.remove(runId);
            // The start cannot say whether the gate already ran the payload, so the claim stays: End cannot complete the task over
            // processes that may survive, and the next startup's recovery checks the launch receipt and releases the claim.
            LocalRun failed = run.finished(Status.RESULTS_UNAVAILABLE, Instant.now(clock), OptionalInt.empty(), Optional.empty(), ImmutableList.of(), false, OptionalLong.of(0))
                    .withTreeUnverified(true);
            try {
                return runs.update(failed, "Cannot start the run: " + failure.getMessage() + "\nRestart ByteQuay to check its processes and free the working copy.");
            }
            catch (RuntimeException saveFailure) {
                failure.addSuppressed(saveFailure);
                throw failure;
            }
        }
        liveRun.process = process;
        process.exit().whenCompleteAsync((exit, failure) -> finish(liveRun, Optional.ofNullable(exit), Optional.ofNullable(failure)), FINISHER);
        return run;
    }

    private void finish(LiveRun liveRun, Optional<ProcessExit> exit, Optional<Throwable> failure)
    {
        boolean unverified = exit.map(value -> value.treeTermination() != ProcessExit.TreeTermination.VERIFIED_STOPPED).orElse(true);
        try {
            LocalRun run = liveRun.run();
            failure.ifPresent(cause -> liveRun.output.append("\nThe run's process result could not be read: " + cause));
            RunOutput.Stored output = liveRun.output.finish();
            Status status;
            List<TestClass> results = ImmutableList.of();
            if (exit.isEmpty()) {
                status = Status.RESULTS_UNAVAILABLE;
            }
            else if (liveRun.stopRequested.get() || exit.orElseThrow().reason() == ProcessExit.Reason.CANCELLED) {
                status = Status.STOPPED;
            }
            else if (exit.orElseThrow().reason() == ProcessExit.Reason.TIMED_OUT) {
                status = Status.TIMED_OUT;
            }
            else {
                Optional<List<TestClass>> reports = SurefireReports.read(liveRun.reports, run.testClass().orElseThrow(), run.startedAt());
                status = status(reports, exit.orElseThrow().exitCode());
                results = reports.orElse(ImmutableList.of());
            }
            liveRun.recorded(run.finished(
                    status,
                    Instant.now(clock),
                    exit.map(value -> OptionalInt.of(value.exitCode())).orElse(OptionalInt.empty()),
                    results.isEmpty() ? Optional.empty() : Optional.of(SurefireReports.counts(results)),
                    results,
                    output.truncated(),
                    output.omittedCharacters()).withTreeUnverified(unverified), output.output());
            liveRun.savedWithOutput(runs.update(liveRun.run(), output.output()));
            if (!unverified) {
                settleVerified(liveRun);
            }
        }
        catch (RuntimeException failed) {
            log.error("Local run {} could not record its result or release the working copy; Stop retries", liveRun.run().id(), failed);
            keepStoppable(liveRun, exit);
        }
        finally {
            liveRun.output.markComplete();
            liveRun.finished.complete(null);
        }
    }

    /** The status of a run that exited by itself, from its fresh reports and exit code; a failed case outranks the exit. */
    private static Status status(Optional<List<TestClass>> reports, int exitCode)
    {
        if (reports.isEmpty()) {
            return Status.RESULTS_UNAVAILABLE;
        }
        List<LocalRun.MethodStatus> methods = reports.orElseThrow().stream().flatMap(result -> result.methods().stream()).map(LocalRun.TestMethod::status).toList();
        if (methods.contains(LocalRun.MethodStatus.FAILED) || methods.contains(LocalRun.MethodStatus.ERROR)) {
            return Status.FAILED_ASSERTION;
        }
        if (exitCode != 0) {
            return Status.BUILD_FAILED;
        }
        return methods.contains(LocalRun.MethodStatus.PASSED) ? Status.PASSED : Status.NO_TESTS_RAN;
    }

    private LocalRun stopAndSettle(LiveRun liveRun)
    {
        ProcessExit.TreeTermination termination = await(liveRun.process.stopAndVerify());
        await(liveRun.finished);
        synchronized (liveRun) {
            if (termination == ProcessExit.TreeTermination.VERIFIED_STOPPED && liveRun.run().treeUnverified()) {
                settleVerified(liveRun);
            }
            return liveRun.run();
        }
    }

    /**
     * Marks a run whose result or release failed as unverified, in memory and best effort in the store, so the tab keeps Stop
     * available and Stop records the result and releases the claim; otherwise the claim would hold until a restart.
     */
    private void keepStoppable(LiveRun liveRun, Optional<ProcessExit> exit)
    {
        synchronized (liveRun) {
            LocalRun run = liveRun.run();
            if (run.status() == Status.RUNNING) {
                liveRun.recorded(run.finished(Status.RESULTS_UNAVAILABLE, Instant.now(clock), exit.map(value -> OptionalInt.of(value.exitCode())).orElse(OptionalInt.empty()),
                        Optional.empty(), ImmutableList.of(), false, OptionalLong.empty()), "The run's result could not be recorded.");
            }
            liveRun.saved(liveRun.run().withTreeUnverified(true));
            try {
                liveRun.saved(runs.update(liveRun.run()));
            }
            catch (RuntimeException ignored) {
                // The store is failing as well; Stop saves the same record.
            }
        }
    }

    /** Releases the claim before acknowledging the launch, so a crash between them leaves a receipt rather than a stale claim. */
    private void settleVerified(LiveRun liveRun)
    {
        synchronized (liveRun) {
            LocalRun run = liveRun.run();
            turns.releaseLocalRun(TaskId.valueOf(run.taskId()), run.id());
            run.launchId().ifPresent(supervisor::acknowledge);
            if (run.treeUnverified() || liveRun.unsavedOutput != null) {
                LocalRun settled = run.withTreeUnverified(false);
                if (liveRun.unsavedOutput == null) {
                    liveRun.saved(runs.update(settled));
                }
                else {
                    liveRun.savedWithOutput(runs.update(settled, liveRun.unsavedOutput));
                }
            }
        }
    }

    private void releaseAfterFailure(TaskId taskId, String runId, RuntimeException failure)
    {
        try {
            turns.releaseLocalRun(taskId, runId);
        }
        catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

    private static <T> T await(CompletionStage<T> stage)
    {
        try {
            return stage.toCompletableFuture().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Interrupted while stopping the run", failure);
        }
        catch (ExecutionException | TimeoutException failure) {
            throw new AgentUnavailableException("The run's processes could not be verified stopped", failure);
        }
    }

    private int nextOrdinal(TaskId taskId)
    {
        return runs.listByTask(taskId.toString(), 1).stream().findFirst().map(LocalRun::ordinal).orElse(0) + 1;
    }

    private static String testClass(Path root, String path)
    {
        String source = new String(ReviewCodeService.readCheckout(root, path), UTF_8);
        String simpleName = Path.of(path).getFileName().toString().replaceFirst("\\.java$", "");
        Matcher declaration = PACKAGE.matcher(source);
        return declaration.find() ? declaration.group(1) + "." + simpleName : simpleName;
    }

    private AgentReview requireReview(String reviewId)
    {
        return reviewStore.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
    }

    /**
     * Output after a requested offset.
     *
     * @param nextOffset the offset to request next
     * @param skippedBytes characters between the requested offset and {@code text} that are no longer held
     * @param complete the run's record is final and nothing follows {@code nextOffset}
     */
    public record OutputChunk(String text, long nextOffset, long skippedBytes, boolean complete)
    {
        public OutputChunk
        {
            requireNonNull(text, "text is null");
        }
    }

    private record AgentStart(Instant occurredAt, WorkingCopySource source) {}

    private record Preflight(TaskId taskId, String workspaceId, Path root, String testClass, Optional<String> module, Map<String, String> environment) {}

    /** A run started by this backend; kept until its task's next run so a client streaming its output keeps consistent offsets. */
    private static final class LiveRun
    {
        private final RunOutput output = new RunOutput();
        private final Path reports;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private volatile LocalRun run;
        private volatile ManagedProcess process;
        // The final output while its save has not succeeded, or null once the stored row has it.
        private volatile String unsavedOutput;

        private LiveRun(LocalRun run, Path reports)
        {
            this.run = run;
            this.reports = reports;
        }

        private LocalRun run()
        {
            return run;
        }

        /** The final record, held before its save so a failed save loses nothing. */
        private void recorded(LocalRun finished, String finalOutput)
        {
            run = finished;
            unsavedOutput = finalOutput;
        }

        private void saved(LocalRun saved)
        {
            run = saved;
        }

        private void savedWithOutput(LocalRun saved)
        {
            run = saved;
            unsavedOutput = null;
        }
    }
}
