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
package com.bytequay.app.service.concepts;

import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.PermissionResolver;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.ToolCall;
import com.bytequay.app.service.tools.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit coverage for the meta-tools: a real
 * {@link ConceptRegistry} (scanned against the Phase A seeds), a
 * mock {@link AgentToolRegistry}, and a stub
 * {@link PermissionResolver} that hands back fixed grants. Exercises
 * the manifest, the kind filter, query matching, the alternates
 * surface in {@code lookup_term}, and the by-grant filtering.
 */
class TestConceptToolHandlers
{
    private ConceptRegistry registry;
    private AgentToolRegistry toolRegistry;
    private ConceptToolHandlers handlers;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PermissionResolver permissions = new PermissionResolver()
    {
        @Override
        public AgentRole roleFor(String threadId)
        {
            return AgentRole.TRUNK;
        }

        @Override
        public Set<SecurityType> grants(String threadId)
        {
            return EnumSet.allOf(SecurityType.class);
        }

        @Override
        public RunningScope runningScope(String threadId)
        {
            return RunningScope.NONE;
        }
    };

    @BeforeEach
    void boot()
            throws IOException
    {
        registry = new ConceptRegistry();
        registry.scan();
        // ConceptToolHandlers calls tools.byName(...) when filtering
        // by grant — an empty registry is fine because the seed
        // concepts' relatedTools point at names that aren't in this
        // mock, and the handler treats unknown tools as a pass-
        // through (concepts stay visible). Tests that need stricter
        // gating wire a mocked registry directly.
        toolRegistry = Mockito.mock(AgentToolRegistry.class);
        Mockito.when(toolRegistry.byName(Mockito.anyString())).thenReturn(Optional.empty());
        handlers = new ConceptToolHandlers(registry, toolRegistry, permissions, mapper);
    }

    private static ToolCall call()
    {
        return new ToolCall("thread-1", mapper().createObjectNode(), AgentRole.TRUNK);
    }

    private static ObjectMapper mapper()
    {
        return new ObjectMapper();
    }

    @Test
    void listTermsReturnsAllSeedsWhenUnfiltered()
            throws Exception
    {
        ToolOutcome outcome = handlers.listTerms(new ConceptToolHandlers.ListTermsArgs(null, null), call());
        JsonNode parsed = parseSuccess(outcome);
        assertThat(parsed.isArray()).isTrue();
        // Phase A seeded 9 concepts; Phase B added 5 FILTER concepts
        // (urgent + the four siblings). Refresh this number when the
        // seed list changes.
        assertThat(parsed.size())
                .as("every APP-scoped seed must be visible to a fully-granted role")
                .isEqualTo(14);
    }

    @Test
    void listTermsFiltersByKind()
            throws Exception
    {
        ToolOutcome outcome = handlers.listTerms(new ConceptToolHandlers.ListTermsArgs(null, "STATE"), call());
        JsonNode parsed = parseSuccess(outcome);
        // Phase A seeded two STATE concepts.
        assertThat(parsed.size()).isEqualTo(2);
        assertThat(parsed.get(0).path("kind").asText()).isEqualTo("STATE");
    }

    @Test
    void listTermsRejectsUnknownKind()
    {
        ToolOutcome outcome = handlers.listTerms(new ConceptToolHandlers.ListTermsArgs(null, "WIDGET"), call());
        ToolOutcome.Completed c = (ToolOutcome.Completed) outcome;
        assertThat(c.isError()).isTrue();
        assertThat(c.text()).contains("unknown kind");
    }

    @Test
    void listTermsFiltersByQuery()
            throws Exception
    {
        ToolOutcome outcome = handlers.listTerms(
                new ConceptToolHandlers.ListTermsArgs("ship", null), call());
        JsonNode parsed = parseSuccess(outcome);
        assertThat(parsed.size()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (JsonNode brief : parsed) {
            if ("ship".equals(brief.path("name").asText())) {
                found = true;
            }
        }
        assertThat(found).as("ship concept should match its own query").isTrue();
    }

    @Test
    void lookupTermReturnsFullDetail()
            throws Exception
    {
        ToolOutcome outcome = handlers.lookupTerm(new ConceptToolHandlers.LookupTermArgs("task"), call());
        JsonNode parsed = parseSuccess(outcome);
        assertThat(parsed.path("name").asText()).isEqualTo("task");
        assertThat(parsed.path("kind").asText()).isEqualTo("NOUN");
        assertThat(parsed.path("scope").asText()).isEqualTo("APP");
        assertThat(parsed.path("relatedTools").isArray()).isTrue();
        assertThat(parsed.path("alternates").isArray()).isTrue();
    }

    @Test
    void lookupTermReturnsAlternatesWhenRuntimeShadowsApp()
            throws Exception
    {
        ConceptSpec userTask = new ConceptSpec(
                "task",
                List.of(),
                ConceptKind.NOUN,
                "user override for this workspace",
                List.of(),
                List.of(),
                List.of(),
                ConceptScope.USER,
                "test://user/task");
        registry.registerRuntime(userTask);

        ToolOutcome outcome = handlers.lookupTerm(new ConceptToolHandlers.LookupTermArgs("task"), call());
        JsonNode parsed = parseSuccess(outcome);
        assertThat(parsed.path("scope").asText()).isEqualTo("USER");
        assertThat(parsed.path("alternates").size())
                .as("APP seed should survive as an alternate after the USER override")
                .isEqualTo(1);
        assertThat(parsed.path("alternates").get(0).path("name").asText()).isEqualTo("task");
    }

    @Test
    void lookupTermRejectsBlankName()
    {
        ToolOutcome outcome = handlers.lookupTerm(new ConceptToolHandlers.LookupTermArgs(""), call());
        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
    }

    @Test
    void lookupTermErrorsForUnknownName()
    {
        ToolOutcome outcome = handlers.lookupTerm(
                new ConceptToolHandlers.LookupTermArgs("not-a-thing"), call());
        ToolOutcome.Completed c = (ToolOutcome.Completed) outcome;
        assertThat(c.isError()).isTrue();
        assertThat(c.text()).contains("no concept named");
    }

    private static JsonNode parseSuccess(ToolOutcome outcome)
            throws Exception
    {
        ToolOutcome.Completed c = (ToolOutcome.Completed) outcome;
        assertThat(c.isError()).isFalse();
        return new ObjectMapper().readTree(c.text());
    }
}
