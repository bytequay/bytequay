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
package com.bytequay.app.google;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.GoogleWorkspaceBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoogleWorkspaceBridgeResolver
{
    @Test
    void requiresConsentAndAnInteractiveOpenTaskAndRechecksOnEveryCall()
    {
        String id = UUID.randomUUID().toString();
        AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.interactive()).thenReturn(true);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(task.worktreeRemovedAt()).thenReturn(Optional.empty());
        AgentTaskStore tasks = mock(AgentTaskStore.class);
        when(tasks.find(TaskId.valueOf(id))).thenReturn(Optional.of(task));
        GoogleWorkspaceService service = mock(GoogleWorkspaceService.class);
        GoogleSnapshot snapshot = GoogleSnapshot.empty("people/one", "one@example.com", "One");
        when(service.snapshot()).thenReturn(Optional.of(snapshot));
        GoogleWorkspaceBridgeResolver resolver = new GoogleWorkspaceBridgeResolver(service, tasks, new ObjectMapper().findAndRegisterModules());
        assertThat(resolver.resolve(id)).isEmpty();
        when(service.snapshot()).thenReturn(Optional.of(snapshot.withAgentAccess(true)));
        GoogleWorkspaceBridge bridge = resolver.resolve(id).orElseThrow();
        String read = "{\"action\":\"LIST\",\"source\":\"MAIL\"}";
        assertThat(bridge.call().apply(read)).contains("One in-memory Gmail search page", "savedAt");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"SEND\",\"source\":\"MAIL\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"LIST\",\"source\":\"MAIL\",\"refresh\":true}"))
                .hasMessageContaining("Unknown field");
        when(service.snapshot()).thenReturn(Optional.of(snapshot));
        assertThatThrownBy(() -> bridge.call().apply(read)).hasMessageContaining("access is disabled");
        when(service.snapshot()).thenReturn(Optional.of(snapshot.withAgentAccess(true)));
        when(task.interactive()).thenReturn(false);
        assertThat(resolver.resolve(id)).isEmpty();
        when(task.interactive()).thenReturn(true);
        when(task.state()).thenReturn(TaskState.COMPLETED);
        assertThatThrownBy(() -> bridge.call().apply(read)).hasMessageContaining("no longer open");
    }
}
