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

import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.store.PrDetailStore;
import com.google.common.base.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

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
     * sections and known source changes refresh their own group even when PR metadata returns a 304.
     *
     * @param repo owner/repository name
     * @param number positive pull-request number
     * @param section section whose completeness must be checked
     * @return detail and head from one stored raw snapshot, with local timestamps and explicit limitations
     * @throws RuntimeException the existing refresh error when no cached detail is available
     *
     * On failure, the original cached detail and timestamps are retained even if the refresh partially
     * wrote local state. Selected reads may retry on later calls. This method never marks a PR viewed or starts a repository-wide refresh.
     */
    public Read read(String repo, int number, Section section)
    {
        String repository = parseRef(repo, number).repoFullName().toLowerCase(Locale.ROOT);
        return read(pullRequests.prepareRead(repository, number), section);
    }

    public Read read(PullRequestRead route, Section section)
    {
        requireNonNull(section, "section is null");
        pullRequests.prepareRead(route);
        String repository = route.githubRef().repoFullName().toLowerCase(Locale.ROOT);
        int number = route.number();
        Optional<Cached> cached = cached(repository, number);
        if (cached.isPresent() && fresh(cached.orElseThrow(), section) && !incomplete(cached.orElseThrow().stored(), section)) {
            return result(cached.orElseThrow(), section, false, false);
        }
        try {
            if (section == Section.CHECKS) {
                refresh.refreshChecks(route);
            }
            else {
                refresh.refreshDetail(route);
            }
            Optional<Cached> updated = cached(repository, number);
            Cached available = updated.or(() -> cached)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pull request detail has not been refreshed"));
            return result(available, section, true, updated.isEmpty());
        }
        catch (RuntimeException failure) {
            log.info("Selected PR {}#{} {} refresh failed; cached fallback={}: {}", repository, number, section, cached.isPresent(), failure.getClass().getSimpleName());
            if (cached.isEmpty() || !allowsCachedFallback(failure)) {
                throw failure;
            }
            return result(cached.orElseThrow(), section, true, true);
        }
    }

    static boolean allowsCachedFallback(RuntimeException failure)
    {
        return Throwables.getCausalChain(failure).stream().noneMatch(cause -> {
            if (cause instanceof HostingFailure hosting) {
                return switch (hosting.kind()) {
                    case AUTHENTICATION_REQUIRED, FORBIDDEN, MISSING_OR_INACCESSIBLE, REVISION_CONFLICT -> true;
                    default -> false;
                };
            }
            return cause instanceof ResponseStatusException response && switch (response.getStatusCode().value()) {
                case 401, 403, 404, 409 -> true;
                default -> false;
            };
        });
    }

    private Optional<Cached> cached(String repo, int number)
    {
        Optional<PullRequest> row = pullRequests.findStored(repo, number);
        row.ifPresent(value -> checkArgument(value.repo().equalsIgnoreCase(repo) && value.number() == number, "Stored PR identity does not match selected request"));
        return row.flatMap(value -> details.find(value.id())
                .map(stored -> new Cached(value, stored, details.findSyncedAt(repo, number),
                        Optional.ofNullable(details.findFreshness(value.id())).orElseGet(PrDetailFreshness::unknown))));
    }

    private boolean fresh(Cached cached, Section section)
    {
        PrDetailFreshness freshness = cached.freshness();
        Optional<Instant> validatedAt = switch (section) {
            case PR -> freshness.metadataAt();
            case TIMELINE -> freshness.discussionAt();
            case CHECKS -> freshness.checksAt();
        };
        Duration maxAge = section == Section.CHECKS ? Duration.ofSeconds(30) : Duration.ofSeconds(60);
        boolean sourceCurrent = section == Section.TIMELINE
                ? freshness.discussionSourceUpdatedAt().filter(at -> !at.isBefore(pullRequests.discussionSourceUpdatedAt(cached.row()))).isPresent()
                : cached.stored().raw().updatedAt() != null && !cached.stored().raw().updatedAt().isBefore(cached.row().updatedAt());
        return sourceCurrent && validatedAt.filter(at -> at.isAfter(Instant.now(clock).minus(maxAge))).isPresent();
    }

    private Read result(Cached cached, Section section, boolean attempted, boolean failed)
    {
        PullRequest row = cached.row();
        StoredPrDetail stored = cached.stored();
        Read read = new Read(PullRequestDetailMapper.toPullRequestDetail(row.id(), row.repo(), row.number(), stored, stored.viewerCanWrite()),
                Optional.of(row.updatedAt()), cached.cacheUpdatedAt(), Optional.ofNullable(stored.raw().headSha()).filter(head -> !head.isBlank()), attempted, failed,
                failed || !fresh(cached, section), incomplete(stored, section));
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

    private record Cached(PullRequest row, StoredPrDetail stored, Optional<Instant> cacheUpdatedAt, PrDetailFreshness freshness) {}
}
