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
import com.bytequay.agent.core.OneShotSession;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.app.agent.WorkflowAgentTasks;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.team.TeamService;
import com.bytequay.core.domain.AgendaCalendar;
import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaItem;
import com.bytequay.core.domain.AgendaRun;
import com.bytequay.core.store.AgendaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.bytequay.app.agenda.AgendaReader.clip;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Makes each working day's agenda in a disposable read-only session whose only tool is {@code agenda_context}, keeps what the app
 * can stand behind, and answers Home with today's items and whether stored facts have closed them.
 */
@Service
public class AgendaService
{
    private static final Logger log = LoggerFactory.getLogger(AgendaService.class);
    private static final Duration MAXIMUM_RUNTIME = Duration.ofMinutes(5);
    // With no earlier agenda, "new" reaches back over a weekend.
    public static final Duration FIRST_LOOK = Duration.ofDays(3);
    public static final int EARLIER_DAYS = 6;
    private static final int RETAINED_DAYS = 30;
    private static final int REASON = 200;
    private static final String INSTRUCTIONS = """
            You plan the user's working day in ByteQuay.
            %s
            Suggest at most five things the user should do today, most important first: unblock others before starting new work, answer people
            waiting on the user, finish work that is close to done, then start the next ready Epic item. Do not repeat an earlier day's open item
            unless something about it changed, and then say what changed. When overview has the calendar's busy times, fit the list to the free
            time: fewer items on a full day, a short review or reply in a short free block, and only urgent asks on a day off. Use only refs
            agenda_context returned. Reply with one JSON object only.
            """.formatted(AgendaPages.INSTRUCTION.strip());
    private static final String REPLY = """
            Plan today's agenda with agenda_context, then reply with only this JSON object:
            {"summary": "one or two sentences, at most 240 characters",
             "items": [{"ref": "a ref agenda_context returned",
                        "action": "start | reply | review | fix | merge | answer | decide | follow_up",
                        "title": "at most 80 characters, starting with a verb",
                        "label": "at most 32 characters, the title without its verb",
                        "why": "at most 200 characters: the fact that makes it matter today"}]}
            Give zero to five items: fewer on a quiet day, never padding. start fits work, issue and jira refs; review fits other people's pr
            refs and review refs; reply fits pr, task and jira refs; fix and merge fit the user's pr refs and task refs; answer fits task and
            review refs; decide fits epic refs; follow_up fits any ref.
            """;
    private static final String RECAP = """
            Today is the last working day of the week: also add "recap": {"text": "at most 400 characters looking back over the week",
            "merged": [refs], "slipped": [refs]}, using the week that kind history returns.
            """;

    private final AgendaStore store;
    private final AgendaReader reader;
    private final AgendaCalendarReader calendars;
    private final TeamService teams;
    private final AgendaDone done;
    private final AgendaSettings settings;
    private final AppSettingsService appSettings;
    private final ModelSettingsService models;
    private final AgentDriverRegistry drivers;
    private final AgentResourceService resources;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Instant started;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Optional<Failure> lastFailure = Optional.empty();

    public AgendaService(AgendaStore store, AgendaReader reader, AgendaCalendarReader calendars, TeamService teams, AgendaDone done, AgendaSettings settings,
            AppSettingsService appSettings, ModelSettingsService models, AgentDriverRegistry drivers, AgentResourceService resources, ObjectMapper mapper, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.reader = requireNonNull(reader, "reader is null");
        this.calendars = requireNonNull(calendars, "calendars is null");
        this.teams = requireNonNull(teams, "teams is null");
        this.done = requireNonNull(done, "done is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.models = requireNonNull(models, "models is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.resources = requireNonNull(resources, "resources is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.started = clock.instant();
    }

    /** A ready agenda's failed Make again, which keeps the agenda it could not replace. */
    private record Failure(String reason, Instant at) {}

    /** @param workspaceId a workspace Home's own items only, without the summary and recap written across every repository */
    public AgendaView today(Optional<String> workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        AgendaSchedule schedule = settings.schedule();
        LocalDate today = today();
        Optional<AgendaDay> day = store.find(today);
        String state = running.get() ? "RUNNING" : day.map(value -> value.state().name()).orElse("NONE");
        Optional<AgendaDay> ready = day.filter(value -> value.state() == AgendaDay.State.READY);
        List<AgendaView.Item> items = ready.map(value -> {
            Map<String, AgendaDone.Status> statuses = done.statuses(value);
            return value.items().stream()
                    .filter(item -> workspaceId.isEmpty() || item.workspaceId().equals(workspaceId))
                    .map(item -> item(item, statuses.getOrDefault(item.ref(), AgendaDone.Status.OPEN)))
                    .collect(toImmutableList());
        }).orElse(ImmutableList.of());
        Optional<String> failure = day.filter(value -> value.state() == AgendaDay.State.FAILED).flatMap(AgendaDay::failure)
                .or(() -> lastFailure.filter(value -> ready.filter(agenda -> value.at().isAfter(agenda.generatedAt())).isPresent()).map(Failure::reason));
        boolean global = workspaceId.isEmpty();
        return new AgendaView(today.toString(), state, schedule.enabled(), schedule.minute(), day.map(AgendaDay::generatedAt), day.map(AgendaDay::model),
                ready.filter(ignored -> global).map(AgendaDay::summary), items, ready.filter(ignored -> global).flatMap(AgendaDay::recap),
                ready.flatMap(AgendaDay::calendar), failure);
    }

    /** Makes today's agenda again now, on any day; the agenda already shown stays until the new one is ready. */
    public AgendaView makeAgain()
    {
        if (!running.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Today's agenda is already being made");
        }
        Thread.startVirtualThread(() -> {
            try {
                run(AgendaRun.Trigger.MAKE_AGAIN);
            }
            finally {
                running.set(false);
            }
        });
        return today(Optional.empty());
    }

    /** Marks one of today's items done or set aside; an empty mark lets stored facts decide again. */
    public AgendaView mark(String ref, Optional<AgendaDay.Mark> mark)
    {
        requireNonNull(ref, "ref is null");
        requireNonNull(mark, "mark is null");
        LocalDate today = today();
        AgendaDay day = store.find(today).filter(value -> value.state() == AgendaDay.State.READY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Today's agenda is not ready"));
        checkArgument(day.items().stream().anyMatch(item -> item.ref().equals(ref)), "That item is not on today's agenda");
        Map<String, AgendaDay.Mark> marks = new HashMap<>(day.marks());
        mark.ifPresentOrElse(value -> marks.put(ref, value), () -> marks.remove(ref));
        store.save(day.withMarks(marks));
        return today(Optional.empty());
    }

    /**
     * Makes today's agenda when the schedule says it is due: a working day, after the set time and outside quiet hours, with no agenda
     * yet, and with pull requests synced since the later of the set time and this backend's start, so it never plans on last night's data.
     */
    void runIfDue(Optional<Instant> lastSync)
    {
        AgendaSchedule schedule = settings.schedule();
        LocalDate today = today();
        if (!schedule.enabled() || !schedule.workingDay(today.getDayOfWeek())) {
            return;
        }
        Instant due = today.atStartOfDay(ZoneId.systemDefault()).plusMinutes(schedule.minute()).toInstant();
        Instant fresh = due.isAfter(started) ? due : started;
        if (clock.instant().isBefore(due) || lastSync.filter(at -> !at.isBefore(fresh)).isEmpty() || appSettings.inQuietHours() || store.find(today).isPresent()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            run(AgendaRun.Trigger.SCHEDULED);
        }
        finally {
            running.set(false);
        }
    }

    // The shared clock is UTC; the agenda's day and set time follow the user's wall clock, as quiet hours do.
    private LocalDate today()
    {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    private void run(AgendaRun.Trigger trigger)
    {
        LocalDate today = today();
        Instant startedAt = clock.instant();
        List<AgendaDay> earlier = store.recent(EARLIER_DAYS + 1);
        Instant since = earlier.stream().filter(day -> day.day().isBefore(today) && day.state() == AgendaDay.State.READY)
                .map(AgendaDay::generatedAt).findFirst().orElse(startedAt.minus(FIRST_LOOK));
        AgendaSchedule schedule = settings.schedule();
        boolean recapDay = schedule.recapDay().filter(today.getDayOfWeek()::equals).isPresent();
        Optional<AgentModelSetting> selected = Optional.empty();
        Optional<AgendaPages> pages = Optional.empty();
        Optional<AgendaCalendar> calendar = Optional.empty();
        try {
            AgentModelSetting model = models.agentModel(AgentModelType.DEVELOPMENT, Optional.empty());
            selected = Optional.of(model);
            AgentLaneId lane = new AgentLaneId(model.configId());
            drivers.checkWorkspaceAccess(lane, Optional.empty());
            WorkflowAgentTasks.requireReady(drivers, lane, AgentPermissionPolicy.AUTO, Optional.of(model.model()));
            AgendaCalendar busy = calendars.today(schedule, today);
            calendar = Optional.of(busy);
            resolveCircleAccount();
            AgendaSnapshot snapshot = reader.read(today, since, recapDay, earlier, busy);
            AgendaPages session = new AgendaPages(snapshot, mapper);
            pages = Optional.of(session);
            AgentTurnResult result = OneShotSession.run(resources, drivers.require(lane), (taskId, scratch) -> new AgentSessionContext(
                            taskId.toString(), OneShotSession.scratchWorktree(scratch, "agenda-worktree"), scratch.runtimeDirectory(), scratch.providerDirectory(),
                            AgentPermissionPolicy.AUTO, Optional.empty(), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                            Optional.of(model.model()), model.effort(), SessionAccess.READ_ONLY, Optional.empty(), INSTRUCTIONS, Optional.empty(),
                            ImmutableList.of(session.tool())),
                    REPLY + (recapDay ? RECAP : ""), MAXIMUM_RUNTIME, turn -> {}).result();
            if (result.state() != AgentTurnResult.State.COMPLETED) {
                throw new AgentUnavailableException(result.terminalReason().orElse("The agent stopped before it answered"));
            }
            Set<String> mine = snapshot.mine().stream().map(AgendaSnapshot.Mine::ref).collect(toImmutableSet());
            AgendaReply reply = AgendaReply.parse(result.finalResponse().orElse(""), snapshot, session.served(), mine, mapper);
            Map<String, AgendaDay.Mark> marks = store.find(today).map(day -> kept(day.marks(), reply.items())).orElse(ImmutableMap.of());
            store.save(new AgendaDay(today, AgendaDay.State.READY, clock.instant(), since, lane.value(), model.modelLabel(), reply.summary(), reply.items(), reply.recap(),
                    marks, calendar, Optional.empty()));
            lastFailure = Optional.empty();
            record(today, startedAt, AgendaDay.State.READY, trigger, selected, session.calls(), reply.picked(), reply.dropped(), calendar, Optional.empty());
        }
        catch (RuntimeException failure) {
            String reason = clip(Optional.ofNullable(failure.getMessage()).filter(message -> !message.isBlank()).orElse("The agenda could not be made"), REASON);
            log.warn("Could not make the agenda for {}: {}", today, reason, failure);
            if (store.find(today).filter(day -> day.state() == AgendaDay.State.READY).isPresent()) {
                lastFailure = Optional.of(new Failure(reason, clock.instant()));
            }
            else {
                store.save(new AgendaDay(today, AgendaDay.State.FAILED, clock.instant(), since, selected.map(AgentModelSetting::configId).orElse(""),
                        selected.map(AgentModelSetting::modelLabel).orElse(""), "", ImmutableList.of(), Optional.empty(), ImmutableMap.of(), calendar, Optional.of(reason)));
            }
            record(today, startedAt, AgendaDay.State.FAILED, trigger, selected, pages.map(AgendaPages::calls).orElse(ImmutableList.of()), ImmutableList.of(),
                    ImmutableList.of(), calendar, Optional.of(reason));
        }
        store.deleteBefore(today.minusDays(RETAINED_DAYS));
    }

    /**
     * The reader takes only circles already read, because the assistant reads it inside a turn. This run may still ask GitHub which
     * account owns them, before its turn starts, so an agenda made soon after a start keeps them.
     */
    private void resolveCircleAccount()
    {
        try {
            teams.directory();
        }
        catch (RuntimeException unresolved) {
            log.warn("Agenda could not resolve the account for circles: {}", unresolved.getMessage());
        }
    }

    private void record(LocalDate today, Instant startedAt, AgendaDay.State state, AgendaRun.Trigger trigger, Optional<AgentModelSetting> model,
            List<AgendaRun.Call> calls, List<AgendaRun.Pick> picked, List<AgendaRun.Drop> dropped, Optional<AgendaCalendar> calendar, Optional<String> failure)
    {
        try {
            store.record(new AgendaRun(UUID.randomUUID().toString(), today, startedAt, Duration.between(startedAt, clock.instant()).toMillis(), state, trigger,
                    model.map(AgentModelSetting::configId).orElse(""), model.map(AgentModelSetting::model).orElse(""), calls, picked, dropped,
                    calendar.map(AgendaCalendar::state).orElse("off"), failure));
        }
        catch (RuntimeException unrecorded) {
            log.warn("Could not record the agenda run for {}", today, unrecorded);
        }
    }

    /** The marks of items made again: a ref that stays keeps what the user said about it. */
    private static Map<String, AgendaDay.Mark> kept(Map<String, AgendaDay.Mark> marks, List<AgendaItem> items)
    {
        Set<String> refs = items.stream().map(AgendaItem::ref).collect(toImmutableSet());
        return marks.entrySet().stream().filter(entry -> refs.contains(entry.getKey())).collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static AgendaView.Item item(AgendaItem item, AgendaDone.Status status)
    {
        return new AgendaView.Item(item.ref(), item.action().name().toLowerCase(Locale.ROOT), item.title(), item.label(), item.why(), item.at(), item.workspaceId(),
                item.target(), item.where(), status.state().name().toLowerCase(Locale.ROOT), status.byYou(), status.text(), status.at());
    }
}
