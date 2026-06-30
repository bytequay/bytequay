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
    public BacklogItem create(String threadId, String title, String body, List<String> tags)
    {
        String threadIdValue = nullToEmpty(threadId).strip();
        String titleValue = nullToEmpty(title).strip();
        if (threadIdValue.isEmpty()) {
            throw status(400, "threadId is required");
        }
        if (titleValue.isEmpty()) {
            throw status(400, "title is required");
        }
        BacklogItem item = new BacklogItem(
                UUID.randomUUID().toString(),
                threadIdValue,
                titleValue,
                nullToEmpty(body).strip(),
                tags == null ? List.of() : tags,
                Instant.now(),
                /* startedAt */ null,
                /* linkedTaskId */ null);
        return store.save(item);
    }

    @Override
    public BacklogItem update(String id, String title, String body, List<String> tags)
    {
        BacklogItem existing = require(id);
        String nextTitle = title == null ? existing.title() : title.strip();
        if (nextTitle.isEmpty()) {
            throw status(400, "title cannot be blank");
        }
        BacklogItem updated = new BacklogItem(
                existing.id(),
                existing.threadId(),
                nextTitle,
                body == null ? existing.body() : body.strip(),
                tags == null ? existing.tags() : tags,
                existing.createdAt(),
                existing.startedAt(),
                existing.linkedTaskId());
        return store.save(updated);
    }

    @Override
    public void delete(String id)
    {
        store.delete(nullToEmpty(id).strip());
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
        BacklogItem updated = new BacklogItem(
                item.id(),
                item.threadId(),
                item.title(),
                item.body(),
                item.tags(),
                item.createdAt(),
                Instant.now(),
                linkedTaskId);
        store.save(updated);
        return new StartResult(updated, linkedTaskId);
    }

    private BacklogItem require(String id)
    {
        return store.findById(nullToEmpty(id).strip())
                .orElseThrow(() -> status(404, "backlog item not found: " + id));
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
