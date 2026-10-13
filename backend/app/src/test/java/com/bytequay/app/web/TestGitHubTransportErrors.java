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

import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.web.agent.AgentController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestGitHubTransportErrors
{
    @Test
    void taskPullRequestSyncDoesNotClaimAnActionMayHaveCompleted()
            throws Exception
    {
        AgentController controller = mock(AgentController.class);
        when(controller.refreshTaskPullRequests("task", "workspace")).thenThrow(new ResourceAccessException("Operation timed out"));

        standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(post("/agent/tasks/task/pull-requests/sync").queryParam("workspaceId", "workspace"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("GitHub did not respond. Try refreshing again."));
    }

    @Test
    void pullRequestReadsAndRefreshesDoNotClaimAnActionMayHaveCompleted()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        when(pullRequests.openPullRequestDetail("owner/repo", 7)).thenThrow(new ResourceAccessException("Operation timed out"));
        when(refresh.refreshDetail("owner/repo", 7)).thenThrow(new ResourceAccessException("Operation timed out"));
        when(refresh.refreshAllDetail("owner/repo", 7)).thenThrow(new ResourceAccessException("Operation timed out"));
        var mvc = standaloneSetup(new PullRequestController(pullRequests, refresh))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/prs/detail").queryParam("repo", "owner/repo").queryParam("number", "7"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("GitHub did not respond. Try refreshing again."));
        mvc.perform(post("/prs/detail/refresh").queryParam("repo", "owner/repo").queryParam("number", "7"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("GitHub did not respond. Try refreshing again."));
        mvc.perform(post("/prs/detail/refresh-all").queryParam("repo", "owner/repo").queryParam("number", "7"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("GitHub did not respond. Try refreshing again."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void unrecognizedCommandsKeepWriteWarningEvenWhenAFollowUpReadTimesOut(String method)
    {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/new-command/refresh");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/new-command/refresh");

        var response = new GlobalExceptionHandler().handleResourceAccessException(
                new ResourceAccessException("I/O error on GET request for https://api.github.com/repos/owner/repo/pulls/7: Operation timed out"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("GitHub did not respond. Refresh before retrying; a submitted action may already have completed.");
    }
}
