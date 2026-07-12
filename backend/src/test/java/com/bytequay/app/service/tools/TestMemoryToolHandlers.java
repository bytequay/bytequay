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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemConfidence;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemOrigin;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.MemoryItemSource;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.MemoryItemStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercise of the two meta-tools against real
 * memory_item rows and the Flyway-migrated schema. Sanity-checks
 * the kind filter, the query filter, the lookup detail shape, and
 * the superseded → liveSuccessor lookup path.
 */
@SpringBootTest
class TestMemoryToolHandlers
{
    private static final String WORKSPACE_ID = "ws-default";

    @Autowired
    private MemoryToolHandlers handlers;

    @Autowired
    private MemoryItemStore store;

    @Autowired
    private ThreadStore threadStore;

    private ToolCall callOn(String threadId)
    {
        ensureThread(threadId);
        ObjectNode args = new ObjectMapper().createObjectNode();
        return new ToolCall(threadId, args, AgentRole.TRUNK);
    }

    private void ensureThread(String threadId)
    {
        if (threadStore.findThreadById(threadId).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        threadStore.saveThread(new Thread(
                threadId, ThreadKind.CLI_AGENT, "claude-code", null,
                "Memory tool test", ThreadStatus.IDLE, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null,
                ThreadFlow.BUILD, WORKSPACE_ID, null, null));
    }

    private MemoryItem seedDecision(String text)
    {
        return store.insert(new MemoryItemStore.NewItem(
                MemoryItemScopeKind.WORKSPACE,
                WORKSPACE_ID,
                MemoryItemKind.DECISION,
                text,
                List.of(MemoryItemSource.thread("thread-x")),
                MemoryItemConfidence.HIGH,
                List.of(),
                MemoryItemOrigin.DISTILL));
    }

    private MemoryItem seedBlocker(String text)
    {
        return store.insert(new MemoryItemStore.NewItem(
                MemoryItemScopeKind.WORKSPACE,
                WORKSPACE_ID,
                MemoryItemKind.BLOCKER,
                text,
                List.of(MemoryItemSource.thread("thread-y")),
                MemoryItemConfidence.MEDIUM,
                List.of(),
                MemoryItemOrigin.DISTILL));
    }

    @Test
    void recallReturnsMatchingKind()
            throws Exception
    {
        long marker = System.currentTimeMillis();
        seedDecision("Marker " + marker + " — pick Java 25.");
        seedBlocker("Marker " + marker + " — waiting on AWS key rotation.");

        MemoryToolHandlers.RecallMemoryArgs args = new MemoryToolHandlers.RecallMemoryArgs(
                "DECISION", "Marker " + marker, null, 10);
        ToolOutcome.Completed out = (ToolOutcome.Completed) handlers.recallMemory(args, callOn("t-1"));

        assertThat(out.isError()).isFalse();
        JsonNode parsed = new ObjectMapper().readTree(out.text());
        assertThat(parsed.isArray()).isTrue();
        // Every returned row must be a DECISION (kind filter held)
        for (JsonNode row : parsed) {
            assertThat(row.path("kind").asText()).isEqualTo("DECISION");
            assertThat(row.path("oneLineSummary").asText()).contains("Java 25");
        }
        assertThat(parsed.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void recallRejectsUnknownKind()
    {
        ToolOutcome.Completed out = (ToolOutcome.Completed) handlers.recallMemory(
                new MemoryToolHandlers.RecallMemoryArgs("WIDGET", null, null, null),
                callOn("t-1"));
        assertThat(out.isError()).isTrue();
        assertThat(out.text()).contains("unknown kind");
    }

    @Test
    void lookupReturnsFullDetail()
            throws Exception
    {
        MemoryItem seeded = seedDecision("Lookup detail probe " + System.nanoTime());

        ToolOutcome.Completed out = (ToolOutcome.Completed) handlers.lookupMemory(
                new MemoryToolHandlers.LookupMemoryArgs(seeded.id()),
                callOn("t-1"));

        assertThat(out.isError()).isFalse();
        JsonNode parsed = new ObjectMapper().readTree(out.text());
        assertThat(parsed.path("id").asLong()).isEqualTo(seeded.id());
        assertThat(parsed.path("kind").asText()).isEqualTo("DECISION");
        assertThat(parsed.path("text").asText()).startsWith("Lookup detail probe");
        assertThat(parsed.path("sources").isArray()).isTrue();
        assertThat(parsed.path("sources").size()).isEqualTo(1);
    }

    @Test
    void lookupOfSupersededRowCarriesLiveSuccessor()
            throws Exception
    {
        MemoryItem older = seedDecision("Older decision " + System.nanoTime());
        MemoryItem newer = seedDecision("Newer decision " + System.nanoTime());
        store.markSuperseded(older.id(), newer.id());

        ToolOutcome.Completed out = (ToolOutcome.Completed) handlers.lookupMemory(
                new MemoryToolHandlers.LookupMemoryArgs(older.id()),
                callOn("t-1"));

        JsonNode parsed = new ObjectMapper().readTree(out.text());
        assertThat(parsed.path("supersededBy").asLong()).isEqualTo(newer.id());
        assertThat(parsed.path("liveSuccessor").path("id").asLong()).isEqualTo(newer.id());
        assertThat(parsed.path("liveSuccessor").path("oneLineSummary").asText())
                .startsWith("Newer decision");
    }

    @Test
    void lookupErrorsForUnknownId()
    {
        ToolOutcome.Completed out = (ToolOutcome.Completed) handlers.lookupMemory(
                new MemoryToolHandlers.LookupMemoryArgs(99_999_999L),
                callOn("t-1"));
        assertThat(out.isError()).isTrue();
        assertThat(out.text()).contains("no memory item");
    }
}
