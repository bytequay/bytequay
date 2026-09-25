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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.app.context.DevelopmentContextActivityService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDevelopmentContextActivityController
{
    @Test
    void refusesToReadAnotherWorkspacesTaskReceipts()
    {
        AgentTaskService tasks = mock(AgentTaskService.class);
        DevelopmentContextActivityService activity = mock(DevelopmentContextActivityService.class);
        TaskId taskId = TaskId.random();
        when(tasks.getInteractive("wrong-workspace", taskId))
                .thenThrow(new IllegalArgumentException("Task belongs to another workspace"));

        DevelopmentContextActivityController controller = new DevelopmentContextActivityController(tasks, activity);

        assertThatThrownBy(() -> controller.get(taskId.toString(), "wrong-workspace"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another workspace");
        verifyNoInteractions(activity);
    }
}
