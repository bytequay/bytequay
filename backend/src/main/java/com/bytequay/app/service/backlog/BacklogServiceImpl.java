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
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.BacklogStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.distillation.DistillationSignalService;
import com.bytequay.app.service.threads.ThreadService;
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
        implements BacklogService
{
    private final BacklogStore store;
    private final ThreadService threadService;
    private final ThreadStore threadStore;
    private final DistillationSignalService distillation;

    public BacklogServiceImpl(
            BacklogStore store,
            ThreadService threadService,
            ThreadStore threadStore,
            DistillationSignalService distillation)
    {
        this.store = requireNonNull(store, "store is null");
        this.threadService = requireNonNull(threadService, "threadService is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.distillation = requireNonNull(distillation, "distillation is null");
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
            List<String> siblings = new ArrayList<>(ids);
            siblings.remove(i);
            store.save(BacklogItem.create(
                    ids.get(i),
                    threadIdValue,
                    workspaceId,
                    title,
                    nullToEmpty(in.body()).strip(),
                    in.tags() == null ? List.of() : in.tags(),
                    normalisePriority(in.priority()),
                    BacklogItem.SOURCE_TRUNK_SPLIT,
                    BacklogItem.CREATED_BY_TRUNK_AGENT,
                    now,
                    siblings));
        }
        return new BatchResult(ids, groupId);
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
        BacklogItem skipped = store.save(
                item.markNotToProceed(reasonValue.isEmpty() ? null : reasonValue, Instant.now()));
        distillation.record(
                "backlog-skip", skipped.id(), "skipped", reasonValue.isEmpty() ? null : reasonValue,
                Map.of("title", skipped.title()), skipped.threadId(), skipped.workspaceId());
        return skipped;
    }

    @Override
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

    @Override
    public StartResult startDevelopment(String id)
    {
        BacklogItem item = require(id);
        if (!BacklogItem.STATUS_CREATED.equals(item.status())) {
            throw status(409, "backlog item is not in created (can't start exploration)");
        }
        // Hand the item to the trunk as a fresh planning prompt: the trunk
        // agent reads the code, asks clarifying questions, drafts a plan, and
        // eventually cuts a task — none of which happens here. We only post
        // the prompt and flip the item to in-progress. The id prefix is the
        // only way the trunk can later tell create_task which item to
        // resolve — nothing else carries it into that conversation.
        String content = item.body().isBlank()
                ? item.title()
                : item.title() + "\n\n" + item.body();
        String prompt = "(backlog item " + item.id() + " — pass this as backlog_item_id if you cut a "
                + "task from it)\n\n" + content;
        threadService.sendTrunk(item.threadId(), prompt);

        BacklogItem updated = store.save(item.markInProgress(Instant.now()));
        distillation.record(
                "backlog-start", updated.id(), "started", null,
                Map.of("title", updated.title()), updated.threadId(), updated.workspaceId());
        return new StartResult(updated, /* taskId — none cut yet */ null);
    }

    @Override
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

    @Override
    public BacklogItem resolve(String id, String taskId)
    {
        BacklogItem item = require(id);
        if (BacklogItem.STATUS_RESOLVED.equals(item.status())) {
            throw status(409, "backlog item already resolved");
        }
        BacklogItem resolved = store.save(item.markResolved(taskId, Instant.now()));
        distillation.record(
                "backlog-resolve", resolved.id(), "resolved", null,
                Map.of("title", resolved.title(), "taskId", taskId), resolved.threadId(), resolved.workspaceId());
        return resolved;
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
