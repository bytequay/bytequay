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

import com.bytequay.app.service.goal.GoalItemView;
import com.bytequay.app.service.goal.GoalService;
import com.bytequay.app.service.goal.GoalView;
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

/** Local workspace goals; nothing here reaches GitHub or Jira. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/goals")
public class GoalController
{
    private final GoalService goals;

    public GoalController(GoalService goals)
    {
        this.goals = requireNonNull(goals, "goals is null");
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

    public record CreateGoalRequest(String title, String description) {}

    public record UpdateGoalRequest(String title, String description, Boolean pinned) {}

    public record GoalItemRequest(GoalItemKind kind, String key) {}
}
