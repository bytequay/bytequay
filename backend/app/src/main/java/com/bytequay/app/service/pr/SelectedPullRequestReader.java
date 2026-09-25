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
package com.bytequay.app.service.pr;

import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static com.bytequay.app.service.pr.PullRequestService.RefreshMode.FULL;
import static com.bytequay.app.service.pr.PullRequestService.RefreshMode.NONE;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Reads one explicitly selected PR section through the existing local cache and refresh services. */
@Service
public class SelectedPullRequestReader
{
    private static final Logger log = LoggerFactory.getLogger(SelectedPullRequestReader.class);

    private final PullRequestService pullRequests;
    private final PrDetailStore details;
    private final PullRequestRefreshService refresh;
    private final Clock clock;

    public SelectedPullRequestReader(PullRequestService pullRequests, PrDetailStore details, PullRequestRefreshService refresh, Clock clock)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Returns cached data when the requested section is complete and existing freshness rules permit it.
     * Otherwise performs at most one selected refresh, then projects the persisted snapshot. Missing
     * sections and known row/detail lag bypass the PR ETag so a 304 cannot preserve that missing data.
     *
     * @param repo owner/repository name
     * @param number positive pull-request number
     * @param section section whose completeness must be checked
     * @return detail and head from one stored raw snapshot, with local timestamps and explicit limitations
     * @throws RuntimeException the existing refresh error when no cached detail is available
     *
     * On failure, the original cached detail and timestamps are retained even if the refresh partially
     * wrote local state. Selected reads may retry on later calls; background sync's retry backoff does
     * not apply. This method never marks a PR viewed or starts a repository-wide refresh.
     */
    public Read read(String repo, int number, Section section)
    {
        String repository = parseRef(repo, number).repoFullName().toLowerCase(Locale.ROOT);
        requireNonNull(section, "section is null");
        Optional<Cached> cached = cached(repository, number);
        if (cached.isPresent() && mode(cached.orElseThrow()) == NONE && !incomplete(cached.orElseThrow().stored(), section)) {
            return result(cached.orElseThrow(), section, false, false);
        }
        boolean all = cached.isEmpty() || incomplete(cached.orElseThrow().stored(), section)
                || mode(cached.orElseThrow()) == FULL;
        try {
            if (all) {
                refresh.refreshAllDetail(repository, number);
            }
            else {
                refresh.refreshDetail(repository, number);
            }
            Cached updated = cached(repository, number)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pull request detail has not been refreshed"));
            return result(updated, section, true, false);
        }
        catch (RuntimeException failure) {
            log.info("Selected PR {}#{} {} refresh failed; cached fallback={}: {}", repository, number, section, cached.isPresent(), failure.getClass().getSimpleName());
            if (cached.isEmpty()) {
                throw failure;
            }
            return result(cached.orElseThrow(), section, true, true);
        }
    }

    private Optional<Cached> cached(String repo, int number)
    {
        Optional<PullRequest> row = pullRequests.findStored(repo, number);
        row.ifPresent(value -> checkArgument(value.repo().equalsIgnoreCase(repo) && value.number() == number, "Stored PR identity does not match selected request"));
        return row.flatMap(value -> details.find(value.id())
                .map(stored -> new Cached(value, stored, details.findSyncedAt(repo, number))));
    }

    private PullRequestService.RefreshMode mode(Cached cached)
    {
        return PullRequestService.refreshMode(cached.row(), cached.row().updatedAt(), Optional.ofNullable(cached.stored().raw().updatedAt()),
                cached.cacheUpdatedAt(), Instant.now(clock));
    }

    private Read result(Cached cached, Section section, boolean attempted, boolean failed)
    {
        PullRequest row = cached.row();
        StoredPrDetail stored = cached.stored();
        Read read = new Read(PullRequestDetailMapper.toPullRequestDetail(row.id(), row.repo(), row.number(), stored, stored.viewerCanWrite()),
                Optional.of(row.updatedAt()), cached.cacheUpdatedAt(), Optional.ofNullable(stored.raw().headSha()).filter(head -> !head.isBlank()), attempted, failed,
                failed || mode(cached) != NONE, incomplete(stored, section));
        log.debug("Selected PR {}#{} {}: refreshAttempted={}, refreshFailed={}, stale={}, incomplete={}",
                row.repo(), row.number(), section, read.refreshAttempted(), read.refreshFailed(), read.stale(), read.incomplete());
        return read;
    }

    private static boolean incomplete(StoredPrDetail stored, Section section)
    {
        return switch (section) {
            case PR -> false;
            case TIMELINE -> !stored.timelineComplete();
            case CHECKS -> !stored.checkRunsComplete() || stored.raw().headSha() == null || stored.raw().headSha().isBlank();
        };
    }

    public enum Section
    {
        PR,
        TIMELINE,
        CHECKS
    }

    /**
     * One projected stored snapshot and the outcome of this selected read.
     *
     * @param rowUpdatedAt GitHub update time from the captured list row
     * @param cacheUpdatedAt local cache write time, which can include status-only saves; not full discussion freshness
     * @param observedHeadSha head identity from the same stored raw detail used to construct {@code detail}
     * @param refreshAttempted whether this read attempted one existing selected refresh
     * @param refreshFailed whether refresh or its persisted reread failed and this read returned its original cached snapshot
     * @param stale whether shared freshness checks remain unsatisfied or refresh failed
     * @param incomplete whether any source required by the requested section remains unavailable
     */
    public record Read(
            PullRequestDetail detail,
            Optional<Instant> rowUpdatedAt,
            Optional<Instant> cacheUpdatedAt,
            Optional<String> observedHeadSha,
            boolean refreshAttempted,
            boolean refreshFailed,
            boolean stale,
            boolean incomplete)
    {
        public Read
        {
            requireNonNull(detail, "detail is null");
            requireNonNull(rowUpdatedAt, "rowUpdatedAt is null");
            requireNonNull(cacheUpdatedAt, "cacheUpdatedAt is null");
            requireNonNull(observedHeadSha, "observedHeadSha is null");
            checkArgument(!refreshFailed || refreshAttempted, "A failed refresh must have been attempted");
        }
    }

    private record Cached(PullRequest row, StoredPrDetail stored, Optional<Instant> cacheUpdatedAt) {}
}
