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

import com.bytequay.app.context.ContextSpecCheck;
import com.bytequay.app.service.goal.GoalActivity;
import com.bytequay.app.service.goal.GoalConversationService;
import com.bytequay.app.service.goal.GoalGap;
import com.bytequay.app.service.goal.GoalItemState;
import com.bytequay.app.service.goal.GoalItemView;
import com.bytequay.app.service.goal.GoalKnowledgeNoteView;
import com.bytequay.app.service.goal.GoalLessonService;
import com.bytequay.app.service.goal.GoalResearchService;
import com.bytequay.app.service.goal.GoalService;
import com.bytequay.app.service.goal.GoalSpecChecks;
import com.bytequay.app.service.goal.GoalView;
import com.bytequay.app.service.goal.WorkItemView;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalKnowledgeNote;
import com.bytequay.core.domain.GoalLesson;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.domain.WorkItem;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

class TestGoalController
{
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final GoalItemView MERGED = new GoalItemView(GoalItemKind.PULL_REQUEST, "trinodb/trino#30702", "#30702", "Retry Iceberg commits",
            GoalItemState.MERGED, "Merged", false, Optional.of("https://github.com/trinodb/trino/pull/30702"),
            Optional.of(new GoalActivity("#30702 merged", NOW)), Optional.of(NOW), Optional.empty());
    private static final GoalView GOAL = new GoalView("goal-1", "workspace", "Delta Lake correctness", "", true,
            Optional.of(new GoalSource(GoalItemKind.ISSUE, "trinodb/trino#31000")), Optional.empty(), 0, Optional.empty(), false,
            ImmutableList.of(new WorkItemView("work-1", "workspace", Optional.of("goal-1"), "Retry Iceberg commits", WorkItem.Kind.DEVELOP,
                    "", WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.WORKING, 0,
                    ImmutableList.of(MERGED), ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0)),
                    ImmutableList.of(), ImmutableList.of(), NOW, NOW);

    private final GoalService goals = mock(GoalService.class);
    private final GoalConversationService conversations = mock(GoalConversationService.class);
    private final GoalResearchService research = mock(GoalResearchService.class);
    private final GoalLessonService lessons = mock(GoalLessonService.class);
    private final GoalSpecChecks specChecks = mock(GoalSpecChecks.class);
    private final MockMvc mvc = standaloneSetup(new GoalController(goals, conversations, research, lessons, specChecks)).build();

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
        verify(goals, never()).pause(any(), any(), anyBoolean());

        when(goals.update("workspace", "goal-1", Optional.empty(), Optional.empty(), false)).thenReturn(GOAL);
        when(goals.pause("workspace", "goal-1", true)).thenReturn(GOAL);
        mvc.perform(patch("/api/workspaces/workspace/goals/goal-1").contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));
        verify(goals).pause("workspace", "goal-1", true);

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
    void startsAndContinuesTheGoalConversation()
            throws Exception
    {
        when(conversations.start("workspace", "op-1", "Become an Iceberg connector expert")).thenReturn(GOAL);
        mvc.perform(post("/api/workspaces/workspace/goals/conversation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"op-1\",\"message\":\"Become an Iceberg connector expert\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("goal-1"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.definition").value(nullValue()));

        GoalExchange answered = GoalExchange.ask("op-2", "goal-1", "workspace", "Reads first", 0, NOW).answered("codex/gpt", new GoalExchange.Reply("You want to start with reads.",
                Optional.of(new GoalExchange.Question("Do you know the Iceberg specification?", ImmutableList.of("Yes", "Not yet"))), Optional.empty(), Optional.empty(),
                Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty()), NOW);
        when(conversations.ask("workspace", "goal-1", "op-2", "Reads first", Optional.empty())).thenReturn(ImmutableList.of(answered));
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/exchanges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"op-2\",\"message\":\"Reads first\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("ANSWERED"))
                .andExpect(jsonPath("$[0].reply.question.suggestions[1]").value("Not yet"))
                .andExpect(jsonPath("$[0].reply.proposal").value(nullValue()));

        when(conversations.accept("workspace", "goal-1", "op-2", Optional.of("Iceberg reads"))).thenReturn(GOAL);
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/definition").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchangeId\":\"op-2\",\"title\":\"Iceberg reads\"}"))
                .andExpect(status().isOk());
        verify(conversations).accept("workspace", "goal-1", "op-2", Optional.of("Iceberg reads"));

        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/exchanges/op-2/retry")).andExpect(status().isOk());

        when(conversations.acceptCourse("workspace", "goal-1", "op-3")).thenReturn(GOAL);
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/course").contentType(MediaType.APPLICATION_JSON).content("{\"exchangeId\":\"op-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.course").value(nullValue()));
        verify(conversations).acceptCourse("workspace", "goal-1", "op-3");
        when(conversations.acceptPlan("workspace", "goal-1", "op-4")).thenReturn(GOAL);
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/plan").contentType(MediaType.APPLICATION_JSON).content("{\"exchangeId\":\"op-4\"}"))
                .andExpect(status().isOk());
        verify(conversations).acceptPlan("workspace", "goal-1", "op-4");
        verify(conversations).retry("workspace", "goal-1", "op-2");
    }

    @Test
    void startsAndDismissesCardsAndReadsTheGoalsNotes()
            throws Exception
    {
        GoalRecommendation card = new GoalRecommendation("card-1", "goal-1", "workspace", new GoalRecommendation.Card(GoalRecommendation.Kind.RESEARCH,
                "Read the Iceberg specification", "You start from the specification", "Snapshots and manifests", ImmutableList.of("https://iceberg.apache.org/spec/"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                GoalRecommendation.State.SHOWN, Optional.empty(), Optional.empty(), Optional.empty(), "op-1", NOW, NOW);
        when(conversations.startCard("workspace", "goal-1", "card-1")).thenReturn(card.started(Optional.of("task-1"), NOW));
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/recommendations/card-1/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STARTED"))
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.card.pages[0]").value("https://iceberg.apache.org/spec/"));

        when(conversations.dismiss("workspace", "goal-1", "card-1")).thenReturn(card.dismissed(NOW));
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/recommendations/card-1/dismiss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DISMISSED"));

        GoalKnowledgeNoteView note = new GoalKnowledgeNoteView("note-1", "Manifest lists", "A snapshot lists its manifests.",
                ImmutableList.of(new GoalKnowledgeNote.Source("trinodb/trino:core/A.java", Optional.of("a".repeat(40)))), "task-1", NOW, true, Optional.of("m7"));
        when(research.notes("workspace", "goal-1")).thenReturn(ImmutableList.of(note));
        mvc.perform(get("/api/workspaces/workspace/goals/goal-1/knowledge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].topic").value("Manifest lists"))
                .andExpect(jsonPath("$[0].sources[0].commit").value("a".repeat(40)))
                .andExpect(jsonPath("$[0].stale").value(true))
                .andExpect(jsonPath("$[0].memoryId").value("m7"));
        when(research.remember("workspace", "goal-1", "note-1")).thenReturn(note);
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/knowledge/note-1/remember"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryId").value("m7"));
    }

    @Test
    void opensReadsAndFinishesLessons()
            throws Exception
    {
        when(lessons.open("workspace", "goal-1", "lesson-1")).thenReturn(GoalLesson.writing("lesson-1", "goal-1", "workspace", NOW));
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/lessons/lesson-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WRITING"));
        when(lessons.writeAgain("workspace", "goal-1", "lesson-1")).thenReturn(GoalLesson.writing("lesson-1", "goal-1", "workspace", NOW.plusSeconds(1)));
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/lessons/lesson-1").param("again", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WRITING"));
        verify(lessons).writeAgain("workspace", "goal-1", "lesson-1");
        when(lessons.find("workspace", "goal-1", "lesson-1")).thenReturn(Optional.of(GoalLesson.writing("lesson-1", "goal-1", "workspace", NOW).written("codex/gpt",
                new GoalLesson.Content(Optional.of("A table is a tree of metadata."), ImmutableList.of("A snapshot never changes once written."),
                        ImmutableList.of(new GoalLesson.Section("Metadata", "Each snapshot lists its manifests.", Optional.of(new GoalLesson.Diagram(GoalLesson.Diagram.Kind.TREE,
                                "Table metadata", ImmutableList.of(new GoalLesson.Node("table", "Table", Optional.empty(), Optional.empty(), Optional.empty()),
                                        new GoalLesson.Node("snapshot", "Snapshot", Optional.of("Never changes"), Optional.of("table"), Optional.empty())), ImmutableList.of())))),
                        Optional.empty(), "Run one test.", "It passes.", "What is a snapshot?"), NOW)));
        mvc.perform(get("/api/workspaces/workspace/goals/goal-1/lessons/lesson-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.check").value("What is a snapshot?"))
                .andExpect(jsonPath("$.content.summary").value("A table is a tree of metadata."))
                .andExpect(jsonPath("$.content.sections[0].diagram.kind").value("TREE"))
                .andExpect(jsonPath("$.content.sections[0].diagram.nodes[1].parent").value("table"));
        when(lessons.find("workspace", "goal-1", "lesson-2")).thenReturn(Optional.empty());
        mvc.perform(get("/api/workspaces/workspace/goals/goal-1/lessons/lesson-2")).andExpect(status().isNotFound());
        when(lessons.finish("workspace", "goal-1", "lesson-1", "op-9")).thenReturn(GOAL);
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/lessons/lesson-1/done").contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"op-9\"}"))
                .andExpect(status().isOk());
        verify(lessons).finish("workspace", "goal-1", "lesson-1", "op-9");

        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/exchanges").contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-10\",\"message\":\"Why a tree?\",\"lessonId\":\"lesson-1\"}")).andExpect(status().isOk());
        verify(conversations).ask("workspace", "goal-1", "op-10", "Why a tree?", Optional.of("lesson-1"));
    }

    @Test
    void stopsTheReplyTheAgentIsWriting()
            throws Exception
    {
        when(conversations.stop("workspace", "goal-1", "op-1")).thenReturn(ImmutableList.of());
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/exchanges/op-1/stop")).andExpect(status().isOk());
        verify(conversations).stop("workspace", "goal-1", "op-1");
    }

    @Test
    void summarizesFinishedWorkAgain()
            throws Exception
    {
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/work/work-1/summary")).andExpect(status().isAccepted());
        verify(conversations).summarize("workspace", "goal-1", "work-1");
    }

    @Test
    void updateNowAsksTheAgentToReassess()
            throws Exception
    {
        when(conversations.reassess("workspace", "goal-1", "op-1")).thenReturn(ImmutableList.of());
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/reassess").contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"op-1\"}"))
                .andExpect(status().isOk());
        verify(conversations).reassess("workspace", "goal-1", "op-1");
    }

    @Test
    void choosesTheGoalsOwnModelOrFollowsTheWorkspaceAgain()
            throws Exception
    {
        AgentModelSetting chosen = new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "codex", "Codex", "gpt-5.6-sol", "GPT-5.6 Sol", Optional.of("low"));
        when(conversations.chooseModel("workspace", "goal-1", "codex", "gpt-5.6-sol", Optional.of("low"))).thenReturn(chosen);
        mvc.perform(put("/api/workspaces/workspace/goals/goal-1/model").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configId\":\"codex\",\"model\":\"gpt-5.6-sol\",\"effort\":\"low\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explicit").value(true))
                .andExpect(jsonPath("$.effort").value("low"));

        when(conversations.followWorkspaceModel("workspace", "goal-1"))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, false, "codex", "Codex", "gpt-5.6-sol", "GPT-5.6 Sol", Optional.of("xhigh")));
        mvc.perform(delete("/api/workspaces/workspace/goals/goal-1/model")).andExpect(status().isOk()).andExpect(jsonPath("$.explicit").value(false));

        when(conversations.model("workspace", "goal-1")).thenReturn(chosen);
        mvc.perform(get("/api/workspaces/workspace/goals/goal-1/model")).andExpect(status().isOk()).andExpect(jsonPath("$.model").value("gpt-5.6-sol"));
    }

    @Test
    void readsTheLastSpecChecksGapsAndAddsOneToTheGoal()
            throws Exception
    {
        GoalGap gap = new GoalGap("check-1", "c-1", "plugin/trino-delta-lake/", "Checkpoint keys actions by path only", "Reconcile by path and deletion vector",
                "https://github.com/delta-io/delta/blob/master/PROTOCOL.md", Optional.empty(), Optional.of(new ContextSpecCheck.Draft("Checkpoint loses rows",
                "Body", ImmutableList.of("bug"))), Optional.empty(), Optional.empty());
        when(specChecks.gaps("workspace", "goal-1")).thenReturn(ImmutableList.of(gap));
        mvc.perform(get("/api/workspaces/workspace/goals/goal-1/gaps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].candidateId").value("c-1"))
                .andExpect(jsonPath("$[0].draft.title").value("Checkpoint loses rows"));
        mvc.perform(post("/api/workspaces/workspace/goals/goal-1/gaps/c-1")).andExpect(status().isOk()).andExpect(jsonPath("$[0].module").value("plugin/trino-delta-lake/"));
        verify(specChecks).adopt("workspace", "goal-1", "c-1");
    }

    @Test
    void theUserCorrectsATopicLevel()
            throws Exception
    {
        when(conversations.correctTopic("workspace", "goal-1", "Manifests", GoalTopic.Level.LEARNING)).thenReturn(GOAL);
        mvc.perform(put("/api/workspaces/workspace/goals/goal-1/topics").contentType(MediaType.APPLICATION_JSON).content("{\"topic\":\"Manifests\",\"level\":\"LEARNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics").isArray());
        verify(conversations).correctTopic("workspace", "goal-1", "Manifests", GoalTopic.Level.LEARNING);
    }
}
