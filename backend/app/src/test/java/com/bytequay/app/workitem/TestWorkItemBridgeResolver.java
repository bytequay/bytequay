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
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.store.WorkItemStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
                mock(WorkItemStore.class), new ObjectMapper().findAndRegisterModules());
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
        bridge.call().apply("{\"action\":\"READ\",\"id\":\"private\"}");
        verify(catalog).read("workspace", "private");
        when(task.state()).thenReturn(TaskState.COMPLETED);
        assertThat(resolver.resolve(taskId)).isEmpty();
        assertThatThrownBy(() -> bridge.call().apply(request)).hasMessageContaining("no longer available");
    }
}
