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

import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.service.backlog.BacklogServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestBacklogToolHandlers
{
    private final BacklogServiceImpl backlog = mock(BacklogServiceImpl.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final BacklogToolHandlers handlers = new BacklogToolHandlers(backlog, mapper);

    @Test
    void proposeCreatesTheItemsAndReturnsLabeledIds()
            throws Exception
    {
        when(backlog.createBatch(eq("t1"), any()))
                .thenReturn(new BacklogServiceImpl.BatchResult(List.of("a", "b"), "grp-1"));
        JsonNode items = mapper.readTree(
                "[{\"title\":\"A\",\"body\":\"x\",\"tags\":[\"ui\"]},{\"title\":\"B\"}]");

        ToolOutcome.Completed result = (ToolOutcome.Completed) handlers.proposeBacklogItems(
                new BacklogToolHandlers.ProposeBacklogItemsArgs(items, "found 2"),
                new ToolCall(ThreadScope.TRUNK, "t1", null, AgentRole.TRUNK));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("grp-1").contains("backlogItemIds");
        JsonNode payload = mapper.readTree(result.text());
        assertThat(payload.path("backlogItems").get(0).path("id").asText()).isEqualTo("a");
        assertThat(payload.path("backlogItems").get(0).path("title").asText()).isEqualTo("A");
        assertThat(payload.path("backlogItems").get(1).path("id").asText()).isEqualTo("b");
        assertThat(payload.path("backlogItems").get(1).path("title").asText()).isEqualTo("B");
        verify(backlog).createBatch(eq("t1"), argThat(
                (List<BacklogServiceImpl.NewBacklogItem> forwarded) ->
                        forwarded.size() == 2 && "A".equals(forwarded.get(0).title())));
    }

    @Test
    void proposeRejectsANonArrayOrEmptyItems()
            throws Exception
    {
        ToolOutcome.Completed empty = (ToolOutcome.Completed) handlers.proposeBacklogItems(
                new BacklogToolHandlers.ProposeBacklogItemsArgs(mapper.readTree("[]"), null),
                new ToolCall(ThreadScope.TRUNK, "t1", null, AgentRole.TRUNK));
        assertThat(empty.isError()).isTrue();
        verify(backlog, never()).createBatch(any(), any());
    }

    @Test
    void proposeRejectsAnItemMissingATitle()
            throws Exception
    {
        ToolOutcome.Completed result = (ToolOutcome.Completed) handlers.proposeBacklogItems(
                new BacklogToolHandlers.ProposeBacklogItemsArgs(
                        mapper.readTree("[{\"body\":\"no title here\"}]"), null),
                new ToolCall(ThreadScope.TRUNK, "t1", null, AgentRole.TRUNK));
        assertThat(result.isError()).isTrue();
        verify(backlog, never()).createBatch(any(), any());
    }
}
