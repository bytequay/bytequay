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

import com.bytequay.app.jira.JiraDtos;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.service.goal.WorkItemCommand;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.app.service.goal.WorkItemView;
import com.bytequay.app.workitem.SpecWorkItems;
import com.bytequay.app.workitem.UnifiedWorkItem;
import com.bytequay.app.workitem.WorkItemCatalog;
import com.bytequay.app.workitem.WorkItemContextView;
import com.bytequay.app.workitem.WorkItemPublicationService;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final WorkItemCatalog catalog;
    private final WorkItemPublicationService publications;
    private final JiraService jira;
    private final SpecWorkItems specs;

    public WorkItemController(WorkItemService items, WorkItemCatalog catalog, WorkItemPublicationService publications, JiraService jira, SpecWorkItems specs)
    {
        this.items = requireNonNull(items, "items is null");
        this.catalog = requireNonNull(catalog, "catalog is null");
        this.publications = requireNonNull(publications, "publications is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.specs = requireNonNull(specs, "specs is null");
    }

    @PostMapping("/spec")
    public WorkItemView spec(@PathVariable String workspaceId, @RequestBody SpecFinding request)
    {
        return specs.save(workspaceId, request.checkId(), request.candidateId());
    }

    public record SpecFinding(String checkId, String candidateId) {}

    @GetMapping("/catalog")
    public List<UnifiedWorkItem> catalog(@PathVariable String workspaceId)
    {
        return catalog.list(workspaceId);
    }

    @GetMapping("/catalog/{id}")
    public UnifiedWorkItem read(@PathVariable String workspaceId, @PathVariable String id)
    {
        return catalog.read(workspaceId, id);
    }

    @PostMapping("/{itemId}/publish")
    public WorkItemView publish(@PathVariable String workspaceId, @PathVariable String itemId, @RequestBody WorkItemPublicationService.Publication request)
    {
        return publications.publish(workspaceId, itemId, request);
    }

    @PostMapping("/{itemId}/reconcile")
    public WorkItemView reconcile(@PathVariable String workspaceId, @PathVariable String itemId, @RequestBody Reconcile request)
    {
        return publications.reconcile(workspaceId, itemId, request.destination(), request.key());
    }

    @PostMapping("/jira-types")
    public List<JiraDtos.Choice> types(@PathVariable String workspaceId, @RequestParam String connectionId, @RequestParam String cloudId, @RequestParam String project)
    {
        items.list(workspaceId);
        return jira.publicationTypes(connectionId, cloudId, project);
    }

    public record Reconcile(WorkItemPublicationService.Publication destination, String key) {}

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
        checkArgument(request.goalId().isEmpty() || request.epicId().isEmpty(), "New work starts in a Goal or an Epic, not both");
        if (request.epicId().isPresent()) {
            return items.createInEpic(workspaceId, request.id(), request.epicId().orElseThrow(), request.title(), request.kind(), request.acceptanceCriteria(),
                    request.completionRule());
        }
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

    @PutMapping("/epic-order")
    public List<WorkItemView> orderEpic(@PathVariable String workspaceId, @RequestBody OrderEpicWork request)
    {
        checkArgument(request.epicId() != null && request.ids() != null, "Epic and order are required");
        return items.orderEpic(workspaceId, request.epicId(), request.ids());
    }

    @PostMapping("/epic")
    public WorkItemService.EpicJoin joinEpic(@PathVariable String workspaceId, @RequestBody JoinEpic request)
    {
        checkArgument(request.epicId() != null && request.kind() != null && request.key() != null, "Epic and reference are required");
        return items.joinEpic(workspaceId, request.epicId(), request.kind(), request.key());
    }

    @DeleteMapping("/{itemId}/epic")
    public RemovedFromEpic removeFromEpic(@PathVariable String workspaceId, @PathVariable String itemId)
    {
        return new RemovedFromEpic(items.removeFromEpic(workspaceId, itemId));
    }

    @PutMapping("/{itemId}/epic")
    public WorkItemService.EpicJoin assignEpic(@PathVariable String workspaceId, @PathVariable String itemId, @RequestBody AssignEpic request)
    {
        checkArgument(request.epicId() != null, "Epic is required");
        return items.joinLocalEpic(workspaceId, request.epicId(), itemId);
    }

    public record AssignEpic(String epicId) {}

    @GetMapping("/{itemId}/context-links")
    public List<WorkItemContextView> contextLinks(@PathVariable String workspaceId, @PathVariable String itemId)
    {
        return catalog.contextLinks(workspaceId, itemId);
    }

    @PutMapping("/{itemId}/context-links")
    public WorkItemView linkContext(@PathVariable String workspaceId, @PathVariable String itemId, @RequestBody ContextLink request)
    {
        return catalog.linkContext(workspaceId, itemId, request.expectedRevision(), request.targetId());
    }

    @DeleteMapping("/{itemId}/context-links")
    public WorkItemView unlinkContext(@PathVariable String workspaceId, @PathVariable String itemId, @RequestBody ContextLink request)
    {
        return catalog.unlinkContext(workspaceId, itemId, request.expectedRevision(), request.targetId());
    }

    public record ContextLink(long expectedRevision, String targetId) {}

    @PostMapping("/{itemId}/links")
    public WorkItemView link(@PathVariable String workspaceId, @PathVariable String itemId, @RequestBody LinkWorkItem request)
    {
        checkArgument(request.kind() != null && request.key() != null && request.expectedRevision() >= 0, "reference and revision are required");
        return items.link(workspaceId, itemId, request.expectedRevision(), request.kind(), request.key());
    }

    public record CreateWorkItem(String id, Optional<String> goalId, String title, WorkItem.Kind kind, String acceptanceCriteria, WorkItem.CompletionRule completionRule,
            Optional<String> epicId) {}

    public record LinkWorkItem(long expectedRevision, GoalItemKind kind, String key) {}

    /** Every work item of the Goal, in the order the user wants them. */
    public record OrderWork(String goalId, List<String> ids) {}

    /** Every work item of the Epic, in the order the user wants them. */
    public record OrderEpicWork(String epicId, List<String> ids) {}

    /** A source, task or pull request to put in the Epic. */
    public record JoinEpic(String epicId, GoalItemKind kind, String key) {}

    /** The retained work without its Epic. */
    public record RemovedFromEpic(Optional<WorkItemView> item) {}
}
