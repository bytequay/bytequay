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
package com.bytequay.app.service.backlog;

import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.BacklogStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.distillation.DistillationSignalServiceImpl;
import com.bytequay.app.service.threads.ThreadService;
import com.google.common.collect.ImmutableSet;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

@Service
public class BacklogServiceImpl
{
    public record StartResult(BacklogItem item, String taskId) {}

    public record NewBacklogItem(
            String title, String body, List<String> tags, String priority) {}

    public record BatchResult(
            List<String> backlogItemIds, String relatedBacklogGroupId) {}

    private final BacklogStore store;
    private final ThreadService threadService;
    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final DistillationSignalServiceImpl distillation;

    public BacklogServiceImpl(
            BacklogStore store,
            ThreadService threadService,
            ThreadStore threadStore,
            TaskStore taskStore,
            DistillationSignalServiceImpl distillation)
    {
        this.store = requireNonNull(store, "store is null");
        this.threadService = requireNonNull(threadService, "threadService is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.distillation = requireNonNull(distillation, "distillation is null");
    }
    public List<BacklogItem> list(String threadId)
    {
        List<BacklogItem> items = store.findByThread(nullToEmpty(threadId).strip());
        List<BacklogItem> reconciled = new ArrayList<>(items.size());
        for (BacklogItem item : items) {
            reconciled.add(settleIfTaskCompleted(item));
        }
        return reconciled;
    }

    /**
     * Self-heal a resolved (task-cut) item whose cut task has since reached
     * COMPLETED — advancing it to {@code shipped} (PR merged) or {@code
     * closed} (closed unmerged, or no PR). Settling is driven from here, on
     * read, rather than a one-shot completion event so that a task which
     * merged before this wiring existed — or whose event was lost — still
     * settles the next time its backlog is viewed (by the trunk panel or the
     * agent's timeline, both of which route through {@link #list}). The write
     * lands at most once per item: once settled, the status guard below
     * short-circuits every later read.
     */
    private BacklogItem settleIfTaskCompleted(BacklogItem item)
    {
        if (!BacklogItem.STATUS_RESOLVED.equals(item.status()) || item.linkedTaskId() == null) {
            return item;
        }
        Task task = taskStore.findTaskById(item.linkedTaskId()).orElse(null);
        if (task == null || task.phase() != TaskPhase.COMPLETED) {
            return item;
        }
        boolean merged = task.prNumber() != null && !"closed".equals(task.prState());
        BacklogItem settled = store.save(merged ? item.markShipped() : item.markClosed());
        distillation.record(
                merged ? "backlog-ship" : "backlog-close", settled.id(),
                merged ? "shipped" : "closed", null,
                Map.of("title", settled.title(), "taskId", item.linkedTaskId()),
                settled.threadId(), settled.workspaceId());
        return settled;
    }
    public List<BacklogItem> listForWorkspace(
            String workspaceId, String status, String threadId, String tag, String query)
    {
        String statusFilter = nullToEmpty(status).strip();
        String threadFilter = nullToEmpty(threadId).strip();
        String tagFilter = nullToEmpty(tag).strip();
        String q = nullToEmpty(query).strip().toLowerCase(Locale.ROOT);
        return store.findByWorkspace(nullToEmpty(workspaceId).strip()).stream()
                .filter(i -> statusFilter.isEmpty() || statusFilter.equals(i.status()))
                .filter(i -> threadFilter.isEmpty() || threadFilter.equals(i.threadId()))
                .filter(i -> tagFilter.isEmpty() || i.tags().contains(tagFilter))
                .filter(i -> q.isEmpty()
                        || i.title().toLowerCase(Locale.ROOT).contains(q)
                        || i.body().toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }
    public BacklogItem create(String threadId, String title, String body, List<String> tags, String priority)
    {
        String threadIdValue = nullToEmpty(threadId).strip();
        String titleValue = nullToEmpty(title).strip();
        if (threadIdValue.isEmpty()) {
            throw status(400, "threadId is required");
        }
        if (titleValue.isEmpty()) {
            throw status(400, "title is required");
        }
        Thread trunk = threadStore.findThreadById(threadIdValue)
                .orElseThrow(() -> status(404, "trunk not found: " + threadIdValue));
        String workspaceId = trunk.workspaceId();
        BacklogItem item = BacklogItem.create(
                UUID.randomUUID().toString(),
                threadIdValue,
                workspaceId,
                titleValue,
                nullToEmpty(body).strip(),
                tags == null ? List.of() : tags,
                normalisePriority(priority),
                BacklogItem.SOURCE_MANUAL,
                BacklogItem.CREATED_BY_USER,
                Instant.now(),
                /* relatedBacklogIds */ List.of())
                .withPublicFields(
                        allocateKey(workspaceId), titleValue,
                        nullToEmpty(body).strip(), null,
                        List.of(new BacklogItem.Link("trunk", threadIdValue)));
        return store.save(item);
    }
    public BacklogItem createForWorkspace(
            String workspaceId,
            String trunkId,
            String title,
            String summary,
            String detail,
            String impactRisk,
            List<String> tags,
            String priority,
            List<BacklogItem.Link> links)
    {
        String workspace = nullToEmpty(workspaceId).strip();
        String trunk = nullToEmpty(trunkId).strip();
        String titleValue = nullToEmpty(title).strip();
        String summaryValue = nullToEmpty(summary).strip();
        if (workspace.isEmpty()) {
            throw status(400, "workspaceId is required");
        }
        if (trunk.isEmpty()) {
            throw status(400, "trunkId is required");
        }
        if (titleValue.isEmpty()) {
            throw status(400, "title is required");
        }
        if (summaryValue.isEmpty()) {
            throw status(400, "summary is required");
        }
        requireTrunkInWorkspace(workspace, trunk);
        List<BacklogItem.Link> nextLinks = withTrunkLink(trunk, links);
        BacklogItem item = BacklogItem.create(
                        UUID.randomUUID().toString(),
                        trunk,
                        workspace,
                        titleValue,
                        nullToEmpty(detail).strip(),
                        tags == null ? List.of() : tags,
                        normalisePriority(priority),
                        BacklogItem.SOURCE_MANUAL,
                        BacklogItem.CREATED_BY_USER,
                        Instant.now(),
                        List.of())
                .withPublicFields(
                        allocateKey(workspace),
                        summaryValue,
                        nullToEmpty(detail).strip(),
                        blankToNull(impactRisk),
                        nextLinks);
        return store.save(item);
    }
    public BacklogItem getForWorkspace(String workspaceId, String itemKey)
    {
        return requireForWorkspace(workspaceId, itemKey);
    }
    public BacklogItem updateForWorkspace(
            String workspaceId,
            String itemKey,
            String title,
            String summary,
            String detail,
            String impactRisk,
            List<String> tags,
            String priority,
            List<BacklogItem.Link> links)
    {
        BacklogItem existing = requireForWorkspace(workspaceId, itemKey);
        String nextTitle = title == null ? existing.title() : title.strip();
        String nextSummary = summary == null ? existing.summary() : summary.strip();
        if (nextTitle.isEmpty()) {
            throw status(400, "title is required");
        }
        if (nextSummary.isEmpty()) {
            throw status(400, "summary is required");
        }
        List<BacklogItem.Link> nextLinks = links == null
                ? existing.links()
                : withTrunkLink(existing.threadId(), links);
        BacklogItem updated = existing
                .withDetails(
                        nextTitle,
                        detail == null ? existing.detail() : detail.strip(),
                        tags == null ? existing.tags() : tags,
                        priority == null ? existing.priority() : normalisePriority(priority))
                .withPublicFields(
                        existing.itemKey(),
                        nextSummary,
                        detail == null ? existing.detail() : detail.strip(),
                        impactRisk == null ? existing.impactRisk() : blankToNull(impactRisk),
                        nextLinks);
        return store.save(updated);
    }
    public BatchResult createBatch(String threadId, List<NewBacklogItem> items)
    {
        String threadIdValue = nullToEmpty(threadId).strip();
        if (threadIdValue.isEmpty()) {
            throw status(400, "threadId is required");
        }
        if (items == null || items.isEmpty()) {
            throw status(400, "at least one item is required");
        }
        String workspaceId = threadStore.findThreadById(threadIdValue)
                .map(Thread::workspaceId)
                .orElse(null);
        // Pre-generate the ids so each item can carry its siblings as
        // relatedBacklogIds (the "trunk found N candidates" linkage).
        List<String> ids = items.stream().map(x -> UUID.randomUUID().toString()).toList();
        String groupId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        for (int i = 0; i < items.size(); i++) {
            NewBacklogItem in = items.get(i);
            String title = nullToEmpty(in.title()).strip();
            if (title.isEmpty()) {
                throw status(400, "each item needs a title");
            }
            String detail = nullToEmpty(in.body()).strip();
            List<String> siblings = new ArrayList<>(ids);
            siblings.remove(i);
            store.save(BacklogItem.create(
                    ids.get(i),
                    threadIdValue,
                    workspaceId,
                    title,
                    detail,
                    in.tags() == null ? List.of() : in.tags(),
                    normalisePriority(in.priority()),
                    BacklogItem.SOURCE_TRUNK_SPLIT,
                    BacklogItem.CREATED_BY_TRUNK_AGENT,
                    now,
                    siblings)
                    .withPublicFields(
                            allocateKey(workspaceId), summaryFrom(title, detail),
                            detail, null,
                            siblingLinks(threadIdValue, siblings)));
        }
        return new BatchResult(ids, groupId);
    }
    public BacklogItem update(String id, String title, String body, List<String> tags, String priority)
    {
        BacklogItem existing = require(id);
        String nextTitle = title == null ? existing.title() : title.strip();
        if (nextTitle.isEmpty()) {
            throw status(400, "title cannot be blank");
        }
        BacklogItem updated = existing.withDetails(
                nextTitle,
                body == null ? existing.body() : body.strip(),
                tags == null ? existing.tags() : tags,
                priority == null ? existing.priority() : normalisePriority(priority));
        return store.save(updated);
    }
    public void delete(String id)
    {
        store.delete(nullToEmpty(id).strip());
    }
    public BacklogItem skip(String id, String reason)
    {
        BacklogItem item = require(id);
        if (BacklogItem.STATUS_RESOLVED.equals(item.status())) {
            throw status(409, "backlog item already resolved");
        }
        String reasonValue = nullToEmpty(reason).strip();
        BacklogItem skipped = store.save(
                item.markNotToProceed(reasonValue.isEmpty() ? null : reasonValue, Instant.now()));
        distillation.record(
                "backlog-skip", skipped.id(), "skipped", reasonValue.isEmpty() ? null : reasonValue,
                Map.of("title", skipped.title()), skipped.threadId(), skipped.workspaceId());
        return skipped;
    }
    public BacklogItem revive(String id)
    {
        BacklogItem item = require(id);
        if (!BacklogItem.STATUS_NOT_TO_PROCEED.equals(item.status())) {
            throw status(409, "backlog item is not in not-to-proceed");
        }
        BacklogItem revived = store.save(item.markCreated());
        distillation.record(
                "backlog-revive", revived.id(), "revived", null,
                Map.of("title", revived.title()), revived.threadId(), revived.workspaceId());
        return revived;
    }
    public StartResult startDevelopment(String id)
    {
        return startDevelopment(id, null);
    }
    public StartResult startDevelopment(String id, String trunkId)
    {
        BacklogItem item = require(id);
        return startItem(item, trunkId);
    }
    public StartResult startDevelopmentForWorkspace(
            String workspaceId, String itemKey, String trunkId)
    {
        return startItem(requireForWorkspace(workspaceId, itemKey), trunkId);
    }

    private StartResult startItem(BacklogItem item, String trunkId)
    {
        if (!BacklogItem.STATUS_CREATED.equals(item.status())) {
            throw status(409, "backlog item is not open (can't start exploration)");
        }
        // Hand the item to the trunk as a fresh planning prompt. The trunk
        // either cuts the task when its understanding is solid or asks the
        // user to confirm the direction first; none of that happens here. The
        // id prefix is the only way the trunk can later tell create_task which
        // item to resolve — nothing else carries it into that conversation.
        if (trunkId != null && !trunkId.isBlank()) {
            String workspaceId = item.workspaceId();
            Thread selected = requireTrunkInWorkspace(workspaceId, trunkId);
            item = item.withThread(selected.id());
        }
        String content = item.detail() == null || item.detail().isBlank()
                ? item.summary()
                : item.summary() + "\n\n" + item.detail();
        String key = item.itemKey() == null ? item.id() : item.itemKey();
        String prompt = "(backlog item " + key + " — pass this as backlog_item_id if you cut a "
                + "task from it)\n\n"
                + "Before cutting a task from this backlog item, read enough code/context to state "
                + "the goal, intended direction, and effort/risk.\n\n"
                + "If the direction is clear and you are confident, call create_task with backlog_item_id="
                + item.id() + " and include the plan you would hand to the task.\n\n"
                + "If any important direction is uncertain, do not cut the task yet. Use "
                + "ask_user_question to ask the user to confirm/approve the task direction with "
                + "your understanding, intended approach, risk/effort, and the specific "
                + "uncertainty.\n\n"
                + content;
        BacklogItem updated = store.save(item.markInProgress(Instant.now()));
        threadService.sendTrunk(updated.threadId(), prompt);
        distillation.record(
                "backlog-start", updated.id(), "started", null,
                Map.of("title", updated.title()), updated.threadId(), updated.workspaceId());
        return new StartResult(updated, /* taskId — none cut yet */ null);
    }
    public BacklogItem cancelExploration(String id)
    {
        BacklogItem item = require(id);
        if (!BacklogItem.STATUS_IN_PROGRESS.equals(item.status())) {
            throw status(409, "backlog item is not in exploration");
        }
        BacklogItem restored = store.save(item.markCreated());
        distillation.record(
                "backlog-cancel-exploration", restored.id(), "cancelled", null,
                Map.of("title", restored.title()), restored.threadId(), restored.workspaceId());
        return restored;
    }
    public BacklogItem resolve(String id, String taskId)
    {
        BacklogItem item = require(id);
        if (!BacklogItem.STATUS_IN_PROGRESS.equals(item.status())) {
            throw status(409, "backlog item is not in progress");
        }
        if (item.linkedTaskId() != null) {
            throw status(409, "backlog item is already linked to a task");
        }
        String taskIdValue = nullToEmpty(taskId).strip();
        if (taskIdValue.isEmpty()) {
            throw status(400, "taskId is required");
        }
        Task task = taskStore.findTaskById(taskIdValue)
                .orElseThrow(() -> status(404, "task not found: " + taskId));
        if (!item.threadId().equals(task.threadId())) {
            throw status(409, "backlog item and task belong to different trunks");
        }
        boolean taskAlreadyLinked = store.findByThread(item.threadId()).stream()
                .anyMatch(existing -> !item.id().equals(existing.id())
                        && taskIdValue.equals(existing.linkedTaskId()));
        if (taskAlreadyLinked) {
            throw status(409, "task is already linked to another backlog item");
        }
        try {
            if (!store.resolveIfInProgressAndUnlinked(
                    item.id(), taskIdValue, Instant.now())) {
                throw status(409, "backlog item changed while the task was being linked");
            }
        }
        catch (DataAccessException e) {
            if (!isUniqueTaskLinkViolation(e)) {
                throw e;
            }
            throw status(409, "task is already linked to another backlog item");
        }
        BacklogItem resolved = require(item.id());
        distillation.record(
                "backlog-resolve", resolved.id(), "resolved", null,
                Map.of("title", resolved.title(), "taskId", taskIdValue),
                resolved.threadId(), resolved.workspaceId());
        return resolved;
    }

    private BacklogItem require(String id)
    {
        String itemId = nullToEmpty(id).strip();
        return store.findById(itemId)
                .orElseThrow(() -> status(404, "backlog item not found: " + id));
    }

    private BacklogItem requireForWorkspace(String workspaceId, String itemKey)
    {
        String workspace = nullToEmpty(workspaceId).strip();
        String key = nullToEmpty(itemKey).strip().toUpperCase(Locale.ROOT);
        if (workspace.isEmpty() || key.isEmpty()) {
            throw status(400, "workspaceId and itemKey are required");
        }
        return store.findByWorkspaceAndItemKey(workspace, key)
                .orElseThrow(() -> status(404,
                        "backlog item not found in workspace: " + itemKey));
    }

    private Thread requireTrunkInWorkspace(String workspaceId, String trunkId)
    {
        return threadStore.findThreadById(nullToEmpty(trunkId).strip())
                .filter(thread -> workspaceId.equals(thread.workspaceId()))
                .orElseThrow(() -> status(404,
                        "trunk is not in this workspace: " + trunkId));
    }

    private String allocateKey(String workspaceId)
    {
        String allocated = workspaceId == null ? null : store.nextItemKey(workspaceId);
        return allocated == null
                ? "BQ-" + UUID.randomUUID().toString().substring(0, 8)
                : allocated;
    }

    private static List<BacklogItem.Link> siblingLinks(
            String threadId, List<String> siblings)
    {
        List<BacklogItem.Link> links = new ArrayList<>();
        links.add(new BacklogItem.Link("trunk", threadId));
        siblings.forEach(id -> links.add(new BacklogItem.Link("backlog", id)));
        return links;
    }

    private static List<BacklogItem.Link> withTrunkLink(
            String trunkId, List<BacklogItem.Link> links)
    {
        List<BacklogItem.Link> value = new ArrayList<>();
        value.add(new BacklogItem.Link("trunk", trunkId));
        if (links != null) {
            links.stream()
                    .filter(link -> link != null
                            && link.type() != null
                            && link.id() != null
                            && !link.type().isBlank()
                            && !link.id().isBlank())
                    .filter(link -> !("trunk".equals(link.type()) && trunkId.equals(link.id())))
                    .forEach(value::add);
        }
        return List.copyOf(value);
    }

    private static String blankToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String summaryFrom(String title, String detail)
    {
        if (detail.isEmpty()) {
            return title;
        }
        int paragraphEnd = detail.indexOf("\n\n");
        return paragraphEnd < 0 ? detail : detail.substring(0, paragraphEnd).strip();
    }

    /** Clamp a priority to the allowed set, defaulting to {@code medium}. */
    private static String normalisePriority(String priority)
    {
        String value = nullToEmpty(priority).strip().toLowerCase(Locale.ROOT);
        return PRIORITIES.contains(value) ? value : BacklogItem.PRIORITY_MEDIUM;
    }

    private static final Set<String> PRIORITIES = ImmutableSet.of("low", "medium", "high");

    private static boolean isUniqueTaskLinkViolation(DataAccessException exception)
    {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = nullToEmpty(cause.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("unique constraint failed: backlog_item.linked_task_id")
                    || message.contains("idx_backlog_item_linked_task")) {
                return true;
            }
        }
        return false;
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
