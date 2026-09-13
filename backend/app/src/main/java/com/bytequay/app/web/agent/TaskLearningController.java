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
package com.bytequay.app.web.agent;

import com.bytequay.app.localreview.LocalReviewService;
import com.bytequay.app.localreview.LocalReviewView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
public class TaskLearningController
{
    private final LocalReviewService reviews;

    public TaskLearningController(LocalReviewService reviews)
    {
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    @GetMapping("/agent/tasks/{taskId}/learning")
    public Optional<InteractiveAgentHttpContract.TurnResponse> latest(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        return reviews.latestLearning(taskId, workspaceId).map(InteractiveAgentHttpContract::turn);
    }

    @PostMapping("/agent/tasks/{taskId}/learning/comments")
    public LocalReviewView create(@PathVariable String taskId, @RequestParam String workspaceId,
            @RequestHeader("Idempotency-Key") String operationKey, @RequestBody LocalReviewService.LearningCreate request)
    {
        return reviews.createLearning(taskId, workspaceId, request, operationKey);
    }
}
