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

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
        return new PlanView(learning.get(workspaceId));
    }

    @PostMapping("/learn")
    public ContextBuildService.View learn(@PathVariable String workspaceId, @RequestBody LearnRequest request)
    {
        return learning.start(workspaceId, new ContextLearningService.StartRequest(
                request.requestKey(), request.expectedRevision(), request.expectedBuildSequence(), request.reportId(), request.topicIds()));
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
        return new PlanView(Optional.empty());
    }

    @PostMapping("/plan/sources")
    public PlanView allowSource(@PathVariable String workspaceId, @RequestBody SourceRequest request)
    {
        return new PlanView(Optional.of(learning.allowSource(workspaceId, new ContextLearningService.SourceRequest(
                request.planId(), Optional.ofNullable(request.sourceId()), Optional.ofNullable(request.suggestedUrl()),
                request.expectedRevision(), request.expectedBuildSequence()))));
    }

    public record SourceRequest(String planId, String sourceId, String suggestedUrl, Long expectedRevision, Long expectedBuildSequence)
    {
        public SourceRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0 && expectedBuildSequence != null && expectedBuildSequence >= 0,
                    "Notebook revision and build sequence are required");
            new ContextLearningService.SourceRequest(planId, Optional.ofNullable(sourceId), Optional.ofNullable(suggestedUrl),
                    expectedRevision, expectedBuildSequence);
        }
    }

    public record PlanView(Optional<ContextLearningPlan> plan) {}

    public record LearnRequest(String requestKey, Long expectedRevision, Long expectedBuildSequence, String reportId, List<String> topicIds)
    {
        public LearnRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0 && expectedBuildSequence != null && expectedBuildSequence >= 0,
                    "Notebook revision and build sequence are required");
            ContextLearningService.StartRequest checked = new ContextLearningService.StartRequest(
                    requestKey, expectedRevision, expectedBuildSequence, reportId, topicIds);
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
