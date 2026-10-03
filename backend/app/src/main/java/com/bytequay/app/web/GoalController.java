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
import com.bytequay.app.service.goal.GoalItemView;
import com.bytequay.app.service.goal.GoalService;
import com.bytequay.app.service.goal.GoalView;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.GoalItemKind;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    public GoalController(GoalService goals, GoalConversationService conversations)
    {
        this.goals = requireNonNull(goals, "goals is null");
        this.conversations = requireNonNull(conversations, "conversations is null");
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
        return goals.update(workspaceId, goalId, Optional.ofNullable(request.title()), Optional.ofNullable(request.description()), Boolean.TRUE.equals(request.pinned()));
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
        return conversations.ask(workspaceId, goalId, request.operationId(), request.message());
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

    public record CreateGoalRequest(String title, String description) {}

    public record MessageRequest(String operationId, String message) {}

    public record AcceptRequest(String exchangeId, String title) {}

    public record UpdateGoalRequest(String title, String description, Boolean pinned) {}

    public record GoalItemRequest(GoalItemKind kind, String key) {}
}
