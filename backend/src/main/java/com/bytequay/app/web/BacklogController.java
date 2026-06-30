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

import com.bytequay.app.beans.backlog.BacklogItemDto;
import com.bytequay.app.beans.backlog.BatchCreateBacklogRequest;
import com.bytequay.app.beans.backlog.BatchCreateBacklogResponse;
import com.bytequay.app.beans.backlog.CreateBacklogItemRequest;
import com.bytequay.app.beans.backlog.SkipBacklogItemRequest;
import com.bytequay.app.beans.backlog.StartDevelopmentResponse;
import com.bytequay.app.beans.backlog.UpdateBacklogItemRequest;
import com.bytequay.app.service.backlog.BacklogService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the per-thread backlog (the trunk's Backlog tab). List
 * / create are thread-scoped; update / delete / start-development address
 * a single item. "Start development" cuts a task seeded from the item.
 */
@RestController
public class BacklogController
{
    private final BacklogService backlog;

    public BacklogController(BacklogService backlog)
    {
        this.backlog = requireNonNull(backlog, "backlog is null");
    }

    @GetMapping("/api/threads/{threadId}/backlog")
    public List<BacklogItemDto> list(@PathVariable String threadId)
    {
        return backlog.list(threadId).stream().map(BacklogItemDto::from).toList();
    }

    @GetMapping("/api/workspaces/{workspaceId}/backlog")
    public List<BacklogItemDto> listForWorkspace(
            @PathVariable String workspaceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String thread,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q)
    {
        return backlog.listForWorkspace(workspaceId, status, thread, tag, q).stream()
                .map(BacklogItemDto::from)
                .toList();
    }

    @PostMapping("/api/threads/{threadId}/backlog")
    public BacklogItemDto create(@PathVariable String threadId, @RequestBody CreateBacklogItemRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "request body is required");
        }
        return BacklogItemDto.from(
                backlog.create(threadId, body.title(), body.body(), body.tags(), body.priority()));
    }

    @PostMapping("/api/threads/{threadId}/backlog/batch")
    public BatchCreateBacklogResponse createBatch(
            @PathVariable String threadId, @RequestBody BatchCreateBacklogRequest body)
    {
        if (body == null || body.items() == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "items are required");
        }
        List<BacklogService.NewBacklogItem> items = body.items().stream()
                .map(i -> new BacklogService.NewBacklogItem(i.title(), i.body(), i.tags(), i.priority()))
                .toList();
        BacklogService.BatchResult result = backlog.createBatch(threadId, items);
        return new BatchCreateBacklogResponse(result.backlogItemIds(), result.relatedBacklogGroupId());
    }

    @PatchMapping("/api/backlog/{itemId}")
    public BacklogItemDto update(@PathVariable String itemId, @RequestBody UpdateBacklogItemRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "request body is required");
        }
        return BacklogItemDto.from(
                backlog.update(itemId, body.title(), body.body(), body.tags(), body.priority()));
    }

    @DeleteMapping("/api/backlog/{itemId}")
    public void delete(@PathVariable String itemId)
    {
        backlog.delete(itemId);
    }

    @PostMapping("/api/backlog/{itemId}/skip")
    public BacklogItemDto skip(@PathVariable String itemId, @RequestBody(required = false) SkipBacklogItemRequest body)
    {
        return BacklogItemDto.from(backlog.skip(itemId, body == null ? null : body.reason()));
    }

    @PostMapping("/api/backlog/{itemId}/revive")
    public BacklogItemDto revive(@PathVariable String itemId)
    {
        return BacklogItemDto.from(backlog.revive(itemId));
    }

    @PostMapping("/api/backlog/{itemId}/start-development")
    public StartDevelopmentResponse startDevelopment(@PathVariable String itemId)
    {
        BacklogService.StartResult result = backlog.startDevelopment(itemId);
        return new StartDevelopmentResponse(BacklogItemDto.from(result.item()), result.taskId());
    }

    @PostMapping("/api/backlog/{itemId}/cancel-exploration")
    public BacklogItemDto cancelExploration(@PathVariable String itemId)
    {
        return BacklogItemDto.from(backlog.cancelExploration(itemId));
    }
}
