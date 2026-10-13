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

import com.bytequay.app.discussion.AskCommentCommand;
import com.bytequay.app.discussion.AskDiscussionCommand;
import com.bytequay.app.discussion.OpenDiscussionCommand;
import com.bytequay.app.discussion.TaskDiscussionService;
import com.bytequay.app.discussion.TaskDiscussionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
public class TaskDiscussionController
{
    private final TaskDiscussionService discussions;

    public TaskDiscussionController(TaskDiscussionService discussions)
    {
        this.discussions = requireNonNull(discussions, "discussions is null");
    }

    @GetMapping("/agent/tasks/{taskId}/discussions")
    public List<TaskDiscussionView> list(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        return discussions.list(workspaceId, taskId);
    }

    @PostMapping("/agent/tasks/{taskId}/discussions")
    public TaskDiscussionView open(@PathVariable String taskId, @RequestParam String workspaceId, @RequestBody Open request)
    {
        return discussions.open(new OpenDiscussionCommand(workspaceId, taskId, request.turnId(), request.quote(), request.occurrence(), request.question()));
    }

    @PostMapping("/agent/tasks/{taskId}/discussions/comments")
    public TaskDiscussionView askComment(@PathVariable String taskId, @RequestParam String workspaceId, @RequestBody AskComment request)
    {
        return discussions.askComment(new AskCommentCommand(workspaceId, taskId, request.repo(), request.number(), request.key(), request.question()));
    }

    /** Only drafts: the text reaches GitHub solely through the user's own Comment on the thread's reply editor. */
    @PostMapping("/agent/tasks/{taskId}/discussions/{id}/reply-draft")
    public ReplyDraft draftReply(@PathVariable String taskId, @PathVariable String id, @RequestParam String workspaceId)
    {
        return new ReplyDraft(discussions.draftReply(workspaceId, taskId, id));
    }

    @PostMapping("/agent/tasks/{taskId}/discussions/{id}/messages")
    public TaskDiscussionView ask(@PathVariable String taskId, @PathVariable String id,
            @RequestParam String workspaceId, @RequestBody Ask request)
    {
        return discussions.ask(new AskDiscussionCommand(workspaceId, taskId, id, request.question()));
    }

    @PostMapping("/agent/tasks/{taskId}/discussions/{id}/messages/{messageId}/retry")
    public TaskDiscussionView retry(@PathVariable String taskId, @PathVariable String id,
            @PathVariable String messageId, @RequestParam String workspaceId)
    {
        return discussions.retry(workspaceId, taskId, id, messageId);
    }

    @PatchMapping("/agent/tasks/{taskId}/discussions/{id}")
    public TaskDiscussionView resolve(@PathVariable String taskId, @PathVariable String id,
            @RequestParam String workspaceId, @RequestBody Resolve request)
    {
        return discussions.resolve(workspaceId, taskId, id, request.resolved(), request.expectedVersion());
    }

    public record Open(String turnId, String quote, int occurrence, String question) {}

    public record Ask(String question) {}

    public record Resolve(boolean resolved, long expectedVersion) {}

    public record AskComment(String repo, int number, String key, String question) {}

    public record ReplyDraft(String text) {}
}
