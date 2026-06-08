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
package com.bytequay.app.service.inspector;

import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemConfidence;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemOrigin;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.MemoryItemSource;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.skills.RoleSkillService;
import com.bytequay.app.service.skills.SkillManifestService;
import com.bytequay.app.service.tools.TurnAssembler;
import com.bytequay.app.service.tools.TurnRequest;
import com.bytequay.app.service.workspaces.MemoryItemService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase A's load-bearing tests:
 * <ol>
 *   <li>{@link AssembledContext} ships all eight sections in
 *       serialised order.</li>
 *   <li>The {@link AssembledContext#wire()} {@link TurnRequest} is
 *       <em>byte-identical</em> to what
 *       {@link TurnAssembler#assemble} would return for the same
 *       inputs — the "view, not send" non-negotiable depends on
 *       this not drifting.</li>
 *   <li>An empty axis produces an empty section, not a missing one
 *       (callers can rely on the {@code sections[i].kind}
 *       indexing).</li>
 * </ol>
 */
class TestContextAssembler
{
    private ThreadStore threadStore;
    private TaskStore taskStore;
    private RoleSkillService roleSkills;
    private WorkspaceService workspaces;
    private SkillManifestService skillManifest;
    private MemoryItemService memoryItems;
    private TurnAssembler turnAssembler;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp()
            throws IOException
    {
        threadStore = mock(ThreadStore.class);
        taskStore = mock(TaskStore.class);
        ConceptRegistry concepts = new ConceptRegistry();
        concepts.scan();
        roleSkills = new RoleSkillService(concepts);
        workspaces = mock(WorkspaceService.class);
        skillManifest = mock(SkillManifestService.class);
        memoryItems = mock(MemoryItemService.class);
        turnAssembler = new TurnAssembler();
        assembler = new ContextAssembler(
                threadStore, taskStore, roleSkills, workspaces, skillManifest,
                memoryItems, turnAssembler);

        when(skillManifest.query(any())).thenReturn(List.of());
        when(memoryItems.renderToMarkdown(any(), anyString())).thenReturn("");
        when(threadStore.listRecentMessages(anyString(), anyInt())).thenReturn(List.of());
    }

    @Test
    void forThreadReturnsAllEightSectionsInSerialisedOrder()
    {
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));
        when(workspaces.getMemory("ws-1")).thenReturn("brain body");

        AssembledContext ctx = assembler.forThread("th-1");

        assertThat(ctx.scope()).isEqualTo(ContextScope.TRUNK);
        assertThat(ctx.scopeId()).isEqualTo("th-1");
        assertThat(ctx.sections()).extracting(ContextSection::kind).containsExactly(
                SectionKind.TOOLS,
                SectionKind.ROLE,
                SectionKind.BRAIN,
                SectionKind.CONCEPT_PREAMBLE,
                SectionKind.SKILL_MANIFEST,
                SectionKind.MEMORY,
                SectionKind.HISTORY,
                SectionKind.NEW_TURN);
    }

    @Test
    void wireIsByteIdenticalToTurnAssemblerForSameInputs()
    {
        // The "view, not send" non-negotiable: whatever bytes the
        // inspector shows must match what TurnAssembler.assemble
        // would produce. If a future refactor diverges, this test
        // fires loudly.
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));
        when(workspaces.getMemory("ws-1")).thenReturn("brain body");

        AssembledContext ctx = assembler.forThread("th-1");
        TurnRequest expected = turnAssembler.assemble(
                List.of(),
                TurnAssembler.ProviderShape.ANTHROPIC,
                roleSkills.trunkTemplate(),
                "brain body",
                roleSkills.buildConceptPreamble(),
                "",
                "",
                List.of(),
                "");

        assertThat(ctx.wire()).isEqualTo(expected);
    }

    @Test
    void forTaskUsesTheFrozenRoleSkillBody()
    {
        Thread th = thread("th-1", "ws-1");
        Task task = task("tk-1", "th-1", "// frozen role for tk-1\n");
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(th));
        when(taskStore.findTaskById("tk-1")).thenReturn(Optional.of(task));
        when(workspaces.getMemory("ws-1")).thenReturn("brain");

        AssembledContext ctx = assembler.forTask("th-1", "tk-1");

        assertThat(ctx.scope()).isEqualTo(ContextScope.TASK);
        assertThat(ctx.scopeId()).isEqualTo("tk-1");
        ContextSection role = ctx.sections().get(1);
        assertThat(role.kind()).isEqualTo(SectionKind.ROLE);
        assertThat(role.body()).isEqualTo("// frozen role for tk-1\n");
    }

    @Test
    void emptyAxesProduceEmptyBodiesNotMissingSections()
    {
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", null)));

        AssembledContext ctx = assembler.forThread("th-1");

        assertThat(ctx.sections()).hasSize(8);
        assertThat(ctx.sections().get(2).kind()).isEqualTo(SectionKind.BRAIN);
        assertThat(ctx.sections().get(2).body()).isEmpty();
        assertThat(ctx.sections().get(5).kind()).isEqualTo(SectionKind.MEMORY);
        assertThat(ctx.sections().get(5).body()).isEmpty();
    }

    @Test
    void forThreadThrows404WhenThreadIsMissing()
    {
        when(threadStore.findThreadById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assembler.forThread("nope"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no thread with id nope");
    }

    @Test
    void forTaskThrows404WhenTaskBelongsToAnotherThread()
    {
        Task taskOnOther = task("tk-1", "th-other", "");
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));
        when(taskStore.findTaskById("tk-1")).thenReturn(Optional.of(taskOnOther));

        assertThatThrownBy(() -> assembler.forTask("th-1", "tk-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("is not on thread th-1");
    }

    @Test
    void historySectionConcatenatesContentJsonFromStoredMessages()
    {
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));
        when(threadStore.listRecentMessages(eq("th-1"), anyInt()))
                .thenReturn(List.of(
                        message("m-1", "th-1", "{\"role\":\"user\",\"text\":\"hello\"}"),
                        message("m-2", "th-1", "{\"role\":\"assistant\",\"text\":\"hi\"}")));

        AssembledContext ctx = assembler.forThread("th-1");

        ContextSection history = ctx.sections().get(6);
        assertThat(history.kind()).isEqualTo(SectionKind.HISTORY);
        assertThat(history.body()).contains("hello").contains("hi");
    }

    @Test
    void brainSectionCarriesOneProvenanceEntry()
    {
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));
        when(workspaces.getMemory("ws-1")).thenReturn("workspace memory body");

        AssembledContext ctx = assembler.forThread("th-1");

        ContextSection brain = ctx.sections().get(2);
        assertThat(brain.kind()).isEqualTo(SectionKind.BRAIN);
        assertThat(brain.sources()).hasSize(1);
        assertThat(brain.sources().get(0).kind()).isEqualTo("brain");
        assertThat(brain.sources().get(0).href()).isEqualTo("/settings/workspace-memory");
    }

    @Test
    void conceptPreambleSectionTagsEveryConcept()
    {
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));

        AssembledContext ctx = assembler.forThread("th-1");

        ContextSection preamble = ctx.sections().get(3);
        assertThat(preamble.kind()).isEqualTo(SectionKind.CONCEPT_PREAMBLE);
        assertThat(preamble.sources())
                .extracting(p -> p.kind()).containsOnly("concept");
        assertThat(preamble.sources())
                .extracting(p -> p.label())
                .contains("task", "thread", "trunk", "pr", "ship", "next", "awaiting_review");
        assertThat(preamble.sources().get(0).href()).startsWith("/settings/concepts#");
    }

    @Test
    void memorySectionTagsEveryAppliedItem()
    {
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));
        MemoryItem item = new MemoryItem(
                42L,
                MemoryItemScopeKind.WORKSPACE,
                "ws-1",
                MemoryItemKind.DECISION,
                "Pin Spring Boot 3.5.",
                List.of(MemoryItemSource.thread("t-1")),
                MemoryItemConfidence.HIGH,
                List.of(),
                null, null,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"),
                MemoryItemOrigin.DISTILL);
        when(memoryItems.listLive(any(), eq("ws-1"))).thenReturn(List.of(item));
        when(memoryItems.renderToMarkdown(any(), eq("ws-1")))
                .thenReturn("## Decisions\n\n- Pin Spring Boot 3.5.\n");

        AssembledContext ctx = assembler.forThread("th-1");

        ContextSection memory = ctx.sections().get(5);
        assertThat(memory.kind()).isEqualTo(SectionKind.MEMORY);
        assertThat(memory.sources()).hasSize(1);
        assertThat(memory.sources().get(0).kind()).isEqualTo("memory_item");
        assertThat(memory.sources().get(0).href()).isEqualTo("/settings/workspace-memory#item-42");
    }

    @Test
    void historySectionTagsEveryMessage()
    {
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));
        when(threadStore.listRecentMessages(eq("th-1"), anyInt()))
                .thenReturn(List.of(
                        message("m-1", "th-1", "{\"text\":\"hi\"}"),
                        message("m-2", "th-1", "{\"text\":\"hello\"}")));

        AssembledContext ctx = assembler.forThread("th-1");

        ContextSection history = ctx.sections().get(6);
        assertThat(history.sources()).hasSize(2);
        assertThat(history.sources()).allSatisfy(p -> {
            assertThat(p.kind()).isEqualTo("history");
            assertThat(p.label()).contains("seq");
        });
    }

    @Test
    void toolsSectionTagsEveryToolByName()
    {
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));

        AssembledContext ctx = assembler.forThread("th-1");

        ContextSection tools = ctx.sections().get(0);
        assertThat(tools.kind()).isEqualTo(SectionKind.TOOLS);
        // Three built-in skill tools always present for ANTHROPIC.
        assertThat(tools.sources()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(tools.sources()).extracting(p -> p.label())
                .contains("list_skills", "list_tools", "load_skill");
    }

    @Test
    void metaCarriesProviderShapeAndPositiveTotalTokens()
    {
        when(threadStore.findThreadById("th-1")).thenReturn(Optional.of(thread("th-1", "ws-1")));
        when(workspaces.getMemory("ws-1")).thenReturn("some workspace memory body");

        AssembledContext ctx = assembler.forThread("th-1");

        assertThat(ctx.meta().providerShape()).isEqualTo("ANTHROPIC");
        assertThat(ctx.meta().model()).isNotBlank();
        assertThat(ctx.meta().totalTokens()).isGreaterThan(0);
    }

    private static Thread thread(String id, String workspaceId)
    {
        return new Thread(
                id, ThreadKind.CLI_AGENT, "anthropic", "session-1",
                "Title", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L,
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"),
                null, null, ThreadFlow.BUILD,
                workspaceId, null, null);
    }

    private static Task task(String id, String threadId, String roleSkill)
    {
        return new Task(
                id, threadId, 1, TaskStatus.RUNNING,
                "dev/" + id, "/tmp/wt", "main", "/tmp/repo",
                null, null,
                null, null, null,
                "ai_review", null, null,
                0L, 0L, 0L, null,
                Instant.parse("2026-06-01T00:00:00Z"), null, null,
                "name", roleSkill, null);
    }

    private static ThreadMessage message(String id, String threadId, String contentJson)
    {
        return new ThreadMessage(
                id, threadId, null, 1L,
                "user", "text", contentJson,
                null, null, null, null,
                Instant.parse("2026-06-01T00:00:00Z"));
    }
}
