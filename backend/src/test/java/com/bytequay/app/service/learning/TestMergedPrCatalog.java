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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestHistoryPage;
import com.bytequay.app.repository.PullRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the date-partition merged-PR enumerator. Drives it with a
 * fake GitHub client so the &gt;1,000-result partition crossing and the
 * retryable-partial-pagination behaviours are deterministic.
 */
class TestMergedPrCatalog
{
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 21);

    /** Collects recorded sources and the last checkpointed cursor. */
    private static final class Recorder
            implements MergedPrCatalog.Sink
    {
        final List<RepoPrSource> sources = new ArrayList<>();

        @Override
        public void record(RepoPrSource source)
        {
            sources.add(source);
        }

        @Override
        public void checkpoint(CatalogCursor cursor)
        {
            // Persistence is exercised in the store/service tests.
        }
    }

    @Test
    void testCrossesGitHubResultCapBySplittingDateWindows()
    {
        // The full window reports 1,500 results (over GitHub's 1,000 cap), so
        // the enumerator must subdivide before it can reach every PR.
        FakeGitHub github = new FakeGitHub();
        MergedPrCatalog splitCatalog = new MergedPrCatalog(github, new ObjectMapper());
        Recorder sink = new Recorder();

        MergedPrCatalog.Outcome outcome = splitCatalog.catalog(
                "ws-1", "acme/widget", "pat", 1,
                MergedPrCatalog.initialCursor(TODAY), sink);

        assertThat(outcome.state()).isEqualTo(MergedPrCatalog.State.CAUGHT_UP);
        // The over-cap full window was split into two, each cataloged once.
        assertThat(outcome.cursor().partitions()).hasSize(2);
        assertThat(outcome.cursor().partitions()).allMatch(CatalogCursor.Partition::exhausted);
        assertThat(sink.sources).extracting(RepoPrSource::prNumber)
                .containsExactlyInAnyOrder(11, 12, 21, 22);
        // Nothing was recorded from the over-cap probe of the full window.
        assertThat(github.queriedWindows).contains("2008-01-01..2026-07-21");
    }

    @Test
    void testPartialPaginationIsVisibleAndRetryable()
    {
        FailingThenHealingGitHub github = new FailingThenHealingGitHub();
        MergedPrCatalog c = new MergedPrCatalog(github, new ObjectMapper());
        Recorder sink = new Recorder();

        // First pass: the fetch fails (rate limit / truncation). The pass
        // ends PARTIAL with its cursor intact — the window is not exhausted.
        MergedPrCatalog.Outcome first = c.catalog(
                "ws-1", "acme/widget", "pat", 1,
                MergedPrCatalog.initialCursor(TODAY), sink);
        assertThat(first.state()).isEqualTo(MergedPrCatalog.State.PARTIAL);
        assertThat(first.error()).isNotBlank();
        assertThat(first.cursor().complete()).isFalse();
        assertThat(sink.sources).isEmpty();

        // Retry from the preserved cursor: the client now succeeds and the
        // pass completes without restarting from page one.
        github.heal();
        MergedPrCatalog.Outcome second = c.catalog(
                "ws-1", "acme/widget", "pat", 1, first.cursor(), sink);
        assertThat(second.state()).isEqualTo(MergedPrCatalog.State.CAUGHT_UP);
        assertThat(sink.sources).extracting(RepoPrSource::prNumber).containsExactly(1);
    }

    @Test
    void testPagesThroughAMultiPageWindow()
    {
        // A single window with two pages of results, exhausted in order.
        MergedPrCatalog c = new MergedPrCatalog(new MultiPageGitHub(), new ObjectMapper());
        Recorder sink = new Recorder();

        MergedPrCatalog.Outcome outcome = c.catalog(
                "ws-1", "acme/widget", "pat", 1,
                MergedPrCatalog.initialCursor(TODAY), sink);

        assertThat(outcome.state()).isEqualTo(MergedPrCatalog.State.CAUGHT_UP);
        assertThat(sink.sources).extracting(RepoPrSource::prNumber).containsExactly(1, 2);
    }

    // ── fakes ───────────────────────────────────────────────────────

    private static PullRequest pr(int number)
    {
        return new PullRequest(number, "acme/widget", number, "title " + number, "alice",
                "url", null, Instant.parse("2020-01-01T00:00:00Z"), PullRequest.Origin.AUTHORED,
                ImmutableList.of(), null, false, null, null, null, ImmutableList.of(),
                null, 0, 0, 0, null,
                "closed", null, Instant.parse("2020-01-02T00:00:00Z"), null, null, null, null,
                null, null, "feature");
    }

    private static String windowOf(String query)
    {
        int at = query.indexOf("merged:");
        return at < 0 ? "" : query.substring(at + "merged:".length()).trim();
    }

    /** Full window is over-cap; the two halves each hold two PRs. */
    private static final class FakeGitHub
            implements PullRequestRepository
    {
        final List<String> queriedWindows = new ArrayList<>();

        @Override
        public PullRequestHistoryPage searchPullRequestsPaged(
                String pat, String query, int page, int perPage, String sort, String order)
        {
            String window = windowOf(query);
            queriedWindows.add(window);
            if (window.equals("2008-01-01..2026-07-21")) {
                return new PullRequestHistoryPage(List.of(), page, perPage, 1500, true);
            }
            // Left half vs right half, distinguished by their start date.
            List<PullRequest> items = window.startsWith("2008-01-01")
                    ? List.of(pr(11), pr(12)) : List.of(pr(21), pr(22));
            return new PullRequestHistoryPage(items, page, perPage, items.size(), false);
        }
    }

    /** Throws until {@link #heal()} is called. */
    private static final class FailingThenHealingGitHub
            implements PullRequestRepository
    {
        private boolean healthy;

        void heal()
        {
            healthy = true;
        }

        @Override
        public PullRequestHistoryPage searchPullRequestsPaged(
                String pat, String query, int page, int perPage, String sort, String order)
        {
            if (!healthy) {
                throw new IllegalStateException("secondary rate limit");
            }
            return new PullRequestHistoryPage(List.of(pr(1)), page, perPage, 1, false);
        }
    }

    /** One window, two pages. */
    private static final class MultiPageGitHub
            implements PullRequestRepository
    {
        @Override
        public PullRequestHistoryPage searchPullRequestsPaged(
                String pat, String query, int page, int perPage, String sort, String order)
        {
            Function<Integer, PullRequest> item = TestMergedPrCatalog::pr;
            if (page == 1) {
                return new PullRequestHistoryPage(List.of(item.apply(1)), page, perPage, 2, true);
            }
            return new PullRequestHistoryPage(List.of(item.apply(2)), page, perPage, 2, false);
        }
    }
}
