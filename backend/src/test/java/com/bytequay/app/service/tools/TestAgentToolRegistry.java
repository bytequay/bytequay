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

import com.bytequay.app.service.concepts.ConceptRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentToolRegistry
{
    /** None of the records under test reference concepts via
     *  {@code enumFromConcepts}, so the empty registry exercises the
     *  schema path that doesn't touch concepts. */
    private static final ConceptRegistry NO_CONCEPTS = new ConceptRegistry();

    @Test
    void schemaForRecordWithRequiredAndOptionalFields()
            throws Exception
    {
        String schema = AgentToolRegistry.generateSchema(SampleArgs.class, NO_CONCEPTS);

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
        String schema = AgentToolRegistry.generateSchema(NoArgs.class, NO_CONCEPTS);

        assertThat(schema).isEqualTo(
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}");
    }

    @Test
    void schemaForVoidArgsType()
    {
        String schema = AgentToolRegistry.generateSchema(Void.class, NO_CONCEPTS);

        assertThat(schema).isEqualTo(
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}");
    }

    @Test
    void schemaIsByteStableAcrossCalls()
    {
        // Same input — same bytes. The model's prefix cache hashes
        // the generated schema verbatim.
        String first = AgentToolRegistry.generateSchema(SampleArgs.class, NO_CONCEPTS);
        String second = AgentToolRegistry.generateSchema(SampleArgs.class, NO_CONCEPTS);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void schemaHonoursWireNameOverride()
    {
        String schema = AgentToolRegistry.generateSchema(WireNameArgs.class, NO_CONCEPTS);

        // The Java component is draftReply but the wire field must be
        // draft_reply because of the @ToolParam(wireName=…) override.
        assertThat(schema).contains("\"draft_reply\"");
        assertThat(schema).doesNotContain("\"draftReply\"");
    }

    @Test
    void localPrCommentSchemaExposesParentCommentIdForReplies()
    {
        String schema = AgentToolRegistry.generateSchema(
                PRRecordToolHandlers.RecordPrCommentArgs.class, NO_CONCEPTS);

        assertThat(schema).contains("\"parent_comment_id\"");
        assertThat(schema).doesNotContain("\"parentCommentId\"");
    }

    @Test
    void schemaForJsonNodeFieldMapsToObject()
    {
        String schema = AgentToolRegistry.generateSchema(JsonFieldArgs.class, NO_CONCEPTS);

        assertThat(schema).contains("\"input\":{\"type\":\"object\"");
    }

    @Test
    void schemaForAListFieldPublishesItsElementType()
    {
        // This used to publish {"type":"object"} for a List, so the model was
        // told to send an object and then rejected by Jackson for sending one.
        // check_test_coverage ships that shape and is live in four catalogs.
        String schema = AgentToolRegistry.generateSchema(
                BrainToolHandlers.CheckCoverageArgs.class, NO_CONCEPTS);

        assertThat(schema)
                .contains("\"files\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}")
                .doesNotContain("\"files\":{\"type\":\"object\"");
    }

    @Test
    void schemaFailsLoudlyForNonRecordArgs()
    {
        assertThat(catchThrowable(() -> AgentToolRegistry.generateSchema(String.class, NO_CONCEPTS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a record");
    }

    @Test
    void schemaRendersEnumFromConceptsWithPerValueDescriptions()
            throws Exception
    {
        // Phase A's seeded concepts are enough: "task" and "thread"
        // are NOUN concepts but enumFromConcepts doesn't filter by
        // kind, so they round-trip just fine for this schema test.
        ConceptRegistry concepts = new ConceptRegistry();
        concepts.scan();

        String schema = AgentToolRegistry.generateSchema(EnumArgs.class, concepts);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode prop = mapper.readTree(schema).path("properties").path("kind");

        assertThat(prop.path("type").asText()).isEqualTo("string");
        // enum preserves declaration order from @ToolParam.
        assertThat(prop.path("enum")).hasSize(2);
        assertThat(prop.path("enum").get(0).asText()).isEqualTo("task");
        assertThat(prop.path("enum").get(1).asText()).isEqualTo("thread");
        // oneOf carries the per-value description from each concept.
        assertThat(prop.path("oneOf")).hasSize(2);
        assertThat(prop.path("oneOf").get(0).path("const").asText()).isEqualTo("task");
        assertThat(prop.path("oneOf").get(0).path("description").asText())
                .startsWith("One unit of work within a thread");
        assertThat(prop.path("oneOf").get(1).path("const").asText()).isEqualTo("thread");
    }

    @Test
    void schemaFailsFastWhenEnumFromConceptsReferencesUnknownName()
            throws Exception
    {
        ConceptRegistry concepts = new ConceptRegistry();
        concepts.scan();

        assertThat(catchThrowable(() ->
                AgentToolRegistry.generateSchema(UnknownEnumArgs.class, concepts)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown concept: not_a_real_concept");
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

    public record EnumArgs(
            @ToolParam(description = "what to look up",
                    enumFromConcepts = {"task", "thread"}) String kind) {}

    public record UnknownEnumArgs(
            @ToolParam(description = "broken",
                    enumFromConcepts = "not_a_real_concept") String kind) {}
}
