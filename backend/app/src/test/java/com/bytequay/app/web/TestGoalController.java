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

import com.bytequay.app.service.goal.GoalActivity;
import com.bytequay.app.service.goal.GoalItemState;
import com.bytequay.app.service.goal.GoalItemView;
import com.bytequay.app.service.goal.GoalService;
import com.bytequay.app.service.goal.GoalView;
import com.bytequay.app.service.goal.WorkItemView;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.domain.WorkItem;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestGoalController
{
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final GoalItemView MERGED = new GoalItemView(GoalItemKind.PULL_REQUEST, "trinodb/trino#30702", "#30702", "Retry Iceberg commits",
            GoalItemState.MERGED, "Merged", false, Optional.of("https://github.com/trinodb/trino/pull/30702"),
            Optional.of(new GoalActivity("#30702 merged", NOW)), Optional.of(NOW));
    private static final GoalView GOAL = new GoalView("goal-1", "workspace", "Delta Lake correctness", "", true,
            Optional.of(new GoalSource(GoalItemKind.ISSUE, "trinodb/trino#31000")),
            ImmutableList.of(new WorkItemView("work-1", "workspace", Optional.of("goal-1"), "Retry Iceberg commits", WorkItem.Kind.DEVELOP,
                    "", WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.WORKING, 0,
                    ImmutableList.of(MERGED), ImmutableList.of(), NOW, NOW)), NOW, NOW);

    private final GoalService goals = mock(GoalService.class);
    private final MockMvc mvc = standaloneSetup(new GoalController(goals)).build();

    @Test
    void listsGoalsWithTheirItemsReadLocally()
            throws Exception
    {
        when(goals.list("workspace")).thenReturn(ImmutableList.of(GOAL));
        mvc.perform(get("/api/workspaces/workspace/goals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pinned").value(true))
                .andExpect(jsonPath("$[0].source.kind").value("ISSUE"))
                .andExpect(jsonPath("$[0].items[0].links[0].kind").value("PULL_REQUEST"))
                .andExpect(jsonPath("$[0].items[0].links[0].label").value("#30702"))
                .andExpect(jsonPath("$[0].items[0].state").value("WORKING"))
                .andExpect(jsonPath("$[0].items[0].links[0].url").value("https://github.com/trinodb/trino/pull/30702"))
                .andExpect(jsonPath("$[0].items[0].links[0].activity.text").value("#30702 merged"))
                .andExpect(jsonPath("$[0].items[0].links[0].addedAt").exists());
    }

    @Test
    void createsPinsAndChangesItems()
            throws Exception
    {
        when(goals.create("workspace", "Parquet performance", "")).thenReturn(GOAL);
        mvc.perform(post("/api/workspaces/workspace/goals").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Parquet performance\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("goal-1"));

        when(goals.update("workspace", "goal-1", Optional.empty(), Optional.empty(), true)).thenReturn(GOAL);
        mvc.perform(patch("/api/workspaces/workspace/goals/goal-1").contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}"))
                .andExpect(status().isOk());
        verify(goals).update("workspace", "goal-1", Optional.empty(), Optional.empty(), true);

        when(goals.addItem("workspace", "goal-1", GoalItemKind.JIRA, "connection:cloud:10412")).thenReturn(GOAL);
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"JIRA\",\"key\":\"connection:cloud:10412\"}"))
                .andExpect(status().isOk());

        when(goals.removeItem("workspace", "goal-1", GoalItemKind.PULL_REQUEST, "trinodb/trino#30702")).thenReturn(GOAL);
        mvc.perform(delete("/api/workspaces/workspace/goals/goal-1/items").param("kind", "PULL_REQUEST").param("key", "trinodb/trino#30702"))
                .andExpect(status().isOk());
        verify(goals).removeItem("workspace", "goal-1", GoalItemKind.PULL_REQUEST, "trinodb/trino#30702");

        mvc.perform(delete("/api/workspaces/workspace/goals/goal-1")).andExpect(status().isNoContent());
        verify(goals).delete("workspace", "goal-1");
    }

    @Test
    void searchesStoredItemsForTheAddBox()
            throws Exception
    {
        when(goals.candidates("workspace", "retry")).thenReturn(ImmutableList.of(new GoalItemView(MERGED.kind(), MERGED.key(), MERGED.label(), MERGED.title(),
                MERGED.state(), MERGED.status(), false, MERGED.url(), MERGED.activity(), Optional.empty())));
        mvc.perform(get("/api/workspaces/workspace/goals/candidates").param("q", "retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("trinodb/trino#30702"))
                .andExpect(jsonPath("$[0].addedAt").value(nullValue()));
    }
}
