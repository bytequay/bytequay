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

import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalLesson;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.store.GoalKnowledgeNoteStore;
import com.bytequay.core.store.GoalLessonStore;
import com.bytequay.core.store.GoalRecommendationStore;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static java.util.Objects.requireNonNull;

/**
 * The lessons of a goal's course. A lesson is written once, by a tool-free request from the goal's knowledge notes, the first time the user
 * opens it, so reopening it needs no model call. Finishing it accepts its Learn work item and tells the goal's agent.
 */
@Service
public class GoalLessonService
{
    static final String INTERRUPTED = "ByteQuay stopped before the lesson was written.";
    private static final Logger log = LoggerFactory.getLogger(GoalLessonService.class);

    private final GoalService goals;
    private final WorkItemService work;
    private final GoalLessonStore lessons;
    private final GoalKnowledgeNoteStore notes;
    private final GoalRecommendationStore recommendations;
    private final ModelSettingsService models;
    private final GoalPlanner planner;
    private final ObjectProvider<GoalConversationService> conversations;
    private final Clock clock;
    private final Executor executor;
    // The lessons this process is writing; one still writing outside it was interrupted.
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Object writes = new Object();

    @Autowired
    public GoalLessonService(GoalService goals, WorkItemService work, GoalLessonStore lessons, GoalKnowledgeNoteStore notes, GoalRecommendationStore recommendations,
            ModelSettingsService models, GoalPlanner planner, ObjectProvider<GoalConversationService> conversations, Clock clock)
    {
        this(goals, work, lessons, notes, recommendations, models, planner, conversations, clock,
                Executors.newSingleThreadExecutor(Thread.ofVirtual().name("goal-lessons-", 0).factory()));
    }

    GoalLessonService(GoalService goals, WorkItemService work, GoalLessonStore lessons, GoalKnowledgeNoteStore notes, GoalRecommendationStore recommendations,
            ModelSettingsService models, GoalPlanner planner, ObjectProvider<GoalConversationService> conversations, Clock clock, Executor executor)
    {
        this.goals = requireNonNull(goals, "goals is null");
        this.work = requireNonNull(work, "work is null");
        this.lessons = requireNonNull(lessons, "lessons is null");
        this.notes = requireNonNull(notes, "notes is null");
        this.recommendations = requireNonNull(recommendations, "recommendations is null");
        this.models = requireNonNull(models, "models is null");
        this.planner = requireNonNull(planner, "planner is null");
        this.conversations = requireNonNull(conversations, "conversations is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /**
     * The lesson as it stands, written or being written; opening one for the first time, or after it failed, asks for it to be written.
     * Opening a lesson is what Start means on its card.
     */
    public GoalLesson open(String workspaceId, String goalId, String lessonId)
    {
        GoalLesson lesson;
        synchronized (writes) {
            lesson(goal(workspaceId, goalId), lessonId);
            Optional<GoalLesson> existing = lessons.find(lessonId);
            if (existing.filter(found -> found.state() != GoalLesson.State.FAILED).isPresent()) {
                start(goalId, lessonId);
                return existing.orElseThrow();
            }
            lesson = GoalLesson.writing(lessonId, goalId, workspaceId, clock.instant());
            lessons.save(lesson);
        }
        start(goalId, lessonId);
        running.add(lesson.id());
        GoalLesson requested = lesson;
        executor.execute(() -> {
            try {
                write(requested);
            }
            catch (RuntimeException failure) {
                log.warn("Lesson {} was not settled", requested.id(), failure);
            }
            finally {
                running.remove(requested.id());
            }
        });
        return lesson;
    }

    /** The lesson as it stands; a local read that never asks for one to be written. */
    public Optional<GoalLesson> find(String workspaceId, String goalId, String lessonId)
    {
        lesson(goal(workspaceId, goalId), lessonId);
        return lessons.find(lessonId);
    }

    /**
     * Done is the user's decision: accepts the lesson's Learn work item with what it promised, settles its card, and tells the goal's
     * agent. Finishing it again changes nothing.
     */
    public GoalView finish(String workspaceId, String goalId, String lessonId, String operationId)
    {
        WorkItemView item = lesson(goal(workspaceId, goalId), lessonId);
        if (item.state() != WorkItem.State.DONE) {
            work.change(workspaceId, lessonId, new WorkItemCommand(operationId, item.revision(), WorkItemCommand.Action.ACCEPT, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), "Finished the lesson."));
        }
        synchronized (writes) {
            recommendations.list(goalId).stream()
                    .filter(card -> card.card().workItemId().filter(lessonId::equals).isPresent()
                            && (card.state() == GoalRecommendation.State.SHOWN || card.state() == GoalRecommendation.State.STARTED))
                    .forEach(card -> recommendations.save(card.done(clock.instant())));
        }
        conversations.getObject().tellDecision(workspaceId, goalId, "lesson:" + lessonId, "The user finished the lesson \"" + item.title() + "\".");
        return goal(workspaceId, goalId);
    }

    private void start(String goalId, String lessonId)
    {
        recommendations.list(goalId).stream()
                .filter(card -> card.state() == GoalRecommendation.State.SHOWN && card.card().workItemId().filter(lessonId::equals).isPresent())
                .forEach(card -> recommendations.save(card.started(Optional.empty(), clock.instant())));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void settleInterrupted()
    {
        synchronized (writes) {
            for (GoalLesson lesson : lessons.writing()) {
                if (!running.contains(lesson.id())) {
                    lessons.save(lesson.failed(Optional.empty(), INTERRUPTED, clock.instant()));
                }
            }
        }
    }

    void write(GoalLesson lesson)
    {
        Optional<String> model = Optional.empty();
        GoalLesson settled;
        try {
            GoalView goal = goal(lesson.workspaceId(), lesson.goalId());
            WorkItemView item = lesson(goal, lesson.id());
            String part = goal.course().orElseThrow().parts().stream().filter(candidate -> candidate.lessons().contains(lesson.id()))
                    .map(GoalCourse.Part::title).findFirst().orElseThrow();
            AgentModelSetting setting = models.goalModel(goal.id(), lesson.workspaceId());
            model = Optional.of(setting.configId() + "/" + setting.model());
            settled = lesson.written(model.orElseThrow(), planner.lesson(setting, goal, item, part, notes.list(goal.id())), clock.instant());
        }
        catch (RuntimeException failure) {
            log.debug("Lesson {} was not written", lesson.id(), failure);
            String reason = failure instanceof ResponseStatusException status ? status.getReason() : failure.getMessage();
            settled = lesson.failed(model, reason == null || reason.isBlank() ? "The lesson could not be written." : reason, clock.instant());
        }
        synchronized (writes) {
            // Only the request this job was given is settled; a restart's settlement or a later request replaced any other.
            if (lessons.find(lesson.id()).filter(lesson::equals).isPresent()) {
                lessons.save(settled);
            }
        }
    }

    private GoalView goal(String workspaceId, String goalId)
    {
        return goals.list(workspaceId).stream().filter(goal -> goal.id().equals(goalId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found"));
    }

    /** The Learn work item of a lesson in the goal's accepted course. */
    private static WorkItemView lesson(GoalView goal, String lessonId)
    {
        boolean inCourse = goal.course().stream().flatMap(course -> course.parts().stream()).anyMatch(part -> part.lessons().contains(lessonId));
        return goal.items().stream().filter(item -> inCourse && item.id().equals(lessonId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lesson not found"));
    }
}
