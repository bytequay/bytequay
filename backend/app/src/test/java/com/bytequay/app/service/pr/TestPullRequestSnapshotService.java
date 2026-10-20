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

import com.bytequay.app.repository.github.ConditionalList;
import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubPullRequestDetailResponse;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestPullRequestSnapshotService
{
    private static final PullRequestRef REF = PullRequestRef.of("owner", "repo", 7);
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private static final Instant UPDATED = Instant.parse("2026-09-20T00:00:00Z");
    private final GitHubPullRequestReadClient gitHub = mock(GitHubPullRequestReadClient.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final PrDiffStore diffs = mock(PrDiffStore.class);
    private final PullRequestSnapshotService snapshots = new PullRequestSnapshotService(gitHub, mock(GitHubActionsClient.class), details, diffs);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void unavailableHeadDoesNotValidateEmptyChecks(String head)
    {
        when(details.find(1L)).thenReturn(Optional.of(new StoredPrDetail(raw(head), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of())));

        assertThatThrownBy(() -> snapshots.refreshChecks("pat", REF, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(502));

        verify(details, never()).saveStatus(anyLong(), any());
        verify(details, never()).updateStatusEtags(anyLong(), any(), any());
    }

    @Test
    void discussionSaveReturnsRetainedPartialSourcesWithoutSavingChecks()
    {
        StoredPrDetail fetched = new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(StoredPrDetail.COMMENTS));
        StoredPrDetail retained = stored();
        when(details.find(1L)).thenReturn(Optional.of(retained));
        Instant sourceAt = UPDATED.plusSeconds(10);

        assertThat(snapshots.saveDiscussion(1L, fetched, sourceAt)).isSameAs(retained);

        verify(details).saveDiscussion(1L, fetched, sourceAt);
        verify(details, never()).save(anyLong(), any());
        verify(details, never()).saveStatus(anyLong(), any());
    }

    @Test
    void unchangedDiffValidatesItsOwnEtagsAndKeepsBoundFileContents()
    {
        StoredPrDiff stored = diff();
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(stored));
        when(diffs.findDefaultEtags(1L)).thenReturn(new PrDiffEtags(Optional.of("files"), Optional.of("commits")));
        when(gitHub.fetchPrDiffFiles("pat", REF, stored.files(), Optional.of("files")))
                .thenReturn(new ConditionalList<>(Optional.empty(), Optional.of("files")));
        when(gitHub.fetchPrDiffCommits("pat", REF, stored.commits(), Optional.of("commits")))
                .thenReturn(new ConditionalList<>(Optional.empty(), Optional.of("commits")));
        when(gitHub.getPullRequest("pat", REF, Optional.empty())).thenReturn(coordinates(HEAD));

        snapshots.refreshDefaultDiff("pat", REF, 1L);

        ArgumentCaptor<StoredPrDiff> saved = ArgumentCaptor.forClass(StoredPrDiff.class);
        verify(diffs).saveDefault(eq(1L), saved.capture(), any());
        assertThat(saved.getValue().baseSha()).contains(BASE);
        assertThat(saved.getValue().headSha()).contains(HEAD);
        assertThat(saved.getValue().fileContents()).isEqualTo(stored.fileContents());
        assertThat(saved.getValue().validatedAt()).hasValueSatisfying(at -> assertThat(at).isAfter(UPDATED));
    }

    @Test
    void changedHeadDuringDiffFetchKeepsThePreviousArtifactAndCoordinates()
    {
        StoredPrDiff stored = diff();
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(stored));
        when(diffs.findDefaultEtags(1L)).thenReturn(PrDiffEtags.none());
        when(gitHub.fetchPrDiffFiles("pat", REF, stored.files(), Optional.empty()))
                .thenReturn(new ConditionalList<>(Optional.of(stored.files()), Optional.of("new-files")));
        when(gitHub.fetchPrDiffCommits("pat", REF, stored.commits(), Optional.empty()))
                .thenReturn(new ConditionalList<>(Optional.of(stored.commits()), Optional.of("new-commits")));
        when(gitHub.getPullRequest("pat", REF, Optional.empty())).thenReturn(coordinates("c".repeat(40)));

        assertThatThrownBy(() -> snapshots.refreshDefaultDiff("pat", REF, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));

        verify(diffs, never()).saveDefault(anyLong(), any(), any());
    }

    @Test
    void fileBodyUsesCachedCoordinatesAndCannotAttachToAReplacedDefaultDiff()
    {
        StoredPrDiff stored = diff();
        DiffFileContent content = new DiffFileContent("before", "after");
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(stored));
        when(gitHub.fetchPrDiffFileContent("pat", REF, "a.txt", BASE, HEAD)).thenReturn(content);
        when(diffs.saveDefaultFileContent(1L, BASE, HEAD, "a.txt", content)).thenReturn(false);

        assertThatThrownBy(() -> snapshots.refreshFileContent("pat", REF, 1L, "a.txt", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));

        verify(gitHub).fetchPrDiffFileContent("pat", REF, "a.txt", BASE, HEAD);
        verifyNoMoreInteractions(gitHub);
        verify(diffs, never()).saveFileContent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void aSameHeadWithADifferentBaseDoesNotReuseTheDiff()
    {
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(new StoredPrDiff(ImmutableList.of(), ImmutableList.of(), ImmutableMap.of(),
                Optional.of("d".repeat(40)), Optional.of(HEAD), Optional.of(UPDATED))));

        assertThat(snapshots.defaultDiffMatches(1L, raw())).isFalse();
    }

    private static PullRequestFetch coordinates(String head)
    {
        GitHubPullRequestDetailResponse response = new GitHubPullRequestDetailResponse(1L, 7, null, null, null, null, null, null, null, null,
                null, ImmutableList.of(), ImmutableList.of(), null, false, 0, 0, 0, ImmutableList.of(), ImmutableList.of(),
                new GitHubPullRequestDetailResponse.Head(head, "feature"), new GitHubPullRequestDetailResponse.Base(BASE, "main"), null, null);
        return new PullRequestFetch(Optional.empty(), Optional.empty(), Optional.of(response));
    }

    private static StoredPrDetail stored()
    {
        return new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    private static StoredPrDiff diff()
    {
        return new StoredPrDiff(ImmutableList.of(new DiffFile("a.txt", "modified", 1, 0, "+line")), ImmutableList.of(),
                ImmutableMap.of("a.txt", new DiffFileContent("before", "after")), Optional.of(BASE), Optional.of(HEAD), Optional.of(UPDATED));
    }

    private static PrRawDetail raw()
    {
        return raw(HEAD);
    }

    private static PrRawDetail raw(String head)
    {
        return new PrRawDetail("Title", "https://github.com/owner/repo/pull/7", "open", "alice", null, UPDATED, UPDATED, null, "body", ImmutableList.of(),
                ImmutableMap.of(), ImmutableList.of(), null, false, true, "clean", 1, 0, 1, 0, ImmutableList.of(), head, "feature", "main", BASE);
    }
}
