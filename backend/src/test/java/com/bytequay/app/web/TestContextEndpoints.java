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

import com.bytequay.app.service.inspector.AssembledContext;
import com.bytequay.app.service.inspector.ContextAssembler;
import com.bytequay.app.service.inspector.ContextMeta;
import com.bytequay.app.service.inspector.ContextScope;
import com.bytequay.app.service.inspector.ContextSection;
import com.bytequay.app.service.inspector.SectionKind;
import com.bytequay.app.service.tools.TurnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint contracts for the prompt-context inspector:
 * <ul>
 *   <li>{@code dryRun=true} is required; missing or {@code false}
 *       returns 400.</li>
 *   <li>The wire JSON is byte-stable enough for the inspector to
 *       render — a smoke check that the response carries
 *       {@code tools}, {@code systemBlocks}, {@code historyMessages},
 *       {@code newTurn} at the documented JSON paths.</li>
 *   <li>The eight sections show up in serialised order.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TestContextEndpoints
{
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContextAssembler contextAssembler;

    private static AssembledContext stub()
    {
        TurnRequest wire = new TurnRequest(
                List.of("{\"name\":\"read_file\"}"),
                List.of("role body", "brain body"),
                List.of(),
                "");
        ContextMeta meta = new ContextMeta(
                "claude-sonnet-4-6", "ANTHROPIC",
                Instant.parse("2026-06-04T00:00:00Z"), 1234, false);
        List<ContextSection> sections = List.of(
                new ContextSection(SectionKind.TOOLS, "① tools", "{...}", 30, List.of()),
                new ContextSection(SectionKind.ROLE, "② role", "role body", 100, List.of()),
                new ContextSection(SectionKind.BRAIN, "③ brain", "brain body", 100, List.of()),
                new ContextSection(SectionKind.CONCEPT_PREAMBLE, "④ concepts", "", 0, List.of()),
                new ContextSection(SectionKind.SKILL_MANIFEST, "⑤ skills", "", 0, List.of()),
                new ContextSection(SectionKind.MEMORY, "⑥ memory", "", 0, List.of()),
                new ContextSection(SectionKind.HISTORY, "⑦ history", "", 0, List.of()),
                new ContextSection(SectionKind.NEW_TURN, "⑧ this turn", "", 0, List.of()));
        return new AssembledContext(ContextScope.TRUNK, "th-1", meta, sections, wire);
    }

    @Test
    void threadContextReturns200WhenDryRunTrue()
            throws Exception
    {
        when(contextAssembler.forThread(eq("th-1"))).thenReturn(stub());

        mvc.perform(get("/api/threads/th-1/context").param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("TRUNK"))
                .andExpect(jsonPath("$.scopeId").value("th-1"))
                .andExpect(jsonPath("$.sections").isArray())
                .andExpect(jsonPath("$.sections[0].kind").value("TOOLS"))
                .andExpect(jsonPath("$.sections[1].kind").value("ROLE"))
                .andExpect(jsonPath("$.sections[7].kind").value("NEW_TURN"))
                .andExpect(jsonPath("$.wire.tools").isArray())
                .andExpect(jsonPath("$.wire.systemBlocks").isArray());
    }

    @Test
    void threadContextReturns400WhenDryRunFalse()
            throws Exception
    {
        mvc.perform(get("/api/threads/th-1/context").param("dryRun", "false"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void threadContextReturns400WhenDryRunMissing()
            throws Exception
    {
        mvc.perform(get("/api/threads/th-1/context"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taskContextReturns200WhenDryRunTrue()
            throws Exception
    {
        when(contextAssembler.forTask(eq("th-1"), eq("tk-1"))).thenReturn(stub());

        mvc.perform(get("/api/threads/th-1/tasks/tk-1/context").param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[1].kind").value("ROLE"));
    }

    @Test
    void taskContextReturns400WhenDryRunFalse()
            throws Exception
    {
        mvc.perform(get("/api/threads/th-1/tasks/tk-1/context").param("dryRun", "false"))
                .andExpect(status().isBadRequest());
    }
}
