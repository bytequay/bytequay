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
package com.bytequay.app.service.brain;

import com.bytequay.app.beans.brain.BrainMessageResponse;
import com.bytequay.app.developmentflow.task.TaskBrainConversationRuntime;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestBrainService
{
    private static final String TASK_ID = "task-1";

    private final TaskStore tasks = mock(TaskStore.class);
    private final BrainServiceImpl service = new BrainServiceImpl(tasks);

    @Test
    void rejectsLegacyMessages()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(TASK_ID);
        when(tasks.findTaskById(TASK_ID)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.sendMessage(TASK_ID, "How many pushes?", List.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void routesV2MessagesToTheTypedRuntime()
    {
        TaskBrainConversationRuntime typed = mock(TaskBrainConversationRuntime.class);
        BrainMessageResponse expected = new BrainMessageResponse("turn", "trunk");
        when(typed.isV2Task(TASK_ID)).thenReturn(true);
        when(typed.sendMessage(TASK_ID, "Inspect this", List.of())).thenReturn(expected);
        service.setV2Brain(typed);

        assertThat(service.sendMessage(TASK_ID, "Inspect this", List.of())).isEqualTo(expected);
    }

    @Test
    void rejectsBlankTextAndUnknownTasks()
    {
        assertThatThrownBy(() -> service.sendMessage(TASK_ID, "  ", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.sendMessage("missing", "hi", null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
