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

import com.bytequay.app.domain.InvestigationReviewData.InvestigationStepRow;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.localpr.PRService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestInvestigationReviewTools
{
    @Test
    void recordFindingRequiresAnExactFileRange()
    {
        InvestigationReviewTools tools = new InvestigationReviewTools(
                mock(InvestigationReviewStore.class), mock(InvestigationReviewContext.class),
                mock(PRService.class), new ObjectMapper());

        JsonNode schema = StreamSupport.stream(
                        tools.tools(TurnSpec.Transport.OPENAI_COMPAT, false).spliterator(), false)
                .filter(tool -> "record_finding".equals(tool.path("function").path("name").asText()))
                .findFirst().orElseThrow()
                .path("function").path("parameters");

        assertThat(StreamSupport.stream(schema.path("required").spliterator(), false)
                .map(JsonNode::asText).toList())
                .contains("path", "start_line", "end_line");
    }

    @Test
    void recordStepParsesStringEncodedArguments()
    {
        InvestigationReviewStore store = mock(InvestigationReviewStore.class);
        when(store.assignmentBelongsToReview("a1", "r1")).thenReturn(true);
        when(store.assignmentRoundIsRunning("a1")).thenReturn(true);
        when(store.mutateWhileAssignmentRoundRunning(any(), any())).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return true;
        });
        InvestigationReviewTools tools = new InvestigationReviewTools(
                store, mock(InvestigationReviewContext.class), mock(PRService.class), new ObjectMapper());

        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("action_type", "readRange");
        input.put("reason", "check the range");
        // A model returned the tool arguments as a JSON string, not an object.
        input.put("arguments", "{\"path\":\"X.java\",\"start_line\":51,\"end_line\":80}");
        tools.executor("r1", "a1").execute(new ToolCall("t1", "record_step", "{}", input));

        ArgumentCaptor<InvestigationStepRow> row = ArgumentCaptor.forClass(InvestigationStepRow.class);
        verify(store).insertStep(row.capture());
        JsonNode arguments = row.getValue().argumentsJson();
        assertThat(arguments.isObject()).isTrue();
        assertThat(arguments.path("path").asText()).isEqualTo("X.java");
        assertThat(arguments.path("start_line").asInt()).isEqualTo(51);
    }
}
