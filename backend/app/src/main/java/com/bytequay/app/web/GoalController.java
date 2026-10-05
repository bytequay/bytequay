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
package com.bytequay.app.web;

import com.bytequay.app.service.goal.GoalConversationService;
import com.bytequay.app.service.goal.GoalGap;
import com.bytequay.app.service.goal.GoalItemView;
import com.bytequay.app.service.goal.GoalKnowledgeNoteView;
import com.bytequay.app.service.goal.GoalLessonService;
import com.bytequay.app.service.goal.GoalResearchService;
import com.bytequay.app.service.goal.GoalService;
import com.bytequay.app.service.goal.GoalSpecChecks;
import com.bytequay.app.service.goal.GoalView;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalLesson;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalTopic;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Local workspace goals and their conversations; nothing here reaches GitHub or Jira. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/goals")
public class GoalController
{
    private final GoalService goals;
    private final GoalConversationService conversations;
    private final GoalResearchService research;
    private final GoalLessonService lessons;
    private final GoalSpecChecks specChecks;

    public GoalController(GoalService goals, GoalConversationService conversations, GoalResearchService research, GoalLessonService lessons, GoalSpecChecks specChecks)
    {
        this.goals = requireNonNull(goals, "goals is null");
        this.conversations = requireNonNull(conversations, "conversations is null");
        this.research = requireNonNull(research, "research is null");
        this.lessons = requireNonNull(lessons, "lessons is null");
        this.specChecks = requireNonNull(specChecks, "specChecks is null");
    }

    @GetMapping
    public List<GoalView> list(@PathVariable String workspaceId)
    {
        return goals.list(workspaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalView create(@PathVariable String workspaceId, @RequestBody CreateGoalRequest request)
    {
        checkArgument(request != null && request.title() != null, "title is required");
        return goals.create(workspaceId, request.title(), Optional.ofNullable(request.description()).orElse(""));
    }

    @PatchMapping("/{goalId}")
    public GoalView update(@PathVariable String workspaceId, @PathVariable String goalId, @RequestBody UpdateGoalRequest request)
    {
        checkArgument(request != null, "request is required");
        GoalView updated = goals.update(workspaceId, goalId, Optional.ofNullable(request.title()), Optional.ofNullable(request.description()),
                Boolean.TRUE.equals(request.pinned()));
        return request.paused() == null ? updated : goals.pause(workspaceId, goalId, request.paused());
    }

    @DeleteMapping("/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId, @PathVariable String goalId)
    {
        goals.delete(workspaceId, goalId);
    }

    @PostMapping("/{goalId}/items")
    public GoalView addItem(@PathVariable String workspaceId, @PathVariable String goalId, @RequestBody GoalItemRequest request)
    {
        checkArgument(request != null && request.kind() != null && request.key() != null, "kind and key are required");
        return goals.addItem(workspaceId, goalId, request.kind(), request.key());
    }

    @DeleteMapping("/{goalId}/items")
    public GoalView removeItem(@PathVariable String workspaceId, @PathVariable String goalId, @RequestParam GoalItemKind kind, @RequestParam String key)
    {
        return goals.removeItem(workspaceId, goalId, kind, key);
    }

    @GetMapping("/candidates")
    public List<GoalItemView> candidates(@PathVariable String workspaceId, @RequestParam(defaultValue = "") String q)
    {
        return goals.candidates(workspaceId, q);
    }

    /** Starts a goal from the user's answer to "What do you want to achieve?" and asks its agent. */
    @PostMapping("/conversation")
    @ResponseStatus(HttpStatus.CREATED)
    public GoalView start(@PathVariable String workspaceId, @RequestBody MessageRequest request)
    {
        checkArgument(request != null && request.operationId() != null, "operationId is required");
        return conversations.start(workspaceId, request.operationId(), request.message());
    }

    @GetMapping("/{goalId}/exchanges")
    public List<GoalExchange> exchanges(@PathVariable String workspaceId, @PathVariable String goalId)
    {
        return conversations.list(workspaceId, goalId);
    }

    @PostMapping("/{goalId}/exchanges")
    public List<GoalExchange> ask(@PathVariable String workspaceId, @PathVariable String goalId, @RequestBody MessageRequest request)
    {
        checkArgument(request != null && request.operationId() != null, "operationId is required");
        return conversations.ask(workspaceId, goalId, request.operationId(), request.message(), Optional.ofNullable(request.lessonId()));
    }

    @PostMapping("/{goalId}/reassess")
    public List<GoalExchange> reassess(@PathVariable String workspaceId, @PathVariable String goalId, @RequestBody OperationRequest request)
    {
        checkArgument(request != null && request.operationId() != null, "operationId is required");
        return conversations.reassess(workspaceId, goalId, request.operationId());
    }

    @PostMapping("/{goalId}/exchanges/{exchangeId}/stop")
    public List<GoalExchange> stop(@PathVariable String workspaceId, @PathVariable String goalId, @PathVariable String exchangeId)
    {
        return conversations.stop(workspaceId, goalId, exchangeId);
    }

    @PostMapping("/{goalId}/exchanges/{exchangeId}/retry")
    public List<GoalExchange> retry(@PathVariable String workspaceId, @PathVariable String goalId, @PathVariable String exchangeId)
    {
        return conversations.retry(workspaceId, goalId, exchangeId);
    }

    @PostMapping("/{goalId}/definition")
    public GoalView accept(@PathVariable String workspaceId, @PathVariable String goalId, @RequestBody AcceptRequest request)
    {
        checkArgument(request != null && request.exchangeId() != null, "exchangeId is required");
        return conversations.accept(workspaceId, goalId, request.exchangeId(), Optional.ofNullable(request.title()));
    }

    @PostMapping("/{goalId}/course")
    public GoalView acceptCourse(@PathVariable String workspaceId, @PathVariable String goalId, @RequestBody AcceptRequest request)
    {
        checkArgument(request != null && request.exchangeId() != null, "exchangeId is required");
        return conversations.acceptCourse(workspaceId, goalId, request.exchangeId());
    }

    @PostMapping("/{goalId}/plan")
    public GoalView acceptPlan(@PathVariable String workspaceId, @PathVariable String goalId, @RequestBody AcceptRequest request)
    {
        checkArgument(request != null && request.exchangeId() != null, "exchangeId is required");
        return conversations.acceptPlan(workspaceId, goalId, request.exchangeId());
    }

    /** Start on a card: research begins its read-only run, and a work card is marked started while its composer opens. */
    @PostMapping("/{goalId}/recommendations/{recommendationId}/start")
    public GoalRecommendation startRecommendation(@PathVariable String workspaceId, @PathVariable String goalId, @PathVariable String recommendationId)
    {
        return conversations.startCard(workspaceId, goalId, recommendationId);
    }

    @PostMapping("/{goalId}/recommendations/{recommendationId}/dismiss")
    public GoalRecommendation dismissRecommendation(@PathVariable String workspaceId, @PathVariable String goalId, @PathVariable String recommendationId)
    {
        return conversations.dismiss(workspaceId, goalId, recommendationId);
    }

    /**
     * Opens a lesson of the goal's course, asking for it to be written the first time, or again when asked; opening it is what Start means
     * on its card.
     */
    @PostMapping("/{goalId}/lessons/{lessonId}")
    public GoalLesson openLesson(@PathVariable String workspaceId, @PathVariable String goalId, @PathVariable String lessonId,
            @RequestParam(defaultValue = "false") boolean again)
    {
        return again ? lessons.writeAgain(workspaceId, goalId, lessonId) : lessons.open(workspaceId, goalId, lessonId);
    }

    @GetMapping("/{goalId}/lessons/{lessonId}")
    public GoalLesson lesson(@PathVariable String workspaceId, @PathVariable String goalId, @PathVariable String lessonId)
    {
        return lessons.find(workspaceId, goalId, lessonId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The lesson is not written yet"));
    }

    @PostMapping("/{goalId}/lessons/{lessonId}/done")
    public GoalView finishLesson(@PathVariable String workspaceId, @PathVariable String goalId, @PathVariable String lessonId, @RequestBody OperationRequest request)
    {
        checkArgument(request != null && request.operationId() != null, "operationId is required");
        return lessons.finish(workspaceId, goalId, lessonId, request.operationId());
    }

    /** The user's own assessment of a topic, which the agent leaves alone from then on. */
    @PutMapping("/{goalId}/topics")
    public GoalView correctTopic(@PathVariable String workspaceId, @PathVariable String goalId, @RequestBody TopicRequest request)
    {
        checkArgument(request != null && request.topic() != null && request.level() != null, "topic and level are required");
        return conversations.correctTopic(workspaceId, goalId, request.topic(), request.level());
    }

    @GetMapping("/{goalId}/model")
    public AgentModelSetting model(@PathVariable String workspaceId, @PathVariable String goalId)
    {
        return conversations.model(workspaceId, goalId);
    }

    @PutMapping("/{goalId}/model")
    public AgentModelSetting chooseModel(@PathVariable String workspaceId, @PathVariable String goalId, @RequestBody WorkspaceController.AgentModelRequest request)
    {
        return conversations.chooseModel(workspaceId, goalId, request.configId(), request.model(), Optional.ofNullable(request.effort()));
    }

    @DeleteMapping("/{goalId}/model")
    public AgentModelSetting followWorkspaceModel(@PathVariable String workspaceId, @PathVariable String goalId)
    {
        return conversations.followWorkspaceModel(workspaceId, goalId);
    }

    @PostMapping("/{goalId}/work/{workItemId}/summary")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void summarize(@PathVariable String workspaceId, @PathVariable String goalId, @PathVariable String workItemId)
    {
        conversations.summarize(workspaceId, goalId, workItemId);
    }

    @GetMapping("/{goalId}/gaps")
    public List<GoalGap> gaps(@PathVariable String workspaceId, @PathVariable String goalId)
    {
        return specChecks.gaps(workspaceId, goalId);
    }

    @PostMapping("/{goalId}/gaps/{candidateId}")
    public List<GoalGap> addGap(@PathVariable String workspaceId, @PathVariable String goalId, @PathVariable String candidateId)
    {
        specChecks.adopt(workspaceId, goalId, candidateId);
        return specChecks.gaps(workspaceId, goalId);
    }

    @GetMapping("/{goalId}/knowledge")
    public List<GoalKnowledgeNoteView> knowledge(@PathVariable String workspaceId, @PathVariable String goalId)
    {
        return research.notes(workspaceId, goalId);
    }

    public record CreateGoalRequest(String title, String description) {}

    /**
     * A message to the goal's agent.
     *
     * @param lessonId the lesson the user asks about, if any
     */
    public record MessageRequest(String operationId, String message, String lessonId) {}

    public record OperationRequest(String operationId) {}

    public record TopicRequest(String topic, GoalTopic.Level level) {}

    public record AcceptRequest(String exchangeId, String title) {}

    public record UpdateGoalRequest(String title, String description, Boolean pinned, Boolean paused) {}

    public record GoalItemRequest(GoalItemKind kind, String key) {}
}
