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
import com.bytequay.app.domain.BranchBase;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.BacklogStore;
import com.bytequay.app.service.threads.TaskQueueScheduler;
import com.bytequay.app.service.threads.TaskQueueService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

@Service
public class BacklogServiceImpl
        implements BacklogService
{
    private final BacklogStore store;
    private final TaskQueueService taskQueue;
    private final TaskQueueScheduler scheduler;

    public BacklogServiceImpl(BacklogStore store, TaskQueueService taskQueue, TaskQueueScheduler scheduler)
    {
        this.store = requireNonNull(store, "store is null");
        this.taskQueue = requireNonNull(taskQueue, "taskQueue is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
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
        try {
            taskQueue.append(item.threadId(), item.title(), BranchBase.MAIN, seedPrompt);
        }
        catch (IllegalArgumentException e) {
            throw status(400, "could not queue task: " + e.getMessage());
        }
        // Serial-execution rule: if the thread's slot is free, the entry
        // we just appended materialises into a task immediately and we can
        // link it. On a busy thread this returns empty and the link stays
        // null until the queue drains.
        Optional<Task> started = scheduler.startNextIfIdle(item.threadId(), null);
        String linkedTaskId = started.map(Task::id).orElse(null);
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
