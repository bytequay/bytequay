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
package com.bytequay.app.web.review;

import com.bytequay.app.discussion.TaskDiscussionService;
import com.bytequay.app.discussion.TaskDiscussionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Private discussions of a review's answers and its pull request's GitHub comments. They belong to the review's
 * task, reached only through the review, because the interactive task routes refuse a workflow-owned task.
 */
@RestController
@RequestMapping("/api/agent-review/{reviewId}/discussions")
public class ReviewThreadController
{
    private final TaskDiscussionService discussions;

    public ReviewThreadController(TaskDiscussionService discussions)
    {
        this.discussions = requireNonNull(discussions, "discussions is null");
    }

    @GetMapping
    public List<TaskDiscussionView> list(@PathVariable String reviewId)
    {
        return discussions.listReviewThreads(reviewId);
    }

    @PostMapping
    public TaskDiscussionView open(@PathVariable String reviewId, @RequestBody Open request)
    {
        return discussions.openReviewDiscussion(reviewId, request.turnId(), request.quote(), request.occurrence(), request.question());
    }

    @PostMapping("/{id}/messages")
    public TaskDiscussionView askPassage(@PathVariable String reviewId, @PathVariable String id, @RequestBody Question request)
    {
        return discussions.askReviewDiscussion(reviewId, id, request.question());
    }

    @PostMapping("/comments")
    public TaskDiscussionView ask(@PathVariable String reviewId, @RequestBody Ask request)
    {
        return discussions.askReviewComment(reviewId, request.key(), request.question());
    }

    @PatchMapping("/{id}")
    public TaskDiscussionView resolve(@PathVariable String reviewId, @PathVariable String id, @RequestBody Resolve request)
    {
        return discussions.resolveReviewThread(reviewId, id, request.resolved(), request.expectedVersion());
    }

    @PostMapping("/{id}/messages/{messageId}/retry")
    public TaskDiscussionView retry(@PathVariable String reviewId, @PathVariable String id, @PathVariable String messageId)
    {
        return discussions.retryReviewThread(reviewId, id, messageId);
    }

    /** Only drafts: the text reaches GitHub solely through the user's own Comment on the thread's reply editor. */
    @PostMapping("/{id}/reply-draft")
    public ReplyDraft draftReply(@PathVariable String reviewId, @PathVariable String id)
    {
        return new ReplyDraft(discussions.draftReviewReply(reviewId, id));
    }

    public record Ask(String key, String question) {}

    public record Open(String turnId, String quote, int occurrence, String question) {}

    public record Question(String question) {}

    public record Resolve(boolean resolved, long expectedVersion) {}

    public record ReplyDraft(String text) {}
}
