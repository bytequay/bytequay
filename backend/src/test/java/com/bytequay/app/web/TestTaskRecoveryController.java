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

import com.bytequay.app.developmentflow.task.V2RecoveryControlService;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CleanupRecoveryAction;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CleanupRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CleanupRecoveryResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskRecoveryController.class)
class TestTaskRecoveryController
{
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private V2RecoveryControlService recovery;

    @Test
    void routesAnExactCiRecoveryChoice()
            throws Exception
    {
        CiRecoveryCommand command = new CiRecoveryCommand(
                "ci-command-1", CiRecoveryAction.EXTEND_BUDGET,
                1, 2, 3, "allow another repair");
        when(recovery.recoverCi("task-1", "episode-1", command))
                .thenReturn(new CiRecoveryResult(
                        "task-1", "episode-1", "ci-command-1",
                        CiRecoveryAction.EXTEND_BUDGET, "OPEN",
                        2, 3, 4, "observation-2"));

        mvc.perform(post("/api/tasks/task-1/ci-repair/episode-1/recover")
                        .contentType("application/json")
                        .content("""
                                {"commandId":"ci-command-1",
                                 "action":"EXTEND_BUDGET",
                                 "rerunDelta":1,"fixDelta":2,"pushDelta":3,
                                 "reason":"allow another repair"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeId").value("episode-1"))
                .andExpect(jsonPath("$.action").value("EXTEND_BUDGET"))
                .andExpect(jsonPath("$.observationOperationId")
                        .value("observation-2"));

        verify(recovery).recoverCi("task-1", "episode-1", command);
    }

    @Test
    void routesAnExactCleanupRecoveryChoice()
            throws Exception
    {
        CleanupRecoveryCommand command = new CleanupRecoveryCommand(
                "cleanup-command-1", CleanupRecoveryAction.WAIVE_OPTIONAL,
                "leave merged remote branch");
        when(recovery.recoverCleanup("task-1", "step-10", command))
                .thenReturn(new CleanupRecoveryResult(
                        "task-1", "step-10", "cleanup-command-1",
                        CleanupRecoveryAction.WAIVE_OPTIONAL,
                        "cleanup-1", "ticket-1", true, true));

        mvc.perform(post("/api/tasks/task-1/cleanup/steps/step-10/recover")
                        .contentType("application/json")
                        .content("""
                                {"commandId":"cleanup-command-1",
                                 "action":"WAIVE_OPTIONAL",
                                 "reason":"leave merged remote branch"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepId").value("step-10"))
                .andExpect(jsonPath("$.action").value("WAIVE_OPTIONAL"))
                .andExpect(jsonPath("$.dispatchTicketId").value("ticket-1"))
                .andExpect(jsonPath("$.rearmed").value(true));

        verify(recovery).recoverCleanup("task-1", "step-10", command);
    }
}
