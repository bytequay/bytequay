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
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(items.list("workspace")).thenReturn(ImmutableList.of(work("Cache table metadata per catalog")));
    }

    @Test
    void proposalsWaitOnTheEpicLessItsWorkAndAnotherAskWhileOneRunsChangesNothing()
    {
        when(helper.answer(any(), contains("Cache table metadata per catalog."), eq(""), anyString(), any(Duration.class), eq("epic-breakdown:" + EPIC))).thenReturn("""
                ```json
                {"items": [
                  {"title": "Cache table metadata per catalog", "doneWhen": "Already the Epic's work"},
                  {"title": "Invalidate cached metadata on DDL", "doneWhen": "ALTER TABLE reads the table again"}
                ]}
                ```""");
        breakdowns.start("workspace", EPIC, SOURCE);
        breakdowns.start("workspace", EPIC, SOURCE);
        assertThat(queued).as("one breakdown runs at a time").hasSize(1);
        assertThat(breakdowns.running(EPIC)).isTrue();

        queued.removeFirst().run();
        assertThat(breakdowns.running(EPIC)).isFalse();
        assertThat(breakdowns.failure(EPIC)).isEmpty();
        assertThat(epics.find(EPIC).orElseThrow().proposals()).extracting(EpicProposal::title, EpicProposal::doneWhen)
                .containsExactly(tuple("Invalidate cached metadata on DDL", "ALTER TABLE reads the table again"));
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
                .thenReturn("{\"items\": [{\"title\": \"Expose cache metrics\", \"doneWhen\": \"Hits and misses are reported\"}]}");
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
            many.append(i == 0 ? "" : ",").append("{\"title\": \"Work ").append(i).append("\", \"doneWhen\": \"Done ").append(i).append("\"}");
        }
        assertThatThrownBy(() -> EpicBreakdowns.parse(many.append("]}").toString())).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicBreakdowns.parse("")).isInstanceOf(AgentUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "{\"items\":null}", "{\"items\":[null]}", "{\"items\":[{\"title\":\"Missing done-when\"}]}",
            "{\"items\":[{\"title\":123,\"doneWhen\":true}]}",
            "{\"items\":[{\"title\":\" \",\"doneWhen\":\"d\"}]}",
            "{\"items\":[{\"title\":\"t\",\"doneWhen\":\"d\",\"why\":\"extra\"}]}",
            "{\"items\":[],\"unexpected\":true}", "{\"items\":[],\"items\":[]}", "{\"items\":[]} {}"})
    void rejectsMalformedProposals(String json)
    {
        assertThatThrownBy(() -> EpicBreakdowns.parse(json)).isInstanceOf(AgentUnavailableException.class);
    }

    @Test
    void acceptsAnExplicitEmptyResultAndEnforcesTextBounds()
    {
        assertThat(EpicBreakdowns.parse("{\"items\":[]}")).isEmpty();
        assertThatThrownBy(() -> EpicBreakdowns.parse("{\"items\":[{\"title\":\"%s\",\"doneWhen\":\"done\"}]}".formatted("x".repeat(121))))
                .isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicBreakdowns.parse("{\"items\":[{\"title\":\"title\",\"doneWhen\":\"%s\"}]}".formatted("x".repeat(401))))
                .isInstanceOf(AgentUnavailableException.class);
    }

    @Test
    void anEpicRecordKeepsEachProposedTitleOnce()
    {
        EpicProposal first = new EpicProposal("1", "Expose cache metrics", "Hits and misses are reported");
        Epic epic = Epic.of(EPIC, "workspace").withProposals(ImmutableList.of(first, new EpicProposal("2", "expose cache metrics", "Again")));
        assertThat(epic.withProposals(ImmutableList.of(new EpicProposal("3", "Expose Cache Metrics", "Once more"))).proposals()).containsExactly(first);
        assertThat(epic.withoutProposal("1").proposals()).isEmpty();
    }

    private static WorkItem work(String title)
    {
        Instant now = Instant.EPOCH;
        return new WorkItem("work-" + title.hashCode(), "workspace", Optional.empty(), title, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(new WorkItemEvent("created", "", "CREATED", "Planned", now)), now, now,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.of(EPIC), 0);
    }
}
