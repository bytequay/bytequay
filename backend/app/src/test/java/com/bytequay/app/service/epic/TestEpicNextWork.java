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

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.app.service.ai.TextOnlyHelper;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workitem.GoalItemState;
import com.bytequay.app.workitem.GoalItemView;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.app.workitem.WorkItemView;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestEpicNextWork
{
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private static final AgentLaneId LANE = new AgentLaneId("codex");

    private final WorkItemService work = mock(WorkItemService.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final TextOnlyHelper helper = mock(TextOnlyHelper.class);
    private final EpicNextWork next = new EpicNextWork(work, models, drivers, helper, Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
    private final WorkItemView merged = work("merged", "epic", WorkItem.State.WORKING, ImmutableList.of(), task("old-task"), task("task"));

    @BeforeEach
    void setUp()
    {
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of("workspace")))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, LANE.value(), "Codex", "model", "Model", Optional.empty()));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
    }

    @Test
    void theOnlyReadyWorkIsPickedWithoutAskingTheModel()
    {
        when(work.list("workspace")).thenReturn(ImmutableList.of(merged,
                work("waits-on-merged", "epic", WorkItem.State.PLANNED, ImmutableList.of("merged")),
                work("waits-on-open", "epic", WorkItem.State.PLANNED, ImmutableList.of("working")),
                work("working", "epic", WorkItem.State.WORKING, ImmutableList.of()),
                work("other-epic", "other", WorkItem.State.PLANNED, ImmutableList.of())));

        next.merged(merged, "#31550");

        assertThat(next.find("task")).contains(new EpicNextWork.Pick("waits-on-merged", "waits-on-merged title",
                "#31550 merged. It is the only work in this Epic ready to start.", NOW));
        assertThat(next.find("old-task")).isEmpty();
        verify(helper, never()).answer(any(), anyString(), anyString(), anyString(), any(Duration.class), anyString());
    }

    @Test
    void theModelChoosesAmongReadyWorkAndAnythingElseFallsBackToTheFirst()
    {
        when(work.list("workspace")).thenReturn(ImmutableList.of(merged,
                work("first", "epic", WorkItem.State.PLANNED, ImmutableList.of()),
                work("second", "epic", WorkItem.State.PLANNED, ImmutableList.of())));
        when(helper.answer(any(), contains("\"id\":\"second\""), eq(""), anyString(), any(Duration.class), eq("epic-next:merged")))
                .thenReturn("```json\n{\"itemId\": \"second\", \"reason\": \"It builds on the reader that merged.\"}\n```");

        next.merged(merged, "#31550");
        assertThat(next.find("task")).map(EpicNextWork.Pick::message).contains("#31550 merged. It builds on the reader that merged.");
        assertThat(next.find("task")).map(EpicNextWork.Pick::itemId).contains("second");

        when(helper.answer(any(), anyString(), anyString(), anyString(), any(Duration.class), anyString())).thenReturn("{\"itemId\": \"merged\", \"reason\": \"Again\"}");
        next.merged(merged, "#31551");
        assertThat(next.find("task")).contains(new EpicNextWork.Pick("first", "first title", "#31551 merged. It comes first among the Epic's ready work.", NOW));
    }

    @Test
    void anEpicWithNoReadyWorkPicksNothing()
    {
        when(work.list("workspace")).thenReturn(ImmutableList.of(merged, work("done", "epic", WorkItem.State.DONE, ImmutableList.of())));
        next.merged(merged, "#31550");
        assertThat(next.find("task")).isEmpty();
    }

    private static GoalItemView task(String id)
    {
        return new GoalItemView(GoalItemKind.TASK, id, "Task", id, GoalItemState.WORKING, "Running", false, Optional.empty(), Optional.empty(), Optional.of(NOW),
                Optional.empty());
    }

    private static WorkItemView work(String id, String epic, WorkItem.State state, ImmutableList<String> blockedBy, GoalItemView... links)
    {
        return new WorkItemView(id, "workspace", id + " title", WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY, state, 1, ImmutableList.copyOf(links),
                ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, blockedBy, Optional.of(epic), 0, false, ImmutableList.of());
    }
}
