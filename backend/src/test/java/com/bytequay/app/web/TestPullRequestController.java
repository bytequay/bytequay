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

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.service.pr.MyActivityService;
import com.bytequay.app.service.pr.PrAnalyticsService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.pr.filters.PullRequestFilters;
import com.bytequay.app.service.threads.PrTaskLinkService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static com.bytequay.app.domain.PullRequest.Origin.AUTHORED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PullRequestController.class)
class TestPullRequestController
{
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PullRequestService pullRequestService;

    @MockitoBean
    private PrAnalyticsService prAnalyticsService;

    @MockitoBean
    private MyActivityService myActivityService;

    @MockitoBean
    private PullRequestFilters prFilters;

    @MockitoBean
    private PrTaskLinkService prTaskLink;

    @Test
    void testListReturns200()
            throws Exception
    {
        when(pullRequestService.listPullRequests()).thenReturn(ImmutableList.of(
                new PullRequest(1L, "owner/repo", 42, "Fix bug", "alice",
                        "https://github.com/owner/repo/pull/42",
                        Instant.parse("2024-05-25T00:00:00Z"),
                        Instant.parse("2024-06-01T00:00:00Z"), AUTHORED, ImmutableList.of(), null, false, null, null, null, ImmutableList.of(),
                        null, 0, 0, 0, null,
                        "open", null, null, null, null, null, null,
                        null, null, "feature/fix-bug")));

        mvc.perform(get("/prs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(42))
                .andExpect(jsonPath("$[0].title").value("Fix bug"));
    }

    @Test
    void testDetailReturns200()
            throws Exception
    {
        // PAT is resolved internally by PullRequestService via PatResolver.
        when(pullRequestService.getPullRequestDetail(eq("owner/repo"), eq(7))).thenReturn(stubDetail());

        mvc.perform(get("/prs/detail")
                        .param("repo", "owner/repo")
                        .param("number", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(7));
    }

    @Test
    void testFilterEndpointDelegatesToPullRequestFilters()
            throws Exception
    {
        PullRequest urgent = new PullRequest(1L, "owner/repo", 7, "Urgent fix", "alice",
                "https://github.com/owner/repo/pull/7",
                Instant.parse("2024-05-25T00:00:00Z"),
                Instant.parse("2024-06-01T00:00:00Z"), AUTHORED, ImmutableList.of(), null, false,
                null, null, null, ImmutableList.of(), null, 0, 0, 0, null,
                "open", null, null, null, null, null, null,
                null, null, "feature/urgent");
        when(pullRequestService.listPullRequests()).thenReturn(ImmutableList.of(urgent));
        when(prFilters.apply(eq("urgent"), any(), any())).thenReturn(ImmutableList.of(urgent));

        mvc.perform(get("/prs/filter/urgent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(7));
    }

    @Test
    void testFilterEndpointReturns400ForUnknownName()
            throws Exception
    {
        when(pullRequestService.listPullRequests()).thenReturn(ImmutableList.of());
        when(prFilters.apply(eq("not-a-filter"), any(), any()))
                .thenThrow(new IllegalArgumentException("unknown PR filter"));

        mvc.perform(get("/prs/filter/not-a-filter"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDetailWithoutStoredPatReturns401()
            throws Exception
    {
        when(pullRequestService.getPullRequestDetail(any(), eq(7))).thenThrow(new ResponseStatusException(
                HttpStatusCode.valueOf(401), "GitHub PAT not configured"));

        mvc.perform(get("/prs/detail").param("repo", "owner/repo").param("number", "7"))
                .andExpect(status().isUnauthorized());
    }

    private static PullRequestDetail stubDetail()
    {
        return new PullRequestDetail("owner/repo", 7, null, ImmutableList.of(), false,
                null, null, 10, 2, 3, 1, 0, 0, ImmutableList.of(),
                PullRequestDetail.CiStatus.PASSING, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(), false,
                null, null, null, null, null, "open", false);
    }
}
