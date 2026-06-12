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
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.credentials.PatResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The reviewer-seat tool surface: read-only catalog, structured
 * refusal of everything else, and finding attribution.
 */
class TestSeatToolset
{
    private final ObjectMapper mapper = new ObjectMapper();

    private ReviewStore reviewStore;
    private ReviewDiffCache diffCache;
    private PullRequestRepository pullRequests;
    private SeatToolset toolset;
    private ToolExecutor executor;

    @BeforeEach
    void setUp()
    {
        reviewStore = mock(ReviewStore.class);
        pullRequests = mock(PullRequestRepository.class);
        diffCache = mock(ReviewDiffCache.class);
        toolset = new SeatToolset(
                reviewStore, diffCache, pullRequests, mock(PatResolver.class), mapper);
        executor = toolset.executorFor(pass(), "seat-1", "Claude");
    }

    @Test
    void writeAndOrchestrationToolsAreRefusedWithStructuredError()
            throws Exception
    {
        for (String forbidden : new String[] {
                "push", "merge_pr", "open_pr", "post_comment", "run_shell",
                "set_agenda", "dispatch_to_reviewer", "mark_phase_done",
                "mark_consensus", "record_dissent", "write_file"}) {
            ToolExecutor.ToolCallResult result = executor.execute(call(forbidden, "{}"));
            assertTrue(result.isError(), forbidden + " must be refused");
            var parsed = mapper.readTree(result.text());
            assertEquals("tool_not_available_to_reviewer", parsed.path("error").asText(),
                    "refusal for " + forbidden + " must be the structured 422 envelope");
            assertEquals(422, parsed.path("status").asInt());
            assertEquals(forbidden, parsed.path("tool").asText());
        }
        verifyNoInteractions(reviewStore);
        verifyNoInteractions(pullRequests);
    }

    @Test
    void reportFindingPersistsWithReporterAttribution()
            throws Exception
    {
        ToolExecutor.ToolCallResult result = executor.execute(call("report_finding",
                "{\"path\":\"src/A.java\",\"line\":42,\"severity\":\"blocker\","
                        + "\"summary\":\"Off-by-one in the loop bound.\"}"));

        assertTrue(!result.isError(), "report_finding should succeed: " + result.text());
        ArgumentCaptor<ReviewFinding> captor = ArgumentCaptor.forClass(ReviewFinding.class);
        verify(reviewStore).saveFinding(captor.capture());
        ReviewFinding f = captor.getValue();
        assertEquals("pass-1", f.reviewPassId());
        assertEquals("src/A.java", f.path());
        assertEquals(42, f.line());
        assertEquals(ReviewFindingSeverity.BLOCKER, f.severity());
        assertEquals(ReviewFindingStatus.REPORTED, f.status());
        assertTrue(f.body().startsWith("[Claude] "),
                "finding body must carry the reporter label, got: " + f.body());
        assertEquals(f.id(), mapper.readTree(result.text()).path("finding_id").asText());
    }

    @Test
    void getPrDiffSlicesPerFile()
    {
        when(diffCache.diffFor(any()))
                .thenReturn("""
                        diff --git a/src/A.java b/src/A.java
                        +a change in A
                        diff --git a/src/B.java b/src/B.java
                        +a change in B
                        """);
        ToolExecutor.ToolCallResult whole = executor.execute(call("get_pr_diff", "{}"));
        assertTrue(whole.text().contains("a change in A"));
        assertTrue(whole.text().contains("a change in B"));

        ToolExecutor.ToolCallResult sliced = executor.execute(
                call("get_pr_diff", "{\"path\":\"src/B.java\"}"));
        assertTrue(sliced.text().contains("a change in B"));
        assertTrue(!sliced.text().contains("a change in A"));
    }

    @Test
    void searchCodeAnchorsMatchesToFiles()
    {
        when(diffCache.diffFor(any()))
                .thenReturn("""
                        diff --git a/src/A.java b/src/A.java
                        +int retryCount = 3;
                        diff --git a/src/B.java b/src/B.java
                        +// no retries here
                        """);
        ToolExecutor.ToolCallResult result = executor.execute(
                call("search_code", "{\"query\":\"retryCount\"}"));
        assertTrue(result.text().contains("src/A.java: +int retryCount = 3;"));
        assertTrue(!result.text().contains("src/B.java"));
    }

    private ToolCall call(String name, String argsJson)
    {
        try {
            return new ToolCall("call-1", name, argsJson, mapper.readTree(argsJson));
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
}
