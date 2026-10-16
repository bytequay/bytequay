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
package com.bytequay.app.web.review;

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.review.IdeCodeQueries;
import com.bytequay.app.review.IdeConnection;
import com.bytequay.app.review.IdeExecutions;
import com.bytequay.app.review.IdeRuntimeService;
import com.bytequay.app.review.ReviewCodeService;
import com.bytequay.app.review.TaskCodeHost;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.core.review.ReviewConflictException;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestIdeController
{
    private final ReviewCodeService code = mock(ReviewCodeService.class);
    private final IdeRuntimeService runtime = mock(IdeRuntimeService.class);
    private final MockMvc mvc = standaloneSetup(new IdeController(code, runtime, mock(IdeCodeQueries.class), mock(IdeExecutions.class)))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json().build()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void resolveHosts()
    {
        when(code.reviewHost(anyString())).thenAnswer(call -> host(call.getArgument(0)));
    }

    private static TaskCodeHost host(String reviewId)
    {
        return new TaskCodeHost("workspace-1", "task-1", Optional.of(reviewId));
    }

    @Test
    void checksBothHostsWithoutStartingTheServer()
            throws Exception
    {
        TaskId taskId = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(taskId, "workspace", RepositoryIdentity.valueOf("acme/project"), Path.of("/worktree", taskId.toString()),
                "bytequay/task/" + taskId, "a".repeat(40));
        when(code.ideWorktree(host("review"))).thenReturn(worktree);
        when(code.ideWorktree(new TaskCodeHost("workspace", "task"))).thenReturn(worktree);
        IdeConnection connection = new IdeConnection("task", "session", "127.0.0.1:54321", "secret", "/worktree", "/bundle", "/cache", "a".repeat(40));
        for (String route : ImmutableList.of("/agent/ide/reviews/review", "/agent/ide/tasks/workspace/task")) {
            when(runtime.existing(worktree)).thenReturn(Optional.empty());
            mvc.perform(get(route)).andExpect(status().isOk()).andExpect(content().string("null"));
            when(runtime.existing(worktree)).thenReturn(Optional.of(connection));
            mvc.perform(get(route)).andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("session"));
        }
        verify(runtime, never()).open(any());
    }

    @Test
    void requiresAnAvailableOwnedWorktreeBeforeReturningTheServer()
            throws Exception
    {
        when(code.ideWorktree(new TaskCodeHost("workspace", "task"))).thenThrow(new ReviewConflictException("The task's worktree was removed"));
        mvc.perform(get("/agent/ide/tasks/workspace/task")).andExpect(status().isConflict());
        verifyNoInteractions(runtime);
    }
}
