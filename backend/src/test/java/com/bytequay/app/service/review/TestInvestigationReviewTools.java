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

import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.localpr.PRService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
