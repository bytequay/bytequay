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

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercise of the {@code recall_thread} MCP tool: seed
 * other threads with active Overall checkpoints, fire a {@code
 * tools/call} JSON-RPC request against {@link McpController}, and
 * assert the digest the agent gets back includes the matching
 * threads and excludes the current one.
 *
 * <p>Tests are read-only against the data plane — no GitHub, no
 * permission gate, no notifications — so a full {@link SpringBootTest}
 * is overkill in spirit, but it's the cheapest way to wire the real
 * SQLite store + Flyway schema the production code talks to.
 */
@SpringBootTest
class TestMcpRecallThread
{
    @Autowired
    private McpController controller;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private ThreadCheckpointStore checkpoints;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void recallThreadReturnsMatchesAndExcludesTheCallingThread()
            throws Exception
    {
        String callingThreadId = newThread("caller", "Currently running");
        String matchA = newThread("flaky-tests-thread", "Flaky test investigation");
        String matchB = newThread("github-rate-limits-thread", "GitHub rate limits");
        String unrelated = newThread("unrelated", "Onboarding doc draft");

        // Overall summaries the agent will recall against. matchA's
        // summary uses "flaky", matchB's bullets use "rate limit", and
        // the unrelated thread has nothing in common with either.
        saveOverall(matchA, "We tracked down a flaky test in the upload pipeline. "
                + "Fix was to reset the in-memory cache between turns.", List.of("upload pipeline"));
        saveOverall(matchB, "Set up an exponential back-off for GitHub queries.",
                List.of("rate limit", "back-off"));
        saveOverall(unrelated, "Drafted the onboarding doc for the cache layer.", List.of("onboarding"));
        // The caller also has an Overall — it should be filtered out so
        // the agent isn't handed back its own in-flight summary.
        saveOverall(callingThreadId, "Caller's own Overall — should not appear.",
                List.of("flaky should not match through here either"));

        JsonNode response = invokeRecall(callingThreadId, "flaky", 5);

        String text = textContentOf(response);
        assertThat(text).contains(matchA);
        assertThat(text).contains("Flaky test investigation");
        assertThat(text).doesNotContain(callingThreadId);
        assertThat(text).doesNotContain(unrelated);
    }

    @Test
    void recallThreadHonoursTheLimitArgument()
            throws Exception
    {
        String caller = newThread("limit-caller", "Limit caller");
        String a = newThread("limit-a", "Topic A");
        String b = newThread("limit-b", "Topic B");
        String c = newThread("limit-c", "Topic C");
        saveOverall(a, "kubernetes ingress", List.of());
        saveOverall(b, "kubernetes services", List.of());
        saveOverall(c, "kubernetes pods", List.of());

        JsonNode response = invokeRecall(caller, "kubernetes", 2);

        String text = textContentOf(response);
        // The capped query should return 2 of the 3 matches — pick the
        // newest two (c then b in newest-generated-first order).
        long matches = List.of(a, b, c).stream()
                .filter(text::contains)
                .count();
        assertThat(matches).isEqualTo(2);
    }

    @Test
    void recallThreadReportsWhenNothingMatches()
            throws Exception
    {
        String caller = newThread("empty-caller", "Empty caller");

        JsonNode response = invokeRecall(caller, "nonexistent-needle-string", 5);

        String text = textContentOf(response);
        assertThat(text).contains("No prior threads matched");
        assertThat(text).contains("nonexistent-needle-string");
    }

    private JsonNode invokeRecall(String threadId, String query, int limit)
            throws Exception
    {
        String rpc = """
                {
                  "jsonrpc": "2.0",
                  "id": 7,
                  "method": "tools/call",
                  "params": {
                    "name": "recall_thread",
                    "arguments": { "query": %s, "limit": %d }
                  }
                }
                """.formatted(mapper.writeValueAsString(query), limit);
        DeferredResult<JsonNode> deferred = controller.handle(threadId, mapper.readTree(rpc));
        // recall_thread is read-only and never parks the request; the
        // result lands synchronously on the calling thread.
        Object resolved = deferred.getResult();
        assertThat(resolved).isInstanceOf(JsonNode.class);
        return (JsonNode) resolved;
    }

    private static String textContentOf(JsonNode rpcResponse)
    {
        JsonNode content = rpcResponse.path("result").path("content");
        assertThat(content.isArray()).isTrue();
        StringBuilder out = new StringBuilder();
        for (JsonNode item : content) {
            out.append(item.path("text").asText()).append('\n');
        }
        return out.toString();
    }

    private String newThread(String slug, String title)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        Thread t = new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                title,
                ThreadStatus.RUNNING,
                /* workingDir */ "/tmp",
                /* branchName */ "main",
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                /* processPid */ null,
                /* logPath */ null,
                now, now, null, null,
                /* metadataJson */ "{\"slug\":\"" + slug + "\"}",
                "DEVELOP", null, null, null, ThreadFlow.BUILD);
        threads.saveThread(t);
        return t.id();
    }

    private void saveOverall(String threadId, String summary, List<String> bullets)
    {
        // The store enforces "at most one active Overall per thread" via
        // replaceOverall; using it (rather than saveSegment) makes the
        // test honest about the row shape the recall code actually sees.
        ThreadCheckpoint overall = new ThreadCheckpoint(
                UUID.randomUUID().toString(),
                threadId,
                /* seq */ 0L,
                /* isOverall */ true,
                /* firstMsgSeq */ 1L,
                /* lastMsgSeq */ 10L,
                /* tokensCovered */ 25_000L,
                summary,
                bullets,
                "claude-haiku-4-5",
                1_000L, 200L, 1L,
                Instant.now(),
                /* supersededAt */ null,
                /* taskId — Overall always thread-scoped */ null);
        checkpoints.replaceOverall(threadId, overall);
    }
}
