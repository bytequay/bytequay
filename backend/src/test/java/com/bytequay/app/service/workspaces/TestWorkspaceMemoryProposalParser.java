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

import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.repository.MemoryItemStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests over the section/heading → kind mapping and
 * the {@code [thread:id]} back-link extraction. The parser is the
 * load-bearing hand-off between the blob distiller and the typed
 * memory_item rows.
 */
class TestWorkspaceMemoryProposalParser
{
    private final WorkspaceMemoryProposalParser parser = new WorkspaceMemoryProposalParser();

    @Test
    void emptyBodyReturnsEmpty()
    {
        assertThat(parser.parse("ws-1", null)).isEmpty();
        assertThat(parser.parse("ws-1", "")).isEmpty();
        assertThat(parser.parse("ws-1", "   \n   ")).isEmpty();
    }

    @Test
    void decisionsSectionEmitsDecisionItems()
    {
        String body = """
                ## Decisions

                - Pin Spring Boot 3.5 across the backend. [thread:t-7]
                - Use SQLite as the local store. [thread:t-9]
                """;

        List<MemoryItemStore.NewItem> items = parser.parse("ws-1", body);

        assertThat(items).hasSize(2);
        assertThat(items).allMatch(i -> i.kind() == MemoryItemKind.DECISION);
        assertThat(items.get(0).text()).isEqualTo("Pin Spring Boot 3.5 across the backend.");
        assertThat(items.get(0).sources())
                .extracting(s -> s.threadId())
                .containsExactly("t-7");
    }

    @Test
    void multipleSectionsEachProduceTheirKind()
    {
        String body = """
                ## Decisions
                - Pick Java 25. [thread:t-1]

                ## Blockers
                - Waiting on AWS key rotation. [thread:t-2]

                ## Conventions
                - Tests use AssertJ. [thread:t-3]

                ## Open questions
                - Should we adopt SSE for live updates? [thread:t-4]
                """;

        List<MemoryItemStore.NewItem> items = parser.parse("ws-1", body);

        assertThat(items).extracting(MemoryItemStore.NewItem::kind)
                .containsExactly(
                        MemoryItemKind.DECISION,
                        MemoryItemKind.BLOCKER,
                        MemoryItemKind.CONVENTION,
                        MemoryItemKind.OPEN_QUESTION);
    }

    @Test
    void bulletWithNoBacklinkIsDropped()
    {
        // Phase E: provenance is non-negotiable. A bullet without
        // an extractable back-link can't be cited or jumped to, so
        // it must not enter the registry. A real back-link in a
        // second bullet survives, proving the section keeps parsing
        // after a drop.
        String body = """
                ## Decisions
                - Unattributed claim — should be dropped.
                - Real attributed decision. [thread:t-9]
                """;

        List<MemoryItemStore.NewItem> items = parser.parse("ws-bytequay", body);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).text()).isEqualTo("Real attributed decision.");
        assertThat(items.get(0).sources())
                .extracting(s -> s.threadId())
                .containsExactly("t-9");
    }

    @Test
    void backlinkIsStrippedFromTheText()
    {
        String body = """
                ## Decisions
                - Use sqlite. [thread:t-9]
                """;

        List<MemoryItemStore.NewItem> items = parser.parse("ws-1", body);

        assertThat(items.get(0).text())
                .as("the back-link goes into sources, not the user-facing text")
                .doesNotContain("[thread:");
    }

    @Test
    void bulletsUnderUnknownSectionAreSkipped()
    {
        String body = """
                ## Architecture
                - Some architectural note. [thread:t-3]

                ## Decisions
                - Real decision. [thread:t-4]
                """;

        List<MemoryItemStore.NewItem> items = parser.parse("ws-1", body);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).text()).isEqualTo("Real decision.");
    }

    @Test
    void focusShiftDefaultsToHighConfidence()
    {
        String body = """
                ## Active focus
                - Rebuild the activity feed. [thread:t-1]
                """;

        List<MemoryItemStore.NewItem> items = parser.parse("ws-1", body);

        assertThat(items.get(0).kind()).isEqualTo(MemoryItemKind.FOCUS_SHIFT);
        // FOCUS_SHIFT uses HIGH; the other kinds default to MEDIUM.
        assertThat(items.get(0).confidence().name()).isEqualTo("HIGH");
    }
}
