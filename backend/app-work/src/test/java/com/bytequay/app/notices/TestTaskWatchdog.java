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

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestTaskWatchdog
{
    private static final Instant START = Instant.parse("2026-11-02T09:00:00Z");
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ZERO,
            ImmutableList.of(), Optional.empty(), Optional.empty());

    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentApprovalStore approvals = mock(AgentApprovalStore.class);
    private final AgentEventService events = mock(AgentEventService.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final List<AgentEventEnvelope> log = new ArrayList<>();
    private final AgentTask task = created(TaskId.random(), Optional.of("workspace"), true, "Fix the flaky test", new AgentLaneId("claude"),
            new RepositoryIdentity("owner", "repo"), Path.of("/tmp/watchdog-worktree"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), START);
    private final AgentTurn earlier = running(START.minus(Duration.ofHours(2)));
    private final AgentTurn turn = running(START);
    private Instant checked = START;
    private TaskWatchdog watchdog;

    @BeforeEach
    void setUp()
    {
        when(tasks.listLive()).thenReturn(ImmutableList.of(task));
        when(turns.findLatest(anySet())).thenReturn(ImmutableMap.of(task.id(), turn));
        when(approvals.listPending()).thenReturn(ImmutableList.of());
        when(events.readHistory(task.id(), OptionalLong.empty(), OptionalLong.empty()))
                .thenAnswer(_ -> new AgentHistoryPage(ImmutableList.copyOf(log), OptionalLong.empty(), OptionalLong.empty(), log.size()));
        when(events.readAfter(eq(task.id()), anyLong(), anyInt())).thenAnswer(call -> log.stream().filter(event -> event.sequence() > call.<Long>getArgument(1))
                .limit(call.<Integer>getArgument(2)).collect(toImmutableList()));
        Clock clock = new Clock()
        {
            @Override
            public ZoneId getZone()
            {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone)
            {
                return this;
            }

            @Override
            public Instant instant()
            {
                return now.get();
            }
        };
        watchdog = new TaskWatchdog(tasks, turns, approvals, events, clock);
    }

    @Test
    void aTurnQuietTenMinutesWithNothingRunningOrWaitingLooksStuckUntilItMovesAgain()
    {
        event(turn, START, message("Looking at the retry"));
        command(turn, START.plusSeconds(60), "c1", ActivityState.STARTED, "/bin/zsh -lc 'gh api repos/owner/repo/pulls'", Optional.empty());
        command(turn, START.plusSeconds(70), "c1", ActivityState.COMPLETED, "/bin/zsh -lc 'gh api repos/owner/repo/pulls'", Optional.of(10_000L));
        check(START.plus(Duration.ofMinutes(5)));
        assertThat(watchdog.stuck(turn.id())).isEmpty();

        // A tool's progress beat and a retry after a model error are signs of life, not of moving on.
        event(turn, START.plus(Duration.ofMinutes(9)), new AgentEventPayload.ToolActivity("Bash", ActivityState.PROGRESS, "", Optional.empty(), Optional.empty()));
        event(turn, START.plus(Duration.ofMinutes(10)), new AgentEventPayload.Lifecycle(AgentEventPayload.LifecyclePhase.RETRYING, "Overloaded, retrying"));
        check(START.plus(Duration.ofMinutes(12)));
        TaskWatchdog.Stuck quiet = watchdog.stuck(turn.id()).orElseThrow();
        assertThat(quiet.reason()).isEqualTo("Quiet 10 min · last: gh api repos/owner/repo/pulls");
        assertThat(quiet.since()).isEqualTo(START.plusSeconds(70).plus(TaskWatchdog.QUIET));
        check(START.plus(Duration.ofMinutes(13)));
        assertThat(watchdog.stuck(turn.id()).map(TaskWatchdog.Stuck::since)).contains(quiet.since());

        // A card waiting for the user is what the turn waits on.
        when(approvals.listPending()).thenReturn(ImmutableList.of(pending(turn)));
        check(START.plus(Duration.ofMinutes(14)));
        assertThat(watchdog.stuck(turn.id())).isEmpty();
        when(approvals.listPending()).thenReturn(ImmutableList.of());

        event(turn, START.plus(Duration.ofMinutes(15)), message("Found it"));
        check(START.plus(Duration.ofMinutes(16)));
        assertThat(watchdog.stuck(turn.id())).isEmpty();
    }

    @Test
    void aCommandLooksStuckOnlyPastTwentyMinutesAndTenTimesItsProgramsUsualTime()
    {
        // The task's earlier builds teach how long its build usually takes.
        for (int run = 0; run < 3; run++) {
            Instant at = START.minus(Duration.ofHours(2)).plus(Duration.ofMinutes(10L * run));
            command(earlier, at, "b" + run, ActivityState.STARTED, "/bin/zsh -lc './mvnw -pl :catalog test'", Optional.empty());
            command(earlier, at.plusSeconds(150), "b" + run, ActivityState.COMPLETED, "/bin/zsh -lc './mvnw -pl :catalog test'", Optional.of(150_000L));
        }
        command(turn, START, "b9", ActivityState.STARTED, "/bin/zsh -lc 'cd catalog && ./mvnw -pl :catalog test'", Optional.empty());
        check(START.plus(Duration.ofMinutes(21)));
        assertThat(watchdog.stuck(turn.id())).isEmpty();
        check(START.plus(Duration.ofMinutes(26)));
        TaskWatchdog.Stuck build = watchdog.stuck(turn.id()).orElseThrow();
        assertThat(build.reason()).isEqualTo("cd catalog && ./mvnw -pl :catalog test has run 26 min, usually 2 min");
        assertThat(build.since()).isEqualTo(START.plus(Duration.ofSeconds(1500)));

        // A program with no runs seen yet waits the twenty minutes alone.
        command(turn, START.plus(Duration.ofMinutes(27)), "b9", ActivityState.COMPLETED, "/bin/zsh -lc 'cd catalog && ./mvnw -pl :catalog test'", Optional.of(1_620_000L));
        command(turn, START.plus(Duration.ofMinutes(27)), "g1", ActivityState.STARTED, "/bin/zsh -lc 'gh run watch 42'", Optional.empty());
        check(START.plus(Duration.ofMinutes(46)));
        assertThat(watchdog.stuck(turn.id())).isEmpty();
        check(START.plus(Duration.ofMinutes(47)));
        assertThat(watchdog.stuck(turn.id()).map(TaskWatchdog.Stuck::reason)).contains("gh run watch 42 has run 20 min");
    }

    @Test
    void timeTheMacSleptCountsTowardNothing()
    {
        event(turn, START, message("Looking at the retry"));
        check(START.plus(Duration.ofMinutes(5)));
        checkOnce(START.plus(Duration.ofMinutes(55)));
        assertThat(watchdog.stuck(turn.id())).isEmpty();
        check(START.plus(Duration.ofMinutes(56)));
        check(START.plus(Duration.ofMinutes(57)));
        assertThat(watchdog.stuck(turn.id())).isEmpty();
        check(START.plus(Duration.ofMinutes(60)));
        assertThat(watchdog.stuck(turn.id()).map(TaskWatchdog.Stuck::reason)).contains("Quiet 11 min");
    }

    @Test
    void namesTheProgramACommandRunsPastItsShell()
    {
        assertThat(TaskWatchdog.program(ImmutableList.of("/bin/zsh", "-lc", "cd catalog && JAVA_HOME=/opt/jdk ./mvnw -pl :catalog test"))).isEqualTo("mvnw");
        assertThat(TaskWatchdog.program(ImmutableList.of("/bin/bash", "-lc", "'set -o pipefail; gh api repos/owner/repo'"))).isEqualTo("gh");
        assertThat(TaskWatchdog.program(ImmutableList.of("git", "status"))).isEqualTo("git");
        assertThat(TaskWatchdog.command(ImmutableList.of("/bin/zsh", "-lc", "'npm test'"))).isEqualTo("npm test");
    }

    /** Checks every minute up to the time, as the job does. */
    private void check(Instant at)
    {
        for (Instant minute = checked.plus(Duration.ofMinutes(1)); minute.isBefore(at); minute = minute.plus(Duration.ofMinutes(1))) {
            checkOnce(minute);
        }
        checkOnce(at);
    }

    /** One check, however long after the last one, such as after the Mac slept. */
    private void checkOnce(Instant at)
    {
        now.set(at);
        checked = at;
        watchdog.check();
    }

    private void command(AgentTurn owner, Instant at, String item, ActivityState state, String argv, Optional<Long> durationMs)
    {
        event(owner, at, item, new AgentEventPayload.CommandActivity(state, ImmutableList.copyOf(argv.split(" ", 3)), Optional.empty(), Optional.empty(), false,
                Optional.empty(), durationMs, ImmutableList.of(), false, Optional.empty()));
    }

    private void event(AgentTurn owner, Instant at, AgentEventPayload payload)
    {
        event(owner, at, "e" + (log.size() + 1), payload);
    }

    private void event(AgentTurn owner, Instant at, String item, AgentEventPayload payload)
    {
        AgentEventSource source = new AgentEventSource(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(item), Optional.empty());
        log.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), log.size() + 1, task.id(), Optional.of(owner.id()), source, at,
                payload));
    }

    private static AgentEventPayload message(String text)
    {
        return new AgentEventPayload.AssistantMessage(MessageKind.COMMENTARY, MessagePhase.FINAL, text);
    }

    private AgentTurn running(Instant at)
    {
        return AgentTurn.accepted(TurnId.random(), task.id(), "input-" + at, SETTINGS, at).markDeliveryStarted(at).running(new ProviderTurnRef("provider-" + at), Optional.empty());
    }

    private static AgentApproval pending(AgentTurn turn)
    {
        ProviderApprovalRequest request = new ProviderApprovalRequest(ProviderApprovalRequest.Kind.TOOL, "Run the build", "./mvnw test", ImmutableList.of(),
                ImmutableList.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY), false, ProviderAgentEventSource.root(), START);
        return AgentApproval.pending(new ApprovalRequest(ApprovalId.random(), turn.taskId(), turn.id(), request, START));
    }
}
