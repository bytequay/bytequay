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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewMcpService
{
    private final ObjectMapper mapper = new ObjectMapper();

    private ReviewStore reviewStore;
    private ReviewDiffCache diffCache;
    private ReviewMcpService service;

    @BeforeEach
    void setUp()
    {
        reviewStore = mock(ReviewStore.class);
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        diffCache = mock(ReviewDiffCache.class);
        SeatToolset toolset = new SeatToolset(
                reviewStore, diffCache, pullRequests, mock(PatResolver.class), mapper);
        service = new ReviewMcpService(toolset, reviewStore, new McpResponses(mapper));

        when(reviewStore.findPassById("pass-1")).thenReturn(Optional.of(pass()));
        when(reviewStore.findParticipantById("seat-1")).thenReturn(Optional.of(seat()));
    }

    @Test
    void initializeAnnouncesTheReviewServer()
    {
        JsonNode response = service.handle("pass-1", "seat-1", rpc("initialize", null));
        assertThat(response.toString()).contains("bytequay-review").contains("2024-11-05");
    }

    @Test
    void toolsListExposesTheFourReviewTools()
    {
        JsonNode response = service.handle("pass-1", "seat-1", rpc("tools/list", null));
        assertThat(response.toString())
                .contains("get_pr_diff").contains("get_file_content")
                .contains("search_code").contains("report_finding");
    }

    @Test
    void toolsCallGetPrDiffReturnsTheCachedDiff()
    {
        when(diffCache.diffFor(any())).thenReturn("diff --git a/A b/A\n+changed line");

        JsonNode response = service.handle("pass-1", "seat-1",
                rpc("tools/call", "{\"name\":\"get_pr_diff\",\"arguments\":{}}"));

        assertThat(response.toString()).contains("changed line");
    }

    @Test
    void toolsCallReportFindingWritesAFindingWithTheSeatsLabel()
    {
        JsonNode response = service.handle("pass-1", "seat-1", rpc("tools/call",
                "{\"name\":\"report_finding\",\"arguments\":"
                        + "{\"path\":\"src/A.java\",\"line\":7,\"severity\":\"major\","
                        + "\"summary\":\"Leaky resource.\"}}"));

        ArgumentCaptor<ReviewFinding> captor = ArgumentCaptor.forClass(ReviewFinding.class);
        verify(reviewStore).saveFinding(captor.capture());
        ReviewFinding finding = captor.getValue();
        assertThat(finding.reviewPassId()).isEqualTo("pass-1");
        assertThat(finding.path()).isEqualTo("src/A.java");
        assertThat(finding.body()).startsWith("[Claude (Anthropic)] ");
        assertThat(response.toString()).contains("finding_id");
    }

    @Test
    void unknownPassIsRejected()
    {
        JsonNode response = service.handle("nope", "seat-1",
                rpc("tools/call", "{\"name\":\"get_pr_diff\",\"arguments\":{}}"));
        assertThat(response.toString()).contains("unknown review pass");
    }

    private JsonNode rpc(String method, String paramsJson)
    {
        try {
            String params = paramsJson == null ? "" : ",\"params\":" + paramsJson;
            return mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"" + params + "}");
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ReviewPass pass()
    {
        return new ReviewPass(
                "pass-1", "thread-1", "acme/widget", 42, "abc",
                ReviewPhase.INDEPENDENT, 0, 3, 500L, 0L, null,
                Instant.ofEpochMilli(0), null);
    }

    private static ReviewParticipant seat()
    {
        return new ReviewParticipant(
                "seat-1", "pass-1", ReviewParticipantKind.REVIEWER, "claude-cli",
                "Claude (Anthropic)", "claude", "#fff", Instant.ofEpochMilli(0), 0L, 0L);
    }
}
