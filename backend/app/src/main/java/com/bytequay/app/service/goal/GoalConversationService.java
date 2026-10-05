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
package com.bytequay.app.service.goal;

import com.bytequay.app.context.ContextText;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.store.GoalExchangeStore;
import com.bytequay.core.store.GoalRecommendationStore;
import com.bytequay.core.store.GoalTopicStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * A goal's conversation with its agent. Each message is saved before the agent is asked and answered off the request thread, one at a
 * time per goal and two at a time across the app. Nothing here starts work, creates a branch or writes to GitHub or Jira.
 */
@Service
public class GoalConversationService
{
    static final int MAX_TITLE = 120;
    static final String INTERRUPTED = "ByteQuay stopped before the agent answered.";
    static final String STOPPED = "Stopped before the agent answered.";
    static final String REASSESS = "The user asked for an update: reassess the goal from its current facts.";
    /** Background events wait this long after a goal's last automatic exchange, so a busy task does not ask the agent on every turn. */
    static final Duration EVENT_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_HELD_SHOWN = 6;
    private static final int MAX_HELD_EVENT = 600;
    // An accepted result repeats the criteria and every merged PR; the agent needs its gist.
    private static final int MAX_DECISION = 600;
    private static final Logger log = LoggerFactory.getLogger(GoalConversationService.class);

    private final GoalService goals;
    private final WorkItemService work;
    private final GoalExchangeStore exchanges;
    private final GoalRecommendationStore recommendations;
    private final WorkspaceStore workspaces;
    private final GoalCandidates candidates;
    private final ObjectProvider<GoalResearchService> research;
    private final ObjectProvider<GoalSpecChecks> specChecks;
    private final GoalQuestions questions;
    private final GoalTopicStore topics;
    private final ModelSettingsService models;
    private final GoalPlanner planner;
    private final AppSettingsService settings;
    private final Clock clock;
    private final Executor executor;
    // The requests this process is answering; a pending exchange outside it was interrupted.
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Object writes = new Object();
    // Guarded by writes. ponytail: held events live in memory, so a restart drops them; the next request still reads the recorded facts.
    private final Map<String, List<Held>> held = new HashMap<>();
    private final Set<String> told = new HashSet<>();
    private final Set<String> waking = new HashSet<>();
    private final Set<String> summarizing = new HashSet<>();

    /**
     * An event the goal's agent has not heard yet; a decision is told as soon as the agent is free.
     *
     * @param reportOn the work a routine report is about, such as a turn that finished normally, which asks the agent nothing by itself
     */
    private record Held(String workspaceId, String operationId, String text, boolean decision, Optional<String> reportOn)
    {
        boolean routine()
        {
            return reportOn.isPresent();
        }
    }

    @Autowired
    public GoalConversationService(GoalService goals, WorkItemService work, GoalExchangeStore exchanges, GoalRecommendationStore recommendations,
            WorkspaceStore workspaces, GoalCandidates candidates, ObjectProvider<GoalResearchService> research, ObjectProvider<GoalSpecChecks> specChecks,
            GoalQuestions questions, GoalTopicStore topics, ModelSettingsService models, GoalPlanner planner, AppSettingsService settings, Clock clock)
    {
        this(goals, work, exchanges, recommendations, workspaces, candidates, research, specChecks, questions, topics, models, planner, settings, clock,
                Executors.newFixedThreadPool(2, Thread.ofVirtual().name("goal-agent-", 0).factory()));
    }

    GoalConversationService(GoalService goals, WorkItemService work, GoalExchangeStore exchanges, GoalRecommendationStore recommendations,
            WorkspaceStore workspaces, GoalCandidates candidates, ObjectProvider<GoalResearchService> research, ObjectProvider<GoalSpecChecks> specChecks,
            GoalQuestions questions, GoalTopicStore topics, ModelSettingsService models, GoalPlanner planner, AppSettingsService settings, Clock clock,
            Executor executor)
    {
        this.goals = requireNonNull(goals, "goals is null");
        this.work = requireNonNull(work, "work is null");
        this.exchanges = requireNonNull(exchanges, "exchanges is null");
        this.recommendations = requireNonNull(recommendations, "recommendations is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.candidates = requireNonNull(candidates, "candidates is null");
        this.research = requireNonNull(research, "research is null");
        this.specChecks = requireNonNull(specChecks, "specChecks is null");
        this.questions = requireNonNull(questions, "questions is null");
        this.topics = requireNonNull(topics, "topics is null");
        this.models = requireNonNull(models, "models is null");
        this.planner = requireNonNull(planner, "planner is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /** The goal's exchanges, oldest first. */
    public List<GoalExchange> list(String workspaceId, String goalId)
    {
        goal(workspaceId, goalId);
        return exchanges.list(goalId);
    }

    /** The model the goal's agent answers and writes lessons with. */
    public AgentModelSetting model(String workspaceId, String goalId)
    {
        goal(workspaceId, goalId);
        return models.goalModel(goalId, workspaceId);
    }

    /** Gives the goal its own model and effort in place of the workspace's Development model. */
    public AgentModelSetting chooseModel(String workspaceId, String goalId, String configId, String model, Optional<String> effort)
    {
        goal(workspaceId, goalId);
        return models.setGoalModel(goalId, workspaceId, configId, model, effort);
    }

    /** Makes the goal follow its workspace's Development model again. */
    public AgentModelSetting followWorkspaceModel(String workspaceId, String goalId)
    {
        goal(workspaceId, goalId);
        return models.clearGoalModel(goalId, workspaceId);
    }

    /** Starts a goal from the user's first answer, makes it the one the pages follow, and asks its agent. */
    public GoalView start(String workspaceId, String operationId, String message)
    {
        GoalExchange exchange;
        synchronized (writes) {
            Optional<GoalExchange> existing = exchanges.find(operationId);
            if (existing.isPresent()) {
                checkArgument(existing.get().workspaceId().equals(workspaceId), "The operation belongs to another workspace.");
                return goal(workspaceId, existing.get().goalId());
            }
            String text = message(message);
            GoalView created = goals.create(workspaceId, title(text), "");
            GoalView pinned = goals.update(workspaceId, created.id(), Optional.empty(), Optional.empty(), true);
            exchange = GoalExchange.ask(operationId, pinned.id(), workspaceId, text, pinned.revision(), clock.instant());
            exchanges.save(exchange);
        }
        dispatch(exchange);
        return goal(workspaceId, exchange.goalId());
    }

    /** Sends the agent a message; resending the same operation returns the conversation without asking again. */
    public List<GoalExchange> ask(String workspaceId, String goalId, String operationId, String message)
    {
        return ask(workspaceId, goalId, operationId, message, Optional.empty());
    }

    /** Sends the agent a message about the lesson the user has open, whose content the agent reads with it. */
    public List<GoalExchange> ask(String workspaceId, String goalId, String operationId, String message, Optional<String> lessonId)
    {
        return send(workspaceId, goalId, operationId, goal -> GoalExchange.ask(operationId, goalId, workspaceId, message(message), goal.revision(), clock.instant())
                .about(lessonId));
    }

    /** Update now: asks the agent to reassess the goal from its current facts, also while guidance is paused. */
    public List<GoalExchange> reassess(String workspaceId, String goalId, String operationId)
    {
        // Held events go with the request instead of waiting for a pass of their own.
        return send(workspaceId, goalId, operationId, goal -> GoalExchange.told(operationId, goalId, workspaceId,
                held.containsKey(goalId) ? REASSESS + "\n\n" + events(release(goalId)) : REASSESS, goal.revision(), clock.instant()));
    }

    private List<GoalExchange> send(String workspaceId, String goalId, String operationId, Function<GoalView, GoalExchange> request)
    {
        GoalExchange exchange;
        synchronized (writes) {
            GoalView goal = goal(workspaceId, goalId);
            Optional<GoalExchange> existing = exchanges.find(operationId);
            if (existing.isPresent()) {
                checkArgument(existing.get().goalId().equals(goalId), "The operation belongs to another goal.");
                return exchanges.list(goalId);
            }
            if (exchanges.list(goalId).stream().anyMatch(earlier -> earlier.state() == GoalExchange.State.PENDING)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The agent is still answering your last message.");
            }
            exchange = request.apply(goal);
            exchanges.save(exchange);
        }
        dispatch(exchange);
        return exchanges.list(goalId);
    }

    /**
     * Tells the goal's agent about a background event, such as a failed turn, a merge or a finished run: held while the agent answers, until
     * five minutes after the goal's last automatic exchange and through the quiet hours, then told with the others in one exchange. A paused
     * goal hears nothing, and repeating an operation tells it once.
     */
    public void tell(String workspaceId, String goalId, String operationId, String event)
    {
        hold(goalId, new Held(workspaceId, operationId, event, false, Optional.empty()));
    }

    /** Tells the goal's agent about a decision, the user's or one that set its pick aside, as soon as it is free; held events go with it. */
    public void tellDecision(String workspaceId, String goalId, String operationId, String event)
    {
        hold(goalId, new Held(workspaceId, operationId, event, true, Optional.empty()));
    }

    /**
     * Reports routine progress on the goal's work, such as a turn that finished normally: held without asking the agent, it goes with the
     * next event or decision the agent is told, or with Update now, and a newer report on the same work replaces it.
     */
    public void report(String workspaceId, String goalId, String operationId, String workItemId, String event)
    {
        hold(goalId, new Held(workspaceId, operationId, event, false, Optional.of(workItemId)));
    }

    private void hold(String goalId, Held event)
    {
        synchronized (writes) {
            if (goal(event.workspaceId(), goalId).paused() || told.contains(event.operationId()) || exchanges.find(event.operationId()).isPresent()) {
                return;
            }
            List<Held> waiting = held.computeIfAbsent(goalId, key -> new ArrayList<>());
            if (waiting.stream().noneMatch(earlier -> earlier.operationId().equals(event.operationId()))) {
                if (event.routine()) {
                    waiting.removeIf(earlier -> earlier.reportOn().equals(event.reportOn()));
                }
                waiting.add(event);
            }
        }
        flush(goalId);
    }

    /**
     * Tells a goal's held events in one exchange once its agent is free and, unless a decision waits, five minutes after its last automatic
     * exchange and outside the quiet hours; until then it wakes itself when the wait ends. Routine reports alone wait for something else.
     */
    void flush(String goalId)
    {
        GoalExchange exchange;
        synchronized (writes) {
            List<Held> waiting = held.getOrDefault(goalId, ImmutableList.of());
            if (waiting.isEmpty()) {
                return;
            }
            Optional<GoalView> goal = goals.list(waiting.getFirst().workspaceId()).stream().filter(found -> found.id().equals(goalId)).findFirst();
            if (goal.isEmpty() || goal.orElseThrow().paused()) {
                held.remove(goalId);
                return;
            }
            List<GoalExchange> conversation = exchanges.list(goalId);
            // An answer in progress flushes again when it settles.
            if (conversation.stream().anyMatch(earlier -> earlier.state() == GoalExchange.State.PENDING)) {
                return;
            }
            Instant now = clock.instant();
            if (waiting.stream().noneMatch(Held::decision)) {
                if (waiting.stream().allMatch(Held::routine)) {
                    return;
                }
                Optional<Instant> window = conversation.stream().filter(GoalExchange::automatic).map(GoalExchange::askedAt).max(Comparator.naturalOrder())
                        .map(last -> last.plus(EVENT_WINDOW));
                Optional<Instant> opens = Stream.of(window, settings.quietUntil()).flatMap(Optional::stream).filter(now::isBefore).max(Comparator.naturalOrder());
                if (opens.isPresent()) {
                    if (waking.add(goalId)) {
                        CompletableFuture.delayedExecutor(Duration.between(now, opens.orElseThrow()).toMillis(), TimeUnit.MILLISECONDS, executor).execute(() -> wake(goalId));
                    }
                    return;
                }
            }
            String first = waiting.getFirst().operationId();
            exchange = GoalExchange.told(first, goalId, waiting.getFirst().workspaceId(), events(release(goalId)), goal.orElseThrow().revision(), now);
            exchanges.save(exchange);
        }
        dispatch(exchange);
    }

    private void wake(String goalId)
    {
        synchronized (writes) {
            waking.remove(goalId);
        }
        try {
            flush(goalId);
        }
        catch (RuntimeException failure) {
            log.warn("The held events of goal {} were not told", goalId, failure);
        }
    }

    /** Takes a goal's held events, remembering them as told. */
    private List<Held> release(String goalId)
    {
        List<Held> events = held.remove(goalId);
        // ponytail: told operations are kept for the life of the process, a few bytes each.
        events.forEach(event -> told.add(event.operationId()));
        return events;
    }

    /** One event as it was, or several numbered with the newest last, within an exchange's message limit. */
    private static String events(List<Held> events)
    {
        if (events.size() == 1) {
            return events.getFirst().text();
        }
        List<Held> shown = events.subList(Math.max(0, events.size() - MAX_HELD_SHOWN), events.size());
        StringBuilder text = new StringBuilder(events.size() + " events since you last heard" + (shown.size() < events.size() ? ", the newest " + shown.size() : "") + ":");
        for (int index = 0; index < shown.size(); index++) {
            text.append('\n').append(index + 1).append(". ").append(ContextText.excerpt(shown.get(index).text(), MAX_HELD_EVENT));
        }
        return text.toString();
    }

    /**
     * Stops the reply the agent is writing: the message fails as stopped, so Ask again can send it again, and the model's turn is cancelled.
     * A late answer is never saved, since only the request a job was given is settled. Stopping a settled message changes nothing.
     */
    public List<GoalExchange> stop(String workspaceId, String goalId, String exchangeId)
    {
        boolean stopped = false;
        synchronized (writes) {
            goal(workspaceId, goalId);
            GoalExchange exchange = exchanges.find(exchangeId).filter(found -> found.goalId().equals(goalId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
            if (exchange.state() == GoalExchange.State.PENDING) {
                exchanges.save(exchange.failed(exchange.model(), STOPPED, clock.instant()));
                stopped = true;
            }
        }
        if (stopped) {
            planner.cancel(exchangeId);
            flush(goalId);
        }
        return exchanges.list(goalId);
    }

    /** Asks again for the goal's last message after it failed or was interrupted. */
    public List<GoalExchange> retry(String workspaceId, String goalId, String exchangeId)
    {
        GoalExchange retried;
        synchronized (writes) {
            GoalView goal = goal(workspaceId, goalId);
            List<GoalExchange> conversation = exchanges.list(goalId);
            GoalExchange exchange = conversation.stream().filter(candidate -> candidate.id().equals(exchangeId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
            if (exchange.state() == GoalExchange.State.PENDING) {
                return conversation;
            }
            if (exchange.state() != GoalExchange.State.FAILED || !conversation.getLast().id().equals(exchangeId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Only the last message can be asked again, after it failed.");
            }
            retried = exchange.retried(goal.revision());
            exchanges.save(retried);
        }
        dispatch(retried);
        return exchanges.list(goalId);
    }

    /** Accepts the definition an answer proposed, optionally under a title the user edited, and lets the agent say what comes next. */
    public GoalView accept(String workspaceId, String goalId, String exchangeId, Optional<String> title)
    {
        GoalExchange exchange = exchanges.find(exchangeId).filter(candidate -> candidate.goalId().equals(goalId) && candidate.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        GoalExchange.Proposal proposal = exchange.reply().flatMap(GoalExchange.Reply::proposal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This answer proposes no definition."));
        GoalDefinition definition = new GoalDefinition(proposal.successDefinition(), proposal.startingKnowledge(), proposal.constraints(), exchangeId, clock.instant());
        GoalView defined = goals.define(workspaceId, goalId, exchange.inputRevision(), title.filter(value -> !value.isBlank()).orElse(proposal.title()),
                proposal.objective(), definition);
        tellDecision(workspaceId, goalId, "accepted:" + exchangeId, "The user accepted the goal definition.");
        return defined;
    }

    /**
     * Accepts the course an answer proposed: each new lesson becomes a Learn work item of the goal, in order, and nothing starts. A revision
     * keeps the accepted lessons it names and drops the unfinished ones it leaves out, with its reason. Accepting it again changes nothing.
     */
    public GoalView acceptCourse(String workspaceId, String goalId, String exchangeId)
    {
        GoalExchange exchange = exchanges.find(exchangeId).filter(candidate -> candidate.goalId().equals(goalId) && candidate.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        GoalCourse.Draft draft = exchange.reply().flatMap(GoalExchange.Reply::course)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This answer proposes no course."));
        GoalView goal = goal(workspaceId, goalId);
        Optional<GoalCourse> accepted = goal.course().filter(course -> !course.exchangeId().equals(exchangeId));
        // A course proposed before the accepted one cannot replace it.
        if (accepted.flatMap(course -> exchanges.find(course.exchangeId())).filter(newer -> newer.askedAt().isAfter(exchange.askedAt())).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A newer course is already accepted. Ask the agent again.");
        }
        Optional<String> invalid = accepted.isPresent() ? invalidRevision(goal, draft) : Optional.empty();
        if (invalid.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, invalid.orElseThrow() + " Ask the agent again.");
        }
        ImmutableList.Builder<GoalCourse.Part> parts = ImmutableList.builder();
        int index = 0;
        for (GoalCourse.DraftPart part : draft.parts()) {
            ImmutableList.Builder<String> lessons = ImmutableList.builder();
            for (GoalCourse.DraftLesson lesson : part.lessons()) {
                // The same lesson of the same proposal is the same work item, so a repeated acceptance creates nothing new.
                String id = lesson.lessonId().orElse(UUID.nameUUIDFromBytes(("lesson:" + exchangeId + ":" + index).getBytes(UTF_8)).toString());
                index++;
                if (lesson.lessonId().isEmpty()) {
                    work.create(workspaceId, id, Optional.of(goalId), lesson.title(), WorkItem.Kind.LEARN, lesson.outcome(), WorkItem.CompletionRule.ACCEPTED_RESULT);
                }
                lessons.add(id);
            }
            parts.add(new GoalCourse.Part(part.title(), lessons.build()));
        }
        GoalCourse course = new GoalCourse(exchangeId, parts.build(), clock.instant(), Optional.empty());
        goals.planCourse(workspaceId, goalId, course);
        Set<String> kept = course.parts().stream().flatMap(part -> part.lessons().stream()).collect(toImmutableSet());
        for (WorkItemView left : goal.items()) {
            boolean dropped = accepted.stream().flatMap(old -> old.parts().stream()).anyMatch(part -> part.lessons().contains(left.id())) && !kept.contains(left.id());
            if (dropped && left.state() != WorkItem.State.DONE && left.state() != WorkItem.State.DROPPED) {
                work.change(workspaceId, left.id(), new WorkItemCommand("course:" + exchangeId + ":" + left.id(), left.revision(), WorkItemCommand.Action.DROP,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        "Left out of the revised course" + draft.reason().map(reason -> ": " + reason).orElse("."),
                        // Accepting the revision is the user's decision about the work that waited on the lesson.
                        Optional.empty(), Optional.empty(), true));
            }
        }
        tellDecision(workspaceId, goalId, "course:" + exchangeId, accepted.isPresent() ? "The user accepted the revised course." : "The user accepted the course.");
        return goal(workspaceId, goalId);
    }

    /** Why a revision cannot replace the accepted course: it names a lesson the course does not hold, or leaves out a finished one. */
    private static Optional<String> invalidRevision(GoalView goal, GoalCourse.Draft draft)
    {
        Set<String> accepted = goal.course().stream().flatMap(course -> course.parts().stream()).flatMap(part -> part.lessons().stream()).collect(toImmutableSet());
        Set<String> kept = draft.parts().stream().flatMap(part -> part.lessons().stream()).flatMap(lesson -> lesson.lessonId().stream()).collect(toImmutableSet());
        if (!accepted.containsAll(kept)) {
            return Optional.of("The revised course names a lesson the course does not hold.");
        }
        boolean leavesFinished = goal.items().stream().anyMatch(item -> accepted.contains(item.id()) && item.state() == WorkItem.State.DONE && !kept.contains(item.id()));
        return leavesFinished ? Optional.of("The revised course leaves out a finished lesson.") : Optional.empty();
    }

    /**
     * Accepts the plan change an answer proposed: each work item it names takes its new priority and criteria and waits on the open work it
     * names, and the open work takes its new order. Only what still differs from the work as it stands is applied, each as the user's
     * decision in its item's history, so accepting again changes nothing. A newer proposal replaces it.
     */
    public GoalView acceptPlan(String workspaceId, String goalId, String exchangeId)
    {
        GoalExchange exchange = exchanges.find(exchangeId).filter(candidate -> candidate.goalId().equals(goalId) && candidate.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        GoalExchange.PlanChange plan = exchange.reply().flatMap(GoalExchange.Reply::plan)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This answer proposes no plan change."));
        if (exchanges.list(goalId).stream().anyMatch(other -> other.askedAt().isAfter(exchange.askedAt()) && other.reply().flatMap(GoalExchange.Reply::plan).isPresent())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A newer plan change was proposed. Accept that one or ask again.");
        }
        GoalView goal = goal(workspaceId, goalId);
        // The work may have moved on since the agent proposed this.
        Optional<String> invalid = invalidPlan(goal, plan);
        if (invalid.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, invalid.orElseThrow() + " Ask the agent again.");
        }
        Map<String, WorkItemView> items = goal.items().stream().collect(toImmutableMap(WorkItemView::id, item -> item));
        Map<String, Long> revisions = new HashMap<>();
        items.values().forEach(item -> revisions.put(item.id(), item.revision()));
        String step = "plan:" + exchangeId + ":";
        // What the change stops work waiting on goes first, so no step on the way closes a cycle the whole change does not.
        for (GoalExchange.WorkChange change : plan.changes()) {
            WorkItemView item = items.get(change.workItemId());
            for (String blocker : item.blockedBy()) {
                if (change.waitsOn().filter(waits -> !waits.contains(blocker)).isPresent()) {
                    decide(workspaceId, item.id(), revisions, step + item.id() + ":unblock:" + blocker, WorkItemCommand.Action.UNBLOCK, Optional.empty(),
                            Optional.empty(), Optional.of(blocker));
                }
            }
        }
        for (GoalExchange.WorkChange change : plan.changes()) {
            WorkItemView item = items.get(change.workItemId());
            for (String blocker : change.waitsOn().orElse(ImmutableList.of())) {
                if (!item.blockedBy().contains(blocker)) {
                    decide(workspaceId, item.id(), revisions, step + item.id() + ":block:" + blocker, WorkItemCommand.Action.BLOCK, Optional.empty(),
                            Optional.empty(), Optional.of(blocker));
                }
            }
            if (change.priority().filter(priority -> priority != item.priority()).isPresent()) {
                decide(workspaceId, item.id(), revisions, step + item.id() + ":priority", WorkItemCommand.Action.PRIORITIZE, Optional.empty(), change.priority(),
                        Optional.empty());
            }
            if (change.acceptanceCriteria().filter(criteria -> !criteria.strip().equals(item.acceptanceCriteria())).isPresent()) {
                decide(workspaceId, item.id(), revisions, step + item.id() + ":criteria", WorkItemCommand.Action.EDIT, change.acceptanceCriteria(), Optional.empty(),
                        Optional.empty());
            }
        }
        if (plan.order().isPresent()) {
            // The open work takes the places open work holds now, so concluded work keeps its own.
            List<WorkItemView> ranked = goal.items().stream().sorted(Comparator.comparingLong(WorkItemView::rank).thenComparing(WorkItemView::createdAt))
                    .collect(toImmutableList());
            ImmutableList.Builder<String> ordered = ImmutableList.builder();
            int next = 0;
            for (WorkItemView item : ranked) {
                ordered.add(open(item) ? plan.order().orElseThrow().get(next++) : item.id());
            }
            List<String> order = ordered.build();
            if (!order.equals(ranked.stream().map(WorkItemView::id).collect(toImmutableList()))) {
                work.order(workspaceId, goalId, order);
            }
        }
        tellDecision(workspaceId, goalId, "plan:" + exchangeId, "The user accepted the plan change: " + plan.reason());
        return goal(workspaceId, goalId);
    }

    private void decide(String workspaceId, String id, Map<String, Long> revisions, String operationId, WorkItemCommand.Action action, Optional<String> criteria,
            Optional<WorkItem.Priority> priority, Optional<String> blocker)
    {
        WorkItemView changed = work.change(workspaceId, id, new WorkItemCommand(operationId, revisions.get(id), action, Optional.empty(), criteria,
                Optional.empty(), Optional.empty(), "Accepted the agent's plan change.", priority, blocker, false));
        revisions.put(id, changed.revision());
    }

    /**
     * Why a plan change cannot apply to the goal's open work: it names work the goal does not hold open, makes work wait on anything else,
     * makes work wait on itself through other work, or orders anything but the open work, each once.
     */
    static Optional<String> invalidPlan(GoalView goal, GoalExchange.PlanChange plan)
    {
        Map<String, List<String>> waits = new HashMap<>();
        goal.items().stream().filter(GoalConversationService::open).forEach(item -> waits.put(item.id(), item.blockedBy()));
        for (GoalExchange.WorkChange change : plan.changes()) {
            if (!waits.containsKey(change.workItemId())) {
                return Optional.of("The plan change names work the goal does not hold open.");
            }
            if (change.waitsOn().filter(blockers -> !waits.keySet().containsAll(blockers)).isPresent()) {
                return Optional.of("The plan change makes work wait on work the goal does not hold open.");
            }
        }
        if (plan.order().filter(order -> !ImmutableSet.copyOf(order).equals(waits.keySet())).isPresent()) {
            return Optional.of("The plan change does not order each open work item once.");
        }
        plan.changes().forEach(change -> change.waitsOn().ifPresent(blockers -> waits.put(change.workItemId(), blockers)));
        Set<String> clear = new HashSet<>();
        for (String id : waits.keySet()) {
            if (circles(id, waits, new HashSet<>(), clear)) {
                return Optional.of("The plan change makes work wait on itself through other work.");
            }
        }
        return Optional.empty();
    }

    /** Whether following what work waits on from {@code id} comes back to work already on the path; work found clear is not walked again. */
    private static boolean circles(String id, Map<String, List<String>> waits, Set<String> path, Set<String> clear)
    {
        if (clear.contains(id) || !waits.containsKey(id)) {
            return false;
        }
        if (!path.add(id)) {
            return true;
        }
        for (String blocker : waits.get(id)) {
            if (circles(blocker, waits, path, clear)) {
                return true;
            }
        }
        path.remove(id);
        clear.add(id);
        return false;
    }

    /**
     * Start on a card: research begins its run; a work card makes its issue or ticket the Goal's work while the composer opens with its
     * instruction, and the task starts only when the user sends it, as that work's attempt; an investigation becomes the Goal's Investigate
     * work.
     */
    public GoalRecommendation startCard(String workspaceId, String goalId, String recommendationId)
    {
        GoalRecommendation card = recommendations.find(recommendationId).filter(found -> found.goalId().equals(goalId) && found.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
        if (card.card().kind() == GoalRecommendation.Kind.RESEARCH) {
            return research.getObject().start(workspaceId, goalId, recommendationId);
        }
        if (card.card().kind() == GoalRecommendation.Kind.SPEC_CHECK) {
            return specChecks.getObject().start(workspaceId, goalId, recommendationId);
        }
        checkArgument(card.card().kind() == GoalRecommendation.Kind.WORK || card.card().kind() == GoalRecommendation.Kind.INVESTIGATE, "A lesson card opens its lesson.");
        if (card.state() == GoalRecommendation.State.STARTED) {
            return card;
        }
        requireShown(card);
        if (card.card().kind() == GoalRecommendation.Kind.WORK) {
            // The source may have closed or been taken since the card floated, so Start reads it again, outside the lock, before anything starts.
            Optional<String> unready = candidates.read(goal(workspaceId, goalId), card.card()).unready();
            if (unready.isPresent()) {
                synchronized (writes) {
                    recommendations.find(recommendationId).filter(current -> current.state() == GoalRecommendation.State.SHOWN)
                            .ifPresent(current -> recommendations.save(current.dismissed(clock.instant())));
                }
                tellDecision(workspaceId, goalId, "unready:" + recommendationId, unready.orElseThrow() + " Recommend another candidate.");
                throw new ResponseStatusException(HttpStatus.CONFLICT, unready.orElseThrow() + " The card is set aside.");
            }
        }
        WorkItemView became = card.card().kind() == GoalRecommendation.Kind.INVESTIGATE ? investigation(workspaceId, goalId, card) : practice(workspaceId, goalId, card);
        synchronized (writes) {
            GoalRecommendation current = recommendations.find(recommendationId).orElseThrow();
            if (current.state() == GoalRecommendation.State.STARTED) {
                return current;
            }
            requireShown(current);
            GoalRecommendation started = current.working(became.id(), clock.instant());
            recommendations.save(started);
            return started;
        }
    }

    /** A practice card's issue or ticket becomes the Goal's Develop work, whose task the user sends from the composer. */
    private WorkItemView practice(String workspaceId, String goalId, GoalRecommendation card)
    {
        GoalItemKind kind = card.card().issue().isPresent() ? GoalItemKind.ISSUE : GoalItemKind.JIRA;
        String source = card.card().ticket().orElseGet(() -> GoalItem.numberedKey(workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found")).monitoredRepository().fullName(), card.card().issue().orElseThrow()));
        return work.adopt(workspaceId, goalId, UUID.nameUUIDFromBytes(("practice:" + card.id()).getBytes(UTF_8)).toString(), card.card().title(),
                card.card().scope(), kind, source);
    }

    /** An investigation card becomes one Investigate work item of the Goal, whose result the user accepts; starting it again finds the same item. */
    private WorkItemView investigation(String workspaceId, String goalId, GoalRecommendation card)
    {
        return work.create(workspaceId, UUID.nameUUIDFromBytes(("investigate:" + card.id()).getBytes(UTF_8)).toString(), Optional.of(goalId), card.card().title(),
                WorkItem.Kind.INVESTIGATE, card.card().scope(), WorkItem.CompletionRule.ACCEPTED_RESULT);
    }

    /**
     * A decision on a Goal's work: a work card settles once the work it became is done or dropped, which lets the agent recommend the next
     * practice, and the agent hears what the user decided. A finished lesson tells the agent itself, naming the lesson.
     */
    @EventListener
    public void decided(WorkItemDecided decision)
    {
        try {
            if (decision.action() != WorkItemCommand.Action.REOPEN) {
                synchronized (writes) {
                    recommendations.list(decision.goalId()).stream()
                            .filter(card -> card.state() == GoalRecommendation.State.STARTED && card.workItemId().filter(decision.workItemId()::equals).isPresent())
                            .forEach(card -> recommendations.save(card.done(clock.instant())));
                }
            }
            if (decision.kind() != WorkItem.Kind.LEARN) {
                String what = switch (decision.action()) {
                    case ACCEPT -> "accepted the result of";
                    case DROP -> "dropped";
                    case REOPEN -> "reopened";
                    case EDIT, BEGIN, MOVE, PRIORITIZE, BLOCK, UNBLOCK -> throw new IllegalArgumentException("Not a decision to report: " + decision.action());
                };
                tellDecision(decision.workspaceId(), decision.goalId(), "decided:" + decision.operationId(),
                        "The user " + what + " \"" + decision.title() + "\"" + unblocked(decision) + ": " + ContextText.excerpt(decision.detail(), MAX_DECISION));
                if (decision.action() == WorkItemCommand.Action.ACCEPT) {
                    summarize(decision.workspaceId(), decision.goalId(), decision.workItemId(), "summary:" + decision.operationId());
                }
            }
        }
        catch (RuntimeException failure) {
            // The decision is saved; the agent reads the work's state with its next request.
            log.warn("Goal {} was not told about work item {}", decision.goalId(), decision.workItemId(), failure);
        }
    }

    /** The goal's open work a finished or dropped item leaves ready, waiting on nothing open any more, as a clause for the agent. */
    private String unblocked(WorkItemDecided decision)
    {
        if (decision.action() == WorkItemCommand.Action.REOPEN) {
            return "";
        }
        GoalView goal = goal(decision.workspaceId(), decision.goalId());
        Set<String> finished = goal.items().stream().filter(item -> item.state() == WorkItem.State.DONE || item.state() == WorkItem.State.DROPPED)
                .map(WorkItemView::id).collect(toImmutableSet());
        List<String> ready = goal.items().stream()
                .filter(item -> item.blockedBy().contains(decision.workItemId()) && !finished.contains(item.id()) && finished.containsAll(item.blockedBy()))
                .map(item -> "\"" + item.title() + "\"").collect(toImmutableList());
        return ready.isEmpty() ? "" : ", which unblocks " + String.join(", ", ready);
    }

    /**
     * Summarize again: writes the summary of the goal's finished work that its acceptance did not get, such as after a failed request. Work
     * that has its summary, or whose summary is being written, is left as it is.
     */
    public void summarize(String workspaceId, String goalId, String workItemId)
    {
        WorkItemView item = goal(workspaceId, goalId).items().stream().filter(found -> found.id().equals(workItemId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Work not found"));
        if (item.state() != WorkItem.State.DONE || item.kind() == WorkItem.Kind.LEARN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only finished work other than a lesson has a summary.");
        }
        Optional<String> accepted = item.history().stream().filter(event -> event.action().equals("ACCEPT")).map(WorkItemEvent::operationId).reduce((first, second) -> second);
        accepted.ifPresent(operation -> summarize(workspaceId, goalId, workItemId, "summary:" + operation));
    }

    /** Writes the summary off the caller's thread; a failed one records nothing, so the facts stay as they are and the user can ask again. */
    private void summarize(String workspaceId, String goalId, String workItemId, String operationId)
    {
        synchronized (writes) {
            if (!summarizing.add(workItemId)) {
                return;
            }
        }
        executor.execute(() -> {
            try {
                GoalView goal = goal(workspaceId, goalId);
                WorkItemView item = goal.items().stream().filter(found -> found.id().equals(workItemId)).findFirst().orElseThrow();
                if (item.history().stream().noneMatch(event -> event.operationId().equals(operationId))) {
                    work.summary(workspaceId, workItemId, operationId, planner.summary(models.goalModel(goalId, workspaceId), goal, item));
                }
            }
            catch (RuntimeException failure) {
                log.info("Goal work {} was not summarized: {}", workItemId, failure.toString());
            }
            finally {
                synchronized (writes) {
                    summarizing.remove(workItemId);
                }
            }
        });
    }

    /**
     * A work card names one of the practice candidates the agent was given, or is dropped; a ticket is kept by its stable reference, which
     * Start adopts.
     */
    private Optional<GoalRecommendation.Card> practice(GoalView goal, GoalRecommendation.Card card)
    {
        GoalCandidates.Pool pool = candidates.pool(goal);
        if (card.issue().isPresent()) {
            return pool.has("#" + card.issue().orElseThrow()) ? Optional.of(card) : Optional.empty();
        }
        String key = card.ticket().orElseThrow();
        return pool.has(key) ? candidates.ticket(goal.workspaceId(), key).map(ticket -> card.withTicket(GoalCandidates.reference(ticket))) : Optional.empty();
    }

    private static void requireShown(GoalRecommendation card)
    {
        if (card.state() != GoalRecommendation.State.SHOWN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This card was already finished or dismissed.");
        }
    }

    /** Not now on a card: it leaves the Goal control, and the agent does not offer the same card again. */
    public GoalRecommendation dismiss(String workspaceId, String goalId, String recommendationId)
    {
        synchronized (writes) {
            goal(workspaceId, goalId);
            GoalRecommendation card = recommendations.find(recommendationId).filter(found -> found.goalId().equals(goalId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
            if (card.state() == GoalRecommendation.State.DISMISSED) {
                return card;
            }
            if (card.state() != GoalRecommendation.State.SHOWN) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A started card cannot be dismissed.");
            }
            GoalRecommendation dismissed = card.dismissed(clock.instant());
            recommendations.save(dismissed);
            return dismissed;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void settleInterrupted()
    {
        synchronized (writes) {
            for (GoalExchange exchange : exchanges.pending()) {
                if (!running.contains(exchange.id())) {
                    exchanges.save(exchange.failed(Optional.empty(), INTERRUPTED, clock.instant()));
                }
            }
        }
    }

    void answer(GoalExchange exchange)
    {
        Optional<String> model = Optional.empty();
        Optional<GoalView> read = Optional.empty();
        GoalExchange settled;
        try {
            GoalView goal = goal(exchange.workspaceId(), exchange.goalId());
            read = Optional.of(goal);
            AgentModelSetting setting = models.goalModel(goal.id(), exchange.workspaceId());
            model = Optional.of(setting.configId() + "/" + setting.model());
            List<GoalExchange> earlier = exchanges.list(goal.id()).stream().takeWhile(candidate -> !candidate.id().equals(exchange.id())).collect(toImmutableList());
            settled = exchange.answered(model.orElseThrow(), planner.answer(setting, goal, earlier, exchange), clock.instant());
            // Invalid output leaves the accepted course and the plan intact and the message to ask again.
            Optional<GoalExchange.Reply> reply = settled.reply();
            Optional<String> invalid = reply.flatMap(GoalExchange.Reply::course).flatMap(course -> invalidRevision(goal, course))
                    .or(() -> reply.flatMap(GoalExchange.Reply::plan).flatMap(plan -> invalidPlan(goal, plan)));
            if (invalid.isPresent()) {
                settled = exchange.failed(model, invalid.orElseThrow(), clock.instant());
            }
        }
        catch (RuntimeException failure) {
            log.debug("Goal exchange {} failed", exchange.id(), failure);
            String reason = failure instanceof ResponseStatusException status ? status.getReason() : failure.getMessage();
            settled = exchange.failed(model, reason == null || reason.isBlank() ? "The agent could not answer." : reason, clock.instant());
        }
        Optional<GoalView> defined = read.filter(goal -> goal.definition().isPresent());
        Optional<GoalRecommendation.Card> answered = settled.reply().flatMap(GoalExchange.Reply::recommendation).filter(found -> defined.isPresent());
        // GitHub or Jira answers whether a practice card's source is still open, so it is asked before the lock.
        GoalCandidates.Read checked = answered.filter(found -> found.kind() == GoalRecommendation.Kind.WORK).map(found -> read(defined.orElseThrow(), found))
                .orElse(GoalCandidates.Read.NOTHING);
        Optional<GoalRecommendation.Card> card = answered.map(found -> checked.doubt().map(found::doubting).orElse(found));
        Optional<String> unready = checked.unready();
        boolean setAside = false;
        synchronized (writes) {
            // Only the request this job was given is settled; a restart's settlement or a later retry replaced any other.
            if (exchanges.find(exchange.id()).filter(exchange::equals).isPresent()) {
                exchanges.save(settled);
                if (card.isPresent()) {
                    setAside = recommend(read.orElseThrow(), settled, card.orElseThrow(), unready);
                }
                if (read.isPresent()) {
                    learn(read.orElseThrow(), settled.reply().map(GoalExchange.Reply::topics).orElse(ImmutableList.of()));
                    Optional<String> title = settled.reply().flatMap(GoalExchange.Reply::title);
                    if (title.isPresent()) {
                        name(read.orElseThrow(), title.orElseThrow());
                    }
                }
            }
        }
        if (setAside) {
            tellDecision(settled.workspaceId(), settled.goalId(), "unready:" + settled.id(), unready.orElseThrow() + " Recommend another candidate.");
        }
        flush(exchange.goalId());
    }

    private GoalCandidates.Read read(GoalView goal, GoalRecommendation.Card card)
    {
        try {
            return practice(goal, card).map(named -> candidates.read(goal, named)).orElse(GoalCandidates.Read.NOTHING);
        }
        catch (RuntimeException failure) {
            log.debug("The practice card of goal exchange for {} was not checked", goal.id(), failure);
            return GoalCandidates.Read.NOTHING;
        }
    }

    /**
     * Gives the goal the name its agent proposed while the goal is undefined, in place of the working title the user's first words gave it or a
     * name the agent gave it before; a title the user chose stays.
     */
    private void name(GoalView goal, String title)
    {
        List<GoalExchange> conversation = exchanges.list(goal.id());
        Set<String> replaceable = new HashSet<>();
        conversation.stream().filter(exchange -> !exchange.automatic()).findFirst().ifPresent(first -> replaceable.add(title(first.message())));
        conversation.forEach(exchange -> exchange.reply().flatMap(GoalExchange.Reply::title).ifPresent(replaceable::add));
        goals.name(goal.workspaceId(), goal.id(), title, replaceable);
    }

    /**
     * Saves the levels the agent changed, each with the evidence it cited that exists: one of the user's questions it was shown, one of their
     * messages to the goal, such as a lesson question or a check answer, or one of the goal's lessons or work items. A level the user set
     * stays theirs.
     */
    private void learn(GoalView goal, List<GoalTopic.Change> changes)
    {
        if (changes.isEmpty()) {
            return;
        }
        Set<String> known = new HashSet<>();
        questions.nearest(goal, GoalPlanner.MAX_QUESTIONS).forEach(question -> known.add(question.id()));
        exchanges.list(goal.id()).stream().filter(exchange -> !exchange.automatic()).forEach(exchange -> known.add(GoalPlanner.messageId(exchange)));
        goal.items().forEach(item -> {
            known.add("lesson:" + item.id());
            known.add("work:" + item.id());
        });
        // What the user said they already know, in the definition they accepted.
        goal.definition().filter(definition -> !definition.startingKnowledge().isBlank()).ifPresent(definition -> known.add("definition:" + definition.exchangeId()));
        for (GoalTopic.Change change : changes) {
            List<String> evidence = change.evidence().stream().filter(known::contains).collect(toImmutableList());
            String id = GoalTopic.id(goal.id(), change.topic());
            if (evidence.isEmpty() || topics.find(id).filter(GoalTopic::userSet).isPresent()) {
                continue;
            }
            topics.save(new GoalTopic(id, goal.id(), goal.workspaceId(), change.topic().strip(), change.level(), evidence, false, clock.instant()));
        }
    }

    /** The user's own assessment of a topic, which the agent leaves alone from then on. */
    public GoalView correctTopic(String workspaceId, String goalId, String topic, GoalTopic.Level level)
    {
        synchronized (writes) {
            GoalView goal = goal(workspaceId, goalId);
            String id = GoalTopic.id(goalId, checkText(topic, "topic", 120));
            List<String> evidence = topics.find(id).map(GoalTopic::evidence).orElse(ImmutableList.of());
            topics.save(new GoalTopic(id, goalId, goal.workspaceId(), topic.strip(), level, evidence, true, clock.instant()));
        }
        return goal(workspaceId, goalId);
    }

    /**
     * Floats the card unless one of its kind is still waiting or running, or the user dismissed the same card. A lesson card must name an
     * unfinished lesson of the goal's course.
     */
    /**
     * Floats the card an answer recommended, unless it names nothing the agent was given or a card of its kind is still waiting. A practice card
     * whose source is no longer open is set aside as dismissed instead, so the candidates leave it out.
     *
     * @return whether the card was set aside
     */
    private boolean recommend(GoalView goal, GoalExchange exchange, GoalRecommendation.Card answered, Optional<String> unready)
    {
        // A lesson card names an unfinished lesson of the course that waits on no open work.
        boolean lesson = answered.workItemId().map(id -> goal.course().stream().flatMap(course -> course.parts().stream()).anyMatch(part -> part.lessons().contains(id))
                && goal.items().stream().anyMatch(item -> item.id().equals(id) && open(item) && item.blockedBy().stream()
                        .noneMatch(blocker -> goal.items().stream().anyMatch(other -> other.id().equals(blocker) && open(other))))).orElse(true);
        Optional<GoalRecommendation.Card> named = switch (answered.kind()) {
            case WORK -> practice(goal, answered);
            // A spec check names a module the agent was given, and waits until every gap of the last one is done, dropped or dismissed.
            case SPEC_CHECK -> specChecks.getObject().modules(goal.workspaceId()).stream().anyMatch(module -> answered.module().filter(module.directory()::equals).isPresent())
                    && specChecks.getObject().gaps(goal.workspaceId(), goal.id()).stream().noneMatch(GoalGap::open) ? Optional.of(answered) : Optional.empty();
            case RESEARCH, LESSON, INVESTIGATE -> Optional.of(answered);
        };
        if (!lesson || named.isEmpty()) {
            return false;
        }
        GoalRecommendation.Card card = named.orElseThrow();
        List<GoalRecommendation> cards = recommendations.list(goal.id());
        boolean waiting = cards.stream().anyMatch(existing -> existing.card().kind() == card.kind()
                && (existing.state() == GoalRecommendation.State.SHOWN || existing.state() == GoalRecommendation.State.STARTED));
        // The same card with other doubts is still the card the user passed on.
        boolean dismissed = cards.stream().anyMatch(existing -> existing.state() == GoalRecommendation.State.DISMISSED
                && existing.card().withUncertainty(Optional.empty()).equals(card.withUncertainty(Optional.empty())));
        if (waiting || dismissed) {
            return false;
        }
        Instant now = clock.instant();
        recommendations.save(new GoalRecommendation("card-" + exchange.id(), goal.id(), goal.workspaceId(), card,
                unready.isPresent() ? GoalRecommendation.State.DISMISSED : GoalRecommendation.State.SHOWN, Optional.empty(), Optional.empty(), Optional.empty(),
                exchange.id(), now, now));
        return unready.isPresent();
    }

    private static boolean open(WorkItemView item)
    {
        return item.state() == WorkItem.State.PLANNED || item.state() == WorkItem.State.WORKING;
    }

    private void dispatch(GoalExchange exchange)
    {
        running.add(exchange.id());
        executor.execute(() -> {
            try {
                answer(exchange);
            }
            catch (RuntimeException failure) {
                log.warn("Goal exchange {} was not settled", exchange.id(), failure);
            }
            finally {
                running.remove(exchange.id());
            }
        });
    }

    private GoalView goal(String workspaceId, String goalId)
    {
        return goals.list(workspaceId).stream().filter(goal -> goal.id().equals(goalId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found"));
    }

    private static String message(String message)
    {
        checkArgument(message != null && !message.isBlank(), "Write a message first.");
        checkArgument(message.strip().length() <= GoalExchange.MAX_MESSAGE, "The message is too long.");
        return message.strip();
    }

    /** A new goal's working title: the first line of the user's first answer, which the accepted definition replaces. */
    static String title(String message)
    {
        String line = message.lines().findFirst().orElse("").strip().replaceAll("\\s+", " ");
        if (line.length() <= MAX_TITLE) {
            return line;
        }
        int cut = line.lastIndexOf(' ', MAX_TITLE - 1);
        return line.substring(0, cut > MAX_TITLE / 2 ? cut : MAX_TITLE - 1) + "…";
    }
}
