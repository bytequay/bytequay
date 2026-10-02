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

import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.memory.MemoryHarvestService;
import com.bytequay.app.memory.MemoryPageReader;
import com.bytequay.app.memory.MemoryPageReader.MemoryPage;
import com.bytequay.app.memory.MemoryService;
import com.bytequay.app.memory.MemorySwitches;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A workspace's repository memory. Every change answers with the page as it now is; nothing here reaches GitHub. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/memory")
public class MemoryController
{
    private final MemoryService memories;
    private final MemoryPageReader pages;
    private final MemoryHarvestService harvests;

    public MemoryController(MemoryService memories, MemoryPageReader pages, MemoryHarvestService harvests)
    {
        this.memories = requireNonNull(memories, "memories is null");
        this.pages = requireNonNull(pages, "pages is null");
        this.harvests = requireNonNull(harvests, "harvests is null");
    }

    @GetMapping
    public MemoryPage page(@PathVariable String workspaceId)
    {
        return pages.page(workspaceId);
    }

    @PatchMapping("/{memoryId}")
    public MemoryPage edit(@PathVariable String workspaceId, @PathVariable String memoryId, @RequestBody EditRequest request)
    {
        checkArgument(request != null && request.expectedVersion() != null && request.text() != null, "expectedVersion and text are required");
        memories.edit(workspaceId, memoryId, request.expectedVersion(), request.text());
        return pages.page(workspaceId);
    }

    @PostMapping("/{memoryId}/restore")
    public MemoryPage restore(@PathVariable String workspaceId, @PathVariable String memoryId, @RequestBody RestoreRequest request)
    {
        checkArgument(request != null && request.expectedVersion() != null, "expectedVersion is required");
        memories.restore(workspaceId, memoryId, request.expectedVersion());
        return pages.page(workspaceId);
    }

    @PostMapping("/{memoryId}/keep")
    public MemoryPage keep(@PathVariable String workspaceId, @PathVariable String memoryId)
    {
        memories.keep(workspaceId, memoryId);
        return pages.page(workspaceId);
    }

    @DeleteMapping("/{memoryId}")
    public MemoryPage delete(@PathVariable String workspaceId, @PathVariable String memoryId)
    {
        memories.delete(workspaceId, memoryId);
        return pages.page(workspaceId);
    }

    @PostMapping("/{memoryId}/undo-delete")
    public MemoryPage undoDelete(@PathVariable String workspaceId, @PathVariable String memoryId)
    {
        memories.undoDelete(workspaceId, memoryId);
        return pages.page(workspaceId);
    }

    @PutMapping("/switches")
    public MemoryPage switches(@PathVariable String workspaceId, @RequestBody SwitchesRequest request)
    {
        checkArgument(request != null && request.reviews() != null && request.development() != null, "reviews and development are required");
        memories.saveSwitches(workspaceId, new MemorySwitches(request.reviews(), request.development()));
        return pages.page(workspaceId);
    }

    /**
     * Learn from my PRs, or Learn from this PR when a pull request is named: reads its maintainer threads on GitHub and starts one
     * harvest run over the new ones. GitHub is only read.
     */
    @PostMapping("/harvest")
    public MemoryHarvestService.Started harvest(@PathVariable String workspaceId, @RequestBody HarvestRequest request)
    {
        checkArgument(request != null && request.requestKey() != null && request.expectedBuildSequence() != null, "requestKey and expectedBuildSequence are required");
        checkArgument(request.pull() == null || request.pull() > 0, "pull must be a pull request number");
        return harvests.start(workspaceId, new ContextBuildService.Request(request.requestKey(), request.expectedBuildSequence()), Optional.ofNullable(request.pull()),
                Optional.ofNullable(request.repo()));
    }

    public record EditRequest(Long expectedVersion, String text) {}

    public record RestoreRequest(Long expectedVersion) {}

    public record SwitchesRequest(Boolean reviews, Boolean development) {}

    public record HarvestRequest(String requestKey, Long expectedBuildSequence, Integer pull, String repo) {}
}
