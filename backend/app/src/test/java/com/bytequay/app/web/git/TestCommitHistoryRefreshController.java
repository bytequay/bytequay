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
package com.bytequay.app.web.git;

import com.bytequay.app.git.CommitHistoryRef;
import com.bytequay.app.git.CommitHistoryRef.Source;
import com.bytequay.app.git.CommitHistoryService;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.CommitRefreshStatus.State.IDLE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestCommitHistoryRefreshController
{
    private CommitHistoryService commits;
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        commits = mock(CommitHistoryService.class);
        mvc = standaloneSetup(new CommitHistoryRefreshController(commits))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsTheCompletedRefreshResult()
            throws Exception
    {
        List<CommitHistoryRef> refs = ImmutableList.of(
                new CommitHistoryRef(Source.WORKSPACE, "feature"),
                new CommitHistoryRef(Source.UPSTREAM, "main"));
        CommitRefreshStatus completed = new CommitRefreshStatus(
                "workspace-id",
                IDLE,
                Optional.of(Instant.parse("2026-08-23T00:00:00Z")),
                Optional.of(Instant.parse("2026-08-23T00:00:00Z")),
                Optional.empty());
        when(commits.refresh("workspace-id", refs)).thenReturn(completed);

        mvc.perform(post("/api/workspaces/workspace-id/git/commits/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refs":[
                                  {"source":"WORKSPACE","branch":"feature"},
                                  {"source":"UPSTREAM","branch":"main"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));

        verify(commits).refresh("workspace-id", refs);
    }

    @Test
    void rejectsEmptyInvalidAndBackendRoutingFields()
            throws Exception
    {
        mvc.perform(post("/api/workspaces/workspace-id/git/commits/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refs\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/workspaces/workspace-id/git/commits/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refs\":[{\"source\":\"WORKSPACE\",\"branch\":\"-invalid\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/workspaces/workspace-id/git/commits/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refs":[{"source":"WORKSPACE","branch":"main",
                                  "repositoryPath":"/not/accepted"}]}
                                """))
                .andExpect(status().isBadRequest());
    }
}
