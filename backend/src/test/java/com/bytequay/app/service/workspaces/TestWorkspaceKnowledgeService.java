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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.beans.workspace.BrainBlockDto;
import com.bytequay.app.beans.workspace.DistillOperationDto;
import com.bytequay.app.beans.workspace.DistillRunDto;
import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TestWorkspaceKnowledgeService
{
    @Autowired
    private WorkspaceKnowledgeService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KnowledgeItemStore knowledge;

    private final List<String> workspaceIds = new ArrayList<>();

    @AfterEach
    void cleanUp()
    {
        for (String workspaceId : workspaceIds) {
            jdbc.update("DELETE FROM memory_item WHERE scope_id = ?",
                    workspaceId);
            jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
        }
    }

    @Test
    void legacyMarkdownBecomesTypedBlocksWithoutLosingLooseProse()
    {
        String workspaceId = workspace("""
                An undocumented release trap remains important.

                ## Conventions

                - Keep controller methods small.

                ## Decisions

                - Use one repository per workspace.
                """);

        service.importLegacyMemory();

        assertThat(service.blocks(workspaceId))
                .extracting(BrainBlockDto::category, BrainBlockDto::body)
                .contains(
                        Tuple.tuple(
                                "Gotchas",
                                "An undocumented release trap remains important."),
                        Tuple.tuple(
                                "Conventions",
                                "Keep controller methods small."),
                        Tuple.tuple(
                                "Decisions",
                                "Use one repository per workspace."));
        assertThat(service.get(workspaceId).markdown())
                .contains("## Gotchas")
                .contains("An undocumented release trap remains important.");
    }

    @Test
    void unchangedMarkdownBlocksKeepTheirStableIds()
    {
        String workspaceId = workspace("");
        service.replaceMarkdown(workspaceId, """
                ## Decisions

                - Preserve this block.
                """);
        long originalId = service.blocks(workspaceId).getFirst().id();

        service.replaceMarkdown(workspaceId, service.get(workspaceId).markdown());

        assertThat(service.blocks(workspaceId))
                .extracting(BrainBlockDto::id)
                .containsExactly(originalId);
    }

    @Test
    void editedDecisionIsDerivedAppliedAndRevertedTransactionally()
    {
        String workspaceId = workspace("");
        DistillRunDto preview = service.createPreview(
                workspaceId,
                "manual",
                List.of(),
                List.of(addBrain("op-1", "Original proposal")));
        service.decide(
                workspaceId,
                preview.id(),
                List.of(decision(
                        "op-1", "Edited proposal", "accepted")));

        DistillRunDto decided = service.requireRun(
                workspaceId, preview.id());
        assertThat(decided.operations().getFirst().decision())
                .isEqualTo("edited");

        DistillRunDto applied = service.apply(
                workspaceId, preview.id());
        assertThat(applied.status()).isEqualTo("applied");
        assertThat(service.get(workspaceId).markdown())
                .contains("Edited proposal");

        DistillRunDto reverted = service.revert(
                workspaceId, preview.id());
        assertThat(reverted.status()).isEqualTo("reverted");
        assertThat(service.get(workspaceId).markdown())
                .doesNotContain("Edited proposal");
    }

    @Test
    void applyAndRevertRefuseToOverwriteInterveningEdits()
    {
        String workspaceId = workspace("");
        DistillRunDto stalePreview = service.createPreview(
                workspaceId,
                "manual",
                List.of(),
                List.of(addBrain("op-stale", "Stale proposal")));
        service.decide(
                workspaceId,
                stalePreview.id(),
                List.of(decision(
                        "op-stale", "Stale proposal", "accepted")));
        service.replaceMarkdown(workspaceId, """
                ## Gotchas

                - The user edited memory after the preview.
                """);

        assertThatThrownBy(() -> service.apply(
                workspaceId, stalePreview.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed");

        DistillRunDto appliedPreview = service.createPreview(
                workspaceId,
                "manual",
                List.of(),
                List.of(addBrain("op-applied", "Applied proposal")));
        service.decide(
                workspaceId,
                appliedPreview.id(),
                List.of(decision(
                        "op-applied", "Applied proposal", "accepted")));
        service.apply(workspaceId, appliedPreview.id());
        service.replaceMarkdown(
                workspaceId,
                service.get(workspaceId).markdown()
                        + "\n## Decisions\n\n- A later user decision.\n");

        assertThatThrownBy(() -> service.revert(
                workspaceId, appliedPreview.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("overwrite edits");
    }

    @Test
    void applyingAnAcceptedSeedRunCompletesTheSeedMilestone()
    {
        String workspaceId = workspace("");
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                INSERT INTO workspace_onboarding (
                    workspace_id, clone_complete, sync_state,
                    memory_seed_complete, updated_at_ms)
                VALUES (?, 1, 'ready', 0, ?)
                """, workspaceId, now);

        DistillRunDto preview = service.createPreview(
                workspaceId,
                "seed",
                List.of(),
                List.of(addBrain("op-1", "Seeded convention")));
        service.decide(
                workspaceId,
                preview.id(),
                List.of(decision("op-1", "Seeded convention", "accepted")));
        service.apply(workspaceId, preview.id());

        assertThat(jdbc.queryForObject("""
                SELECT memory_seed_complete FROM workspace_onboarding
                WHERE workspace_id = ?
                """, Integer.class, workspaceId))
                .as("an accepted seed run completes the milestone")
                .isEqualTo(1);
    }

    @Test
    void genericKnowledgeEditorCannotPartiallyMutateALearnedItem()
    {
        String workspaceId = workspace("");
        long now = Instant.now().toEpochMilli();
        KnowledgeItem learned = new KnowledgeItem(
                "learned-" + UUID.randomUUID(), workspaceId, "acme/widget",
                "convention", "Original", "Keep the original statement.", null,
                List.of("review"), "medium", KnowledgeItem.LIFECYCLE_PENDING,
                null, now, "pr-learning", "digest", "{}", now, now);
        knowledge.insert(learned, List.of(), List.of());

        assertThatThrownBy(() -> service.saveKnowledge(
                workspaceId, learned.id(), "Changed", "Mutated", List.of("review"), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("learned knowledge");
        assertThat(knowledge.findById(learned.id()).orElseThrow().statement())
                .isEqualTo("Keep the original statement.");
    }

    private String workspace(String memory)
    {
        String id = "ws-knowledge-" + UUID.randomUUID();
        workspaceIds.add(id);
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                INSERT INTO workspaces (
                    id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms, detached_at_ms)
                VALUES (?, ?, ?, 0, ?, ?, ?)
                """, id, id, memory, now, now, now);
        return id;
    }

    private static DistillOperationDto addBrain(
            String id, String body)
    {
        return new DistillOperationDto(
                id,
                "brain",
                "add",
                null,
                null,
                "Decisions",
                null,
                body,
                List.of(),
                "pending",
                body);
    }

    private static DistillOperationDto decision(
            String id, String body, String decision)
    {
        return new DistillOperationDto(
                id,
                "brain",
                "add",
                null,
                null,
                "Decisions",
                null,
                body,
                List.of(),
                decision,
                null);
    }
}
