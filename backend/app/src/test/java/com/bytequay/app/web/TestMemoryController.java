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

import com.bytequay.app.memory.MemoryHarvestService;
import com.bytequay.app.memory.MemoryPageReader;
import com.bytequay.app.memory.MemoryService;
import com.bytequay.app.memory.MemorySwitches;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemorySource;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestMemoryController
{
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final Memory MEMORY = new Memory("m12", "workspace", Memory.Kind.RULE, "Document every new table property.", ImmutableList.of("plugin/"),
            ImmutableList.of(new MemorySource(MemorySource.Kind.PULL_REQUEST, "https://github.com/trinodb/trino/pull/31201#discussion_r1", "Docs, please.", NOW,
                    Optional.of(new MemorySource.PullRequestPart(31201, MemorySource.Part.REVIEW_COMMENT, "1", "alice", true, true, true, false)), Optional.empty())),
            new Memory.Origin(false, "Learn from my PRs", Optional.of("task-1")), 1, Optional.empty(), 37, 4, ImmutableList.of(), NOW, NOW);
    private static final MemoryPageReader.MemoryPage PAGE = new MemoryPageReader.MemoryPage(
            ImmutableList.of(new MemoryPageReader.Entry(MEMORY, ImmutableList.of("plugin/"))), new MemorySwitches(true, false),
            ImmutableList.of(new MemoryPageReader.GuidelineView("CONTRIBUTING.md", 5120, false, "https://github.com/trinodb/trino/blob/abc/CONTRIBUTING.md")),
            Optional.of("abc"), new MemoryPageReader.HarvestView(Optional.of(new MemoryHarvestService.Cursor(31201, NOW)), Optional.empty()));

    private final MemoryService memories = mock(MemoryService.class);
    private final MemoryPageReader pages = mock(MemoryPageReader.class);
    private final MemoryHarvestService harvests = mock(MemoryHarvestService.class);
    private final MockMvc mvc = standaloneSetup(new MemoryController(memories, pages, harvests)).build();

    @Test
    void readsThePageWithItsMemoriesSwitchesAndGuidelines()
            throws Exception
    {
        when(pages.page("workspace")).thenReturn(PAGE);
        mvc.perform(get("/api/workspaces/workspace/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memories[0].memory.id").value("m12"))
                .andExpect(jsonPath("$.memories[0].memory.sources[0].pullRequest.author").value("alice"))
                .andExpect(jsonPath("$.memories[0].memory.sources[0].section").doesNotExist())
                .andExpect(jsonPath("$.memories[0].memory.origin.taskId").value("task-1"))
                .andExpect(jsonPath("$.memories[0].memory.previous").doesNotExist())
                .andExpect(jsonPath("$.memories[0].memory.delivered").value(37))
                .andExpect(jsonPath("$.memories[0].missingScope[0]").value("plugin/"))
                .andExpect(jsonPath("$.switches.development").value(false))
                .andExpect(jsonPath("$.guidelines[0].url").value("https://github.com/trinodb/trino/blob/abc/CONTRIBUTING.md"))
                .andExpect(jsonPath("$.head").value("abc"));
    }

    @Test
    void everyChangeAnswersWithThePage()
            throws Exception
    {
        when(pages.page("workspace")).thenReturn(PAGE);
        mvc.perform(patch("/api/workspaces/workspace/memory/m12").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"text\":\"New text\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memories[0].memory.id").value("m12"));
        verify(memories).edit("workspace", "m12", 1, "New text");

        mvc.perform(post("/api/workspaces/workspace/memory/m12/restore").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk());
        verify(memories).restore("workspace", "m12", 2);

        mvc.perform(post("/api/workspaces/workspace/memory/m12/keep")).andExpect(status().isOk());
        verify(memories).keep("workspace", "m12");

        mvc.perform(delete("/api/workspaces/workspace/memory/m12")).andExpect(status().isOk());
        verify(memories).delete("workspace", "m12");

        mvc.perform(post("/api/workspaces/workspace/memory/m12/undo-delete")).andExpect(status().isOk());
        verify(memories).undoDelete("workspace", "m12");

        mvc.perform(put("/api/workspaces/workspace/memory/switches").contentType(MediaType.APPLICATION_JSON).content("{\"reviews\":false,\"development\":true}"))
                .andExpect(status().isOk());
        verify(memories).saveSwitches("workspace", new MemorySwitches(false, true));
    }
}
