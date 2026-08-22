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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.git.CommitRefreshCause.MANUAL;
import static com.bytequay.core.domain.CommitRefreshStatus.State.QUEUED;
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
    void acceptsValidatedSourceAndBranchSelectorsWithoutWaitingForSynchronization()
            throws Exception
    {
        List<CommitHistoryRef> refs = List.of(
                new CommitHistoryRef(Source.WORKSPACE, "feature"),
                new CommitHistoryRef(Source.UPSTREAM, "main"));
        CommitRefreshStatus queued = new CommitRefreshStatus(
                "workspace-id",
                QUEUED,
                Optional.of(Instant.parse("2026-08-23T00:00:00Z")),
                Optional.empty(),
                Optional.empty());
        when(commits.requestRefresh("workspace-id", refs, MANUAL)).thenReturn(queued);

        mvc.perform(post("/api/workspaces/workspace-id/git/commits/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refs":[
                                  {"source":"WORKSPACE","branch":"feature"},
                                  {"source":"UPSTREAM","branch":"main"}
                                ]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("QUEUED"));

        verify(commits).requestRefresh("workspace-id", refs, MANUAL);
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
