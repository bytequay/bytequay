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
package com.bytequay.app.service.recent;

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentText;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RecentTouch;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.RecentStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRecentServiceImpl
{
    private static final Instant NOW = Instant.parse("2026-08-28T18:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-08-28");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryRecentStore store = new InMemoryRecentStore();
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final PrViewStateStore viewStates = mock(PrViewStateStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final RecentTouchRecorder recorder = mock(RecentTouchRecorder.class);

    private RecentServiceImpl service;

    @BeforeEach
    void setUp()
    {
        service = new RecentServiceImpl(store, recorder, details, viewStates, workspaces, credentials, CLOCK);
        when(credentials.cachedLogin()).thenReturn(Optional.of("me"));
        when(viewStates.findByPrIds(anySet())).thenReturn(ImmutableMap.of());
    }

    @Test
    void pagesOneDayAtATimeAndEndCapsWhenTheWindowIsSpent()
    {
        store.put(touch("pr:o/r#1", TODAY, NOW, RecentReason.READ, 12));
        store.put(touch("pr:o/r#2", TODAY, NOW.minusSeconds(3600), RecentReason.APPROVED, 0));
        store.put(touch("pr:o/r#3", TODAY.minusDays(2), NOW.minusSeconds(2 * 86_400), RecentReason.MERGED, 0));

        RecentDayPage first = service.stream(everything(), null);
        assertThat(first.day()).isEqualTo(TODAY);
        assertThat(first.rows()).extracting(RecentRow::itemKey).containsExactly("pr:o/r#1", "pr:o/r#2");
        assertThat(first.nextCursor()).isEqualTo(TODAY.minusDays(2));
        assertThat(first.endOfWindow()).isFalse();
        assertThat(first.activeDays()).containsExactly(TODAY, TODAY.minusDays(2));

        RecentDayPage second = service.stream(everything(), first.nextCursor());
        assertThat(second.day()).isEqualTo(TODAY.minusDays(2));
        assertThat(second.rows()).hasSize(1);
        assertThat(second.endOfWindow()).isTrue();
        assertThat(second.nextCursor()).isNull();

        RecentDayPage exhausted = service.stream(everything(), TODAY.minusDays(3));
        assertThat(exhausted.rows()).isEmpty();
        assertThat(exhausted.endOfWindow()).isTrue();
    }

    @Test
    void labelsTheReasonBadgeTheWayTheDesignReadsIt()
    {
        assertThat(RecentServiceImpl.reasonLabel(touch("a", TODAY, NOW, RecentReason.READ, 12))).isEqualTo("Read 12m");
        assertThat(RecentServiceImpl.reasonLabel(touch("a", TODAY, NOW, RecentReason.READ, 0))).isEqualTo("Read");
        assertThat(RecentServiceImpl.reasonLabel(touch("a", TODAY, NOW, RecentReason.PUSHED, 2))).isEqualTo("Pushed 2");
        assertThat(RecentServiceImpl.reasonLabel(touch("a", TODAY, NOW, RecentReason.PROMPTED, 0))).isEqualTo("You prompted");
        assertThat(RecentServiceImpl.reasonLabel(touch("a", TODAY, NOW, RecentReason.MERGED, 0))).isEqualTo("Merged");
    }

    @Test
    void appliesTheChipsAndTheNarrowField()
    {
        store.put(touch("pr:o/r#1", TODAY, NOW, RecentReason.APPROVED, 0));
        store.put(issueTouch("issue:o/r#2", TODAY, NOW.minusSeconds(60)));

        assertThat(service.stream(typed(RecentItemType.ISSUE), null).rows())
                .extracting(RecentRow::itemKey).containsExactly("issue:o/r#2");
        assertThat(service.stream(action(RecentReason.APPROVED), null).rows())
                .extracting(RecentRow::itemKey).containsExactly("pr:o/r#1");
        assertThat(service.stream(narrowed("nested"), null).rows())
                .extracting(RecentRow::itemKey).containsExactly("pr:o/r#1");
    }

    @Test
    void findsTheItemFromAWordTheUserWroteInIt()
    {
        store.put(touch("pr:o/r#1", TODAY, NOW, RecentReason.COMMENTED, 0));
        store.put(touch("pr:o/r#2", TODAY, NOW.minusSeconds(60), RecentReason.READ, 3));
        store.putText(new RecentText(
                "pr:o/r#1:comment:1",
                RecentItemType.PULL_REQUEST,
                "pr:o/r#1",
                RecentTextKind.COMMENT,
                "the metadata cache invalidation still races with a concurrent snapshot commit",
                NOW,
                1,
                "o/r",
                "Support sorted by nested fields in Iceberg",
                null));

        assertThat(service.search("race", 10)).extracting(RecentRow::itemKey).containsExactly("pr:o/r#1");
        assertThat(service.search("nested", 10)).extracting(RecentRow::itemKey).containsExactly("pr:o/r#1", "pr:o/r#2");
        assertThat(service.search("", 10)).hasSize(2);
    }

    @Test
    void returnsTheSentenceAndItsOffsetsForWordsMode()
    {
        store.putText(new RecentText(
                "pr:o/r#1:comment:1",
                RecentItemType.PULL_REQUEST,
                "pr:o/r#1",
                RecentTextKind.COMMENT,
                "Nothing to see here. The cache invalidation still races with a snapshot commit. And a third one.",
                NOW,
                1,
                "o/r",
                "Support sorted by nested fields",
                null));

        List<RecentWordHit> hits = service.words("race", 10);
        assertThat(hits).hasSize(1);
        RecentWordHit hit = hits.getFirst();
        assertThat(hit.sentence()).isEqualTo("The cache invalidation still races with a snapshot commit.");
        assertThat(hit.highlights()).hasSize(1);
        assertThat(hit.sentence().substring(hit.highlights().getFirst().start(), hit.highlights().getFirst().end()))
                .isEqualTo("race");
        assertThat(hit.kindLabel()).isEqualTo("comment");
        assertThat(hit.neverPosted()).isFalse();
    }

    @Test
    void requiresEveryWordOfAMultiWordSearch()
    {
        store.putText(text("a", "the metadata cache invalidation races with a snapshot commit", NOW));
        store.putText(text("b", "the reader lease races the writer", NOW.minusSeconds(60)));

        assertThat(service.words("race", 10)).extracting(RecentWordHit::id).containsExactly("a", "b");
        // "cache" only appears in the first, so the second must drop out even
        // though the database narrowed on the longer word.
        assertThat(service.words("cache race", 10)).extracting(RecentWordHit::id).containsExactly("a");
        assertThat(service.words("cache nowhere", 10)).isEmpty();
    }

    @Test
    void returnsTheNewestWritingFirst()
    {
        store.putText(text("old", "an early note about the race", NOW.minusSeconds(7200)));
        store.putText(text("new", "a later note about the race", NOW));

        assertThat(service.words("race", 10)).extracting(RecentWordHit::id).containsExactly("new", "old");
    }

    @Test
    void tagsAnUnsentDraftAsNeverPosted()
    {
        store.putText(new RecentText(
                "pr:o/r#1:draft",
                RecentItemType.PULL_REQUEST,
                "pr:o/r#1",
                RecentTextKind.DRAFT,
                "still think the race window is wider than the retry covers",
                NOW,
                1,
                "o/r",
                "Support sorted by nested fields",
                null));

        RecentWordHit hit = service.words("race", 10).getFirst();
        assertThat(hit.neverPosted()).isTrue();
        assertThat(hit.kindLabel()).isEqualTo("draft");
    }

    @Test
    void reportsWhatMovedSinceTheUserLastLooked()
    {
        Instant read = NOW.minusSeconds(7200);
        store.put(touch("pr:o/r#1", TODAY, read, RecentReason.COMMENTED, 0));
        when(viewStates.findByPrIds(anySet()))
                .thenReturn(ImmutableMap.of(11L, new PrViewState(11L, read, null, null, null)));
        when(details.find(11L)).thenReturn(Optional.of(detailWith(
                ImmutableList.of(
                        new PrTimelineEvent(1L, "commented", "ezra-b", NOW.minusSeconds(600), "reply", Optional.empty()),
                        new PrTimelineEvent(2L, "commented", "me", NOW.minusSeconds(500), "mine", Optional.empty()),
                        new PrTimelineEvent(3L, "committed", "ezra-b", NOW.minusSeconds(400), null, Optional.empty())),
                ImmutableList.of(new PrCheckRunState(9L, "ci", "completed", "failure", null, "check_run", NOW.minusSeconds(300))),
                ImmutableList.of("me"))));

        List<RecentChange> changes = service.changes();
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().moved()).isTrue();
        assertThat(changes.getFirst().wantsYou()).isTrue();
        assertThat(changes.getFirst().summary())
                .isEqualTo("1 new reply and 1 new commit and CI went red since you read it");
    }

    @Test
    void staysQuietWhenNothingMovedAndNobodyIsWaitingOnYou()
    {
        store.put(touch("pr:o/r#1", TODAY, NOW, RecentReason.COMMENTED, 0));
        when(viewStates.findByPrIds(anySet()))
                .thenReturn(ImmutableMap.of(11L, new PrViewState(11L, NOW, null, null, null)));
        when(details.find(11L)).thenReturn(Optional.of(detailWith(
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of())));

        assertThat(service.changes()).isEmpty();
    }

    @Test
    void clampsTheRequestedWindowToWhatIsStillKept()
    {
        store.put(touch("pr:o/r#1", TODAY, NOW, RecentReason.READ, 1));

        assertThat(service.windowStart()).isEqualTo(TODAY.minusDays(13));
        service.stream(
                new RecentFilter(TODAY.minusDays(400), TODAY, ImmutableSet.of(), null, null, false, null),
                null);
        assertThat(store.lastFrom).isEqualTo(TODAY.minusDays(13));
    }

    @Test
    void clampsAnOpenEndedUpperBoundToToday()
    {
        store.put(touch("pr:o/r#1", TODAY, NOW, RecentReason.READ, 1));

        // Days are stored as ISO text, so LocalDate.MAX would sort below every
        // real date and the window would come back empty.
        RecentDayPage page = service.stream(
                new RecentFilter(TODAY.minusDays(13), LocalDate.MAX, ImmutableSet.of(), null, null, false, null),
                null);
        assertThat(store.lastTo).isEqualTo(TODAY);
        assertThat(page.rows()).hasSize(1);
    }

    @Test
    void scopesAWorkspaceToTheRepositoryItWatchesRatherThanTheStampOnTheRow()
    {
        // A pull request read from the global list carries no workspace at all,
        // which is the common case and used to be filtered straight out.
        store.put(touch("pr:o/r#1", TODAY, NOW, RecentReason.APPROVED, 0));
        store.put(elsewhere("pr:other/repo#9", TODAY, NOW.minusSeconds(60)));
        when(workspaces.findById("ws-1")).thenReturn(Optional.of(workspace("ws-1", "o", "r")));

        RecentDayPage page = service.stream(
                new RecentFilter(TODAY.minusDays(13), TODAY, ImmutableSet.of(), null, "ws-1", false, null),
                null);
        assertThat(page.rows()).extracting(RecentRow::itemKey).containsExactly("pr:o/r#1");
    }

    @Test
    void stillMatchesARowStampedWithTheWorkspaceWhenItCarriesNoRepository()
    {
        store.put(new RecentTouch(
                "task:t1@" + TODAY, RecentItemType.TASK, "task:t1", TODAY, NOW, RecentReason.PROMPTED, 0,
                null, "rebase release-1.4", null, "ws-1", null, true, null, null));
        when(workspaces.findById("ws-1")).thenReturn(Optional.of(workspace("ws-1", "o", "r")));

        assertThat(service.stream(
                new RecentFilter(TODAY.minusDays(13), TODAY, ImmutableSet.of(), null, "ws-1", false, null), null)
                .rows())
                .extracting(RecentRow::itemKey).containsExactly("task:t1");
    }

    @Test
    void ignoresADwellShorterThanTheThreshold()
    {
        assertThat(RecentService.DWELL_THRESHOLD).isEqualTo(Duration.ofSeconds(20));
    }

    private static RecentFilter everything()
    {
        return new RecentFilter(TODAY.minusDays(13), TODAY, ImmutableSet.of(), null, null, false, null);
    }

    private static RecentFilter typed(RecentItemType type)
    {
        return new RecentFilter(TODAY.minusDays(13), TODAY, ImmutableSet.of(type), null, null, false, null);
    }

    private static RecentFilter action(RecentReason reason)
    {
        return new RecentFilter(TODAY.minusDays(13), TODAY, ImmutableSet.of(), reason, null, false, null);
    }

    private static RecentFilter narrowed(String text)
    {
        return new RecentFilter(TODAY.minusDays(13), TODAY, ImmutableSet.of(), null, null, false, text);
    }

    private static StoredPrDetail detailWith(
            List<PrTimelineEvent> timeline,
            List<PrCheckRunState> checkRuns,
            List<String> requestedReviewers)
    {
        PrRawDetail raw = new PrRawDetail(
                "Support sorted by nested fields in Iceberg",
                "https://github.com/o/r/pull/1",
                "open",
                "ezra-b",
                "",
                NOW.minusSeconds(86_400),
                NOW,
                null,
                "body",
                ImmutableList.of(),
                ImmutableMap.of(),
                ImmutableList.of(),
                null,
                false,
                true,
                "clean",
                1,
                1,
                1,
                requestedReviewers.size(),
                requestedReviewers,
                "abc123",
                "head",
                "main");
        return new StoredPrDetail(raw, ImmutableList.of(), timeline, checkRuns);
    }

    private static RecentTouch touch(String itemKey, LocalDate day, Instant at, RecentReason reason, int count)
    {
        return new RecentTouch(
                itemKey + "@" + day,
                RecentItemType.PULL_REQUEST,
                itemKey,
                day,
                at,
                reason,
                count,
                1,
                "Support sorted by nested fields in Iceberg",
                "o/r",
                null,
                "https://github.com/o/r/pull/1",
                false,
                11L,
                "ezra-b");
    }

    private static RecentText text(String id, String body, Instant writtenAt)
    {
        return new RecentText(
                id, RecentItemType.PULL_REQUEST, "pr:o/r#1", RecentTextKind.COMMENT, body, writtenAt,
                1, "o/r", "Support sorted by nested fields", null);
    }

    private static Workspace workspace(String id, String owner, String repo)
    {
        return new Workspace(
                id,
                owner + "/" + repo,
                1L,
                RepoRef.of(owner, repo),
                Optional.empty(),
                Optional.empty(),
                1L,
                RepoRef.of(owner, repo),
                Workspace.Topology.DIRECT,
                "main",
                "https://github.com/" + owner + "/" + repo + ".git",
                Optional.empty(),
                Optional.empty(),
                "/tmp/" + id,
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    private static RecentTouch elsewhere(String itemKey, LocalDate day, Instant at)
    {
        return new RecentTouch(
                itemKey + "@" + day, RecentItemType.PULL_REQUEST, itemKey, day, at,
                RecentReason.READ, 1, 9, "Somewhere else", "other/repo", null,
                "https://github.com/other/repo/pull/9", false, null, "finn-ep");
    }

    private static RecentTouch issueTouch(String itemKey, LocalDate day, Instant at)
    {
        return new RecentTouch(
                itemKey + "@" + day,
                RecentItemType.ISSUE,
                itemKey,
                day,
                at,
                RecentReason.COMMENTED,
                0,
                2,
                "Iceberg REST catalog auth options",
                "o/r",
                null,
                "https://github.com/o/r/issues/2",
                false,
                null,
                "finn-ep");
    }

    /** Enough of the port to exercise the reading logic without a database. */
    private static final class InMemoryRecentStore
            implements RecentStore
    {
        private final Map<String, RecentTouch> touches = new LinkedHashMap<>();
        private final Map<String, RecentText> texts = new LinkedHashMap<>();
        private LocalDate lastFrom;
        private LocalDate lastTo;

        void put(RecentTouch touch)
        {
            recordTouch(touch);
        }

        void putText(RecentText text)
        {
            saveText(text);
        }

        @Override
        public void recordTouch(RecentTouch touch)
        {
            touches.merge(touch.id(), touch, RecentTouch::mergedWith);
        }

        @Override
        public List<RecentTouch> findTouches(LocalDate from, LocalDate to)
        {
            lastFrom = from;
            lastTo = to;
            List<RecentTouch> rows = new ArrayList<>(touches.values().stream()
                    .filter(touch -> !touch.day().isBefore(from) && !touch.day().isAfter(to))
                    .collect(toImmutableList()));
            rows.sort((left, right) -> right.touchedAt().compareTo(left.touchedAt()));
            return ImmutableList.copyOf(rows);
        }

        @Override
        public void saveText(RecentText text)
        {
            texts.put(text.id(), text);
        }

        @Override
        public void deleteText(String textId)
        {
            texts.remove(textId);
        }

        @Override
        public List<RecentText> findTextByItemKey(String itemKey)
        {
            return texts.values().stream()
                    .filter(text -> text.itemKey().equals(itemKey))
                    .collect(toImmutableList());
        }

        @Override
        public List<RecentText> findTextContaining(String fragment, int limit)
        {
            return texts.values().stream()
                    .filter(text -> text.body().toLowerCase(Locale.ROOT).contains(fragment))
                    .sorted((left, right) -> right.writtenAt().compareTo(left.writtenAt()))
                    .limit(limit)
                    .collect(toImmutableList());
        }

        @Override
        public int deleteBefore(LocalDate cutoffExclusive)
        {
            int before = touches.size();
            touches.values().removeIf(touch -> touch.day().isBefore(cutoffExclusive));
            return before - touches.size();
        }

        @Override
        public void deleteAll()
        {
            touches.clear();
            texts.clear();
        }

        @Override
        public boolean deleteTouch(String touchId)
        {
            return touches.remove(touchId) != null;
        }
    }
}
