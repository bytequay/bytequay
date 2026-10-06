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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.LocalRun.Scope;
import com.bytequay.core.review.LocalRun.Status;
import com.bytequay.core.review.LocalRunStore;
import com.bytequay.core.review.ReviewNotFoundException;
import com.google.common.collect.ImmutableList;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Native test/debug execution uses the existing run history and LOCAL_RUN claim; language services keep their separate lifetime. */
@Component
public class IdeExecutions
{
    private static final Logger log = LoggerFactory.getLogger(IdeExecutions.class);
    private static final Duration MAX_RUNTIME = Duration.ofHours(1);
    private final IdeRuntimeService runtime;
    private final ReviewCodeService code;
    private final AgentTurnService turns;
    private final AgentReviewService reviews;
    private final AgentReviewStore reviewStore;
    private final LocalRunStore runs;
    private final ReviewLocks locks;
    private final Clock clock;
    private final Map<String, Live> live = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public IdeExecutions(IdeRuntimeService runtime, ReviewCodeService code, AgentTurnService turns, AgentReviewService reviews,
            AgentReviewStore reviewStore, LocalRunStore runs, ReviewLocks locks, Clock clock)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.code = requireNonNull(code, "code is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.locks = requireNonNull(locks, "locks is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public LocalRun start(String reviewId, Start request)
    {
        Lock lock = locks.lock(reviewId);
        lock.lock();
        try {
            TaskWorktree worktree = code.ideWorktree(reviewId);
            AgentReview review = reviewStore.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found"));
            LocalRunService.requireIdle(turns, reviewStore.listRuns(reviewId), review, reviews.task(reviewId));
            return start(worktree, request);
        }
        finally {
            lock.unlock();
        }
    }

    public LocalRun start(TaskCodeHost host, Start request)
    {
        AgentTask task = code.mutableTask(host);
        TaskWorktree worktree = code.ideWorktree(host);
        LocalRunService.requireTaskIdle(turns, task);
        return start(worktree, request);
    }

    private LocalRun start(TaskWorktree worktree, Start request)
    {
        checkState(!closing, "ByteQuay is shutting down");
        String id = UUID.randomUUID().toString();
        turns.claimLocalRun(worktree.taskId(), id);
        Live execution;
        try {
            IdeRuntimeService.Execution processes = runtime.trackExecution(worktree.taskId().toString(), request.sessionId());
            ReviewCodeService.WorkingCopySource source = code.workingCopySource(worktree.canonicalWorktree());
            int ordinal = runs.listByTask(worktree.taskId().toString(), 1).stream().findFirst().map(LocalRun::ordinal).orElse(0) + 1;
            LocalRun run = runs.insert(new LocalRun(id, worktree.taskId().toString(), worktree.workspaceId(), ordinal, LocalRun.Initiator.USER, request.scope(),
                    request.path(), Optional.empty(), Optional.empty(), Optional.empty(), request.label(), Optional.of(source.head()), source.localPaths(),
                    Optional.of(source.fingerprint()), Status.RUNNING, clock.instant(), Optional.empty(), OptionalInt.empty(), Optional.empty(), false, OptionalLong.empty(),
                    ImmutableList.of(), Optional.empty(), Optional.of(processes.process().launchId()), false, 0));
            execution = new Live(run, request.sessionId(), processes);
            live.put(id, execution);
        }
        catch (RuntimeException failure) {
            // The renderer has not received permission to execute yet, so only ordinary IDE services can be running.
            turns.releaseLocalRun(worktree.taskId(), id);
            throw failure;
        }
        return execution.run;
    }

    public void output(String taskId, String sessionId, String id, Output request)
    {
        Live execution = requireLive(taskId, sessionId, id);
        synchronized (execution) {
            checkState(execution.run.status() == Status.RUNNING, "The execution has finished");
            if (request.sequence() == execution.sequence) {
                checkArgument(execution.lastOutput.filter(request::equals).isPresent(), "Execution output retry changed");
                return;
            }
            checkArgument(request.sequence() == execution.sequence + 1, "Execution output is out of order");
            execution.output.append(request.text());
            execution.sequence = request.sequence();
            execution.lastOutput = Optional.of(request);
            execution.outputTruncated |= request.truncated();
        }
    }

    public LocalRun finish(String taskId, String sessionId, String id, Result result)
    {
        if (!live.containsKey(id)) {
            return runs.find(id).filter(run -> run.taskId().equals(taskId) && run.status() != Status.RUNNING && !run.treeUnverified())
                    .orElseThrow(() -> new ReviewNotFoundException("IDE execution is no longer active"));
        }
        Live execution = requireLive(taskId, sessionId, id);
        synchronized (execution) {
            if (execution.run.status() == Status.RUNNING) {
                if (execution.run.scope() != Scope.TESTS) {
                    checkArgument(result.tests().isEmpty(), "A debug or program session cannot report test results");
                }
                if (result.incomplete()) {
                    execution.output.append("\nSome test evidence was unavailable or exceeded the result limit.\n");
                    execution.outputTruncated = true;
                }
                record(execution, result.status(execution.run.scope()), result.exitCode(), result.tests());
            }
            settle(execution);
            return execution.run;
        }
    }

    /** Explicit recovery after cooperative cancellation failed: stop the owned IDE tree, never an arbitrary PID. */
    public LocalRun stop(String taskId, String sessionId, String id)
    {
        Live execution = requireLive(taskId, sessionId, id);
        runtime.stop(taskId, sessionId);
        synchronized (execution) {
            if (execution.run.status() == Status.RUNNING) {
                record(execution, Status.STOPPED, OptionalInt.empty(), ImmutableList.of());
            }
            settle(execution);
            return execution.run;
        }
    }

    /** The caller has already resolved this stored run through its authorized review or task host. */
    LocalRun stop(LocalRun stored)
    {
        Live execution = live.get(stored.id());
        if (execution == null) {
            return runs.find(stored.id()).filter(run -> run.taskId().equals(stored.taskId())).orElse(stored);
        }
        return stop(stored.taskId(), execution.sessionId, stored.id());
    }

    Optional<LocalRunService.OutputChunk> output(LocalRun stored, long after)
    {
        Live execution = live.get(stored.id());
        if (execution == null || !execution.run.taskId().equals(stored.taskId())) {
            return Optional.empty();
        }
        return Optional.of(execution.output.read(after));
    }

    private Live requireLive(String taskId, String sessionId, String id)
    {
        Live execution = live.get(id);
        if (execution == null || !execution.run.taskId().equals(taskId) || !execution.sessionId.equals(sessionId)) {
            throw new ReviewNotFoundException("IDE execution is no longer active");
        }
        return execution;
    }

    private void record(Live execution, Status status, OptionalInt exitCode, List<LocalRun.TestClass> tests)
    {
        RunOutput.Stored output = execution.output.finish();
        List<LocalRun.TestMethod> methods = tests.stream().flatMap(group -> group.methods().stream()).toList();
        Optional<LocalRun.Counts> counts = tests.isEmpty() ? Optional.empty() : Optional.of(new LocalRun.Counts(methods.size(),
                count(methods, LocalRun.MethodStatus.FAILED), count(methods, LocalRun.MethodStatus.ERROR), count(methods, LocalRun.MethodStatus.SKIPPED)));
        LocalRun finished = execution.run.finished(status, clock.instant(), exitCode, counts, tests, output.truncated() || execution.outputTruncated,
                execution.outputTruncated ? OptionalLong.empty() : output.omittedCharacters()).withTreeUnverified(true);
        execution.run = runs.update(finished, output.output());
        execution.output.markComplete();
    }

    private void settle(Live execution)
    {
        if (execution.run.status() == Status.RUNNING || !execution.processes.quiescent()) {
            return;
        }
        LocalRun run = execution.run;
        // Keep the IDE launch marker for idle shutdown/recovery; finishing one run does not finish its language services.
        turns.releaseLocalRun(TaskId.valueOf(run.taskId()), run.id());
        execution.run = runs.update(run.withTreeUnverified(false));
        live.remove(run.id(), execution);
        Thread.ofVirtual().name("bytequay-ide-run-finished").start(() -> {
            try {
                reviewStore.findReviewByTask(run.taskId()).ifPresent(review -> reviews.reconcile(review.id()));
            }
            catch (RuntimeException failure) {
                log.debug("Cannot reconcile review after IDE execution {}", run.id(), failure);
            }
        });
    }

    @Scheduled(fixedDelay = 1000)
    public void reconcile()
    {
        for (Live execution : live.values()) {
            try {
                synchronized (execution) {
                    if (execution.run.status() == Status.RUNNING) {
                        if (execution.processes.process().exit().toCompletableFuture().isDone()) {
                            record(execution, Status.INTERRUPTED, OptionalInt.empty(), ImmutableList.of());
                        }
                        else if (execution.run.startedAt().plus(MAX_RUNTIME).isBefore(clock.instant())) {
                            runtime.stop(execution.run.taskId(), execution.sessionId);
                            record(execution, Status.TIMED_OUT, OptionalInt.empty(), ImmutableList.of());
                        }
                    }
                    settle(execution);
                }
            }
            catch (RuntimeException failure) {
                log.warn("IDE execution {} still needs cleanup: {}", execution.run.id(), failure.getMessage());
            }
        }
    }

    @PreDestroy
    public void close()
    {
        closing = true;
        for (Live execution : live.values()) {
            try {
                stop(execution.run.taskId(), execution.sessionId, execution.run.id());
            }
            catch (RuntimeException failure) {
                log.warn("IDE execution {} requires recovery on restart", execution.run.id(), failure);
            }
        }
    }

    private static int count(List<LocalRun.TestMethod> methods, LocalRun.MethodStatus status)
    {
        return (int) methods.stream().filter(method -> method.status() == status).count();
    }

    public record Start(String sessionId, Scope scope, String label, Optional<String> path)
    {
        public Start
        {
            checkText(sessionId, "sessionId", 64);
            checkArgument(scope == Scope.TESTS || scope == Scope.DEBUG || scope == Scope.RUN, "Unsupported IDE execution scope");
            checkText(label, "label", 2000);
            requireNonNull(path, "path is null").ifPresent(ReviewCodeService::checkPath);
        }
    }

    public record Output(long sequence, String text, boolean truncated)
    {
        public Output
        {
            checkArgument(sequence > 0, "sequence is not positive");
            requireNonNull(text, "text is null");
            checkArgument(text.length() <= 65_536, "Execution output chunk is too large");
        }
    }

    public record Result(boolean cancelled, boolean failed, boolean incomplete, OptionalInt exitCode, List<LocalRun.TestClass> tests)
    {
        public Result
        {
            requireNonNull(exitCode, "exitCode is null");
            tests = ImmutableList.copyOf(tests);
            checkArgument(tests.size() <= 1000 && tests.stream().mapToLong(group -> group.methods().size()).sum() <= 2000, "Too many test results");
            int characters = 0;
            for (LocalRun.TestClass group : tests) {
                checkText(group.className(), "test group", 1024);
                checkArgument(group.durationMs().orElse(0) >= 0, "Test group duration is negative");
                characters += group.className().length();
                for (LocalRun.TestMethod method : group.methods()) {
                    checkText(method.name(), "test name", 2000);
                    checkArgument(method.durationMs().orElse(0) >= 0, "Test duration is negative");
                    checkOptionalText(method.message(), "test message", 16_384);
                    checkOptionalText(method.trace(), "test trace", 32_768);
                    characters += method.name().length() + method.message().map(String::length).orElse(0) + method.trace().map(String::length).orElse(0);
                }
            }
            checkArgument(characters <= 1_048_576, "Test result evidence is too large");
        }

        Status status(Scope scope)
        {
            if (cancelled) {
                return Status.STOPPED;
            }
            if (scope != Scope.TESTS) {
                return failed || exitCode.orElse(0) != 0 ? Status.FAILED : Status.COMPLETED;
            }
            List<LocalRun.TestMethod> methods = tests.stream().flatMap(group -> group.methods().stream()).toList();
            if (count(methods, LocalRun.MethodStatus.FAILED) + count(methods, LocalRun.MethodStatus.ERROR) > 0) {
                return Status.FAILED_ASSERTION;
            }
            if (failed || exitCode.orElse(0) != 0) {
                return Status.BUILD_FAILED;
            }
            if (incomplete) {
                return Status.RESULTS_UNAVAILABLE;
            }
            return count(methods, LocalRun.MethodStatus.PASSED) > 0 ? Status.PASSED : Status.NO_TESTS_RAN;
        }
    }

    private static final class Live
    {
        private volatile LocalRun run;
        private final String sessionId;
        private final IdeRuntimeService.Execution processes;
        private final RunOutput output = new RunOutput();
        private long sequence;
        private Optional<Output> lastOutput = Optional.empty();
        private boolean outputTruncated;

        private Live(LocalRun run, String sessionId, IdeRuntimeService.Execution processes)
        {
            this.run = run;
            this.sessionId = sessionId;
            this.processes = processes;
        }
    }
}
