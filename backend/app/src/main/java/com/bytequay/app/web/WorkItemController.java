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

import com.bytequay.app.service.goal.WorkItemCommand;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.app.service.goal.WorkItemView;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
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

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/work-items")
public class WorkItemController
{
    private final WorkItemService items;

    public WorkItemController(WorkItemService items)
    {
        this.items = requireNonNull(items, "items is null");
    }

    @GetMapping
    public List<WorkItemView> list(@PathVariable String workspaceId)
    {
        return items.list(workspaceId);
    }

    @PostMapping
    public WorkItemView create(@PathVariable String workspaceId, @RequestBody CreateWorkItem request)
    {
        checkArgument(request.id() != null && request.title() != null && request.kind() != null && request.acceptanceCriteria() != null && request.completionRule() != null,
                "id, title, kind, acceptance criteria and completion rule are required");
        return items.create(workspaceId, request.id(), request.goalId(), request.title(), request.kind(), request.acceptanceCriteria(), request.completionRule());
    }

    @PostMapping("/{itemId}/decisions")
    public WorkItemView change(@PathVariable String workspaceId, @PathVariable String itemId, @RequestBody WorkItemCommand command)
    {
        return items.change(workspaceId, itemId, command);
    }

    @PutMapping("/order")
    public List<WorkItemView> order(@PathVariable String workspaceId, @RequestBody OrderWork request)
    {
        checkArgument(request.goalId() != null && request.ids() != null, "goal and order are required");
        return items.order(workspaceId, request.goalId(), request.ids());
    }

    @PostMapping("/{itemId}/links")
    public WorkItemView link(@PathVariable String workspaceId, @PathVariable String itemId, @RequestBody LinkWorkItem request)
    {
        checkArgument(request.kind() != null && request.key() != null && request.expectedRevision() >= 0, "reference and revision are required");
        return items.link(workspaceId, itemId, request.expectedRevision(), request.kind(), request.key());
    }

    public record CreateWorkItem(String id, Optional<String> goalId, String title, WorkItem.Kind kind, String acceptanceCriteria, WorkItem.CompletionRule completionRule) {}

    public record LinkWorkItem(long expectedRevision, GoalItemKind kind, String key) {}

    /** Every work item of the Goal, in the order the user wants them. */
    public record OrderWork(String goalId, List<String> ids) {}
}
