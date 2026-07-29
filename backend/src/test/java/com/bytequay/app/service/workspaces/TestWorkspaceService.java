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

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceCardDto;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.repository.WorkspaceStore.WorkspaceStats;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.concepts.WorkspaceGlossaryParser;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.bytequay.app.service.review.ReviewSessionPurge;
import com.bytequay.app.service.threads.ThreadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorkspaceService
{
    private final WorkspaceStore store = mock(WorkspaceStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
    private final ThreadService threadService = mock(ThreadService.class);
    private final InvestigationReviewService investigationReviews =
            mock(InvestigationReviewService.class);
    private final ReviewSessionPurge reviewSessionPurge = mock(ReviewSessionPurge.class);
    private final WorkspaceDataPurger dataPurger = mock(WorkspaceDataPurger.class);
    private final WorkspaceService service = new WorkspaceService(
            store,
            new WorkspaceGlossaryParser(),
            new ConceptRegistry(),
            threadStore,
            watchedRepos,
            threadService,
            investigationReviews,
            reviewSessionPurge,
            dataPurger);

    @BeforeEach
    void runReviewPurgeCallback()
    {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(reviewSessionPurge).purgeWorkspace(any(), any());
    }

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
                "ws-bytequay", "chenjian2664/bytequay",
                "## Decisions\n- decision a\n\n## Blockers\n- blocker x\n",
                /* isScratch */ false,
                /* workModel */ null,
                Instant.parse("2026-05-22T08:00:00Z"),
                Instant.parse("2026-05-22T09:00:00Z"));
        when(store.listWorkspaces()).thenReturn(List.of(one));
        when(store.fetchStats(eq("ws-bytequay"), anyLong()))
                .thenReturn(new WorkspaceStats(3, 2, 1, 1840L, 1716_300_000_000L));
        when(store.listRepos("ws-bytequay"))
                .thenReturn(List.of(
                        new WorkspaceRepo("ws-bytequay", "chenjian2664/bytequay",
                                null, false, Instant.parse("2026-05-22T08:00:00Z"))));

        List<WorkspaceCardDto> cards = service.listWithStats();

        assertThat(cards).hasSize(1);

        WorkspaceCardDto card = cards.getFirst();
        assertThat(card.id()).isEqualTo("ws-bytequay");
        assertThat(card.name()).isEqualTo("chenjian2664/bytequay");
        assertThat(card.isScratch()).isFalse();
        assertThat(card.repos()).containsExactly("bytequay");
        assertThat(card.activeThreadCount()).isEqualTo(3);
        assertThat(card.tasksInFlight()).isEqualTo(2);
        assertThat(card.needsAttentionCount()).isEqualTo(1);
        assertThat(card.spendTodayMilliUsd()).isEqualTo(1840L);
        assertThat(card.lastActivityMs()).isEqualTo(1716_300_000_000L);
        assertThat(card.memory().decisionCount()).isEqualTo(1);
        assertThat(card.memory().blockerCount()).isEqualTo(1);
        assertThat(card.color()).isNotBlank();
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

    // ── slug derivation + workspace id allocation ───────────────────

    @Test
    void deriveSlugLowercasesAndJoinsRunsWithDashes()
    {
        assertThat(WorkspaceService.deriveSlug("ByteQuay")).isEqualTo("bytequay");
        assertThat(WorkspaceService.deriveSlug("My Workspace"))
                .isEqualTo("my-workspace");
        assertThat(WorkspaceService.deriveSlug("trino — distributed SQL"))
                .isEqualTo("trino-distributed-sql");
    }

    @Test
    void deriveSlugCollapsesAdjacentSeparators()
    {
        // Multiple non-alphanumerics in a row collapse to a single dash
        // so "a — b" doesn't render as "a---b".
        assertThat(WorkspaceService.deriveSlug("a — b")).isEqualTo("a-b");
        assertThat(WorkspaceService.deriveSlug("hello!!!world"))
                .isEqualTo("hello-world");
    }

    @Test
    void deriveSlugStripsLeadingAndTrailingSeparators()
    {
        assertThat(WorkspaceService.deriveSlug("  ByteQuay  ")).isEqualTo("bytequay");
        assertThat(WorkspaceService.deriveSlug("!!ByteQuay!!"))
                .isEqualTo("bytequay");
    }

    @Test
    void deriveSlugReturnsEmptyForUnsluggableInput()
    {
        // Caller falls back to a UUID-style stub when the derived slug
        // is empty (all symbols / blank).
        assertThat(WorkspaceService.deriveSlug("")).isEmpty();
        assertThat(WorkspaceService.deriveSlug("   ")).isEmpty();
        assertThat(WorkspaceService.deriveSlug("!!!")).isEmpty();
        assertThat(WorkspaceService.deriveSlug(null)).isEmpty();
    }

    @Test
    void deriveSlugTruncatesAtTheCharCapWithoutTrailingDash()
    {
        // A 30-char-input long-name slugs to 24 chars max; the truncation
        // must not leave a dangling dash.
        String result = WorkspaceService.deriveSlug(
                "this is a very long workspace name");
        assertThat(result.length()).isLessThanOrEqualTo(WorkspaceService.SLUG_MAX_CHARS);
        assertThat(result).doesNotEndWith("-");
    }

    @Test
    void createRequiresExactlyOneVerifiedLocalClone()
    {
        assertThatThrownBy(() -> service.create(new WorkspaceService.NewWorkspaceRequest(
                "ignored", null, false, "", List.of())))
                .hasMessageContaining("exactly one locally cloned repository");
    }

    @Test
    void ensureForVerifiedCloneCreatesOneWorkspaceNamedForItsRepository()
    {
        WatchedRepo watched = new WatchedRepo(1, "acme", "widgets", 0,
                System.getProperty("java.io.tmpdir"), null, null);
        when(watchedRepos.find("acme", "widgets")).thenReturn(Optional.of(watched));
        when(store.listWorkspaces()).thenReturn(List.of());
        when(store.findWorkspaceById(any())).thenReturn(Optional.empty());

        Workspace created = service.ensureForVerifiedClone("acme", "widgets");

        assertThat(created.name()).isEqualTo("acme/widgets");
        ArgumentCaptor<WorkspaceRepo> repo = ArgumentCaptor.forClass(WorkspaceRepo.class);
        verify(store).addRepo(repo.capture());
        assertThat(repo.getValue().repoFullName()).isEqualTo("acme/widgets");
    }

    @Test
    void ensureForVerifiedCloneReusesTheRepositoryWorkspace()
    {
        WatchedRepo watched = new WatchedRepo(1, "acme", "widgets", 0,
                System.getProperty("java.io.tmpdir"), null, null);
        Workspace existing = workspace("ws-widgets", "acme/widgets");
        when(watchedRepos.find("acme", "widgets")).thenReturn(Optional.of(watched));
        when(store.listWorkspaces()).thenReturn(List.of(existing));
        when(store.listRepos("ws-widgets")).thenReturn(List.of(
                new WorkspaceRepo("ws-widgets", "acme/widgets", null, false, Instant.EPOCH)));

        Workspace ensured = service.ensureForVerifiedClone("acme", "widgets");

        assertThat(ensured).isEqualTo(existing);
        verify(store, never()).saveWorkspace(any());
    }

    @Test
    void createUsesTheTypedWorkspaceNameAndRejectsAnExistingRepositoryMapping()
    {
        WatchedRepo watched = new WatchedRepo(1, "acme", "widgets", 0,
                System.getProperty("java.io.tmpdir"), null, null);
        when(watchedRepos.find("acme", "widgets")).thenReturn(Optional.of(watched));
        when(store.listWorkspaces()).thenReturn(List.of());
        when(store.findWorkspaceById(any())).thenReturn(Optional.empty());

        Workspace created = service.create(new WorkspaceService.NewWorkspaceRequest(
                "Widget delivery", null, false, "", List.of("acme/widgets")));

        assertThat(created.name()).isEqualTo("Widget delivery");
        assertThat(created.id()).isEqualTo("ws-widget-delivery");

        Workspace existing = workspace("ws-existing", "Existing widgets");
        when(store.listWorkspaces()).thenReturn(List.of(existing));
        when(store.listRepos("ws-existing")).thenReturn(List.of(
                new WorkspaceRepo("ws-existing", "acme/widgets", null, false, Instant.EPOCH)));

        assertThatThrownBy(() -> service.create(new WorkspaceService.NewWorkspaceRequest(
                "Another workspace", null, false, "", List.of("acme/widgets"))))
                .hasMessageContaining("already mapped");
    }

    // ── cascade delete ──────────────────────────────────────────────

    @Test
    void deletePurgesEveryThreadThenDropsTheWorkspace()
    {
        when(store.findWorkspaceById("ws-bytequay"))
                .thenReturn(Optional.of(workspace("ws-bytequay", "ByteQuay")));
        when(threadStore.listThreadsByWorkspace("ws-bytequay"))
                .thenReturn(List.of(threadWithId("th-1"), threadWithId("th-2")));

        service.delete("ws-bytequay");

        // Each thread is purged (its agents stopped, worktrees reaped, row +
        // DB cascade) BEFORE the workspace row is dropped — required for
        // correctness, since threads.workspace_id has no ON DELETE CASCADE.
        InOrder order = inOrder(
                threadService, reviewSessionPurge,
                investigationReviews, dataPurger, store);
        order.verify(threadService).purge("th-1");
        order.verify(threadService).purge("th-2");
        order.verify(reviewSessionPurge).purgeWorkspace(eq("ws-bytequay"), any());
        order.verify(investigationReviews).purgeByWorkspace("ws-bytequay");
        order.verify(dataPurger).purgeWorkspaceScoped("ws-bytequay");
        order.verify(store).deleteWorkspace("ws-bytequay");
    }

    @Test
    void deleteAbortsWhenOneThreadPurgeThrows()
    {
        when(store.findWorkspaceById("ws-bytequay"))
                .thenReturn(Optional.of(workspace("ws-bytequay", "ByteQuay")));
        when(threadStore.listThreadsByWorkspace("ws-bytequay"))
                .thenReturn(List.of(threadWithId("th-1"), threadWithId("th-2")));
        doThrow(new RuntimeException("wedged git worktree"))
                .when(threadService).purge("th-1");

        assertThatThrownBy(() -> service.delete("ws-bytequay"))
                .hasMessageContaining("wedged git worktree");

        verify(threadService, never()).purge("th-2");
        verify(reviewSessionPurge, never()).purgeWorkspace(any(), any());
        verify(investigationReviews, never()).purgeByWorkspace(any());
        verify(store, never()).deleteWorkspace(any());
    }

    @Test
    void deleteDropsTheWorkspaceWhenItHasNoThreads()
    {
        when(store.findWorkspaceById("ws-empty"))
                .thenReturn(Optional.of(workspace("ws-empty", "Empty")));
        when(threadStore.listThreadsByWorkspace("ws-empty")).thenReturn(List.of());

        service.delete("ws-empty");

        verify(threadService, never()).purge(any());
        verify(reviewSessionPurge).purgeWorkspace(eq("ws-empty"), any());
        verify(investigationReviews).purgeByWorkspace("ws-empty");
        verify(store).deleteWorkspace("ws-empty");
    }

    private static Thread threadWithId(String id)
    {
        Instant now = Instant.now();
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", null, "Thread " + id,
                ThreadStatus.IDLE, "claude-sonnet-4.6", 0L, 0L, 0L, now, now,
                null, null, ThreadFlow.BUILD, "ws-1", null);
    }

    private static Workspace workspace(String id, String name)
    {
        Instant now = Instant.now();
        return new Workspace(id, name, "", false, null, now, now);
    }
}
