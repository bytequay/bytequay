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
package com.bytequay.app.service.epic;

import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.service.ai.TextOnlyHelper;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.EpicProposal;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.WorkItemStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestEpicBreakdowns
{
    private static final String EPIC = "ISSUE:github.com:1";
    private static final AgentLaneId LANE = new AgentLaneId("codex");
    private static final EpicBreakdowns.Source SOURCE = new EpicBreakdowns.Source("#1", "Iceberg REST catalog: cache metadata", "Cache table metadata per catalog.");

    private final InMemoryEpicStore epics = new InMemoryEpicStore();
    private final EpicSources sources = mock(EpicSources.class);
    private final WorkItemStore items = mock(WorkItemStore.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final TextOnlyHelper helper = mock(TextOnlyHelper.class);
    private final AgentUnitOfWork transactions = new AgentUnitOfWork()
    {
        @Override
        public <T> T execute(Work<T> work)
        {
            return work.run();
        }
    };
    private final List<Runnable> queued = new ArrayList<>();
    private final EpicBreakdowns breakdowns = new EpicBreakdowns(epics, sources, items, models, drivers, helper, transactions, queued::add);

    @BeforeEach
    void setUp()
    {
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of("workspace")))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, LANE.value(), "Codex", "model", "Model", Optional.empty()));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
        when(sources.marked("workspace", EPIC)).thenReturn(true);
        when(items.list("workspace")).thenReturn(ImmutableList.of(work("Cache table metadata per catalog", "Each catalog keeps its own cache of table metadata.")));
    }

    @Test
    void proposalsWaitOnTheEpicLessItsWorkAndAnotherAskWhileOneRunsChangesNothing()
    {
        when(helper.answer(any(), contains("Cache table metadata per catalog."), eq(""), anyString(), any(Duration.class), eq("epic-breakdown:" + EPIC))).thenReturn("""
                ```json
                {"items": [
                  {"title": "Cache table metadata per catalog", "description": "Keep one cache per catalog.", "doneWhen": "Already the Epic's work"},
                  {"title": "Invalidate cached metadata on DDL", "description": " Drop a table's cached metadata when DDL changes it. ",
                   "doneWhen": "ALTER TABLE reads the table again", "background": "Stale metadata survives ALTER TABLE.", "evidence": null, "reproduceSteps": " "}
                ]}
                ```""");
        breakdowns.start("workspace", EPIC, SOURCE);
        breakdowns.start("workspace", EPIC, SOURCE);
        assertThat(queued).as("one breakdown runs at a time").hasSize(1);
        assertThat(breakdowns.running(EPIC)).isTrue();

        queued.removeFirst().run();
        assertThat(breakdowns.running(EPIC)).isFalse();
        assertThat(breakdowns.failure(EPIC)).isEmpty();
        assertThat(epics.find(EPIC).orElseThrow().proposals())
                .extracting(EpicProposal::title, EpicProposal::doneWhen, EpicProposal::description, EpicProposal::background, EpicProposal::evidence, EpicProposal::reproduceSteps)
                .containsExactly(tuple("Invalidate cached metadata on DDL", "ALTER TABLE reads the table again", "Drop a table's cached metadata when DDL changes it.",
                        Optional.of("Stale metadata survives ALTER TABLE."), Optional.empty(), Optional.empty()));
        verify(helper).answer(any(), argThat(prompt -> prompt.contains("\"title\":\"Cache table metadata per catalog\"") && prompt.contains("\"state\":\"PLANNED\"")
                && prompt.contains("\"sources\":[\"#12\"]") && prompt.contains("Each catalog keeps its own cache of table metadata.")),
                eq(""), anyString(), any(Duration.class), anyString());
    }

    @Test
    void anAnswerThatAddsNothingSaysSoInsteadOfPadding()
    {
        when(helper.answer(any(), anyString(), anyString(), anyString(), any(Duration.class), anyString()))
                .thenReturn("{\"items\": [], \"reason\": \"Caching per catalog is the Epic's only requirement and is already its work.\"}");
        breakdowns.start("workspace", EPIC, SOURCE);
        queued.removeFirst().run();
        assertThat(breakdowns.failure(EPIC)).isEmpty();
        assertThat(breakdowns.note(EPIC)).contains("No more valuable work to suggest. Caching per catalog is the Epic's only requirement and is already its work.");
        assertThat(epics.find(EPIC).orElseThrow().proposals()).isEmpty();

        when(helper.answer(any(), anyString(), anyString(), anyString(), any(Duration.class), anyString())).thenReturn("{\"items\": []}");
        breakdowns.start("workspace", EPIC, SOURCE);
        assertThat(breakdowns.note(EPIC)).as("asking again clears what the last answer said").isEmpty();
        queued.removeFirst().run();
        assertThat(breakdowns.note(EPIC)).contains("No more valuable work to suggest.");

        when(helper.answer(any(), anyString(), anyString(), anyString(), any(Duration.class), anyString()))
                .thenReturn("{\"items\": [{\"title\": \"cache table metadata per catalog\", \"description\": \"One cache per catalog.\", \"doneWhen\": \"Cached\"}]}");
        breakdowns.start("workspace", EPIC, SOURCE);
        queued.removeFirst().run();
        assertThat(breakdowns.note(EPIC).orElseThrow()).as("a repeat of the Epic's work adds nothing either").contains("What the model suggested is already the Epic's work");

        when(helper.answer(any(), anyString(), anyString(), anyString(), any(Duration.class), anyString()))
                .thenReturn("{\"items\": [{\"title\": \"Expose cache metrics\", \"description\": \"Report hits and misses.\", \"doneWhen\": \"Reported\"}]}");
        breakdowns.start("workspace", EPIC, SOURCE);
        queued.removeFirst().run();
        assertThat(breakdowns.note(EPIC)).as("an answer with new work has nothing to explain").isEmpty();
        assertThat(epics.find(EPIC).orElseThrow().proposals()).hasSize(1);
    }

    @Test
    void anAcceptedProposalsContextNamesEachPartItHas()
    {
        assertThat(new EpicProposal("1", "Invalidate cached metadata on DDL", "ALTER TABLE reads the table again", "Drop a table's cached metadata when DDL changes it.",
                Optional.of("Stale metadata survives ALTER TABLE."), Optional.empty(), Optional.of("1. Create a table\n2. ALTER it\n3. Read it")).context()).isEqualTo("""
                ### Background
                Stale metadata survives ALTER TABLE.

                ### Description
                Drop a table's cached metadata when DDL changes it.

                ### Done when
                ALTER TABLE reads the table again

                ### Steps to reproduce
                1. Create a table
                2. ALTER it
                3. Read it""");
        // A proposal stored with only its done-when line still reads, and still makes a work item.
        assertThat(new EpicProposal("1", "Tune the cache", "Reads are faster", null, null, null, null).context()).isEqualTo("### Done when\nReads are faster");
    }

    @Test
    void aFailedOrUnshapedAnswerIsReportedAndAnEpicUnmarkedMeanwhileKeepsNothing()
    {
        when(helper.answer(any(), anyString(), anyString(), anyString(), any(Duration.class), anyString())).thenReturn("Here is the plan: cache everything.");
        breakdowns.start("workspace", EPIC, SOURCE);
        queued.removeFirst().run();
        assertThat(breakdowns.failure(EPIC)).contains("The model did not answer with work in the expected shape. Ask again.");
        assertThat(epics.find(EPIC)).isEmpty();

        when(helper.answer(any(), anyString(), anyString(), anyString(), any(Duration.class), anyString()))
                .thenReturn("{\"items\": [{\"title\": \"Expose cache metrics\", \"description\": \"Report hits and misses.\", \"doneWhen\": \"Hits and misses are reported\"}]}");
        when(sources.marked("workspace", EPIC)).thenReturn(false);
        breakdowns.start("workspace", EPIC, SOURCE);
        assertThat(breakdowns.failure(EPIC)).as("asking again clears the last failure").isEmpty();
        queued.removeFirst().run();
        assertThat(epics.find(EPIC)).isEmpty();

        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED, Optional.empty(), "Sign in",
                AgentCapabilities.none()));
        assertThatThrownBy(() -> breakdowns.start("workspace", EPIC, SOURCE)).as("a model that is not ready refuses at once").isInstanceOf(AgentUnavailableException.class);
        assertThat(queued).isEmpty();
        assertThat(breakdowns.running(EPIC)).isFalse();
    }

    @Test
    void parsingRejectsOversizedProposals()
    {
        StringBuilder many = new StringBuilder("{\"items\": [");
        for (int i = 0; i < 10; i++) {
            many.append(i == 0 ? "" : ",").append("{\"title\": \"Work ").append(i).append("\", \"description\": \"What\", \"doneWhen\": \"Done ").append(i).append("\"}");
        }
        assertThatThrownBy(() -> EpicBreakdowns.parse(many.append("]}").toString())).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicBreakdowns.parse("")).isInstanceOf(AgentUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "{\"items\":null}", "{\"items\":[null]}", "{\"items\":[{\"title\":\"Missing done-when\",\"description\":\"what\"}]}",
            "{\"items\":[{\"title\":\"Missing description\",\"doneWhen\":\"d\"}]}",
            "{\"items\":[{\"title\":123,\"description\":\"what\",\"doneWhen\":true}]}",
            "{\"items\":[{\"title\":\" \",\"description\":\"what\",\"doneWhen\":\"d\"}]}",
            "{\"items\":[{\"title\":\"t\",\"description\":\" \",\"doneWhen\":\"d\"}]}",
            "{\"items\":[{\"title\":\"t\",\"description\":\"what\",\"doneWhen\":\"d\",\"why\":\"extra\"}]}",
            "{\"items\":[],\"unexpected\":true}", "{\"items\":[],\"items\":[]}", "{\"items\":[]} {}"})
    void rejectsMalformedProposals(String json)
    {
        assertThatThrownBy(() -> EpicBreakdowns.parse(json)).isInstanceOf(AgentUnavailableException.class);
    }

    @Test
    void acceptsAnExplicitEmptyResultAndEnforcesTextBounds()
    {
        assertThat(EpicBreakdowns.parse("{\"items\":[]}").items()).isEmpty();
        assertThat(EpicBreakdowns.parse("{\"items\":[],\"reason\":\" Covered. \"}").reason()).contains("Covered.");
        assertThatThrownBy(() -> EpicBreakdowns.parse("{\"items\":[],\"reason\":\"%s\"}".formatted("x".repeat(401)))).isInstanceOf(AgentUnavailableException.class);
        String shape = "{\"items\":[{\"title\":\"%s\",\"description\":\"%s\",\"doneWhen\":\"%s\",\"evidence\":\"%s\"}]}";
        assertThat(EpicBreakdowns.parse(shape.formatted("x".repeat(120), "x".repeat(6_000), "x".repeat(400), "x".repeat(2_000))).items()).hasSize(1);
        assertThatThrownBy(() -> EpicBreakdowns.parse(shape.formatted("x".repeat(121), "what", "done", "seen"))).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicBreakdowns.parse(shape.formatted("title", "x".repeat(6_001), "done", "seen"))).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicBreakdowns.parse(shape.formatted("title", "what", "x".repeat(401), "seen"))).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicBreakdowns.parse(shape.formatted("title", "what", "done", "x".repeat(2_001)))).isInstanceOf(AgentUnavailableException.class);
    }

    @Test
    void anEpicRecordKeepsEachProposedTitleOnce()
    {
        EpicProposal first = proposal("1", "Expose cache metrics");
        Epic epic = Epic.of(EPIC, "workspace").withProposals(ImmutableList.of(first, proposal("2", "expose cache metrics")));
        assertThat(epic.withProposals(ImmutableList.of(proposal("3", "Expose Cache Metrics"))).proposals()).containsExactly(first);
        assertThat(epic.withoutProposal("1").proposals()).isEmpty();
    }

    private static EpicProposal proposal(String id, String title)
    {
        return new EpicProposal(id, title, "Hits and misses are reported", "Report cache hits and misses.", Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static WorkItem work(String title, String context)
    {
        Instant now = Instant.EPOCH;
        return new WorkItem("work-" + title.hashCode(), "workspace", Optional.empty(), title, WorkItem.Kind.DEVELOP, context, WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.PLANNED, 0, ImmutableList.of(new WorkItemLink("ISSUE:github.com:12", new GoalItem(GoalItemKind.ISSUE, "org/repo#12", "#12", title, now))),
                ImmutableList.of(new WorkItemEvent("created", "", "CREATED", "Planned", now)), now, now,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.of(EPIC), 0, ImmutableList.of());
    }
}
