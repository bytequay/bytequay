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
package com.bytequay.app.workitem;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.WorkItemBridge;
import com.bytequay.app.service.epic.EpicService;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.app.service.goal.WorkItemView;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.store.WorkItemStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestWorkItemBridgeResolver
{
    @Test
    void agentCreationIsLocalScopedIdempotentAndRevokedWithTheTask()
    {
        String taskId = UUID.randomUUID().toString();
        AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(task.worktreeRemovedAt()).thenReturn(Optional.empty());
        AgentTaskStore tasks = mock(AgentTaskStore.class);
        when(tasks.find(TaskId.valueOf(taskId))).thenReturn(Optional.of(task));
        WorkItemService work = mock(WorkItemService.class);
        WorkItemCatalog catalog = mock(WorkItemCatalog.class);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("work", work);
        beans.registerSingleton("catalog", catalog);
        WorkItemBridgeResolver resolver = new WorkItemBridgeResolver(tasks, beans.getBeanProvider(WorkItemCatalog.class), beans.getBeanProvider(WorkItemService.class),
                beans.getBeanProvider(EpicService.class), mock(WorkItemStore.class), new ObjectMapper().findAndRegisterModules());
        WorkItemBridge bridge = resolver.resolve(taskId).orElseThrow();
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"CREATE\",\"source\":\"GITHUB\"}"))
                .hasMessageContaining("Unknown field: source");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"READ\",\"id\":\"private\",\"workspaceId\":\"other\"}"))
                .hasMessageContaining("Unknown field: workspaceId");
        verifyNoInteractions(work, catalog);
        String request = "{\"action\":\"CREATE\",\"operationKey\":\"one\",\"title\":\"Retain context\",\"description\":\"Reproduction and acceptance criteria\"}";
        bridge.call().apply(request);
        bridge.call().apply(request);
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(work, times(2)).create(eq("workspace"), ids.capture(), eq(Optional.empty()), eq("Retain context"), eq(WorkItem.Kind.DEVELOP),
                eq("Reproduction and acceptance criteria"), eq(WorkItem.CompletionRule.MERGED_DELIVERY));
        assertThat(ids.getAllValues().getFirst()).isEqualTo(ids.getAllValues().getLast());
        when(catalog.read("workspace", "private")).thenReturn(new UnifiedWorkItem("private", UnifiedWorkItem.Source.LOCAL, "Private", "Context", "PLANNED", false,
                Instant.EPOCH, Optional.empty(), Optional.empty()));
        bridge.call().apply("{\"action\":\"READ\",\"id\":\"private\"}");
        verify(catalog).read("workspace", "private");
        when(task.state()).thenReturn(TaskState.COMPLETED);
        assertThat(resolver.resolve(taskId)).isEmpty();
        assertThatThrownBy(() -> bridge.call().apply(request)).hasMessageContaining("no longer available");
    }

    @Test
    void agentCanMarkDiscoverAndPopulateALocalEpic()
            throws Exception
    {
        String taskId = UUID.randomUUID().toString();
        AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(task.worktreeRemovedAt()).thenReturn(Optional.empty());
        AgentTaskStore tasks = mock(AgentTaskStore.class);
        when(tasks.find(TaskId.valueOf(taskId))).thenReturn(Optional.of(task));
        WorkItemService work = mock(WorkItemService.class);
        EpicService epics = mock(EpicService.class);
        WorkItemCatalog catalog = mock(WorkItemCatalog.class);
        WorkItemView view = mock(WorkItemView.class, withSettings().mockMaker(MockMakers.INLINE));
        when(view.isEpic()).thenReturn(true);
        UnifiedWorkItem item = new UnifiedWorkItem("local-item", UnifiedWorkItem.Source.LOCAL, "Private Epic", "Outcome", "PLANNED", false,
                Instant.now(), Optional.empty(), Optional.of(view));
        when(catalog.read("workspace", "local-item")).thenReturn(item);
        when(catalog.list("workspace")).thenReturn(ImmutableList.of(item));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("work", work);
        beans.registerSingleton("epics", epics);
        beans.registerSingleton("catalog", catalog);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorkItemBridgeResolver resolver = new WorkItemBridgeResolver(tasks, beans.getBeanProvider(WorkItemCatalog.class), beans.getBeanProvider(WorkItemService.class),
                beans.getBeanProvider(EpicService.class), mock(WorkItemStore.class), mapper);
        WorkItemBridge bridge = resolver.resolve(taskId).orElseThrow();
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"MARK_EPIC\",\"id\":\"local-item\",\"workspaceId\":\"other\"}"))
                .hasMessageContaining("Unknown field: workspaceId");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"MARK_EPIC\"}")).hasMessageContaining("id must contain");
        verifyNoInteractions(epics, work, catalog);
        String marked = bridge.call().apply("{\"action\":\"MARK_EPIC\",\"id\":\"local-item\"}");
        verify(epics).setLocalEpic("workspace", "local-item", true);
        assertThat(mapper.readTree(marked).at("/work/isEpic").asBoolean()).isTrue();
        assertThat(mapper.readTree(bridge.call().apply("{\"action\":\"LIST\"}")).at("/0/isEpic").asBoolean()).isTrue();
        bridge.call().apply("{\"action\":\"CREATE\",\"operationKey\":\"child\",\"title\":\"Connector\",\"description\":\"Acceptance criteria\",\"epicId\":\"LOCAL:local-item\"}");
        verify(work).createInEpic(eq("workspace"), anyString(), eq("LOCAL:local-item"), eq("Connector"), eq(WorkItem.Kind.DEVELOP), eq("Acceptance criteria"),
                eq(WorkItem.CompletionRule.MERGED_DELIVERY));
        bridge.call().apply("{\"action\":\"ASSIGN_EPIC\",\"id\":\"existing-child\",\"epicId\":\"LOCAL:local-item\"}");
        verify(work).joinLocalEpic("workspace", "LOCAL:local-item", "existing-child");
    }

    @Test
    void contextLinksStayScopedAndExposeOnlyDirectSummariesUntilExplicitlyRead()
            throws Exception
    {
        String taskId = UUID.randomUUID().toString();
        AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(task.worktreeRemovedAt()).thenReturn(Optional.empty());
        AgentTaskStore tasks = mock(AgentTaskStore.class);
        when(tasks.find(TaskId.valueOf(taskId))).thenReturn(Optional.of(task));
        WorkItemCatalog catalog = mock(WorkItemCatalog.class);
        WorkItemView view = mock(WorkItemView.class, withSettings().mockMaker(MockMakers.INLINE));
        when(view.id()).thenReturn("local-item");
        when(view.revision()).thenReturn(4L);
        UnifiedWorkItem item = new UnifiedWorkItem("local-item", UnifiedWorkItem.Source.LOCAL, "Private work", "Source context", "PLANNED", false,
                Instant.EPOCH, Optional.empty(), Optional.of(view));
        String targetId = "ISSUE:github.com:42";
        when(catalog.read("workspace", "local-item")).thenReturn(item);
        when(catalog.read("workspace", targetId)).thenReturn(new UnifiedWorkItem(targetId, UnifiedWorkItem.Source.GITHUB, "Related issue", "Full target context", "Open",
                false, Instant.EPOCH, Optional.empty(), Optional.empty()));
        WorkItemContextView summary = new WorkItemContextView(targetId, targetId, UnifiedWorkItem.Source.GITHUB, "Related issue", "Open", true, Optional.empty());
        when(catalog.contextLinks("workspace", "local-item")).thenReturn(ImmutableList.of(summary), ImmutableList.of(summary), ImmutableList.of());
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("catalog", catalog);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorkItemBridgeResolver resolver = new WorkItemBridgeResolver(tasks, beans.getBeanProvider(WorkItemCatalog.class), beans.getBeanProvider(WorkItemService.class),
                beans.getBeanProvider(EpicService.class), mock(WorkItemStore.class), mapper);
        WorkItemBridge bridge = resolver.resolve(taskId).orElseThrow();
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"LINK_CONTEXT\",\"id\":\"local-item\",\"targetId\":\"other\",\"workspaceId\":\"other\"}"))
                .hasMessageContaining("Unknown field: workspaceId");
        verifyNoInteractions(catalog);
        JsonNode read = mapper.readTree(bridge.call().apply("{\"action\":\"READ\",\"id\":\"local-item\"}"));
        assertThat(read.at("/contextLinks/0/title").asText()).isEqualTo("Related issue");
        assertThat(read.at("/contextLinks/0/status").asText()).isEqualTo("Open");
        assertThat(read.at("/contextLinks/0/description").isMissingNode()).isTrue();
        verify(catalog, never()).read("workspace", targetId);
        JsonNode linked = mapper.readTree(bridge.call().apply("{\"action\":\"LINK_CONTEXT\",\"id\":\"local-item\",\"targetId\":\"" + targetId + "\"}"));
        verify(catalog).linkContext("workspace", "local-item", 4, targetId);
        assertThat(linked.at("/contextLinks/0/id").asText()).isEqualTo(targetId);
        JsonNode target = mapper.readTree(bridge.call().apply("{\"action\":\"READ\",\"id\":\"" + targetId + "\"}"));
        assertThat(target.path("description").asText()).isEqualTo("Full target context");
        assertThat(target.path("contextLinks")).isEmpty();
        JsonNode unlinked = mapper.readTree(bridge.call().apply("{\"action\":\"UNLINK_CONTEXT\",\"id\":\"local-item\",\"targetId\":\"" + targetId + "\"}"));
        verify(catalog).unlinkContext("workspace", "local-item", 4, targetId);
        assertThat(unlinked.path("contextLinks")).isEmpty();
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"LINK_CONTEXT\",\"id\":\"" + targetId + "\",\"targetId\":\"local-item\"}"))
                .hasMessageContaining("require a local work item");
    }
}
