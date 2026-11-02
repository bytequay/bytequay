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
package com.bytequay.app.notices;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.agent.spi.ProviderAgentEvent.ActivityState.COMPLETED;
import static com.bytequay.agent.spi.ProviderAgentEvent.ActivityState.FAILED;
import static com.bytequay.agent.spi.ProviderAgentEvent.ActivityState.PROGRESS;
import static com.bytequay.agent.spi.ProviderAgentEvent.ActivityState.STARTED;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Tells which running turns look stuck, from the events they already stored and with no model: a turn quiet for ten minutes with no
 * command running and no card waiting, or a command running far past its program's usual time. It only tells; the user opens the task
 * or stops the turn.
 */
@Component
public class TaskWatchdog
{
    private static final Logger log = LoggerFactory.getLogger(TaskWatchdog.class);
    // Measured on 694 real turns: this long quiet, with nothing running or waiting, caught both model retry storms, and shorter was noise.
    static final Duration QUIET = Duration.ofMinutes(10);
    // And ten times the program's usual time, so a long build is not taken for a hung call.
    static final Duration LONG_COMMAND = Duration.ofMinutes(20);
    private static final int USUAL_TIMES = 10;
    private static final Duration TICK = Duration.ofMinutes(1);
    // A check this much later than its minute means the Mac slept; time asleep counts toward nothing.
    private static final Duration ASLEEP = Duration.ofMinutes(3);
    private static final int READ = 500;
    private static final int RUNS = 200;
    private static final int LABEL = 60;
    // Spawning a subagent waits on the subagent, whose own events show its work.
    private static final Set<String> SPAWNS = ImmutableSet.of("Task", "Agent");
    private static final Pattern SHELL = Pattern.compile("^(?:\\S*/)?(?:ba|z)?sh -l?c (.*)$", Pattern.DOTALL);
    private static final Pattern SETTING = Pattern.compile("^[A-Z_][A-Z0-9_]*=.*");
    // Steps that only prepare the shell, and words that only wrap the program a step runs.
    private static final Set<String> SETUP = ImmutableSet.of("cd", "set", "export", "source");
    private static final Set<String> WRAPPERS = ImmutableSet.of("exec", "time", "env", "nohup");

    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentApprovalStore approvals;
    private final AgentEventService events;
    private final Clock clock;
    // ponytail: all in memory and read again from each running task's latest history page after a start. A program's usual time is
    // learned from the commands read since, so after a start a first build longer than 20 minutes may look stuck until one finished.
    private final Map<TaskId, Watch> watches = new ConcurrentHashMap<>();
    private final Map<String, Deque<Duration>> runs = new ConcurrentHashMap<>();
    private volatile Map<TurnId, Stuck> stuck = ImmutableMap.of();
    private Optional<Instant> checked = Optional.empty();

    public TaskWatchdog(AgentTaskStore tasks, AgentTurnStore turns, AgentApprovalStore approvals, AgentEventService events, Clock clock)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.events = requireNonNull(events, "events is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Why a running turn looks stuck.
     *
     * @param since when it crossed the line, which stays the same while it stays stuck
     */
    public record Stuck(String reason, Instant since)
    {
        public Stuck
        {
            requireNonNull(reason, "reason is null");
            requireNonNull(since, "since is null");
        }
    }

    /** Whether the turn looked stuck at the last check. */
    public Optional<Stuck> stuck(TurnId turnId)
    {
        return Optional.ofNullable(stuck.get(turnId));
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M", scheduler = "workflowScheduler")
    public synchronized void check()
    {
        Instant now = clock.instant();
        Duration asleep = checked.map(last -> Duration.between(last, now).minus(TICK)).filter(late -> late.compareTo(ASLEEP) > 0).orElse(Duration.ZERO);
        checked = Optional.of(now);
        List<TaskId> live = tasks.listLive().stream().map(AgentTask::id).collect(toImmutableList());
        Collection<AgentTurn> running = turns.findLatest(ImmutableSet.copyOf(live)).values().stream().filter(turn -> turn.state() == TurnState.RUNNING)
                .collect(toImmutableList());
        Set<TurnId> waiting = approvals.listPending().stream().map(approval -> approval.request().turnId()).collect(toImmutableSet());
        ImmutableMap.Builder<TurnId, Stuck> found = ImmutableMap.builder();
        for (AgentTurn turn : running) {
            try {
                Watch watch = watches.get(turn.taskId());
                if (watch == null || !watch.turnId.equals(turn.id())) {
                    watch = start(turn);
                    watches.put(turn.taskId(), watch);
                }
                else {
                    watch.shift(asleep);
                }
                read(watch, turn.taskId());
                if (!waiting.contains(turn.id())) {
                    watch.verdict(now).ifPresent(verdict -> found.put(turn.id(), verdict));
                }
            }
            catch (RuntimeException failure) {
                log.debug("The watchdog could not read task {}: {}", turn.taskId(), failure.toString());
            }
        }
        watches.keySet().retainAll(running.stream().map(AgentTurn::taskId).collect(toImmutableSet()));
        stuck = found.buildOrThrow();
    }

    /** A turn first seen running, from its task's latest history page, whose earlier commands also teach their programs' usual time. */
    private Watch start(AgentTurn turn)
    {
        Watch watch = new Watch(turn.id(), turn.startedAt().orElse(turn.createdAt()));
        AgentHistoryPage page = events.readHistory(turn.taskId(), OptionalLong.empty(), OptionalLong.empty());
        page.events().forEach(event -> apply(watch, event));
        watch.sequence = page.latestSequence();
        return watch;
    }

    private void read(Watch watch, TaskId taskId)
    {
        List<AgentEventEnvelope> batch;
        do {
            batch = events.readAfter(taskId, watch.sequence, READ);
            for (AgentEventEnvelope event : batch) {
                apply(watch, event);
                watch.sequence = event.sequence();
            }
        }
        while (batch.size() == READ);
    }

    private void apply(Watch watch, AgentEventEnvelope event)
    {
        String item = event.source().providerItemId().orElse("");
        boolean own = event.turnId().filter(watch.turnId::equals).isPresent();
        switch (event.payload()) {
            case AgentEventPayload.CommandActivity command when !command.background() && !item.isEmpty() -> {
                if (command.state() == STARTED && own) {
                    watch.started(item, new Running(program(command.argv()), clip(command(command.argv())), event.occurredAt()));
                }
                else if (command.state() == COMPLETED || command.state() == FAILED) {
                    Optional<Running> ran = watch.ended(item);
                    command.durationMs().map(Duration::ofMillis).ifPresent(took -> learn(ran.map(Running::program).orElse(program(command.argv())), took));
                }
            }
            case AgentEventPayload.ToolActivity tool when !SPAWNS.contains(tool.name()) && !item.isEmpty() && own -> {
                if (tool.state() == STARTED) {
                    watch.started(item, new Running(tool.name(), tool.name(), event.occurredAt()));
                }
                else if (tool.state() == COMPLETED || tool.state() == FAILED) {
                    watch.ended(item);
                }
            }
            default -> {}
        }
        if (own && !heartbeat(event.payload())) {
            watch.heard(event.occurredAt());
        }
    }

    /** A sign the provider is alive, not that the turn moves on: a tool's progress beat, or a retry after a model error. */
    private static boolean heartbeat(AgentEventPayload payload)
    {
        return payload instanceof AgentEventPayload.ToolActivity tool && tool.state() == PROGRESS
                || payload instanceof AgentEventPayload.Lifecycle lifecycle
                && (lifecycle.phase() == AgentEventPayload.LifecyclePhase.RETRYING || lifecycle.phase() == AgentEventPayload.LifecyclePhase.RATE_LIMITED);
    }

    private void learn(String program, Duration took)
    {
        Deque<Duration> kept = runs.computeIfAbsent(program, _ -> new ArrayDeque<>());
        synchronized (kept) {
            kept.addLast(took);
            if (kept.size() > RUNS) {
                kept.removeFirst();
            }
        }
    }

    /** How long the program's runs usually take: the 95th percentile of those seen, or none yet. */
    private Optional<Duration> usual(String program)
    {
        Deque<Duration> kept = runs.get(program);
        if (kept == null) {
            return Optional.empty();
        }
        synchronized (kept) {
            List<Duration> sorted = kept.stream().sorted().collect(toImmutableList());
            return sorted.isEmpty() ? Optional.empty() : Optional.of(sorted.get(Math.min(sorted.size() - 1, (int) (0.95 * sorted.size()))));
        }
    }

    /** The command a shell runs, as typed, rather than the shell that runs it. */
    static String command(List<String> argv)
    {
        String text = String.join(" ", argv).strip();
        Matcher shell = SHELL.matcher(text);
        return shell.matches() ? unquote(shell.group(1).strip()) : text;
    }

    /** The program a command runs, such as {@code gh} or {@code mvnw}: the first step that is not a {@code cd} or a setting. */
    static String program(List<String> argv)
    {
        for (String step : command(argv).split("&&|\\|\\||;|\\|")) {
            List<String> words = Arrays.stream(step.strip().split("\\s+")).map(TaskWatchdog::unquote)
                    .filter(word -> !word.isEmpty() && !WRAPPERS.contains(word) && !SETTING.matcher(word).matches()).collect(toImmutableList());
            if (!words.isEmpty() && !SETUP.contains(words.getFirst())) {
                return words.getFirst().substring(words.getFirst().lastIndexOf('/') + 1);
            }
        }
        return "command";
    }

    private static String unquote(String text)
    {
        return text.length() >= 2 && (text.startsWith("'") && text.endsWith("'") || text.startsWith("\"") && text.endsWith("\"")) ? text.substring(1, text.length() - 1) : text;
    }

    private static String clip(String text)
    {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= LABEL ? line : line.substring(0, LABEL - 1) + "…";
    }

    private static String minutes(Duration duration)
    {
        return duration.toMinutes() + " min";
    }

    private record Running(String program, String label, Instant startedAt) {}

    /** What one running turn has done, as far as its events tell. */
    private final class Watch
    {
        private final TurnId turnId;
        private final Map<String, Running> running = new LinkedHashMap<>();
        private long sequence;
        private Instant quietSince;
        private Optional<String> lastStep = Optional.empty();

        private Watch(TurnId turnId, Instant startedAt)
        {
            this.turnId = turnId;
            this.quietSince = startedAt;
        }

        private void started(String item, Running run)
        {
            running.put(item, run);
            lastStep = Optional.of(run.label());
        }

        private Optional<Running> ended(String item)
        {
            return Optional.ofNullable(running.remove(item));
        }

        private void heard(Instant at)
        {
            if (at.isAfter(quietSince)) {
                quietSince = at;
            }
        }

        /** Time asleep does not count: what was quiet or running before the Mac slept is as old as it was then. */
        private void shift(Duration asleep)
        {
            if (asleep.isZero()) {
                return;
            }
            quietSince = quietSince.plus(asleep);
            running.replaceAll((_, run) -> new Running(run.program(), run.label(), run.startedAt().plus(asleep)));
        }

        private Optional<Stuck> verdict(Instant now)
        {
            for (Running run : running.values()) {
                Optional<Duration> usual = usual(run.program());
                Duration limit = usual.map(took -> took.multipliedBy(USUAL_TIMES)).filter(longer -> longer.compareTo(LONG_COMMAND) > 0).orElse(LONG_COMMAND);
                if (!run.startedAt().plus(limit).isAfter(now)) {
                    String took = usual.map(value -> ", usually " + (value.toMinutes() > 0 ? minutes(value) : value.toSeconds() + " s")).orElse("");
                    return Optional.of(new Stuck(run.label() + " has run " + minutes(Duration.between(run.startedAt(), now)) + took, run.startedAt().plus(limit)));
                }
            }
            if (running.isEmpty() && !quietSince.plus(QUIET).isAfter(now)) {
                return Optional.of(new Stuck("Quiet " + minutes(Duration.between(quietSince, now)) + lastStep.map(step -> " · last: " + step).orElse(""),
                        quietSince.plus(QUIET)));
            }
            return Optional.empty();
        }
    }
}
