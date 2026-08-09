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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.threads.TaskService;
import com.bytequay.app.service.workmodel.ReasoningEffortService;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({TaskRuntimeController.class, TaskController.class})
class TestTaskResumeControllers
{
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TaskService taskService;
    @MockitoBean
    private WorkModelResolver workModelResolver;
    @MockitoBean
    private ReasoningEffortService reasoningEfforts;
    @MockitoBean
    private ReviewStore reviewStore;

    @Test
    void flatTaskResumeRoutesByTaskId()
            throws Exception
    {
        when(taskService.resumeTask("task-1")).thenReturn(task("task-1", "thread-1"));

        mvc.perform(post("/api/tasks/task-1/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-1"));

        verify(taskService).resumeTask("task-1");
    }

    @Test
    void nestedTaskResumeRemainsACompatibilityWrapper()
            throws Exception
    {
        when(taskService.resumeTask("thread-1", "task-1")).thenReturn(task("task-1", "thread-1"));

        mvc.perform(post("/api/threads/thread-1/tasks/task-1/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-1"));

        verify(taskService).resumeTask("thread-1", "task-1");
    }

    @Test
    void retryCiUsesTheExplicitTaskAction()
            throws Exception
    {
        when(taskService.retryFailedCi("thread-1", "task-1"))
                .thenReturn(task("task-1", "thread-1"));

        mvc.perform(post("/api/threads/thread-1/tasks/task-1/retry-ci"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-1"));

        verify(taskService).retryFailedCi("thread-1", "task-1");
    }

    private static Task task(String id, String threadId)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Task(
                id, threadId, 1L, TaskStatus.IDLE,
                "dev/x", null, "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null,
                now, null, null, null, null, null);
    }
}
