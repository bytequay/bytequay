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

import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSelectedPullRequestDiffReader
{
    private static final String REPO = "owner/repo";
    private static final int NUMBER = 7;
    private static final long ID = 12;
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private static final Instant UPDATED = NOW.minusSeconds(60);
    private static final String HEAD = "a".repeat(40);
    private static final DiffFile FILE = new DiffFile("a.txt", "modified", 1, 0, "+line");

    private final PullRequestRead route = PullRequestReadFixture.route(REPO, NUMBER);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final PrDiffStore diffs = mock(PrDiffStore.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final SelectedPullRequestReader selected = mock(SelectedPullRequestReader.class);
    private final PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
    private final SelectedPullRequestDiffReader reader = new SelectedPullRequestDiffReader(pullRequests, diffs, details, selected, refresh, Clock.fixed(NOW, ZoneOffset.UTC));

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
        cache(REPO, NUMBER, ImmutableList.of(FILE));
        HostingFailure failure = new HostingFailure(kind, "Access changed");
        when(refresh.refreshDiffFiles(route, null, null)).thenThrow(failure);

        assertThatThrownBy(() -> reader.read(REPO, NUMBER, Optional.empty(), true)).isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cachedArtifactIncludingEmptyNeedsNoRemoteRead(boolean empty)
    {
        List<DiffFile> files = empty ? ImmutableList.of() : ImmutableList.of(FILE);
        cache(REPO, NUMBER, files);
        when(details.find(ID)).thenReturn(Optional.of(detail(HEAD, files.size())));

        SelectedPullRequestDiffReader.Read read = reader.read("OWNER/REPO", NUMBER, Optional.empty(), false);

        assertThat(read.files()).isEqualTo(files);
        assertThat(read.version()).matches("[0-9a-f]{64}");
        assertThat(read.observedHeadSha()).contains(HEAD);
        assertThat(read.sourceUpdatedAt()).contains(UPDATED);
        assertThat(read.reportedChangedFiles()).contains(files.size());
        assertThat(read.readAt()).isEqualTo(NOW);
        assertThat(read.refreshAttempted()).isFalse();
        assertThat(read.refreshFailed()).isFalse();
        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains("does not verify complete upstream coverage"));
        verifyNoInteractions(selected, refresh);
    }

    @Test
    void coldSelectedHydrationReusesDiffPopulatedByThatRefresh()
    {
        when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.empty(), Optional.of(row(REPO, NUMBER)));
        when(selected.read(route, SelectedPullRequestReader.Section.PR)).thenReturn(hydrated());
        when(diffs.find(ID, null, null)).thenReturn(Optional.of(diff(ImmutableList.of(FILE))));
        when(details.find(ID)).thenReturn(Optional.of(detail(HEAD, 1)));

        SelectedPullRequestDiffReader.Read read = reader.read(REPO, NUMBER, Optional.empty(), false);

        assertThat(read.files()).containsExactly(FILE);
        assertThat(read.refreshAttempted()).isTrue();
        verify(selected).read(route, SelectedPullRequestReader.Section.PR);
        verifyNoInteractions(refresh);
    }

    @Test
    void missingDiffRefreshesTheSelectedDefaultThenReadsPersistedBytes()
    {
        when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.of(row(REPO, NUMBER)));
        when(diffs.find(ID, null, null)).thenReturn(Optional.empty(), Optional.of(diff(ImmutableList.of(FILE))));
        when(refresh.refreshDiffFiles(route, null, null)).thenReturn(ImmutableList.of());

        SelectedPullRequestDiffReader.Read read = reader.read(REPO, NUMBER, Optional.empty(), false);

        assertThat(read.files()).containsExactly(FILE);
        assertThat(read.refreshAttempted()).isTrue();
        assertThat(read.refreshFailed()).isFalse();
        verify(refresh).refreshDiffFiles(route, null, null);
        verifyNoInteractions(selected);
    }

    @Test
    void forcedInitialRefreshReturnsTheNewPersistedArtifact()
    {
        cache(REPO, NUMBER, ImmutableList.of(FILE));
        DiffFile changed = new DiffFile("new.txt", "added", 2, 0, "+new");
        when(diffs.find(ID, null, null)).thenReturn(Optional.of(diff(ImmutableList.of(FILE))), Optional.of(diff(ImmutableList.of(changed))));

        SelectedPullRequestDiffReader.Read read = reader.read(REPO, NUMBER, Optional.empty(), true);

        assertThat(read.files()).containsExactly(changed);
        assertThat(read.refreshAttempted()).isTrue();
        assertThat(read.refreshFailed()).isFalse();
        verify(refresh).refreshDiffFiles(route, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh", "missing", "reread", "wrong-row", "duplicate"})
    void forcedRefreshFailureRetainsOriginalBytesAndMetadataDespitePartialWrites(String failure)
    {
        cache(REPO, NUMBER, ImmutableList.of(FILE));
        when(details.find(ID)).thenReturn(Optional.of(detail(HEAD, 1)), Optional.of(detail("b".repeat(40), 2)));
        switch (failure) {
            case "refresh" -> when(refresh.refreshDiffFiles(route, null, null))
                    .thenThrow(new IllegalStateException("Partial write then failed"));
            case "missing" -> when(diffs.find(ID, null, null)).thenReturn(Optional.of(diff(ImmutableList.of(FILE))), Optional.empty());
            case "reread" -> when(diffs.find(ID, null, null)).thenReturn(Optional.of(diff(ImmutableList.of(FILE))))
                    .thenThrow(new IllegalStateException("Cannot reread"));
            case "wrong-row" -> when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.of(row(REPO, NUMBER)), Optional.of(row("other/repo", NUMBER)));
            case "duplicate" -> when(diffs.find(ID, null, null))
                    .thenReturn(Optional.of(diff(ImmutableList.of(FILE))), Optional.of(diff(ImmutableList.of(FILE, FILE))));
            default -> throw new AssertionError(failure);
        }

        SelectedPullRequestDiffReader.Read read = reader.read(REPO, NUMBER, Optional.empty(), true);

        assertThat(read.files()).containsExactly(FILE);
        assertThat(read.observedHeadSha()).contains(HEAD);
        assertThat(read.reportedChangedFiles()).contains(1);
        assertThat(read.refreshAttempted()).isTrue();
        assertThat(read.refreshFailed()).isTrue();
        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains("original cached bytes and metadata"));
        verify(details).find(ID);
    }

    @Test
    void noOriginalArtifactPropagatesRefreshFailure()
    {
        when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.of(row(REPO, NUMBER)));
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unavailable");
        when(refresh.refreshDiffFiles(route, null, null)).thenThrow(failure);

        assertThatThrownBy(() -> reader.read(REPO, NUMBER, Optional.empty(), false)).isSameAs(failure);
    }

    @Test
    void continuationChecksOnlyLocalArtifactAndNeverReadsMutableMetadata()
    {
        cache(REPO, NUMBER, ImmutableList.of(FILE));
        String version = reader.read(REPO, NUMBER, Optional.empty(), false).version();
        clearInvocations(pullRequests, diffs, details);

        SelectedPullRequestDiffReader.Read read = reader.read(REPO, NUMBER, Optional.of(version), false);

        assertThat(read.version()).isEqualTo(version);
        assertThat(read.files()).containsExactly(FILE);
        assertThat(read.observedHeadSha()).isEmpty();
        assertThat(read.sourceUpdatedAt()).isEmpty();
        assertThat(read.reportedChangedFiles()).isEmpty();
        verifyNoInteractions(details, selected, refresh);
        verify(pullRequests, never()).prepareRead(any(), anyInt());
        verify(pullRequests).findStored(REPO, NUMBER);
        verify(diffs).find(ID, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"changed", "missing-diff", "missing-row"})
    void continuationRequiresRestartWhenItsOriginalArtifactIsUnavailable(String change)
    {
        cache(REPO, NUMBER, ImmutableList.of(FILE));
        String version = reader.read(REPO, NUMBER, Optional.empty(), false).version();
        clearInvocations(details);
        switch (change) {
            case "changed" -> when(diffs.find(ID, null, null)).thenReturn(Optional.of(diff(ImmutableList.of())));
            case "missing-diff" -> when(diffs.find(ID, null, null)).thenReturn(Optional.empty());
            case "missing-row" -> when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.empty());
            default -> throw new AssertionError(change);
        }

        assertThatThrownBy(() -> reader.read(REPO, NUMBER, Optional.of(version), false)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)).hasMessageContaining("restart");
        verifyNoInteractions(details, selected, refresh);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "g", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "force"})
    void invalidVersionOrForcedContinuationIsRejectedBeforeIo(String value)
    {
        String version = value.equals("force") ? "a".repeat(64) : value;

        assertThatThrownBy(() -> reader.read(REPO, NUMBER, Optional.of(version), value.equals("force"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pullRequests, diffs, details, selected, refresh);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-patch", "blank-patch", "filename", "status", "additions", "deletions", "order", "repository", "number", "boundaries"})
    void contentVersionIncludesEveryOrderedFieldAndPrIdentity(String change)
    {
        DiffFile second = new DiffFile("bc", "modified", 2, 3, "界");
        List<DiffFile> original = ImmutableList.of(FILE, second);
        cache(REPO, NUMBER, original);
        String version = reader.read(REPO, NUMBER, Optional.empty(), false).version();
        List<DiffFile> changed = switch (change) {
            case "null-patch" -> ImmutableList.of(new DiffFile(FILE.filename(), FILE.status(), 1, 0, null), second);
            case "blank-patch" -> ImmutableList.of(new DiffFile(FILE.filename(), FILE.status(), 1, 0, ""), second);
            case "filename" -> ImmutableList.of(new DiffFile("b.txt", FILE.status(), 1, 0, FILE.patch()), second);
            case "status" -> ImmutableList.of(new DiffFile(FILE.filename(), "added", 1, 0, FILE.patch()), second);
            case "additions" -> ImmutableList.of(new DiffFile(FILE.filename(), FILE.status(), 2, 0, FILE.patch()), second);
            case "deletions" -> ImmutableList.of(new DiffFile(FILE.filename(), FILE.status(), 1, 1, FILE.patch()), second);
            case "order" -> ImmutableList.of(second, FILE);
            case "boundaries" -> ImmutableList.of(FILE, new DiffFile("b", "cmodified", 2, 3, "界"));
            default -> original;
        };
        String repo = change.equals("repository") ? "other/repo" : REPO;
        int number = change.equals("number") ? 8 : NUMBER;
        cache(repo, number, changed);

        assertThat(reader.read(repo, number, Optional.empty(), false).version()).isNotEqualTo(version);
    }

    @Test
    void nullPatchAndEmptyPatchHaveDifferentContentVersions()
    {
        cache(REPO, NUMBER, ImmutableList.of(new DiffFile("a", "modified", 0, 0, null)));
        String missing = reader.read(REPO, NUMBER, Optional.empty(), false).version();
        cache(REPO, NUMBER, ImmutableList.of(new DiffFile("a", "modified", 0, 0, "")));

        assertThat(reader.read(REPO, NUMBER, Optional.empty(), false).version()).isNotEqualTo(missing);
    }

    @Test
    void contentVersionRetainsUnpairedSurrogateIdentityInsteadOfUtf8Replacement()
    {
        cache(REPO, NUMBER, ImmutableList.of(new DiffFile("a", "modified", 1, 0, "?")));
        String replacement = reader.read(REPO, NUMBER, Optional.empty(), false).version();
        cache(REPO, NUMBER, ImmutableList.of(new DiffFile("a", "modified", 1, 0, new String(new char[] {(char) 0xD800}))));

        assertThat(reader.read(REPO, NUMBER, Optional.empty(), false).version()).isNotEqualTo(replacement);
    }

    @Test
    void exposesBoundedMissingPatchAndCountMismatchGapsWithoutHydrating()
    {
        cache(REPO, NUMBER, ImmutableList.of(new DiffFile("x".repeat(20_000), "renamed", 0, 0, null)));
        when(details.find(ID)).thenReturn(Optional.of(detail(HEAD, 99)));

        SelectedPullRequestDiffReader.Read read = reader.read(REPO, NUMBER, Optional.empty(), false);

        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains("1 file(s) have no patch", "cannot be distinguished"))
                .anySatisfy(gap -> assertThat(gap).contains("reports 99", "contains 1"));
        assertThat(read.gaps()).allSatisfy(gap -> assertThat(gap.length()).isLessThan(1000));
        verifyNoInteractions(selected, refresh);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-sha", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", " "})
    void invalidCurrentHeadDoesNotRelabelTheCachedDiffHead(String head)
    {
        cache(REPO, NUMBER, ImmutableList.of(FILE));
        when(details.find(ID)).thenReturn(Optional.of(detail(head, 1)));

        SelectedPullRequestDiffReader.Read read = reader.read(REPO, NUMBER, Optional.empty(), false);

        assertThat(read.observedHeadSha()).contains(HEAD);
        assertThat(read.gaps()).anySatisfy(gap -> assertThat(gap).contains("not linked to the currently observed head"));
    }

    @Test
    void rejectsAmbiguousDuplicatePathsBeforeMetadataOrRefresh()
    {
        cache(REPO, NUMBER, ImmutableList.of(FILE, FILE));

        assertThatThrownBy(() -> reader.read(REPO, NUMBER, Optional.empty(), false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        verifyNoInteractions(details, selected, refresh);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsForeignStoredRowBeforeReadingDiff(boolean wrongNumber)
    {
        when(pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.of(row(wrongNumber ? REPO : "other/repo", wrongNumber ? 8 : NUMBER)));

        assertThatThrownBy(() -> reader.read(REPO, NUMBER, Optional.empty(), false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");
        verifyNoInteractions(diffs, details, selected, refresh);
    }

    @Test
    void invalidRepositoryFailsBeforeStoredLookup()
    {
        assertThatThrownBy(() -> reader.read("not-a-repository", NUMBER, Optional.empty(), false)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pullRequests, diffs, details, selected, refresh);
    }

    private void cache(String repo, int number, List<DiffFile> files)
    {
        when(pullRequests.findStored(repo, number)).thenReturn(Optional.of(row(repo, number)));
        when(diffs.find(ID, null, null)).thenReturn(Optional.of(diff(files)));
        when(details.find(ID)).thenReturn(Optional.of(detail(HEAD, files.size())));
    }

    private static StoredPrDiff diff(List<DiffFile> files)
    {
        return new StoredPrDiff(files, ImmutableList.of(), ImmutableMap.of(), Optional.of("c".repeat(40)), Optional.of(HEAD), Optional.of(UPDATED));
    }

    private static SelectedPullRequestReader.Read hydrated()
    {
        StoredPrDetail stored = detail(HEAD, 1);
        PullRequestDetail projected = PullRequestDetailMapper.toPullRequestDetail(ID, REPO, NUMBER, stored, true);
        return new SelectedPullRequestReader.Read(projected, Optional.of(UPDATED), Optional.of(NOW), Optional.of(HEAD), true, false, false, false);
    }

    private static StoredPrDetail detail(String head, int changedFiles)
    {
        PrRawDetail raw = new PrRawDetail("Description", "https://github.com/owner/repo/pull/7", "closed", "octocat", null, UPDATED, UPDATED, UPDATED,
                "Body", ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, true, "clean", 1, 0, changedFiles, 0,
                ImmutableList.of(), head, "feature", "main", "c".repeat(40));
        return new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
    }

    private static PullRequest row(String repo, int number)
    {
        return new PullRequest(ID, repo, number, "PR title", "octocat", "https://github.com/owner/repo/pull/7", UPDATED, UPDATED, PullRequest.Origin.OTHER,
                ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), PullRequestDetail.CiStatus.NONE, 1, 0, 0,
                "closed", UPDATED, null, true, "clean", null, "feature", null, null, null, null);
    }
}
