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
package com.bytequay.app.agenda;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.service.settings.SetupSettingsService;
import com.bytequay.app.team.TeamService;
import com.bytequay.core.domain.AgendaCalendar;
import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaRun;
import com.bytequay.core.store.AgendaStore;
import com.bytequay.core.store.AppSettingsStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockMakers;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAgendaService
{
    private static final ZoneId ZONE = ZoneId.systemDefault();
    // Friday 16 October 2026, 09:02 on this machine's clock; the default schedule makes the agenda at 09:00 on weekdays.
    private static final Instant FRIDAY_MORNING = LocalDateTime.parse("2026-10-16T09:02").atZone(ZONE).toInstant();
    private static final AgentLaneId LANE = new AgentLaneId("claude");
    private static final String REPLY = """
            {"summary": "Your conflict-retry fix merged last night.", "items": [
              {"ref": "task:t1", "action": "answer", "title": "Answer the retry question", "label": "the retry question", "why": "The agent waits on you."},
              {"ref": "pr:owner/gateway#612", "action": "merge", "title": "Merge #612 Route by client tags", "why": "Approved and green since Wednesday."},
              {"ref": "pr:made/up#1", "action": "review", "title": "Review a PR no page named", "why": "x"}],
             "recap": {"text": "Merged 3 PRs.", "merged": ["pr:owner/repository#31390"], "slipped": []}}
            """;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @TempDir
    private Path temporaryDirectory;

    private final MemoryStore store = new MemoryStore();
    private final AgendaReader reader = mock(AgendaReader.class);
    private final AgendaCalendarReader calendars = mock(AgendaCalendarReader.class);
    private final TeamService teams = mock(TeamService.class);
    private final AgendaDone done = mock(AgendaDone.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final AgentSession session = mock(AgentSession.class);
    private final RunningTurn turn = mock(RunningTurn.class);
    private final AgentResourceService resources = mock(AgentResourceService.class);
    private final ItemTaskStarter starts = mock(ItemTaskStarter.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(FRIDAY_MORNING.minusSeconds(3600));
    private final AtomicReference<AgentSessionContext> opened = new AtomicReference<>();
    private final AtomicReference<String> answer = new AtomicReference<>(REPLY);
    private AgendaService agenda;

    @BeforeEach
    void setUp()
            throws Exception
    {
        AppSettingsStore settings = mock(AppSettingsStore.class);
        AgendaSchedule weekdayMornings = new AgendaSchedule(true, AgendaSchedule.DEFAULT.minute(), AgendaSchedule.DEFAULT.days(), Optional.empty());
        when(settings.get(any())).thenReturn(Optional.of(AgendaFixtures.mapper().writeValueAsString(weekdayMornings)));
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.empty()))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "claude", "Claude", "sonnet", "Sonnet", Optional.empty()));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
        // Each run gets its own scratch, as the real resource service gives it.
        when(resources.allocate(any())).thenAnswer(call -> {
            Path runtime = temporaryDirectory.resolve("runtime-" + call.getArgument(0).toString());
            Files.createDirectories(runtime.resolve("provider"));
            return new TaskResources(runtime, runtime.resolve("events.jsonl"), runtime.resolve("blobs"), runtime.resolve("provider"));
        });
        when(driver.open(any(), any())).thenAnswer(call -> {
            AgentSessionContext context = call.getArgument(0);
            opened.set(context);
            // The agent reads its day before it answers; only refs a page names may be kept.
            context.sessionTools().getFirst().call().apply("{\"kind\":\"tasks\"}");
            context.sessionTools().getFirst().call().apply("{\"kind\":\"mine\"}");
            context.sessionTools().getFirst().call().apply("{\"kind\":\"history\"}");
            return session;
        });
        when(session.reference()).thenReturn(new AgentSessionRef(LANE, 1, "session"));
        when(session.start(any())).thenReturn(turn);
        when(turn.completion()).thenAnswer(call -> CompletableFuture.completedFuture(
                new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of(answer.get()), Optional.empty(), Duration.ofSeconds(40), true)));
        when(reader.read(any(), any(), anyBoolean(), any(), any())).thenReturn(AgendaFixtures.friday(0));
        when(calendars.today(any(), any())).thenReturn(new AgendaCalendar("used", 5.5, false, ImmutableList.of(), ImmutableList.of()));
        when(done.statuses(any())).thenReturn(ImmutableMap.of());
        Clock clock = new Clock()
        {
            @Override
            public ZoneId getZone()
            {
                return ZONE;
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
        agenda = new AgendaService(store, reader, calendars, teams, done, new AgendaSettings(settings, AgendaFixtures.mapper(), mock(SetupSettingsService.class)), appSettings,
                models, drivers, resources, starts, AgendaFixtures.mapper(), clock, executor);
    }

    @AfterEach
    void stopWorkers()
    {
        executor.shutdownNow();
        executor.close();
    }

    @Test
    void staysOffUntilTheUserTurnsItOn()
    {
        AppSettingsStore nothingSaved = mock(AppSettingsStore.class);
        when(nothingSaved.get(any())).thenReturn(Optional.empty());
        assertThat(new AgendaSettings(nothingSaved, AgendaFixtures.mapper(), mock(SetupSettingsService.class)).schedule().enabled()).isFalse();
    }

    @Test
    void rejectedWorkReleasesTheAgendaSlot()
    {
        executor.shutdownNow();

        assertThatThrownBy(agenda::makeAgain).isInstanceOf(RejectedExecutionException.class);
        assertThat(agenda.today(Optional.empty()).state()).isEqualTo("NONE");
    }

    @Test
    void waitsForTheSetTimeAndAPullRequestSyncAfterIt()
    {
        now.set(FRIDAY_MORNING.minusSeconds(300));
        agenda.runIfDue(Optional.of(FRIDAY_MORNING.minusSeconds(300)));
        assertThat(store.days).isEmpty();

        now.set(FRIDAY_MORNING);
        // A sync from before 09:00 is last night's data.
        agenda.runIfDue(Optional.of(FRIDAY_MORNING.minusSeconds(300)));
        agenda.runIfDue(Optional.empty());
        assertThat(store.days).isEmpty();
        verify(driver, never()).open(any(), any());

        agenda.runIfDue(Optional.of(FRIDAY_MORNING.minusSeconds(30)));
        assertThat(store.days).containsKey(LocalDate.parse("2026-10-16"));

        agenda.runIfDue(Optional.of(FRIDAY_MORNING));
        assertThat(store.runs).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-03-08", "2026-11-01"})
    @ResourceLock("java.util.TimeZone.default")
    void keepsTheScheduledWallClockTimeAcrossDaylightSavingChanges(String date)
            throws Exception
    {
        TimeZone original = TimeZone.getDefault();
        ZoneId zone = ZoneId.of("America/New_York");
        LocalDate day = LocalDate.parse(date);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            AppSettingsStore settings = mock(AppSettingsStore.class);
            AgendaSchedule schedule = new AgendaSchedule(true, 9 * 60, ImmutableList.of(day.getDayOfWeek()), Optional.empty());
            when(settings.get(any())).thenReturn(Optional.of(AgendaFixtures.mapper().writeValueAsString(schedule)));
            now.set(day.atTime(8, 59).atZone(zone).toInstant());
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenAnswer(call -> now.get());
            AgendaService scheduled = new AgendaService(store, reader, calendars, teams, done,
                    new AgendaSettings(settings, AgendaFixtures.mapper(), mock(SetupSettingsService.class)), appSettings,
                    models, drivers, resources, starts, AgendaFixtures.mapper(), clock, executor);

            scheduled.runIfDue(Optional.of(now.get()));
            assertThat(store.days).isEmpty();
            verify(driver, never()).open(any(), any());

            now.set(day.atTime(9, 0).atZone(zone).toInstant());
            scheduled.runIfDue(Optional.of(now.get()));
            assertThat(store.days).containsKey(day);
            assertThat(store.days.get(day).state()).isEqualTo(AgendaDay.State.READY);
            verify(driver).open(any(), any());
        }
        finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void skipsDaysOffAndQuietHours()
    {
        now.set(FRIDAY_MORNING.plus(Duration.ofDays(1)).plusSeconds(28 * 60));
        agenda.runIfDue(Optional.of(FRIDAY_MORNING.plus(Duration.ofDays(1))));
        now.set(FRIDAY_MORNING);
        when(appSettings.inQuietHours()).thenReturn(true);
        agenda.runIfDue(Optional.of(FRIDAY_MORNING));

        assertThat(store.days).isEmpty();
        assertThat(store.runs).isEmpty();
    }

    @Test
    void keepsWhatTheAgentReadAndLogsTheRun()
    {
        now.set(FRIDAY_MORNING);
        when(teams.directory()).thenThrow(new IllegalStateException("GitHub is unreachable"));
        agenda.runIfDue(Optional.of(FRIDAY_MORNING));

        // The account behind the circles is resolved before the snapshot, which reads only cached circles; failing costs only circles.
        InOrder order = inOrder(teams, reader);
        order.verify(teams).directory();
        order.verify(reader).read(any(), any(), anyBoolean(), any(), any());
        AgentSessionContext context = opened.get();
        assertThat(context.access()).isEqualTo(SessionAccess.READ_ONLY);
        assertThat(context.sessionTools().getFirst().name()).isEqualTo("agenda_context");
        assertThat(context.worktree()).startsWith(temporaryDirectory);

        AgendaDay day = store.days.get(LocalDate.parse("2026-10-16"));
        assertThat(day.state()).isEqualTo(AgendaDay.State.READY);
        assertThat(day.model()).isEqualTo("Sonnet");
        assertThat(day.items()).extracting(item -> item.ref()).containsExactly("task:t1", "pr:owner/gateway#612");
        assertThat(day.recap()).hasValueSatisfying(recap -> assertThat(recap.merged()).hasSize(1));
        AgendaRun run = store.runs.getFirst();
        assertThat(run.trigger()).isEqualTo(AgendaRun.Trigger.SCHEDULED);
        assertThat(run.calls()).extracting(AgendaRun.Call::kind).containsExactly("tasks", "mine", "history");
        assertThat(run.picked()).extracting(AgendaRun.Pick::kind).containsExactly("tasks", "mine");
        assertThat(run.dropped()).extracting(AgendaRun.Drop::reason).containsExactly("unknown ref");
        assertThat(run.calendar()).isEqualTo("used");
        assertThat(day.calendar()).hasValueSatisfying(calendar -> assertThat(calendar.busyHours()).isEqualTo(5.5));
    }

    @Test
    void aFailedFirstRunSaysWhyAndIsNotRetriedOnItsOwn()
    {
        now.set(FRIDAY_MORNING);
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED, Optional.empty(),
                "Claude is signed out", AgentCapabilities.none()));
        agenda.runIfDue(Optional.of(FRIDAY_MORNING));
        agenda.runIfDue(Optional.of(FRIDAY_MORNING.plusSeconds(60)));

        AgendaView view = agenda.today(Optional.empty());
        assertThat(view.state()).isEqualTo("FAILED");
        assertThat(view.failure()).isPresent();
        assertThat(store.runs).hasSize(1);
        assertThat(store.runs.getFirst().failure()).isPresent();
    }

    @Test
    void aFailedRemakeKeepsTheReadyAgenda()
    {
        now.set(FRIDAY_MORNING);
        agenda.runIfDue(Optional.of(FRIDAY_MORNING));
        answer.set("Sorry, no agenda today.");
        now.set(FRIDAY_MORNING.plusSeconds(600));
        agenda.makeAgain();
        waitForRuns(2);

        AgendaView view = agenda.today(Optional.empty());
        assertThat(view.state()).isEqualTo("READY");
        assertThat(view.items()).hasSize(2);
        assertThat(view.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("no agenda"));
    }

    @Test
    void aWorkspaceHomeSeesOnlyItsItemsWithoutTheSummaryOrRecap()
    {
        now.set(FRIDAY_MORNING);
        agenda.runIfDue(Optional.of(FRIDAY_MORNING));

        AgendaView global = agenda.today(Optional.empty());
        assertThat(global.summary()).contains("Your conflict-retry fix merged last night.");
        assertThat(global.recap()).isPresent();
        assertThat(global.items()).hasSize(2);

        AgendaView workspace = agenda.today(Optional.of("ws1"));
        assertThat(workspace.items()).extracting(AgendaView.Item::ref).containsExactly("task:t1");
        assertThat(workspace.summary()).isEmpty();
        assertThat(workspace.recap()).isEmpty();
    }

    @Test
    void marksAreTheUsersAndSurviveARemake()
    {
        now.set(FRIDAY_MORNING);
        agenda.runIfDue(Optional.of(FRIDAY_MORNING));
        agenda.mark("task:t1", Optional.of(AgendaDay.Mark.DISMISSED));
        assertThat(store.days.get(LocalDate.parse("2026-10-16")).marks()).containsEntry("task:t1", AgendaDay.Mark.DISMISSED);

        agenda.makeAgain();
        waitForRuns(2);
        assertThat(store.days.get(LocalDate.parse("2026-10-16")).marks()).containsEntry("task:t1", AgendaDay.Mark.DISMISSED);

        agenda.mark("task:t1", Optional.empty());
        assertThat(store.days.get(LocalDate.parse("2026-10-16")).marks()).isEmpty();
    }

    @Test
    void aStartRowStartsItsItemOnceADay()
    {
        answer.set("""
                {"summary": "", "items": [
                  {"ref": "work:w22", "action": "start", "title": "Start “Expose snapshot id in metadata”", "label": "Expose snapshot id", "why": "Ready, and unblocks 2."},
                  {"ref": "task:t1", "action": "answer", "title": "Answer the retry question", "label": "the retry question", "why": "The agent waits on you."}]}
                """);
        // The ready work item is named on the Epics page, so the agent reads that one too.
        doAnswer(call -> {
            AgentSessionContext context = call.getArgument(0);
            context.sessionTools().getFirst().call().apply("{\"kind\":\"tasks\"}");
            context.sessionTools().getFirst().call().apply("{\"kind\":\"epics\"}");
            return session;
        }).when(driver).open(any(), any());
        now.set(FRIDAY_MORNING);
        agenda.runIfDue(Optional.of(FRIDAY_MORNING));
        ItemTaskStarter.Start start = new ItemTaskStarter.Start("ws1", "Develop this work item", Optional.empty(), Optional.empty(), Optional.of("w22"));
        ItemTaskStarter.Started started = new ItemTaskStarter.Started(mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE)), true, "Claude Sonnet", DevelopmentAutomation.DEFAULT);
        when(starts.item("work:w22")).thenReturn(start);
        when(starts.start(eq(start), eq(Optional.empty()), any(), anyString())).thenReturn(Optional.of(started));

        agenda.start("work:w22");
        agenda.start("work:w22");

        // A row starts one task a day: a click repeated after an unknown outcome names the same task and first turn.
        ArgumentCaptor<TaskId> ids = ArgumentCaptor.forClass(TaskId.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(starts, times(2)).start(eq(start), eq(Optional.empty()), ids.capture(), keys.capture());
        assertThat(ids.getAllValues()).containsOnly(ids.getValue());
        assertThat(keys.getAllValues()).containsOnly("agenda:" + ids.getValue().value());

        assertThatThrownBy(() -> agenda.start("task:t1")).isInstanceOf(IllegalArgumentException.class).hasMessage("That row does not start work");
        assertThatThrownBy(() -> agenda.start("work:w99")).isInstanceOf(IllegalArgumentException.class).hasMessage("That item is not on today's agenda");
        when(starts.start(eq(start), eq(Optional.empty()), any(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> agenda.start("work:w22")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("isn't ready");
    }

    private void waitForRuns(int runs)
    {
        await().alias("completed agenda runs").pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(store.runs).hasSizeGreaterThanOrEqualTo(runs);
                    assertThat(agenda.today(Optional.empty()).state()).isNotEqualTo("RUNNING");
                });
    }

    private static final class MemoryStore
            implements AgendaStore
    {
        private final Map<LocalDate, AgendaDay> days = new HashMap<>();
        private final List<AgendaRun> runs = new CopyOnWriteArrayList<>();

        @Override
        public synchronized Optional<AgendaDay> find(LocalDate day)
        {
            return Optional.ofNullable(days.get(day));
        }

        @Override
        public synchronized List<AgendaDay> recent(int limit)
        {
            return days.values().stream().sorted(Comparator.comparing(AgendaDay::day).reversed()).limit(limit).collect(toImmutableList());
        }

        @Override
        public synchronized void save(AgendaDay day)
        {
            days.put(day.day(), day);
        }

        @Override
        public synchronized void record(AgendaRun run)
        {
            runs.add(run);
        }

        @Override
        public synchronized void deleteBefore(LocalDate day)
        {
            days.keySet().removeIf(value -> value.isBefore(day));
            runs.removeIf(run -> run.day().isBefore(day));
        }
    }
}
