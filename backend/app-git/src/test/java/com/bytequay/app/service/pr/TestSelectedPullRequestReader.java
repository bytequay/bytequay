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

import com.bytequay.app.service.pr.SelectedPullRequestReader.Read;
import com.bytequay.app.service.pr.SelectedPullRequestReader.Section;
import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetailRefresh;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.store.PrDetailStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestSelectedPullRequestReader
{
    private static final String REPO = "owner/repo";
    private static final int NUMBER = 7;
    private static final long ID = 12;
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant UPDATED = NOW.minusSeconds(60);
    private static final String HEAD = "a".repeat(40);
    private static final String NEW_HEAD = "b".repeat(40);

    private final PullRequestRead route = PullRequestReadFixture.route(REPO, NUMBER);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
    private final SelectedPullRequestReader reader = new SelectedPullRequestReader(pullRequests, details, refresh, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void selectedRoute()
    {
        lenient().when(pullRequests.prepareRead(any(), anyInt())).thenAnswer(call -> {
            String repository = call.getArgument(0);
            int number = call.getArgument(1);
            return REPO.equals(repository) && number == NUMBER ? route : PullRequestReadFixture.route(repository, number);
        });
    }

    @ParameterizedTest
    @EnumSource(value = HostingFailure.Kind.class, names = {"AUTHENTICATION_REQUIRED", "FORBIDDEN", "MISSING_OR_INACCESSIBLE"})
    void rejectedAccessCannotReturnCachedData(HostingFailure.Kind kind)
    {
        cached(row(UPDATED, "open", null), stored(UPDATED, HEAD, ImmutableList.of()), Optional.of(NOW));
        when(details.findFreshness(ID)).thenReturn(PrDetailFreshness.unknown());
        HostingFailure failure = new HostingFailure(kind, "Access changed");
        when(refresh.refreshDetail(route)).thenThrow(failure);

        assertThatThrownBy(() -> reader.read(REPO, NUMBER, Section.PR)).isSameAs(failure);
    }

    @ParameterizedTest
    @EnumSource(Section.class)
    void freshCompleteCacheNeedsNoRemoteRead(Section section)
    {
        StoredPrDetail stored = stored(UPDATED, HEAD, ImmutableList.of());
        cached(row(UPDATED, "open", null), stored, Optional.of(NOW));

        Read read = reader.read("OWNER/REPO", NUMBER, section);

        assertThat(read.detail().body()).isEqualTo(stored.raw().body());
        assertThat(read.detail().updatedAt()).isEqualTo(UPDATED);
        assertThat(read.rowUpdatedAt()).contains(UPDATED);
        assertThat(read.cacheUpdatedAt()).contains(NOW);
        assertThat(read.observedHeadSha()).contains(HEAD);
        assertThat(read.refreshAttempted()).isFalse();
        assertThat(read.refreshFailed()).isFalse();
        assertThat(read.stale()).isFalse();
        assertThat(read.incomplete()).isFalse();
        verifyNoInteractions(refresh);
        verify(pullRequests).prepareRead(route);
        verify(pullRequests).findStored(REPO, NUMBER);
    }

    @Test
    void missingDetailForcesFullHydrationAndRereadsPersistedRaw()
    {
        StoredPrDetail hydrated = stored(NOW, NEW_HEAD, ImmutableList.of());
        when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.of(row(UPDATED, "open", null)), Optional.of(row(NOW, "open", null)));
        when(details.find(ID)).thenReturn(Optional.empty(), Optional.of(hydrated));
        when(details.findSyncedAt(REPO, NUMBER)).thenReturn(Optional.of(NOW));
        when(details.findFreshness(ID)).thenReturn(freshness(NOW, NOW));
        when(refresh.refreshDetail(route)).thenReturn(PullRequestDetailRefresh.full(project(hydrated)));

        Read read = reader.read(REPO, NUMBER, Section.PR);

        assertThat(read.detail().body()).isEqualTo(hydrated.raw().body());
        assertThat(read.observedHeadSha()).contains(NEW_HEAD);
        assertThat(read.rowUpdatedAt()).contains(NOW);
        assertThat(read.cacheUpdatedAt()).contains(NOW);
        assertThat(read.refreshAttempted()).isTrue();
        assertThat(read.refreshFailed()).isFalse();
        assertThat(read.stale()).isFalse();
        verify(refresh).refreshDetail(route);
        verifyNoMoreInteractions(refresh);
        verify(pullRequests, never()).getPullRequestDetail(anyString(), anyInt());
    }

    @Test
    void missingRowHydratesOnlyTheSelectedPullRequest()
    {
        StoredPrDetail hydrated = stored(UPDATED, HEAD, ImmutableList.of());
        when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.empty(), Optional.of(row(UPDATED, "open", null)));
        when(details.find(ID)).thenReturn(Optional.of(hydrated));
        when(details.findSyncedAt(REPO, NUMBER)).thenReturn(Optional.of(NOW));
        when(details.findFreshness(ID)).thenReturn(freshness(NOW, NOW));
        when(refresh.refreshDetail(route)).thenReturn(PullRequestDetailRefresh.full(project(hydrated)));

        assertThat(reader.read(REPO, NUMBER, Section.PR).observedHeadSha()).contains(HEAD);
        verify(refresh).refreshDetail(route);
        verifyNoMoreInteractions(refresh);
    }

    @Test
    void knownLagRequestsTheSharedGroupRefresh()
    {
        StoredPrDetail old = stored(UPDATED, HEAD, ImmutableList.of());
        StoredPrDetail latest = stored(NOW, NEW_HEAD, ImmutableList.of());
        cached(row(NOW, "open", null), old, Optional.of(NOW));
        when(details.findEtag(ID)).thenReturn(Optional.of("cached-etag"));
        when(details.find(ID)).thenReturn(Optional.of(old), Optional.of(latest));
        when(refresh.refreshDetail(route)).thenReturn(PullRequestDetailRefresh.full(project(latest)));

        Read read = reader.read(REPO, NUMBER, Section.PR);

        assertThat(read.stale()).isFalse();
        assertThat(read.observedHeadSha()).contains(NEW_HEAD);
        verify(refresh).refreshDetail(route);
        verifyNoMoreInteractions(refresh);
    }

    @Test
    void unresolvedKnownLagRemainsExplicitAfterOneRefresh()
    {
        StoredPrDetail old = stored(UPDATED, HEAD, ImmutableList.of());
        cached(row(NOW, "open", null), old, Optional.of(NOW));
        when(refresh.refreshDetail(route)).thenReturn(PullRequestDetailRefresh.full(project(old)));

        Read read = reader.read(REPO, NUMBER, Section.PR);

        assertThat(read.stale()).isTrue();
        assertThat(read.refreshAttempted()).isTrue();
        assertThat(read.refreshFailed()).isFalse();
        assertThat(read.rowUpdatedAt()).contains(NOW);
        assertThat(read.detail().updatedAt()).isEqualTo(UPDATED);
        verify(refresh).refreshDetail(route);
        verifyNoMoreInteractions(refresh);
    }

    @Test
    void unknownRawUpdateTimeRemainsStaleAfterRefresh()
    {
        StoredPrDetail unknown = stored(null, HEAD, ImmutableList.of());
        cached(row(UPDATED, "open", null), unknown, Optional.of(NOW));
        when(refresh.refreshDetail(route)).thenReturn(PullRequestDetailRefresh.full(project(unknown)));

        Read read = reader.read(REPO, NUMBER, Section.PR);

        assertThat(read.detail().updatedAt()).isNull();
        assertThat(read.rowUpdatedAt()).contains(UPDATED);
        assertThat(read.stale()).isTrue();
        assertThat(read.refreshFailed()).isFalse();
        verify(refresh).refreshDetail(route);
    }

    @ParameterizedTest
    @CsvSource({"TIMELINE,timeline", "TIMELINE,comments", "TIMELINE,commits", "TIMELINE,reviewThreads", "TIMELINE,reactions", "CHECKS,checks"})
    void requestedMissingSourceRefreshesItsOwnGroupDespiteAnEtag(Section section, String missing)
    {
        StoredPrDetail partial = stored(UPDATED, HEAD, ImmutableList.of(missing));
        StoredPrDetail complete = stored(UPDATED, HEAD, ImmutableList.of());
        cached(row(UPDATED, "open", null), partial, Optional.of(NOW));
        when(details.findEtag(ID)).thenReturn(Optional.of("cached-etag"));
        when(details.find(ID)).thenReturn(Optional.of(partial), Optional.of(complete));
        when(refresh.refreshDetail(route)).thenReturn(PullRequestDetailRefresh.full(project(complete)));
        when(refresh.refreshChecks(route)).thenReturn(PullRequestDetailRefresh.status(project(complete)));

        Read read = reader.read(REPO, NUMBER, section);

        assertThat(read.incomplete()).isFalse();
        assertThat(read.refreshAttempted()).isTrue();
        if (section == Section.CHECKS) {
            verify(refresh).refreshChecks(route);
        }
        else {
            verify(refresh).refreshDetail(route);
        }
        verifyNoMoreInteractions(refresh);
    }

    @Test
    void unrelatedMissingSourcesDoNotRefreshTheRequestedSection()
    {
        cached(row(UPDATED, "open", null), stored(UPDATED, HEAD, ImmutableList.of(StoredPrDetail.REVIEWS, StoredPrDetail.CHECKS)), Optional.of(NOW));

        assertThat(reader.read(REPO, NUMBER, Section.TIMELINE).incomplete()).isFalse();
        assertThat(reader.read(REPO, NUMBER, Section.PR).incomplete()).isFalse();
        verifyNoInteractions(refresh);
    }

    @Test
    void successfulPartialRefreshStillReportsRequestedSourceIncomplete()
    {
        StoredPrDetail partial = stored(UPDATED, HEAD, ImmutableList.of(StoredPrDetail.COMMENTS));
        cached(row(UPDATED, "open", null), partial, Optional.of(NOW));
        when(refresh.refreshDetail(route)).thenReturn(PullRequestDetailRefresh.full(project(partial)));

        Read read = reader.read(REPO, NUMBER, Section.TIMELINE);

        assertThat(read.incomplete()).isTrue();
        assertThat(read.refreshAttempted()).isTrue();
        assertThat(read.refreshFailed()).isFalse();
        assertThat(read.detail().unavailableSections()).containsExactly(StoredPrDetail.COMMENTS);
        verify(refresh).refreshDetail(route);
        verifyNoMoreInteractions(refresh);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void checksWithoutObservedHeadRemainIncomplete(String head)
    {
        StoredPrDetail partial = stored(UPDATED, head, ImmutableList.of());
        cached(row(UPDATED, "open", null), partial, Optional.of(NOW));
        when(refresh.refreshChecks(route)).thenReturn(PullRequestDetailRefresh.full(project(partial)));

        Read read = reader.read(REPO, NUMBER, Section.CHECKS);

        assertThat(read.observedHeadSha()).isEmpty();
        assertThat(read.incomplete()).isTrue();
        verify(refresh).refreshChecks(route);
    }

    @Test
    void failedRefreshRetainsOriginalEvidenceAndTimestampsDespitePartialWrites()
    {
        StoredPrDetail old = stored(UPDATED, HEAD, ImmutableList.of());
        StoredPrDetail partialWrite = stored(NOW, NEW_HEAD, ImmutableList.of());
        Instant originalCacheTime = NOW.minusSeconds(600);
        cached(row(NOW, "open", null), old, Optional.of(originalCacheTime));
        when(details.find(ID)).thenReturn(Optional.of(old), Optional.of(partialWrite));
        when(details.findSyncedAt(REPO, NUMBER)).thenReturn(Optional.of(originalCacheTime), Optional.of(NOW));
        when(refresh.refreshDetail(route)).thenThrow(new IllegalStateException("Refresh failed after saving its row"));

        Read read = reader.read(REPO, NUMBER, Section.PR);

        assertThat(read.detail().body()).isEqualTo(old.raw().body());
        assertThat(read.observedHeadSha()).contains(HEAD);
        assertThat(read.cacheUpdatedAt()).contains(originalCacheTime);
        assertThat(read.rowUpdatedAt()).contains(NOW);
        assertThat(read.refreshFailed()).isTrue();
        assertThat(read.refreshAttempted()).isTrue();
        assertThat(read.stale()).isTrue();
        verify(details).find(ID);
        verify(refresh).refreshDetail(route);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "error", "wrong-repository"})
    void failedPersistedRereadRetainsOriginalCache(String outcome)
    {
        StoredPrDetail old = stored(UPDATED, HEAD, ImmutableList.of());
        cached(row(NOW, "open", null), old, Optional.of(NOW));
        when(refresh.refreshDetail(route)).thenReturn(PullRequestDetailRefresh.full(project(old)));
        switch (outcome) {
            case "missing" -> when(details.find(ID)).thenReturn(Optional.of(old), Optional.empty());
            case "error" -> when(details.find(ID)).thenReturn(Optional.of(old)).thenThrow(new IllegalStateException("Cache reread failed"));
            case "wrong-repository" -> when(pullRequests.findStored(REPO, NUMBER))
                    .thenReturn(Optional.of(row(NOW, "open", null)), Optional.of(row("another/repository", NUMBER, NOW, "open", null)));
            default -> throw new IllegalArgumentException(outcome);
        }

        Read read = reader.read(REPO, NUMBER, Section.PR);

        assertThat(read.detail().repo()).isEqualTo(REPO);
        assertThat(read.detail().body()).isEqualTo(old.raw().body());
        assertThat(read.observedHeadSha()).contains(HEAD);
        assertThat(read.refreshFailed()).isTrue();
        assertThat(read.stale()).isTrue();
    }

    @Test
    void noCachePropagatesExistingRefreshFailure()
    {
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub unavailable");
        when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.empty());
        when(refresh.refreshDetail(route)).thenThrow(failure);

        assertThatThrownBy(() -> reader.read(REPO, NUMBER, Section.PR)).isSameAs(failure);
        verify(refresh).refreshDetail(route);
    }

    @Test
    void oldClosedDiscussionIsRevalidatedWhenExplicitlySelected()
    {
        Instant historical = NOW.minusSeconds(90L * 24 * 3600);
        cached(row(historical, "closed", null), stored(historical, HEAD, ImmutableList.of(), "closed"), Optional.of(historical));

        Read read = reader.read(REPO, NUMBER, Section.TIMELINE);

        assertThat(read.stale()).isTrue();
        assertThat(read.refreshAttempted()).isTrue();
        assertThat(read.cacheUpdatedAt()).contains(historical);
        verify(refresh).refreshDetail(route);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refreshesSelectedChecksAtThirtySecondBoundary(boolean monitoredClosed)
    {
        String state = monitoredClosed ? "closed" : "open";
        StoredPrDetail stored = stored(UPDATED, HEAD, ImmutableList.of(), state);
        cached(row(UPDATED, state, monitoredClosed ? UPDATED : null), stored, Optional.of(NOW.minusSeconds(30)));
        when(details.findSyncedAt(REPO, NUMBER)).thenReturn(Optional.of(NOW.minusSeconds(30)), Optional.of(NOW));
        when(details.findFreshness(ID)).thenReturn(freshness(NOW.minusSeconds(30), UPDATED), freshness(NOW, UPDATED));
        when(refresh.refreshChecks(route)).thenReturn(PullRequestDetailRefresh.status(project(stored)));

        Read read = reader.read(REPO, NUMBER, Section.CHECKS);

        assertThat(read.refreshAttempted()).isTrue();
        assertThat(read.refreshFailed()).isFalse();
        assertThat(read.stale()).isFalse();
        assertThat(read.cacheUpdatedAt()).contains(NOW);
        assertThat(read.detail().updatedAt()).isEqualTo(UPDATED);
        verify(refresh).refreshChecks(route);
        verifyNoMoreInteractions(refresh);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMismatchedStoredRowBeforeReadingItsDetail(boolean wrongNumber)
    {
        when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.of(row(wrongNumber ? REPO : "another/repository", wrongNumber ? 8 : NUMBER, UPDATED, "open", null)));

        assertThatThrownBy(() -> reader.read(REPO, NUMBER, Section.PR)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");
        verifyNoInteractions(details, refresh);
    }

    private void cached(PullRequest row, StoredPrDetail stored, Optional<Instant> cacheTime)
    {
        when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.of(row));
        when(details.find(ID)).thenReturn(Optional.of(stored));
        when(details.findSyncedAt(REPO, NUMBER)).thenReturn(cacheTime);
        when(details.findFreshness(ID)).thenReturn(new PrDetailFreshness(cacheTime, cacheTime, cacheTime, Optional.ofNullable(stored.raw().updatedAt())));
        when(pullRequests.discussionSourceUpdatedAt(row)).thenReturn(row.updatedAt());
    }

    private static PrDetailFreshness freshness(Instant at, Instant source)
    {
        return new PrDetailFreshness(Optional.of(at), Optional.of(at), Optional.of(at), Optional.ofNullable(source));
    }

    private static PullRequestDetail project(StoredPrDetail stored)
    {
        return PullRequestDetailMapper.toPullRequestDetail(ID, REPO, NUMBER, stored, stored.viewerCanWrite());
    }

    private static StoredPrDetail stored(Instant updatedAt, String head, List<String> unavailable)
    {
        return stored(updatedAt, head, unavailable, "open");
    }

    private static StoredPrDetail stored(Instant updatedAt, String head, List<String> unavailable, String state)
    {
        PrRawDetail raw = new PrRawDetail("Cached description", "https://github.com/owner/repo/pull/7", state, "octocat", null, UPDATED, updatedAt, null,
                "Body for " + head, ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, true, "clean", 1, 0, 1, 0, ImmutableList.of(), head, "feature", "main");
        return new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), unavailable, true);
    }

    private static PullRequest row(Instant updatedAt, String state, Instant monitoredAt)
    {
        return row(REPO, NUMBER, updatedAt, state, monitoredAt);
    }

    private static PullRequest row(String repo, int number, Instant updatedAt, String state, Instant monitoredAt)
    {
        return new PullRequest(ID, repo, number, "PR title", "octocat", "https://github.com/owner/repo/pull/7", UPDATED, updatedAt, PullRequest.Origin.OTHER,
                ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), PullRequestDetail.CiStatus.NONE, 1, 0, 0,
                state, "closed".equals(state) ? updatedAt : null, null, true, "clean", null, "feature", monitoredAt, null, null, null);
    }
}
