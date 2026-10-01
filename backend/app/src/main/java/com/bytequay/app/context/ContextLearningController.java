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
package com.bytequay.app.context;

import com.google.common.collect.ImmutableList;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Explicit acceptance and continuation of one persisted learning plan. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/repository-context/build")
public class ContextLearningController
{
    private final ContextLearningService learning;

    public ContextLearningController(ContextLearningService learning)
    {
        this.learning = requireNonNull(learning, "learning is null");
    }

    @GetMapping("/plan")
    public PlanView plan(@PathVariable String workspaceId)
    {
        return PlanView.of(learning.get(workspaceId));
    }

    @PostMapping("/learn")
    public ContextBuildService.View learn(@PathVariable String workspaceId, @RequestBody LearnRequest request)
    {
        return learning.start(workspaceId, new ContextLearningService.StartRequest(
                request.requestKey(), request.expectedRevision(), request.expectedBuildSequence(), request.reportId(), request.topicIds(),
                request.excludedSourceIds().orElse(ImmutableList.of())));
    }

    @PostMapping("/continue")
    public ContextBuildService.View continueLearning(@PathVariable String workspaceId, @RequestBody ContinueRequest request)
    {
        return learning.continueLearning(workspaceId, new ContextLearningService.ContinueRequest(
                request.requestKey(), request.expectedRevision(), request.expectedBuildSequence(), request.planId()));
    }

    @DeleteMapping("/plan")
    public PlanView discard(@PathVariable String workspaceId, @RequestBody DiscardRequest request)
    {
        learning.discard(workspaceId, new ContextLearningService.DiscardRequest(
                request.planId(), request.expectedRevision(), request.expectedBuildSequence()));
        return PlanView.of(Optional.empty());
    }

    @PostMapping("/plan/sources")
    public PlanView allowSource(@PathVariable String workspaceId, @RequestBody SourceRequest request)
    {
        return PlanView.of(Optional.of(learning.allowSource(workspaceId, new ContextLearningService.SourceRequest(
                request.planId(), request.suggestedUrl(), request.boundary(), request.expectedRevision(), request.expectedBuildSequence()))));
    }

    @DeleteMapping("/plan/sources")
    public PlanView declineSource(@PathVariable String workspaceId, @RequestBody DeclineRequest request)
    {
        return PlanView.of(Optional.of(learning.declineSource(workspaceId, new ContextLearningService.DeclineRequest(request.planId(), request.suggestedUrl()))));
    }

    @PostMapping("/plan/questions")
    public PlanView addQuestion(@PathVariable String workspaceId, @RequestBody QuestionRequest request)
    {
        return PlanView.of(Optional.of(learning.addQuestion(workspaceId, new ContextLearningService.QuestionRequest(request.planId(), request.topicId(), request.text()))));
    }

    @PutMapping("/plan/questions")
    public PlanView editQuestion(@PathVariable String workspaceId, @RequestBody QuestionEdit request)
    {
        return PlanView.of(Optional.of(learning.editQuestion(workspaceId,
                new ContextLearningService.QuestionEdit(request.planId(), request.topicId(), request.question(), request.text()))));
    }

    @PostMapping("/plan/questions/drop")
    public PlanView dropQuestion(@PathVariable String workspaceId, @RequestBody QuestionDrop request)
    {
        return PlanView.of(Optional.of(learning.dropQuestion(workspaceId, new ContextLearningService.QuestionDrop(request.planId(), request.topicId(), request.question()))));
    }

    @PostMapping("/plan/deeper")
    public ContextBuildService.View goDeeper(@PathVariable String workspaceId, @RequestBody TopicRequest request)
    {
        return learning.goDeeper(workspaceId, request.toService());
    }

    /** Learns one topic's open questions now, ahead of the plan's order. */
    @PostMapping("/plan/topic")
    public ContextBuildService.View learnTopic(@PathVariable String workspaceId, @RequestBody TopicRequest request)
    {
        return learning.learnTopic(workspaceId, request.toService());
    }

    public record QuestionRequest(String planId, String topicId, String text)
    {
        public QuestionRequest
        {
            new ContextLearningService.QuestionRequest(requireNonNull(planId, "planId is null"), requireNonNull(topicId, "topicId is null"),
                    requireNonNull(text, "text is null"));
        }
    }

    public record QuestionEdit(String planId, String topicId, Integer question, String text)
    {
        public QuestionEdit
        {
            checkArgument(question != null && question >= 0, "question is required");
            new ContextLearningService.QuestionEdit(requireNonNull(planId, "planId is null"), requireNonNull(topicId, "topicId is null"), question,
                    requireNonNull(text, "text is null"));
        }
    }

    public record QuestionDrop(String planId, String topicId, Integer question)
    {
        public QuestionDrop
        {
            checkArgument(question != null && question >= 0, "question is required");
            new ContextLearningService.QuestionDrop(requireNonNull(planId, "planId is null"), requireNonNull(topicId, "topicId is null"), question);
        }
    }

    public record TopicRequest(String requestKey, Long expectedRevision, Long expectedBuildSequence, String planId, String topicId)
    {
        public TopicRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0 && expectedBuildSequence != null && expectedBuildSequence >= 0,
                    "Notebook revision and build sequence are required");
            toService(requestKey, expectedRevision, expectedBuildSequence, planId, topicId);
        }

        ContextLearningService.TopicRequest toService()
        {
            return toService(requestKey, expectedRevision, expectedBuildSequence, planId, topicId);
        }

        private static ContextLearningService.TopicRequest toService(String requestKey, long expectedRevision, long expectedBuildSequence, String planId,
                String topicId)
        {
            return new ContextLearningService.TopicRequest(requireNonNull(requestKey, "requestKey is null"), expectedRevision, expectedBuildSequence,
                    requireNonNull(planId, "planId is null"), requireNonNull(topicId, "topicId is null"));
        }
    }

    public record SourceRequest(String planId, String suggestedUrl, ContextLearningService.Boundary boundary, Long expectedRevision, Long expectedBuildSequence)
    {
        public SourceRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0 && expectedBuildSequence != null && expectedBuildSequence >= 0,
                    "Notebook revision and build sequence are required");
            new ContextLearningService.SourceRequest(planId, suggestedUrl, boundary, expectedRevision, expectedBuildSequence);
        }
    }

    public record DeclineRequest(String planId, String suggestedUrl)
    {
        public DeclineRequest
        {
            new ContextLearningService.DeclineRequest(planId, suggestedUrl);
        }
    }

    /** The plan with the question the next Continue learning run would take, so the UI never guesses the order. */
    public record PlanView(Optional<ContextLearningPlan> plan, Optional<ContextLearningPlan.Target> next)
    {
        static PlanView of(Optional<ContextLearningPlan> plan)
        {
            return new PlanView(plan, plan.flatMap(ContextLearningPlan::next));
        }
    }

    public record LearnRequest(String requestKey, Long expectedRevision, Long expectedBuildSequence, String reportId, List<String> topicIds,
            Optional<List<String>> excludedSourceIds)
    {
        public LearnRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0 && expectedBuildSequence != null && expectedBuildSequence >= 0,
                    "Notebook revision and build sequence are required");
            requireNonNull(excludedSourceIds, "excludedSourceIds is null");
            ContextLearningService.StartRequest checked = new ContextLearningService.StartRequest(
                    requestKey, expectedRevision, expectedBuildSequence, reportId, topicIds, excludedSourceIds.orElse(ImmutableList.of()));
            topicIds = checked.topicIds();
        }
    }

    public record DiscardRequest(String planId, Long expectedRevision, Long expectedBuildSequence)
    {
        public DiscardRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0 && expectedBuildSequence != null && expectedBuildSequence >= 0,
                    "Notebook revision and build sequence are required");
            new ContextLearningService.DiscardRequest(planId, expectedRevision, expectedBuildSequence);
        }
    }

    public record ContinueRequest(String requestKey, Long expectedRevision, Long expectedBuildSequence, String planId)
    {
        public ContinueRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0 && expectedBuildSequence != null && expectedBuildSequence >= 0,
                    "Notebook revision and build sequence are required");
            new ContextLearningService.ContinueRequest(requestKey, expectedRevision, expectedBuildSequence, planId);
        }
    }
}
