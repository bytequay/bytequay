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
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.BacklogStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.ThreadService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

@Service
public class BacklogServiceImpl
        implements BacklogService
{
    private final BacklogStore store;
    private final ThreadService threadService;
    private final ThreadStore threadStore;
    private final TaskStore taskStore;

    public BacklogServiceImpl(
            BacklogStore store,
            ThreadService threadService,
            ThreadStore threadStore,
            TaskStore taskStore)
    {
        this.store = requireNonNull(store, "store is null");
        this.threadService = requireNonNull(threadService, "threadService is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    @Override
    public List<BacklogItem> list(String threadId)
    {
        return store.findByThread(nullToEmpty(threadId).strip());
    }

    @Override
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

    @Override
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
        // The workspace pointer comes from the owning thread — the
        // workspace-wide backlog view groups items by it.
        String workspaceId = threadStore.findThreadById(threadIdValue)
                .map(Thread::workspaceId)
                .orElse(null);
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
                /* relatedBacklogIds */ List.of());
        return store.save(item);
    }

    @Override
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

    @Override
    public void delete(String id)
    {
        store.delete(nullToEmpty(id).strip());
    }

    @Override
    public BacklogItem skip(String id, String reason)
    {
        BacklogItem item = require(id);
        if (BacklogItem.STATUS_RESOLVED.equals(item.status())) {
            throw status(409, "backlog item already resolved");
        }
        String reasonValue = nullToEmpty(reason).strip();
        return store.save(item.markNotToProceed(reasonValue.isEmpty() ? null : reasonValue, Instant.now()));
    }

    @Override
    public BacklogItem revive(String id)
    {
        BacklogItem item = require(id);
        if (!BacklogItem.STATUS_NOT_TO_PROCEED.equals(item.status())) {
            throw status(409, "backlog item is not in not-to-proceed");
        }
        return store.save(item.markCreated());
    }

    @Override
    public StartResult startDevelopment(String id)
    {
        BacklogItem item = require(id);
        if (item.isStarted()) {
            throw status(409, "backlog item already started");
        }
        String seedPrompt = item.body().isBlank() ? item.title() : item.title() + "\n\n" + item.body();
        Thread thread = threadStore.findThreadById(item.threadId())
                .orElseThrow(() -> status(404, "thread not found: " + item.threadId()));
        // The task is cut from the clone the thread's latest task ran in.
        String workingDir = taskStore.findLatestTaskForThread(item.threadId())
                .map(Task::workingDir)
                .filter(d -> d != null && !d.isBlank())
                .orElse(null);
        if (workingDir == null) {
            throw status(400, "could not start task: thread has no working dir to cut from");
        }
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                thread.kind(),
                thread.provider(),
                thread.model(),
                item.title(),
                workingDir,
                /* branchName — worktree create derives it */ null,
                seedPrompt,
                /* initialGroupIds */ List.of(),
                "DEVELOP",
                /* linkedPrNumber */ null,
                /* linkedIssueNumber */ null,
                thread.flow(),
                thread.workspaceId(),
                thread.workModel(),
                /* trunkPlan */ null);
        String linkedTaskId;
        try {
            linkedTaskId = threadService.materialiseTask(item.threadId(), request).id();
        }
        catch (IllegalArgumentException e) {
            throw status(400, "could not start task: " + e.getMessage());
        }
        BacklogItem updated = item.markResolved(linkedTaskId, Instant.now());
        store.save(updated);
        return new StartResult(updated, linkedTaskId);
    }

    private BacklogItem require(String id)
    {
        return store.findById(nullToEmpty(id).strip())
                .orElseThrow(() -> status(404, "backlog item not found: " + id));
    }

    /** Clamp a priority to the allowed set, defaulting to {@code medium}. */
    private static String normalisePriority(String priority)
    {
        String value = nullToEmpty(priority).strip().toLowerCase(Locale.ROOT);
        return PRIORITIES.contains(value) ? value : BacklogItem.PRIORITY_MEDIUM;
    }

    private static final Set<String> PRIORITIES = Set.of("low", "medium", "high");

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
