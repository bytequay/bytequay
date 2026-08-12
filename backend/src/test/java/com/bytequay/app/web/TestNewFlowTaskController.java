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

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.TaskCommands;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestNewFlowTaskController
{
    @Test
    void startsThroughTheNarrowGreenfieldCommand()
    {
        TaskCommands commands = mock(TaskCommands.class);
        Task task = mock(Task.class);
        when(task.taskId()).thenReturn("task-1");
        when(task.repositoryId()).thenReturn("octocat/bytequay");
        when(task.status()).thenReturn(TaskStatus.ACTIVE);
        when(commands.startTask(
                anyString(), eq("octocat/bytequay"), eq("Fix the failing CI")))
                .thenReturn(task);

        NewFlowTaskController.StartedTask started =
                new NewFlowTaskController(commands).start(
                        "octocat", "bytequay", "request-1",
                        new NewFlowTaskController.StartTaskBody(
                                "Fix the failing CI"))
                        .getBody();

        assertThat(started).isEqualTo(new NewFlowTaskController.StartedTask(
                "task-1", "octocat/bytequay", "ACTIVE"));
        ArgumentCaptor<String> requestKey = ArgumentCaptor.forClass(String.class);
        verify(commands).startTask(
                requestKey.capture(), eq("octocat/bytequay"),
                eq("Fix the failing CI"));
        assertThat(requestKey.getValue()).startsWith("task-command:v1:");
    }

    @Test
    void rejectsCallerControlledRepositoryShapesBeforeTheCommand()
    {
        TaskCommands commands = mock(TaskCommands.class);
        NewFlowTaskController controller = new NewFlowTaskController(commands);

        assertThatThrownBy(() -> controller.start(
                "octocat/other", "bytequay", "request-1",
                new NewFlowTaskController.StartTaskBody("goal")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
