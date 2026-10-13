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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
public class LocalReviewController
{
    private final LocalReviewService reviews;

    public LocalReviewController(LocalReviewService reviews)
    {
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    @GetMapping("/agent/tasks/{taskId}/local-review-comments")
    public List<LocalReviewView> list(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        return reviews.list(taskId, workspaceId);
    }

    @GetMapping("/agent/local-review-comments")
    public List<LocalReviewView> listPullRequest(@RequestParam String repo, @RequestParam int number, @RequestParam Optional<String> workspaceId)
    {
        return reviews.listPullRequest(repo, number, workspaceId);
    }

    @PostMapping("/agent/tasks/{taskId}/local-review-comments")
    public LocalReviewView create(@PathVariable String taskId, @RequestParam String workspaceId,
            @RequestHeader("Idempotency-Key") String operationKey, @RequestBody LocalReviewService.Create request)
    {
        return reviews.create(taskId, workspaceId, request, operationKey);
    }

    @PostMapping("/agent/tasks/{taskId}/local-review-comments/{id}/messages")
    public LocalReviewView reply(@PathVariable String taskId, @PathVariable String id, @RequestParam String workspaceId,
            @RequestHeader("Idempotency-Key") String operationKey, @RequestBody Reply request)
    {
        return reviews.reply(taskId, workspaceId, id, request.messageId(), request.body(), request.expectedTaskVersion(), operationKey);
    }

    @PostMapping("/agent/tasks/{taskId}/local-review-comments/{id}/messages/{messageId}/retry")
    public LocalReviewView retry(@PathVariable String taskId, @PathVariable String id, @PathVariable String messageId, @RequestParam String workspaceId, @RequestBody Retry request)
    {
        return reviews.retry(taskId, workspaceId, id, messageId, request.expectedTaskVersion());
    }

    @PatchMapping("/agent/tasks/{taskId}/local-review-comments/{id}")
    public LocalReviewView resolve(@PathVariable String taskId, @PathVariable String id, @RequestParam String workspaceId,
            @RequestHeader("Idempotency-Key") String operationKey, @RequestBody Resolve request)
    {
        return reviews.resolve(taskId, workspaceId, id, request.resolved(), request.expectedVersion(), operationKey);
    }

    public record Reply(String messageId, String body, long expectedTaskVersion) {}

    public record Retry(long expectedTaskVersion) {}

    public record Resolve(boolean resolved, long expectedVersion) {}
}
