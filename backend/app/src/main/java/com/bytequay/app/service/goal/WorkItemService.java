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
import com.bytequay.app.service.epic.EpicSources;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.GoalStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

@Service
public class WorkItemService
{
    private final WorkItemStore items;
    private final GoalStore goals;
    private final WorkspaceStore workspaces;
    private final GoalReferenceReader references;
    private final EpicSources epics;
    private final EpicStore epicRecords;
    private final AgentUnitOfWork unitOfWork;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public WorkItemService(WorkItemStore items, GoalStore goals, WorkspaceStore workspaces, GoalReferenceReader references, EpicSources epics,
            EpicStore epicRecords, AgentUnitOfWork unitOfWork, ApplicationEventPublisher events, Clock clock)
    {
        this.items = requireNonNull(items, "items is null");
        this.goals = requireNonNull(goals, "goals is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.references = requireNonNull(references, "references is null");
        this.epics = requireNonNull(epics, "epics is null");
        this.epicRecords = requireNonNull(epicRecords, "epicRecords is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.events = requireNonNull(events, "events is null");
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
        return insert(workspaceId, id, goalId, Optional.empty(), title, kind, criteria, rule, goalId + "|");
    }

    /** New work at the end of an Epic's order; it belongs to no Goal until the user moves it to one. */
    public WorkItemView createInEpic(String workspaceId, String id, String epicId, String title, WorkItem.Kind kind, String criteria,
            WorkItem.CompletionRule rule)
    {
        requireWorkspace(workspaces, workspaceId);
        UUID.fromString(id);
        epics.require(workspaceId, epicId);
        return insert(workspaceId, id, Optional.empty(), Optional.of(epicId), title, kind, criteria, rule, "epic:" + epicId + "|");
    }

    private WorkItemView insert(String workspaceId, String id, Optional<String> goalId, Optional<String> epicId, String title, WorkItem.Kind kind,
            String criteria, WorkItem.CompletionRule rule, String scope)
    {
        String name = checkText(title, "title", 500).trim();
        return unitOfWork.execute(() -> {
            Optional<WorkItem> existing = items.find(id);
            String fingerprint = scope + name + "|" + kind + "|" + criteria + "|" + rule;
            if (existing.isPresent()) {
                WorkItem item = requireItem(workspaceId, id);
                checkArgument(item.history().getFirst().fingerprint().equals(fingerprint), "Work item creation request changed");
                return view(item);
            }
            Instant now = clock.instant();
            WorkItem item = new WorkItem(id, workspaceId, goalId, name, kind, criteria, rule, WorkItem.State.PLANNED, 0, ImmutableList.of(),
                    ImmutableList.of(new WorkItemEvent(id, fingerprint, "CREATED",
                            "Work item accepted: " + name + "\nSuccess: " + criteria + "\nCompletion: " + completionText(rule), now)), now, now,
                    WorkItem.Priority.NORMAL, nextRank(workspaceId, goalId), ImmutableList.of(), epicId, nextEpicRank(workspaceId, epicId));
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
            return view(saveLinks(item, Optional.of(goalId), item.epicId(), links, "Linked to Goal"));
        });
    }

    /**
     * Makes a recommended source the Goal's work: the work item that already owns the source joins the Goal, or a new one takes the card's
     * title and criteria under merged delivery. The task the user then sends for the source becomes its attempt through the existing
     * admission. Starting the same card again changes nothing.
     */
    public WorkItemView adopt(String workspaceId, String goalId, String id, String title, String criteria, GoalItemKind kind, String key)
    {
        requireGoal(workspaceId, Optional.of(goalId));
        UUID.fromString(id);
        String name = checkText(title, "title", 500).trim();
        checkText(criteria, "acceptanceCriteria");
        WorkItemLink requested = references.requireLink(workspaceId, kind, key);
        List<WorkItemLink> links = references.related(workspaceId, requested);
        return unitOfWork.execute(() -> {
            Optional<WorkItem> owner = findOwner(workspaceId, links);
            if (owner.isPresent()) {
                WorkItem item = owner.orElseThrow();
                requireStartable(item, workspaceId);
                if (item.goalId().filter(other -> !other.equals(goalId)).isPresent()) {
                    throw conflict("This work belongs to another Goal. Move it explicitly from its work-item details.");
                }
                return view(saveLinks(item, Optional.of(goalId), item.epicId(), links, "Recommended as practice for the Goal"));
            }
            Instant now = clock.instant();
            WorkItem.CompletionRule rule = WorkItem.CompletionRule.MERGED_DELIVERY;
            WorkItem.State state = links.stream().anyMatch(link -> link.reference().kind() == GoalItemKind.TASK) ? WorkItem.State.WORKING : WorkItem.State.PLANNED;
            WorkItem item = new WorkItem(id, workspaceId, Optional.of(goalId), name, WorkItem.Kind.DEVELOP, criteria.trim(), rule, state, 0, links,
                    ImmutableList.of(new WorkItemEvent(id, requested.identity(), "CREATED",
                            "Practice accepted: " + name + "\nSuccess: " + criteria.trim() + "\nCompletion: " + completionText(rule), now)), now, now,
                    WorkItem.Priority.NORMAL, nextRank(workspaceId, Optional.of(goalId)), ImmutableList.of(), Optional.empty(), 0);
            items.insert(item);
            return view(item);
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
            return view(saveLinks(item, item.goalId(), item.epicId(), links, "Linked supporting work"));
        });
    }

    /**
     * Picks up what the work's tasks connect to now, such as the pull request a task published after the work was linked. Concluded work
     * keeps the evidence it was decided on, and a source or task another work item owns stays with it.
     */
    public WorkItemView linkRelated(String workspaceId, String id)
    {
        WorkItem current = requireItem(workspaceId, id);
        if (concluded(current)) {
            return view(current);
        }
        List<WorkItemLink> related = current.links().stream().filter(link -> link.reference().kind() == GoalItemKind.TASK)
                .flatMap(link -> references.related(workspaceId, link).stream()).collect(toImmutableList());
        return unitOfWork.execute(() -> {
            WorkItem item = requireItem(workspaceId, id);
            List<WorkItemLink> added = related.stream()
                    .filter(link -> item.links().stream().noneMatch(old -> old.identity().equals(link.identity())))
                    .filter(link -> items.findByIdentity(link.identity()).isEmpty())
                    .collect(toImmutableList());
            if (added.isEmpty() || concluded(item)) {
                return view(item);
            }
            return view(saveLinks(item, item.goalId(), item.epicId(), added, "Linked what its task published"));
        });
    }

    private static boolean concluded(WorkItem item)
    {
        return item.state() == WorkItem.State.DONE || item.state() == WorkItem.State.DROPPED;
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
        saveLinks(item, item.goalId(), item.epicId(), ImmutableList.of(source, attempt), "Execution attempt started");
    }

    public void detach(String workspaceId, String goalId, GoalItemKind kind, String key)
    {
        unitOfWork.execute(() -> {
            items.list(workspaceId).stream().filter(item -> item.goalId().filter(goalId::equals).isPresent())
                    .filter(item -> item.links().stream().anyMatch(link -> link.reference().is(kind,
                            kind == GoalItemKind.PULL_REQUEST || kind == GoalItemKind.ISSUE ? key.toLowerCase(Locale.ROOT) : key)))
                    .forEach(item -> saveLinks(item, Optional.empty(), item.epicId(), ImmutableList.of(), "Removed from Goal; work and history retained"));
            return null;
        });
    }

    /** Records a task result in the work item's history, once per operation; whether the item is done stays the user's decision. */
    public void receipt(String workspaceId, String id, String operationId, String detail)
    {
        record(workspaceId, id, operationId, "RECEIPT", detail);
    }

    /** Saves a summary of finished work in its history, once per operation; it changes neither the item's state nor its accepted scope. */
    public void summary(String workspaceId, String id, String operationId, String summary)
    {
        record(workspaceId, id, operationId, "SUMMARY", summary);
    }

    private void record(String workspaceId, String id, String operationId, String action, String detail)
    {
        unitOfWork.execute(() -> {
            WorkItem item = requireItem(workspaceId, id);
            if (item.history().stream().anyMatch(event -> event.operationId().equals(operationId))) {
                return null;
            }
            Instant now = clock.instant();
            WorkItem updated = new WorkItem(id, workspaceId, item.goalId(), item.title(), item.kind(), item.acceptanceCriteria(), item.completionRule(), item.state(),
                    item.revision() + 1, item.links(), ImmutableList.<WorkItemEvent>builder().addAll(item.history())
                            .add(new WorkItemEvent(operationId, "", action, detail, now)).build(), item.createdAt(), now, item.priority(), item.rank(), item.blockedBy(),
                    item.epicId(), item.epicRank());
            update(updated, item.revision());
            return null;
        });
    }

    public WorkItemView change(String workspaceId, String id, WorkItemCommand command)
    {
        AtomicReference<WorkItemDecided> decided = new AtomicReference<>();
        WorkItemView changed = unitOfWork.execute(() -> {
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
            WorkItem.Priority priority = item.priority();
            long rank = item.rank();
            List<String> blockedBy = item.blockedBy();
            List<WorkItem> released = ImmutableList.of();
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
                    // One click can settle what the user accepts against, as when quick-added work or a ticket closed upstream is accepted.
                    criteria = command.acceptanceCriteria().map(String::trim).orElse(criteria);
                    rule = command.completionRule().orElse(rule);
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
                    // Dropped work is not done: the work waiting on it changes only through the user's decision.
                    released = dependents(workspaceId, id);
                    List<String> waiting = released.stream().filter(WorkItemService::open).map(WorkItem::title).collect(toImmutableList());
                    if (!waiting.isEmpty() && !command.releaseDependents()) {
                        throw conflict("Waiting on this work: " + String.join(", ", waiting) + ". Drop it and remove those dependencies, or keep it.");
                    }
                    state = WorkItem.State.DROPPED;
                }
                case REOPEN -> {
                    checkArgument(state == WorkItem.State.DONE || state == WorkItem.State.DROPPED, "Only concluded work can reopen");
                    checkText(detail, "reason");
                    state = WorkItem.State.WORKING;
                }
                case MOVE -> {
                    requireGoal(workspaceId, command.goalId());
                    if (!command.goalId().equals(goalId)) {
                        requireNoDependencies(workspaceId, item);
                        rank = nextRank(workspaceId, command.goalId());
                    }
                    goalId = command.goalId();
                    detail = goalId.map(value -> "Moved to Goal " + value).orElse("Removed from Goal; work and history retained");
                }
                case PRIORITIZE -> {
                    priority = command.priority().orElseThrow(() -> new IllegalArgumentException("priority is required"));
                    detail = "Priority set to " + words(priority);
                }
                case BLOCK -> {
                    checkArgument(open(item), "Reopen before changing what concluded work waits on");
                    String blocker = command.blocker().orElseThrow(() -> new IllegalArgumentException("blocker is required"));
                    WorkItem other = requireItem(workspaceId, blocker);
                    checkArgument(!blocker.equals(id), "Work cannot wait on itself");
                    checkArgument(goalId.isPresent() && other.goalId().equals(goalId) || item.epicId().isPresent() && other.epicId().equals(item.epicId()),
                            "Work can wait only on work of its own Goal or Epic");
                    checkArgument(other.state() != WorkItem.State.DROPPED, "Dropped work cannot block anything");
                    checkArgument(!waitsOn(workspaceId, blocker, id), other.title() + " already waits on this work");
                    blockedBy = blockedBy.contains(blocker) ? blockedBy : ImmutableList.<String>builder().addAll(blockedBy).add(blocker).build();
                    detail = "Waits on " + other.title();
                }
                case UNBLOCK -> {
                    String blocker = command.blocker().orElseThrow(() -> new IllegalArgumentException("blocker is required"));
                    checkArgument(blockedBy.contains(blocker), "This work does not wait on that item");
                    blockedBy = blockedBy.stream().filter(value -> !value.equals(blocker)).collect(toImmutableList());
                    detail = "No longer waits on " + items.find(blocker).map(WorkItem::title).orElse("removed work");
                }
            }
            Instant now = clock.instant();
            WorkItem updated = new WorkItem(id, workspaceId, goalId, title, item.kind(), criteria, rule, state, item.revision() + 1, item.links(),
                    ImmutableList.<WorkItemEvent>builder().addAll(item.history())
                            .add(new WorkItemEvent(command.operationId(), command.toString(), command.action().name(), detail, now)).build(), item.createdAt(), now,
                    priority, rank, blockedBy, item.epicId(), item.epicRank());
            update(updated, item.revision());
            for (WorkItem dependent : released) {
                update(new WorkItem(dependent.id(), workspaceId, dependent.goalId(), dependent.title(), dependent.kind(), dependent.acceptanceCriteria(),
                        dependent.completionRule(), dependent.state(), dependent.revision() + 1, dependent.links(), ImmutableList.<WorkItemEvent>builder()
                                .addAll(dependent.history()).add(new WorkItemEvent(command.operationId() + ":" + dependent.id(), "", "UNBLOCK",
                                        "No longer waits on " + title + ", which was dropped: " + detail, now)).build(),
                        dependent.createdAt(), now, dependent.priority(), dependent.rank(), dependent.blockedBy().stream().filter(value -> !value.equals(id))
                        .collect(toImmutableList()), dependent.epicId(), dependent.epicRank()), dependent.revision());
            }
            boolean concluding = switch (command.action()) {
                case ACCEPT, DROP, REOPEN -> true;
                case EDIT, BEGIN, MOVE, PRIORITIZE, BLOCK, UNBLOCK -> false;
            };
            if (concluding && goalId.isPresent()) {
                decided.set(new WorkItemDecided(workspaceId, goalId.orElseThrow(), id, title, item.kind(), command.action(), command.operationId(), detail));
            }
            return view(updated);
        });
        // Published after the decision is saved, and only for a new one, never its replay.
        if (decided.get() != null) {
            events.publishEvent(decided.get());
        }
        return changed;
    }

    /** Saves the order the user gave a Goal's work, which names each of its items once. */
    public List<WorkItemView> order(String workspaceId, String goalId, List<String> ids)
    {
        requireGoal(workspaceId, Optional.of(goalId));
        return unitOfWork.execute(() -> {
            Map<String, WorkItem> work = items.list(workspaceId).stream().filter(item -> item.goalId().filter(goalId::equals).isPresent())
                    .collect(toImmutableMap(WorkItem::id, item -> item));
            if (ids.size() != work.size() || !ImmutableSet.copyOf(ids).equals(work.keySet())) {
                throw conflict("The Goal's work changed. Order it again.");
            }
            Instant now = clock.instant();
            ImmutableList.Builder<WorkItemView> ordered = ImmutableList.builder();
            for (int rank = 0; rank < ids.size(); rank++) {
                WorkItem item = work.get(ids.get(rank));
                if (item.rank() != rank) {
                    WorkItem moved = new WorkItem(item.id(), workspaceId, item.goalId(), item.title(), item.kind(), item.acceptanceCriteria(), item.completionRule(),
                            item.state(), item.revision() + 1, item.links(), item.history(), item.createdAt(), now, item.priority(), rank, item.blockedBy(),
                            item.epicId(), item.epicRank());
                    update(moved, item.revision());
                    item = moved;
                }
                ordered.add(view(item));
            }
            return ordered.build();
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
                ImmutableList.of(new WorkItemEvent(id, source.identity(), "CREATED", "Accepted work from " + source.reference().label(), now)), now, now,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0);
        items.insert(item);
        return item;
    }

    private WorkItem saveLinks(WorkItem item, Optional<String> goalId, Optional<String> epicId, List<WorkItemLink> links, String reason)
    {
        LinkedHashMap<String, WorkItemLink> all = new LinkedHashMap<>();
        item.links().forEach(link -> all.put(link.identity(), link));
        links.forEach(link -> all.putIfAbsent(link.identity(), link));
        List<WorkItemLink> linked = ImmutableList.copyOf(all.values());
        if (item.links().equals(linked) && item.goalId().equals(goalId) && item.epicId().equals(epicId)) {
            return item;
        }
        WorkItem.State state = item.state();
        checkArgument(linked.equals(item.links()) || state != WorkItem.State.DONE && state != WorkItem.State.DROPPED, "Reopen before adding evidence to concluded work");
        if (state == WorkItem.State.PLANNED && linked.stream().anyMatch(link -> link.reference().kind() == GoalItemKind.TASK)) {
            state = WorkItem.State.WORKING;
        }
        long rank = item.rank();
        if (!goalId.equals(item.goalId())) {
            requireNoDependencies(item.workspaceId(), item);
            rank = nextRank(item.workspaceId(), goalId);
        }
        long epicRank = item.epicRank();
        if (!epicId.equals(item.epicId())) {
            if (item.epicId().isPresent()) {
                requireNoDependencies(item.workspaceId(), item);
            }
            epicRank = nextEpicRank(item.workspaceId(), epicId);
        }
        Instant now = clock.instant();
        WorkItem next = new WorkItem(item.id(), item.workspaceId(), goalId, item.title(), item.kind(), item.acceptanceCriteria(), item.completionRule(), state,
                item.revision() + 1, linked, ImmutableList.<WorkItemEvent>builder().addAll(item.history())
                        .add(new WorkItemEvent(UUID.randomUUID().toString(), "", "LINKED", reason, now)).build(), item.createdAt(), now,
                item.priority(), rank, item.blockedBy(), epicId, epicRank);
        update(next, item.revision());
        return next;
    }

    /**
     * Puts a source, task or pull request in an Epic: the work that already owns it joins, leaving any other Epic, or new work takes its title.
     * An Epic's own issue or ticket cannot be its work.
     */
    public EpicJoin joinEpic(String workspaceId, String epicId, GoalItemKind kind, String key)
    {
        epics.require(workspaceId, epicId);
        WorkItemLink requested = references.requireLink(workspaceId, kind, key);
        checkArgument(!requested.identity().equals(epicId), "An Epic cannot be its own work");
        List<WorkItemLink> links = references.related(workspaceId, requested);
        return unitOfWork.execute(() -> {
            WorkItem item = findOwner(workspaceId, links).orElseGet(() -> newLinked(workspaceId, requested));
            requireItemWorkspace(item, workspaceId);
            Optional<String> movedFrom = item.epicId().filter(other -> !other.equals(epicId));
            return new EpicJoin(view(saveLinks(item, item.goalId(), Optional.of(epicId), links, movedFrom.isPresent() ? "Moved to another Epic" : "Added to Epic")),
                    movedFrom);
        });
    }

    /** Puts a child the Epic's source reported in the Epic: the work that already owns it joins, or new work takes its title. */
    public WorkItemView joinEpic(String workspaceId, String epicId, WorkItemLink child)
    {
        epics.require(workspaceId, epicId);
        return unitOfWork.execute(() -> {
            WorkItem item = findOwner(workspaceId, ImmutableList.of(child)).orElseGet(() -> newLinked(workspaceId, child));
            requireItemWorkspace(item, workspaceId);
            return view(saveLinks(item, item.goalId(), Optional.of(epicId), ImmutableList.of(child), "Synced from the Epic's children"));
        });
    }

    /** Saves the order the user gave an Epic's work, which names each of its items once. */
    public List<WorkItemView> orderEpic(String workspaceId, String epicId, List<String> ids)
    {
        epics.require(workspaceId, epicId);
        return unitOfWork.execute(() -> {
            Map<String, WorkItem> work = items.list(workspaceId).stream().filter(item -> item.epicId().filter(epicId::equals).isPresent())
                    .collect(toImmutableMap(WorkItem::id, item -> item));
            if (ids.size() != work.size() || !ImmutableSet.copyOf(ids).equals(work.keySet())) {
                throw conflict("The Epic's work changed. Order it again.");
            }
            Instant now = clock.instant();
            ImmutableList.Builder<WorkItemView> ordered = ImmutableList.builder();
            for (int rank = 0; rank < ids.size(); rank++) {
                WorkItem item = work.get(ids.get(rank));
                if (item.epicRank() != rank) {
                    WorkItem moved = new WorkItem(item.id(), workspaceId, item.goalId(), item.title(), item.kind(), item.acceptanceCriteria(), item.completionRule(),
                            item.state(), item.revision() + 1, item.links(), item.history(), item.createdAt(), now, item.priority(), item.rank(), item.blockedBy(),
                            item.epicId(), rank);
                    update(moved, item.revision());
                    item = moved;
                }
                ordered.add(view(item));
            }
            return ordered.build();
        });
    }

    /**
     * Takes work out of its Epic. Work that exists only for the Epic is deleted; work that also belongs to a Goal or holds a task attempt stays,
     * without the Epic.
     *
     * @return the work that stayed, or empty when it was deleted
     */
    public Optional<WorkItemView> removeFromEpic(String workspaceId, String id)
    {
        return unitOfWork.execute(() -> {
            WorkItem item = requireItem(workspaceId, id);
            checkArgument(item.epicId().isPresent(), "This work is not in an Epic");
            return release(workspaceId, ImmutableList.of(item), "Removed from its Epic; work and history retained").stream().findFirst().map(this::view);
        });
    }

    /** Removes an unmarked Epic's work the same way, and its record of children, in one transaction with the unmarking. */
    public void releaseEpic(String workspaceId, String epicId)
    {
        unitOfWork.execute(() -> {
            release(workspaceId, items.list(workspaceId).stream().filter(item -> item.epicId().filter(epicId::equals).isPresent()).collect(toImmutableList()),
                    "Left its Epic, which was unmarked; work and history retained");
            epicRecords.delete(epicId);
            return null;
        });
    }

    /**
     * Deletes the leaving work that exists only for its Epic and takes the rest out of it. Waiting that only a shared Epic allowed ends with it,
     * and nothing keeps waiting on deleted work.
     *
     * @return the leaving work that stayed
     */
    private List<WorkItem> release(String workspaceId, List<WorkItem> leaving, String reason)
    {
        Set<String> leavingIds = leaving.stream().map(WorkItem::id).collect(toImmutableSet());
        Set<String> deleted = leaving.stream().filter(item -> item.goalId().isEmpty() && !attempted(item)).map(WorkItem::id).collect(toImmutableSet());
        Map<String, WorkItem> all = items.list(workspaceId).stream().collect(toImmutableMap(WorkItem::id, item -> item));
        leaving.stream().filter(item -> deleted.contains(item.id())).forEach(items::delete);
        Instant now = clock.instant();
        ImmutableList.Builder<WorkItem> kept = ImmutableList.builder();
        for (WorkItem item : all.values()) {
            if (deleted.contains(item.id())) {
                continue;
            }
            boolean left = leavingIds.contains(item.id());
            Optional<String> epicId = left ? Optional.empty() : item.epicId();
            List<String> blockedBy = item.blockedBy().stream().filter(blocker -> {
                if (deleted.contains(blocker)) {
                    return false;
                }
                if (!left && !leavingIds.contains(blocker)) {
                    return true;
                }
                WorkItem other = all.get(blocker);
                return other != null && sharesWork(item, epicId, other, leavingIds);
            }).collect(toImmutableList());
            if (!left && blockedBy.equals(item.blockedBy())) {
                continue;
            }
            List<String> stopped = item.blockedBy().stream().filter(blocker -> !blockedBy.contains(blocker))
                    .map(blocker -> Optional.ofNullable(all.get(blocker)).map(WorkItem::title).orElse("removed work")).collect(toImmutableList());
            String detail = (left ? reason : "") + (stopped.isEmpty() ? "" : (left ? "\n" : "") + "No longer waits on " + String.join(", ", stopped) + ", which left the Epic");
            WorkItem next = new WorkItem(item.id(), workspaceId, item.goalId(), item.title(), item.kind(), item.acceptanceCriteria(), item.completionRule(), item.state(),
                    item.revision() + 1, item.links(), ImmutableList.<WorkItemEvent>builder().addAll(item.history())
                            .add(new WorkItemEvent(UUID.randomUUID().toString(), "", left ? "LEFT_EPIC" : "UNBLOCK", detail, now)).build(), item.createdAt(), now,
                    item.priority(), item.rank(), blockedBy, epicId, left ? 0 : item.epicRank());
            update(next, item.revision());
            if (left) {
                kept.add(next);
            }
        }
        return kept.build();
    }

    /** Whether waiting on {@code blocker} is still allowed once the leaving work is out of its Epic. */
    private static boolean sharesWork(WorkItem item, Optional<String> epicId, WorkItem blocker, Set<String> leavingIds)
    {
        Optional<String> blockerEpic = leavingIds.contains(blocker.id()) ? Optional.empty() : blocker.epicId();
        return item.goalId().isPresent() && item.goalId().equals(blocker.goalId()) || epicId.isPresent() && epicId.equals(blockerEpic);
    }

    private static boolean attempted(WorkItem item)
    {
        return item.links().stream().anyMatch(link -> link.reference().kind() == GoalItemKind.TASK);
    }

    private WorkItemView view(WorkItem item)
    {
        List<GoalItemView> links = item.links().stream().map(link -> references.view(item.workspaceId(), link.reference())).collect(toImmutableList());
        return new WorkItemView(item.id(), item.workspaceId(), item.goalId(), item.title(), item.kind(), item.acceptanceCriteria(), item.completionRule(),
                item.state(), item.revision(), links, item.history(), item.createdAt(), item.updatedAt(), item.priority(), item.rank(), item.blockedBy(),
                item.epicId(), item.epicRank());
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

    /** New work joins the end of its Goal's order. */
    private long nextRank(String workspaceId, Optional<String> goalId)
    {
        return goalId.map(goal -> items.list(workspaceId).stream().filter(item -> item.goalId().filter(goal::equals).isPresent())
                .mapToLong(item -> item.rank() + 1).max().orElse(0)).orElse(0L);
    }

    /** New work joins the end of its Epic's order. */
    private long nextEpicRank(String workspaceId, Optional<String> epicId)
    {
        return epicId.map(epic -> items.list(workspaceId).stream().filter(item -> item.epicId().filter(epic::equals).isPresent())
                .mapToLong(item -> item.epicRank() + 1).max().orElse(0)).orElse(0L);
    }

    private List<WorkItem> dependents(String workspaceId, String id)
    {
        return items.list(workspaceId).stream().filter(item -> item.blockedBy().contains(id)).collect(toImmutableList());
    }

    /** Blockers stay within one Goal or Epic, so work leaves one only once it waits on nothing and nothing waits on it. */
    private void requireNoDependencies(String workspaceId, WorkItem item)
    {
        if (!item.blockedBy().isEmpty() || !dependents(workspaceId, item.id()).isEmpty()) {
            throw conflict("Remove what this work waits on and what waits on it before it leaves its Goal or Epic.");
        }
    }

    /** Whether {@code from} already waits on {@code target}, directly or through other work. */
    private boolean waitsOn(String workspaceId, String from, String target)
    {
        Map<String, List<String>> blockers = items.list(workspaceId).stream().collect(toImmutableMap(WorkItem::id, WorkItem::blockedBy));
        Deque<String> pending = new ArrayDeque<>(List.of(from));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String next = pending.pop();
            if (next.equals(target)) {
                return true;
            }
            if (seen.add(next)) {
                pending.addAll(blockers.getOrDefault(next, ImmutableList.of()));
            }
        }
        return false;
    }

    private static boolean open(WorkItem item)
    {
        return item.state() == WorkItem.State.PLANNED || item.state() == WorkItem.State.WORKING;
    }

    private static String words(WorkItem.Priority priority)
    {
        return priority.name().charAt(0) + priority.name().substring(1).toLowerCase(Locale.ROOT);
    }

    private static String completionText(WorkItem.CompletionRule rule)
    {
        return rule == WorkItem.CompletionRule.MERGED_DELIVERY ? "Merged delivery" : "User accepted result";
    }

    /** Work an Epic took in, and the other Epic it left, if any, so the view can say so. */
    public record EpicJoin(WorkItemView item, Optional<String> movedFrom)
    {
        public EpicJoin
        {
            requireNonNull(item, "item is null");
            requireNonNull(movedFrom, "movedFrom is null");
        }
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
