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

import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceCardDto;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.repository.WorkspaceStore.WorkspaceStats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorkspaceService
{
    private final WorkspaceStore store = mock(WorkspaceStore.class);
    private final WorkspaceService service = new WorkspaceService(store);

    @Test
    void summariseMemoryCountsDecisionAndBlockerBullets()
    {
        String md = """
                # ByteQuay — workspace memory

                ## Architecture
                - Electron + React frontend
                - Spring Boot backend

                ## Decisions
                - Use WebContentsView, never <webview>
                - Pin @vitejs/plugin-react to 4.x
                - Workspace memory ~2k tokens

                ## Blockers
                - Slack OAuth admin-approval friction
                """;

        WorkspaceCardDto.MemorySummary summary = WorkspaceService.summariseMemory(md);

        assertThat(summary.decisionCount()).isEqualTo(3);
        assertThat(summary.blockerCount()).isEqualTo(1);
        assertThat(summary.tokensCap()).isEqualTo(WorkspaceService.MEMORY_TOKEN_CAP);
        // Tokens-used is char-count / 4; the exact number isn't load-
        // bearing here but it must be positive for non-blank input.
        assertThat(summary.tokensUsed()).isGreaterThan(0);
    }

    @Test
    void summariseMemoryIgnoresBulletsOutsideKnownSections()
    {
        // The "Architecture" bullets must not bleed into the
        // decision/blocker counters; only the bullets directly under
        // the named H2 sections are counted.
        String md = """
                ## Architecture
                - bullet a
                - bullet b

                ## Open questions
                - bullet c
                """;

        WorkspaceCardDto.MemorySummary summary = WorkspaceService.summariseMemory(md);

        assertThat(summary.decisionCount()).isZero();
        assertThat(summary.blockerCount()).isZero();
    }

    @Test
    void summariseMemoryIsHeadingCaseInsensitive()
    {
        String md = """
                ## DECISIONS
                - one
                ## blockers
                - alpha
                - beta
                """;

        WorkspaceCardDto.MemorySummary summary = WorkspaceService.summariseMemory(md);

        assertThat(summary.decisionCount()).isEqualTo(1);
        assertThat(summary.blockerCount()).isEqualTo(2);
    }

    @Test
    void summariseMemoryHandlesEmptyInput()
    {
        WorkspaceCardDto.MemorySummary blank = WorkspaceService.summariseMemory("");
        WorkspaceCardDto.MemorySummary nulled = WorkspaceService.summariseMemory(null);

        assertThat(blank.decisionCount()).isZero();
        assertThat(blank.blockerCount()).isZero();
        assertThat(blank.tokensUsed()).isZero();
        assertThat(nulled.tokensUsed()).isZero();
    }

    @Test
    void avatarColorIsStableForSameName()
    {
        // The card avatar's gradient relies on a deterministic colour
        // per name so the UI doesn't shuffle between restarts.
        String first = WorkspaceService.avatarColor("ByteQuay");
        String second = WorkspaceService.avatarColor("ByteQuay");
        String different = WorkspaceService.avatarColor("Trino-trace");

        assertThat(first).isEqualTo(second);
        assertThat(different).isNotEqualTo(first);
    }

    @Test
    void listWithStatsReturnsCardForEachWorkspace()
    {
        Workspace one = new Workspace(
                "ws-default", "ByteQuay",
                "## Decisions\n- decision a\n\n## Blockers\n- blocker x\n",
                /* isScratch */ false,
                /* workModel */ null,
                Instant.parse("2026-05-22T08:00:00Z"),
                Instant.parse("2026-05-22T09:00:00Z"));
        Workspace scratch = new Workspace(
                "ws-scratch", "Scratch", "",
                /* isScratch */ true,
                /* workModel */ null,
                Instant.parse("2026-05-22T08:00:00Z"),
                Instant.parse("2026-05-22T09:00:00Z"));
        when(store.listWorkspaces()).thenReturn(List.of(one, scratch));
        when(store.fetchStats(eq("ws-default"), anyLong()))
                .thenReturn(new WorkspaceStats(3, 2, 1, 1840L, 1716_300_000_000L));
        when(store.listRepos("ws-default"))
                .thenReturn(List.of(
                        new WorkspaceRepo("ws-default", "chenjian2664/bytequay",
                                null, false, Instant.parse("2026-05-22T08:00:00Z")),
                        new WorkspaceRepo("ws-default", "chenjian2664/docs",
                                null, false, Instant.parse("2026-05-22T08:00:00Z"))));
        when(store.listRepos("ws-scratch")).thenReturn(List.of());

        List<WorkspaceCardDto> cards = service.listWithStats();

        assertThat(cards).hasSize(2);

        WorkspaceCardDto defaultCard = cards.get(0);
        assertThat(defaultCard.id()).isEqualTo("ws-default");
        assertThat(defaultCard.name()).isEqualTo("ByteQuay");
        assertThat(defaultCard.isScratch()).isFalse();
        assertThat(defaultCard.repos()).containsExactly("bytequay", "docs");
        assertThat(defaultCard.activeThreadCount()).isEqualTo(3);
        assertThat(defaultCard.tasksInFlight()).isEqualTo(2);
        assertThat(defaultCard.needsAttentionCount()).isEqualTo(1);
        assertThat(defaultCard.spendTodayMilliUsd()).isEqualTo(1840L);
        assertThat(defaultCard.lastActivityMs()).isEqualTo(1716_300_000_000L);
        assertThat(defaultCard.memory().decisionCount()).isEqualTo(1);
        assertThat(defaultCard.memory().blockerCount()).isEqualTo(1);
        assertThat(defaultCard.color()).isNotBlank();

        // Scratch workspaces skip the aggregate query and zero everything
        // out; the card renders the "throwaway · no durable memory" copy.
        WorkspaceCardDto scratchCard = cards.get(1);
        assertThat(scratchCard.isScratch()).isTrue();
        assertThat(scratchCard.activeThreadCount()).isZero();
        assertThat(scratchCard.tasksInFlight()).isZero();
        assertThat(scratchCard.spendTodayMilliUsd()).isZero();
        assertThat(scratchCard.needsAttentionCount()).isZero();
        assertThat(scratchCard.lastActivityMs()).isNull();
        verify(store, never()).fetchStats(eq("ws-scratch"), anyLong());
    }

    @Test
    void listWithStatsHandlesEmptyWorkspaceWithNoLastActivity()
    {
        Workspace fresh = new Workspace(
                "ws-fresh", "Trino-trace", "",
                /* isScratch */ false,
                /* workModel */ null,
                Instant.parse("2026-05-22T08:00:00Z"),
                Instant.parse("2026-05-22T08:00:00Z"));
        when(store.listWorkspaces()).thenReturn(List.of(fresh));
        when(store.fetchStats(eq("ws-fresh"), anyLong()))
                .thenReturn(new WorkspaceStats(0, 0, 0, 0L, null));
        when(store.listRepos("ws-fresh")).thenReturn(List.of());

        List<WorkspaceCardDto> cards = service.listWithStats();

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).lastActivityMs()).isNull();
        assertThat(cards.get(0).repos()).isEmpty();
    }
}
