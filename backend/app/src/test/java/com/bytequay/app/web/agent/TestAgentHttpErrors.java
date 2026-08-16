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

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestAgentHttpErrors
{
    private MockMvc mvc;
    private AgentTaskService taskService;
    private AgentTurnService turnService;
    private AgentDriverRegistry driverRegistry;

    @BeforeEach
    void setUp()
    {
        taskService = mock(AgentTaskService.class);
        turnService = mock(AgentTurnService.class);
        driverRegistry = mock(AgentDriverRegistry.class);
        AgentController controller = new AgentController(
                taskService,
                turnService,
                mock(AgentEventService.class),
                mock(AgentApprovalService.class),
                driverRegistry);
        mvc = standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void mapsDomainFailuresToStableHttpStatuses()
            throws Exception
    {
        String taskId = TaskId.random().toString();
        when(taskService.get(any())).thenThrow(new AgentTaskNotFoundException(TaskId.valueOf(taskId)));
        mvc.perform(get("/agent/tasks/{taskId}", taskId))
                .andExpect(status().isNotFound());

        when(turnService.start(any())).thenThrow(new AgentConflictException("active turn"));
        mvc.perform(post("/agent/tasks/{taskId}/turns", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/agent/tasks/{taskId}/turns", taskId)
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isConflict());

        when(driverRegistry.statuses()).thenThrow(new AgentUnavailableException("provider unavailable"));
        mvc.perform(get("/agent/providers/status"))
                .andExpect(status().isServiceUnavailable());
    }
}
