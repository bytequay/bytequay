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
package com.bytequay.agent.cli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestContextReceiptRef
{
    private static final String SEARCH = "context-search:" + "a".repeat(64);
    private static final String READ = "context-note:" + "b".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void onlyCompletedLocalSearchAndAvailableReadResultsHaveReceiptIdentity()
    {
        String search = "{\"searchRef\":\"" + SEARCH + "\",\"hits\":[]}";
        String opened = "{\"status\":\"AVAILABLE\",\"evidenceRef\":\"" + READ + "\"}";
        assertThat(ContextReceiptRef.fromResult(content(search))).contains(SEARCH);
        assertThat(ContextReceiptRef.fromResult(content(opened))).contains(READ);
        assertThat(ContextReceiptRef.fromResult(mapper.valueToTree(java.util.Map.of("isError", true, "content", content(search).path("content")))))
                .isEmpty();
        assertThat(ContextReceiptRef.fromResult(content("{\"status\":\"VERSION_UNAVAILABLE\",\"evidenceRef\":\"" + READ + "\"}")))
                .isEmpty();
        assertThat(ContextReceiptRef.fromResult(content("{\"searchRef\":\"" + SEARCH + "\"}"))).isEmpty();
    }

    private com.fasterxml.jackson.databind.JsonNode content(String text)
    {
        return mapper.valueToTree(java.util.Map.of("content", java.util.List.of(java.util.Map.of("type", "text", "text", text))));
    }
}
