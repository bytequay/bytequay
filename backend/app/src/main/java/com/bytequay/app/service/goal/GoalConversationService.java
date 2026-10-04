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
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.store.GoalExchangeStore;
import com.bytequay.core.store.GoalRecommendationStore;
import com.bytequay.core.store.GoalTopicStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
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
    private final GoalQuestions questions;
    private final GoalTopicStore topics;
    private final ModelSettingsService models;
    private final GoalPlanner planner;
    private final Clock clock;
    private final Executor executor;
    // The requests this process is answering; a pending exchange outside it was interrupted.
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Object writes = new Object();

    @Autowired
    public GoalConversationService(GoalService goals, WorkItemService work, GoalExchangeStore exchanges, GoalRecommendationStore recommendations,
            WorkspaceStore workspaces, GoalCandidates candidates, ObjectProvider<GoalResearchService> research, GoalQuestions questions, GoalTopicStore topics,
            ModelSettingsService models, GoalPlanner planner, Clock clock)
    {
        this(goals, work, exchanges, recommendations, workspaces, candidates, research, questions, topics, models, planner, clock,
                Executors.newFixedThreadPool(2, Thread.ofVirtual().name("goal-agent-", 0).factory()));
    }

    GoalConversationService(GoalService goals, WorkItemService work, GoalExchangeStore exchanges, GoalRecommendationStore recommendations,
            WorkspaceStore workspaces, GoalCandidates candidates, ObjectProvider<GoalResearchService> research, GoalQuestions questions, GoalTopicStore topics,
            ModelSettingsService models, GoalPlanner planner, Clock clock, Executor executor)
    {
        this.goals = requireNonNull(goals, "goals is null");
        this.work = requireNonNull(work, "work is null");
        this.exchanges = requireNonNull(exchanges, "exchanges is null");
        this.recommendations = requireNonNull(recommendations, "recommendations is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.candidates = requireNonNull(candidates, "candidates is null");
        this.research = requireNonNull(research, "research is null");
        this.questions = requireNonNull(questions, "questions is null");
        this.topics = requireNonNull(topics, "topics is null");
        this.models = requireNonNull(models, "models is null");
        this.planner = requireNonNull(planner, "planner is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /** The goal's exchanges, oldest first. */
    public List<GoalExchange> list(String workspaceId, String goalId)
    {
        goal(workspaceId, goalId);
        return exchanges.list(goalId);
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
            exchange = GoalExchange.ask(operationId, goalId, workspaceId, message(message), goal.revision(), clock.instant()).about(lessonId);
            exchanges.save(exchange);
        }
        dispatch(exchange);
        return exchanges.list(goalId);
    }

    /** Tells the goal's agent about an event, unless it is already answering; repeating the same operation tells it once. */
    public void tell(String workspaceId, String goalId, String operationId, String event)
    {
        GoalExchange exchange;
        synchronized (writes) {
            GoalView goal = goal(workspaceId, goalId);
            // ponytail: an event while the agent answers is dropped; the next request still reads the cards and notes it would report.
            if (exchanges.find(operationId).isPresent() || exchanges.list(goalId).stream().anyMatch(earlier -> earlier.state() == GoalExchange.State.PENDING)) {
                return;
            }
            exchange = GoalExchange.told(operationId, goalId, workspaceId, event, goal.revision(), clock.instant());
            exchanges.save(exchange);
        }
        dispatch(exchange);
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
        tell(workspaceId, goalId, "accepted:" + exchangeId, "The user accepted the goal definition.");
        return defined;
    }

    /**
     * Accepts the course an answer proposed: each lesson becomes a Learn work item of the goal, in order, and nothing starts. Accepting it
     * again changes nothing.
     */
    public GoalView acceptCourse(String workspaceId, String goalId, String exchangeId)
    {
        GoalExchange exchange = exchanges.find(exchangeId).filter(candidate -> candidate.goalId().equals(goalId) && candidate.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        GoalCourse.Draft draft = exchange.reply().flatMap(GoalExchange.Reply::course)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This answer proposes no course."));
        ImmutableList.Builder<GoalCourse.Part> parts = ImmutableList.builder();
        int index = 0;
        for (GoalCourse.DraftPart part : draft.parts()) {
            ImmutableList.Builder<String> lessons = ImmutableList.builder();
            for (GoalCourse.DraftLesson lesson : part.lessons()) {
                // The same lesson of the same proposal is the same work item, so a repeated acceptance creates nothing new.
                String id = UUID.nameUUIDFromBytes(("lesson:" + exchangeId + ":" + index++).getBytes(UTF_8)).toString();
                work.create(workspaceId, id, Optional.of(goalId), lesson.title(), WorkItem.Kind.LEARN, lesson.outcome(), WorkItem.CompletionRule.ACCEPTED_RESULT);
                lessons.add(id);
            }
            parts.add(new GoalCourse.Part(part.title(), lessons.build()));
        }
        GoalView planned = goals.planCourse(workspaceId, goalId, new GoalCourse(exchangeId, parts.build(), clock.instant()));
        tell(workspaceId, goalId, "course:" + exchangeId, "The user accepted the course.");
        return planned;
    }

    /**
     * Start on a card: research begins its run; a work card makes its issue or ticket the Goal's work while the composer opens with its
     * instruction, and the task starts only when the user sends it, as that work's attempt.
     */
    public GoalRecommendation startCard(String workspaceId, String goalId, String recommendationId)
    {
        GoalRecommendation card = recommendations.find(recommendationId).filter(found -> found.goalId().equals(goalId) && found.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
        if (card.card().kind() == GoalRecommendation.Kind.RESEARCH) {
            return research.getObject().start(workspaceId, goalId, recommendationId);
        }
        checkArgument(card.card().kind() == GoalRecommendation.Kind.WORK, "A lesson card opens its lesson.");
        if (card.state() == GoalRecommendation.State.STARTED) {
            return card;
        }
        requireShown(card);
        GoalItemKind kind = card.card().issue().isPresent() ? GoalItemKind.ISSUE : GoalItemKind.JIRA;
        String source = card.card().ticket().orElseGet(() -> GoalItem.numberedKey(workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found")).monitoredRepository().fullName(), card.card().issue().orElseThrow()));
        WorkItemView practice = work.adopt(workspaceId, goalId, UUID.nameUUIDFromBytes(("practice:" + card.id()).getBytes(UTF_8)).toString(), card.card().title(),
                card.card().scope(), kind, source);
        synchronized (writes) {
            GoalRecommendation current = recommendations.find(recommendationId).orElseThrow();
            if (current.state() == GoalRecommendation.State.STARTED) {
                return current;
            }
            requireShown(current);
            GoalRecommendation started = current.practicing(practice.id(), clock.instant());
            recommendations.save(started);
            return started;
        }
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
                    case EDIT, BEGIN, MOVE -> throw new IllegalArgumentException("Not a decision to report: " + decision.action());
                };
                tell(decision.workspaceId(), decision.goalId(), "decided:" + decision.operationId(),
                        "The user " + what + " \"" + decision.title() + "\": " + ContextText.excerpt(decision.detail(), MAX_DECISION));
            }
        }
        catch (RuntimeException failure) {
            // The decision is saved; the agent reads the work's state with its next request.
            log.warn("Goal {} was not told about work item {}", decision.goalId(), decision.workItemId(), failure);
        }
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
            AgentModelSetting setting = models.agentModel(AgentModelType.DEVELOPMENT, Optional.of(exchange.workspaceId()));
            model = Optional.of(setting.configId() + "/" + setting.model());
            List<GoalExchange> earlier = exchanges.list(goal.id()).stream().takeWhile(candidate -> !candidate.id().equals(exchange.id())).collect(toImmutableList());
            settled = exchange.answered(model.orElseThrow(), planner.answer(setting, goal, earlier, exchange), clock.instant());
        }
        catch (RuntimeException failure) {
            log.debug("Goal exchange {} failed", exchange.id(), failure);
            String reason = failure instanceof ResponseStatusException status ? status.getReason() : failure.getMessage();
            settled = exchange.failed(model, reason == null || reason.isBlank() ? "The agent could not answer." : reason, clock.instant());
        }
        synchronized (writes) {
            // Only the request this job was given is settled; a restart's settlement or a later retry replaced any other.
            if (exchanges.find(exchange.id()).filter(exchange::equals).isPresent()) {
                exchanges.save(settled);
                Optional<GoalRecommendation.Card> card = settled.reply().flatMap(GoalExchange.Reply::recommendation);
                if (card.isPresent() && read.flatMap(GoalView::definition).isPresent()) {
                    recommend(read.orElseThrow(), settled, card.orElseThrow());
                }
                if (read.isPresent()) {
                    learn(read.orElseThrow(), settled.reply().map(GoalExchange.Reply::topics).orElse(ImmutableList.of()));
                }
            }
        }
    }

    /**
     * Saves the levels the agent changed, each with the evidence it cited that exists: one of the user's recent questions, or one of the
     * goal's lessons or work items. A level the user set stays theirs.
     */
    private void learn(GoalView goal, List<GoalTopic.Change> changes)
    {
        if (changes.isEmpty()) {
            return;
        }
        Set<String> known = new HashSet<>();
        questions.recent(GoalPlanner.MAX_QUESTIONS).forEach(question -> known.add(question.id()));
        goal.items().forEach(item -> {
            known.add("lesson:" + item.id());
            known.add("work:" + item.id());
        });
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
    private void recommend(GoalView goal, GoalExchange exchange, GoalRecommendation.Card answered)
    {
        boolean lesson = answered.workItemId().map(id -> goal.course().stream().flatMap(course -> course.parts().stream()).anyMatch(part -> part.lessons().contains(id))
                && goal.items().stream().anyMatch(item -> item.id().equals(id) && item.state() != WorkItem.State.DONE && item.state() != WorkItem.State.DROPPED)).orElse(true);
        Optional<GoalRecommendation.Card> named = answered.kind() == GoalRecommendation.Kind.WORK ? practice(goal, answered) : Optional.of(answered);
        if (!lesson || named.isEmpty()) {
            return;
        }
        GoalRecommendation.Card card = named.orElseThrow();
        List<GoalRecommendation> cards = recommendations.list(goal.id());
        boolean waiting = cards.stream().anyMatch(existing -> existing.card().kind() == card.kind()
                && (existing.state() == GoalRecommendation.State.SHOWN || existing.state() == GoalRecommendation.State.STARTED));
        boolean dismissed = cards.stream().anyMatch(existing -> existing.state() == GoalRecommendation.State.DISMISSED && existing.card().equals(card));
        if (!waiting && !dismissed) {
            Instant now = clock.instant();
            recommendations.save(new GoalRecommendation("card-" + exchange.id(), goal.id(), goal.workspaceId(), card, GoalRecommendation.State.SHOWN, Optional.empty(),
                    Optional.empty(), exchange.id(), now, now));
        }
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
