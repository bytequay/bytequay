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
package com.bytequay.app.service.goal;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.GoalStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

@Service
public class WorkItemService
{
    private final WorkItemStore items;
    private final GoalStore goals;
    private final WorkspaceStore workspaces;
    private final GoalReferenceReader references;
    private final AgentUnitOfWork unitOfWork;
    private final Clock clock;

    public WorkItemService(WorkItemStore items, GoalStore goals, WorkspaceStore workspaces, GoalReferenceReader references, AgentUnitOfWork unitOfWork, Clock clock)
    {
        this.items = requireNonNull(items, "items is null");
        this.goals = requireNonNull(goals, "goals is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.references = requireNonNull(references, "references is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public List<WorkItemView> list(String workspaceId)
    {
        requireWorkspace(workspaces, workspaceId);
        return items.list(workspaceId).stream().map(this::view).collect(toImmutableList());
    }

    public WorkItemView create(String workspaceId, String id, Optional<String> goalId, String title, WorkItem.Kind kind,
            String criteria, WorkItem.CompletionRule rule)
    {
        requireWorkspace(workspaces, workspaceId);
        UUID.fromString(id);
        requireGoal(workspaceId, goalId);
        String name = checkText(title, "title", 500).trim();
        return unitOfWork.execute(() -> {
            Optional<WorkItem> existing = items.find(id);
            String fingerprint = goalId + "|" + name + "|" + kind + "|" + criteria + "|" + rule;
            if (existing.isPresent()) {
                WorkItem item = requireItem(workspaceId, id);
                checkArgument(item.history().getFirst().fingerprint().equals(fingerprint), "Work item creation request changed");
                return view(item);
            }
            Instant now = clock.instant();
            WorkItem item = new WorkItem(id, workspaceId, goalId, name, kind, criteria, rule, WorkItem.State.PLANNED, 0, ImmutableList.of(),
                    ImmutableList.of(new WorkItemEvent(id, fingerprint, "CREATED",
                            "Work item accepted: " + name + "\nSuccess: " + criteria + "\nCompletion: " + completionText(rule), now)), now, now);
            items.insert(item);
            return view(item);
        });
    }

    public WorkItemView attach(String workspaceId, String goalId, GoalItemKind kind, String key)
    {
        requireGoal(workspaceId, Optional.of(goalId));
        WorkItemLink requested = references.requireLink(workspaceId, kind, key);
        List<WorkItemLink> links = references.related(workspaceId, requested);
        return unitOfWork.execute(() -> {
            WorkItem item = findOwner(workspaceId, links).orElseGet(() -> newLinked(workspaceId, requested));
            requireItemWorkspace(item, workspaceId);
            if (item.goalId().isPresent() && !item.goalId().orElseThrow().equals(goalId)) {
                throw conflict("This work item belongs to another Goal. Move it explicitly from its work-item details.");
            }
            return view(saveLinks(item, Optional.of(goalId), links, "Linked to Goal"));
        });
    }

    public WorkItemView link(String workspaceId, String id, long revision, GoalItemKind kind, String key)
    {
        WorkItemLink link = references.requireLink(workspaceId, kind, key);
        List<WorkItemLink> links = references.related(workspaceId, link);
        return unitOfWork.execute(() -> {
            WorkItem item = requireItem(workspaceId, id);
            if (links.stream().allMatch(added -> item.links().stream().anyMatch(old -> old.identity().equals(added.identity())))) {
                return view(item);
            }
            requireRevision(item, revision);
            checkArgument(item.state() != WorkItem.State.DONE && item.state() != WorkItem.State.DROPPED, "Reopen before changing accepted scope");
            for (WorkItemLink added : links) {
                items.findByIdentity(added.identity()).filter(owner -> !owner.id().equals(id)).ifPresent(owner -> {
                    throw conflict("Source or task belongs to work item " + owner.id() + ". Open that item instead.");
                });
            }
            return view(saveLinks(item, item.goalId(), links, "Linked supporting work"));
        });
    }

    /** Rejects a new attempt on concluded or another workspace's work before task creation prepares a worktree. */
    public void requireStartable(String workspaceId, String sourceIdentity)
    {
        items.findByIdentity(sourceIdentity).ifPresent(item -> requireStartable(item, workspaceId));
    }

    /**
     * Called in the existing task/source transaction, before the first turn is admitted. It takes no Java lock:
     * the caller holds the only database connection, so waiting here would deadlock against a write waiting for it.
     */
    public void recordAttempt(AgentTask task, WorkItemLink source)
    {
        String workspaceId = task.workspaceId().orElseThrow();
        WorkItem item = items.findByIdentity(source.identity()).orElseGet(() -> newLinked(workspaceId, source));
        requireStartable(item, workspaceId);
        WorkItemLink taskLink = GoalReferenceReader.taskLink(task);
        WorkItemLink attempt = new WorkItemLink(taskLink.identity(), taskLink.reference(), source.issueSource(), source.jiraSource());
        saveLinks(item, item.goalId(), ImmutableList.of(source, attempt), "Execution attempt started");
    }

    public boolean hasWork(String workspaceId, String goalId)
    {
        return items.list(workspaceId).stream().anyMatch(item -> item.goalId().filter(goalId::equals).isPresent());
    }

    public void detach(String workspaceId, String goalId, GoalItemKind kind, String key)
    {
        unitOfWork.execute(() -> {
            items.list(workspaceId).stream().filter(item -> item.goalId().filter(goalId::equals).isPresent())
                    .filter(item -> item.links().stream().anyMatch(link -> link.reference().is(kind,
                            kind == GoalItemKind.PULL_REQUEST || kind == GoalItemKind.ISSUE ? key.toLowerCase(Locale.ROOT) : key)))
                    .forEach(item -> saveLinks(item, Optional.empty(), ImmutableList.of(), "Removed from Goal; work and history retained"));
            return null;
        });
    }

    public WorkItemView change(String workspaceId, String id, WorkItemCommand command)
    {
        return unitOfWork.execute(() -> {
            WorkItem item = requireItem(workspaceId, id);
            Optional<WorkItemEvent> replay = item.history().stream().filter(event -> event.operationId().equals(command.operationId())).findFirst();
            if (replay.isPresent()) {
                checkArgument(replay.orElseThrow().fingerprint().equals(command.toString()), "Operation content changed");
                return view(item);
            }
            requireRevision(item, command.expectedRevision());
            WorkItem.State state = item.state();
            Optional<String> goalId = item.goalId();
            String title = item.title();
            String criteria = item.acceptanceCriteria();
            WorkItem.CompletionRule rule = item.completionRule();
            String detail = command.detail().trim();
            switch (command.action()) {
                case EDIT -> {
                    checkArgument(state != WorkItem.State.DONE && state != WorkItem.State.DROPPED, "Reopen before changing accepted scope");
                    title = command.title().map(String::trim).orElse(title);
                    criteria = command.acceptanceCriteria().map(String::trim).orElse(criteria);
                    rule = command.completionRule().orElse(rule);
                    detail = "Updated accepted scope: " + title + " · " + criteria + " · " + completionText(rule);
                }
                case BEGIN -> {
                    checkArgument(state == WorkItem.State.PLANNED, "Only planned work can begin");
                    state = WorkItem.State.WORKING;
                    detail = "Work started";
                }
                case ACCEPT -> {
                    checkArgument(state != WorkItem.State.DONE && state != WorkItem.State.DROPPED, "Work is already concluded");
                    checkText(criteria, "acceptanceCriteria");
                    checkText(detail, "accepted result and scope coverage");
                    if (rule == WorkItem.CompletionRule.MERGED_DELIVERY) {
                        List<GoalItemView> pulls = item.links().stream().filter(link -> link.reference().kind() == GoalItemKind.PULL_REQUEST)
                                .map(link -> references.view(workspaceId, link.reference())).collect(toImmutableList());
                        // A PR closed without merging is an abandoned attempt: never delivery, and never a blocker for the PR that replaced it.
                        // ponytail: links are never removed, so a wrongly linked open PR blocks until it closes; add unlink if that bites.
                        boolean delivered = pulls.stream().anyMatch(pull -> pull.state() == GoalItemState.MERGED);
                        boolean settled = pulls.stream().allMatch(pull -> pull.state() == GoalItemState.MERGED || pull.state() == GoalItemState.ENDED);
                        checkArgument(delivered && settled, "Link a merged PR and refresh the others until each is merged or closed before accepting delivery");
                        detail += "\nObserved merged PRs: " + urls(pulls, GoalItemState.MERGED);
                        if (pulls.stream().anyMatch(pull -> pull.state() == GoalItemState.ENDED)) {
                            detail += "\nAbandoned PRs, closed without merging: " + urls(pulls, GoalItemState.ENDED);
                        }
                    }
                    detail = "User accepted all criteria: " + criteria + "\n" + detail;
                    state = WorkItem.State.DONE;
                }
                case DROP -> {
                    checkArgument(state != WorkItem.State.DROPPED, "Work is already dropped");
                    checkText(detail, "reason");
                    state = WorkItem.State.DROPPED;
                }
                case REOPEN -> {
                    checkArgument(state == WorkItem.State.DONE || state == WorkItem.State.DROPPED, "Only concluded work can reopen");
                    checkText(detail, "reason");
                    state = WorkItem.State.WORKING;
                }
                case MOVE -> {
                    requireGoal(workspaceId, command.goalId());
                    goalId = command.goalId();
                    detail = goalId.map(value -> "Moved to Goal " + value).orElse("Removed from Goal; work and history retained");
                }
            }
            Instant now = clock.instant();
            WorkItem updated = new WorkItem(id, workspaceId, goalId, title, item.kind(), criteria, rule, state, item.revision() + 1, item.links(),
                    ImmutableList.<WorkItemEvent>builder().addAll(item.history())
                            .add(new WorkItemEvent(command.operationId(), command.toString(), command.action().name(), detail, now)).build(), item.createdAt(), now);
            update(updated, item.revision());
            return view(updated);
        });
    }

    private Optional<WorkItem> findOwner(String workspaceId, List<WorkItemLink> links)
    {
        LinkedHashMap<String, WorkItem> found = new LinkedHashMap<>();
        for (WorkItemLink link : links) {
            items.findByIdentity(link.identity()).ifPresent(item -> found.put(item.id(), item));
        }
        // Also recognize PRs connected to already retained task identities, including a later publication.
        for (WorkItem item : items.list(workspaceId)) {
            if (item.links().stream().flatMap(link -> references.related(workspaceId, link).stream())
                    .anyMatch(existing -> links.stream().anyMatch(link -> link.identity().equals(existing.identity())))) {
                found.put(item.id(), item);
            }
        }
        checkArgument(found.size() <= 1, "These references belong to different work items; link the intended PR explicitly");
        return found.values().stream().findFirst();
    }

    private WorkItem newLinked(String workspaceId, WorkItemLink source)
    {
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        WorkItem item = new WorkItem(id, workspaceId, Optional.empty(), source.reference().title(), WorkItem.Kind.DEVELOP, "",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(source),
                ImmutableList.of(new WorkItemEvent(id, source.identity(), "CREATED", "Accepted work from " + source.reference().label(), now)), now, now);
        items.insert(item);
        return item;
    }

    private WorkItem saveLinks(WorkItem item, Optional<String> goalId, List<WorkItemLink> links, String reason)
    {
        LinkedHashMap<String, WorkItemLink> all = new LinkedHashMap<>();
        item.links().forEach(link -> all.put(link.identity(), link));
        links.forEach(link -> all.putIfAbsent(link.identity(), link));
        List<WorkItemLink> linked = ImmutableList.copyOf(all.values());
        if (item.links().equals(linked) && item.goalId().equals(goalId)) {
            return item;
        }
        WorkItem.State state = item.state();
        checkArgument(linked.equals(item.links()) || state != WorkItem.State.DONE && state != WorkItem.State.DROPPED, "Reopen before adding evidence to concluded work");
        if (state == WorkItem.State.PLANNED && linked.stream().anyMatch(link -> link.reference().kind() == GoalItemKind.TASK)) {
            state = WorkItem.State.WORKING;
        }
        Instant now = clock.instant();
        WorkItem next = new WorkItem(item.id(), item.workspaceId(), goalId, item.title(), item.kind(), item.acceptanceCriteria(), item.completionRule(), state,
                item.revision() + 1, linked, ImmutableList.<WorkItemEvent>builder().addAll(item.history())
                        .add(new WorkItemEvent(UUID.randomUUID().toString(), "", "LINKED", reason, now)).build(), item.createdAt(), now);
        update(next, item.revision());
        return next;
    }

    private WorkItemView view(WorkItem item)
    {
        List<GoalItemView> links = item.links().stream().map(link -> references.view(item.workspaceId(), link.reference())).collect(toImmutableList());
        return new WorkItemView(item.id(), item.workspaceId(), item.goalId(), item.title(), item.kind(), item.acceptanceCriteria(), item.completionRule(),
                item.state(), item.revision(), links, item.history(), item.createdAt(), item.updatedAt());
    }

    private WorkItem requireItem(String workspaceId, String id)
    {
        WorkItem item = items.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Work item not found"));
        requireItemWorkspace(item, workspaceId);
        return item;
    }

    private static void requireItemWorkspace(WorkItem item, String workspaceId)
    {
        if (!item.workspaceId().equals(workspaceId)) {
            throw conflict("Work item belongs to workspace " + item.workspaceId() + ". Open its existing workspace.");
        }
    }

    private static void requireStartable(WorkItem item, String workspaceId)
    {
        requireItemWorkspace(item, workspaceId);
        if (item.state() == WorkItem.State.DONE || item.state() == WorkItem.State.DROPPED) {
            throw conflict("Reopen the work item before starting another attempt.");
        }
    }

    private static String urls(List<GoalItemView> pulls, GoalItemState state)
    {
        return pulls.stream().filter(pull -> pull.state() == state).map(pull -> pull.url().orElse(pull.key())).collect(joining(", "));
    }

    private void requireGoal(String workspaceId, Optional<String> id)
    {
        id.ifPresent(value -> goals.find(value).filter(goal -> goal.workspaceId().equals(workspaceId) && !goal.archived())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found")));
    }

    private static void requireRevision(WorkItem item, long revision)
    {
        if (item.revision() != revision) {
            throw conflict("Work item changed. Refresh before applying this decision.");
        }
    }

    private void update(WorkItem item, long expectedRevision)
    {
        if (!items.update(item, expectedRevision)) {
            throw conflict("Work item changed. Refresh before applying this decision.");
        }
    }

    private static String completionText(WorkItem.CompletionRule rule)
    {
        return rule == WorkItem.CompletionRule.MERGED_DELIVERY ? "Merged delivery" : "User accepted result";
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
