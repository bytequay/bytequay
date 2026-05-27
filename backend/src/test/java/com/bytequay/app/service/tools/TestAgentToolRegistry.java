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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentToolRegistry
{
    @Test
    void schemaForRecordWithRequiredAndOptionalFields()
            throws Exception
    {
        String schema = AgentToolRegistry.generateSchema(SampleArgs.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(schema);
        assertThat(parsed.path("type").asText()).isEqualTo("object");
        JsonNode props = parsed.path("properties");
        assertThat(props.path("query").path("type").asText()).isEqualTo("string");
        assertThat(props.path("query").path("description").asText())
                .isEqualTo("Free-text filter");
        assertThat(props.path("limit").path("type").asText()).isEqualTo("integer");
        // Required is sorted + only includes flagged fields.
        assertThat(parsed.path("required")).hasSize(1);
        assertThat(parsed.path("required").get(0).asText()).isEqualTo("query");
    }

    @Test
    void schemaForEmptyRecord()
    {
        String schema = AgentToolRegistry.generateSchema(NoArgs.class);

        assertThat(schema).isEqualTo(
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}");
    }

    @Test
    void schemaForVoidArgsType()
    {
        String schema = AgentToolRegistry.generateSchema(Void.class);

        assertThat(schema).isEqualTo(
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}");
    }

    @Test
    void schemaIsByteStableAcrossCalls()
    {
        // Same input — same bytes. The model's prefix cache hashes
        // the generated schema verbatim.
        String first = AgentToolRegistry.generateSchema(SampleArgs.class);
        String second = AgentToolRegistry.generateSchema(SampleArgs.class);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void schemaHonoursWireNameOverride()
    {
        String schema = AgentToolRegistry.generateSchema(WireNameArgs.class);

        // The Java component is draftReply but the wire field must be
        // draft_reply because of the @ToolParam(wireName=…) override.
        assertThat(schema).contains("\"draft_reply\"");
        assertThat(schema).doesNotContain("\"draftReply\"");
    }

    @Test
    void schemaForJsonNodeFieldMapsToObject()
    {
        String schema = AgentToolRegistry.generateSchema(JsonFieldArgs.class);

        assertThat(schema).contains("\"input\":{\"type\":\"object\"");
    }

    @Test
    void schemaFailsLoudlyForNonRecordArgs()
    {
        assertThat(catchThrowable(() -> AgentToolRegistry.generateSchema(String.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a record");
    }

    private static Throwable catchThrowable(Runnable r)
    {
        try {
            r.run();
            return null;
        }
        catch (Throwable t) {
            return t;
        }
    }

    public record SampleArgs(
            @ToolParam(description = "Free-text filter", required = true) String query,
            @ToolParam(description = "Max threads to return") Integer limit) {}

    public record NoArgs() {}

    public record WireNameArgs(
            @ToolParam(description = "draft reply", wireName = "draft_reply") String draftReply) {}

    public record JsonFieldArgs(
            @ToolParam(description = "raw json") JsonNode input) {}
}
