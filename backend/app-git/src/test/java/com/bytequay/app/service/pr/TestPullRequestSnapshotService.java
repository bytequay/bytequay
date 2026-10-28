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
import com.bytequay.app.repository.github.GitHubActionsClient.PrCheckRunsFetch;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.hosting.DesktopHostingService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestPullRequestSnapshotService
{
    private static final PullRequestRef REF = PullRequestRef.of("owner", "repo", 7);
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private static final String SCOPE = "desktop-scope";
    private static final Instant UPDATED = Instant.parse("2026-09-20T00:00:00Z");
    private final GitHubPullRequestReadClient gitHub = mock(GitHubPullRequestReadClient.class);
    private final DesktopHostingService desktop = mock(DesktopHostingService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final PrDiffStore diffs = mock(PrDiffStore.class);
    private final GitHubActionsClient actions = mock(GitHubActionsClient.class);
    private final PullRequestSnapshotService snapshots = new PullRequestSnapshotService(gitHub, actions, desktop, details, diffs);

    @BeforeEach
    void setUp()
    {
        when(desktop.scope("pat")).thenReturn(SCOPE);
        when(desktop.withCurrent(eq(SCOPE), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        when(details.findCredentialScope(1L)).thenReturn(Optional.of(SCOPE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void unavailableHeadDoesNotValidateEmptyChecks(String head)
    {
        when(details.find(1L)).thenReturn(Optional.of(new StoredPrDetail(raw(head), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false)));

        assertThatThrownBy(() -> snapshots.refreshChecks("pat", REF, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(502));

        verify(details, never()).saveStatus(anyLong(), any());
        verify(details, never()).updateStatusEtags(anyLong(), any(), any());
    }

    @Test
    void discussionSaveReturnsRetainedPartialSourcesWithoutSavingChecks()
    {
        StoredPrDetail fetched = new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(StoredPrDetail.COMMENTS), false);
        StoredPrDetail retained = stored();
        when(details.find(1L)).thenReturn(Optional.of(retained));
        Instant sourceAt = UPDATED.plusSeconds(10);

        assertThat(snapshots.saveDiscussion(1L, fetched, sourceAt, SCOPE, snapshots.captureRead(REF))).isSameAs(retained);

        verify(details).saveDiscussion(1L, fetched, sourceAt);
        verify(details).setCredentialScope(1L, SCOPE);
        verify(details, never()).save(anyLong(), any());
        verify(details, never()).saveStatus(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedDiffValidatesItsOwnEtagsAndKeepsBoundFileContents(boolean unchangedFiles)
    {
        StoredPrDiff stored = diff();
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(stored));
        when(diffs.findDefaultEtags(1L)).thenReturn(new PrDiffEtags(Optional.of("files"), Optional.of("commits")));
        when(gitHub.fetchPrDiffFiles("pat", REF, stored.files(), Optional.of("files")))
                .thenReturn(new ConditionalList<>(unchangedFiles ? Optional.empty() : Optional.of(stored.files()), Optional.of("files")));
        when(gitHub.fetchPrDiffCommits("pat", REF, stored.commits(), Optional.of("commits")))
                .thenReturn(new ConditionalList<>(Optional.empty(), Optional.of("commits")));
        when(desktop.refresh("pat", REF, true)).thenReturn(coordinates(HEAD));

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
        when(desktop.refresh("pat", REF, true)).thenReturn(coordinates("c".repeat(40)));

        assertThatThrownBy(() -> snapshots.refreshDefaultDiff("pat", REF, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));

        verify(diffs, never()).saveDefault(anyLong(), any(), any());
    }

    @Test
    void validatedDiffReturnsTheSameArtifactItChecked()
    {
        StoredPrDiff matching = diff();
        StoredPrDiff replacement = new StoredPrDiff(ImmutableList.of(new DiffFile("other.txt", "added", 1, 0, "+other")), ImmutableList.of());
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(matching), Optional.of(replacement));

        assertThat(snapshots.getValidatedDiffFiles(1L, HEAD)).isSameAs(matching.files());

        verify(diffs).find(1L, null, null);
        verifyNoMoreInteractions(diffs);
        verifyNoInteractions(gitHub);
    }

    @ParameterizedTest
    @ValueSource(strings = {"base", "head", "missing-base", "missing-head", "missing-detail-base", "unvalidated", "absent"})
    void validatedDiffRejectsMissingOrDifferentCoordinates(String mismatch)
    {
        List<DiffFile> files = diff().files();
        StoredPrDiff cached = new StoredPrDiff(files, ImmutableList.of(), ImmutableMap.of(),
                mismatch.equals("missing-base") ? Optional.empty() : Optional.of(mismatch.equals("base") ? "c".repeat(40) : BASE),
                mismatch.equals("missing-head") ? Optional.empty() : Optional.of(mismatch.equals("head") ? "c".repeat(40) : HEAD),
                mismatch.equals("unvalidated") ? Optional.empty() : Optional.of(UPDATED));
        when(details.find(1L)).thenReturn(Optional.of(new StoredPrDetail(raw(mismatch.equals("missing-detail-base") ? null : BASE, HEAD),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false)));
        when(diffs.find(1L, null, null)).thenReturn(mismatch.equals("absent") ? Optional.empty() : Optional.of(cached));

        assertThatThrownBy(() -> snapshots.getValidatedDiffFiles(1L, HEAD))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode().value()).isEqualTo(409);
                    assertThat(failure.getReason()).contains("refresh the pull request");
                });

        verify(diffs).find(1L, null, null);
        verifyNoMoreInteractions(diffs);
        verifyNoInteractions(gitHub);
        if (!mismatch.equals("absent")) {
            assertThat(snapshots.getDiffFiles(1L, null, null)).isSameAs(files);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void validatedDiffRequiresTheExpectedHead(String invalid)
    {
        assertThatThrownBy(() -> snapshots.getValidatedDiffFiles(1L, invalid)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(diffs, gitHub, details);
    }

    @Test
    void fileBodyUsesCachedCoordinatesAndCannotAttachToAReplacedDefaultDiff()
    {
        StoredPrDiff stored = new StoredPrDiff(diff().files(), ImmutableList.of(), ImmutableMap.of(), Optional.of(BASE), Optional.of(HEAD), Optional.of(UPDATED));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cachedFileBodyIsReusedForTheSameDiffCoordinates(boolean range)
    {
        String base = range ? BASE : null;
        String head = range ? HEAD : null;
        when(diffs.find(1L, base, head)).thenReturn(Optional.of(diff()));

        snapshots.refreshFileContent("pat", REF, 1L, "a.txt", base, head);

        assertThat(snapshots.getFileContent(1L, "a.txt", base, head)).isEqualTo(diff().fileContents().get("a.txt"));
        verifyNoInteractions(gitHub);
        verify(diffs, never()).saveDefaultFileContent(anyLong(), any(), any(), any(), any());
        verify(diffs, never()).saveFileContent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void refreshingTheSameCommitRangeKeepsCachedFileBodies()
    {
        StoredPrDiff stored = diff();
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(diffs.find(1L, BASE, HEAD)).thenReturn(Optional.of(stored));
        when(gitHub.fetchPrDiffFiles("pat", REF, BASE, HEAD)).thenReturn(stored.files());

        snapshots.refreshDiff("pat", REF, 1L, BASE, HEAD);

        ArgumentCaptor<StoredPrDiff> saved = ArgumentCaptor.forClass(StoredPrDiff.class);
        verify(diffs).save(eq(1L), eq(BASE), eq(HEAD), saved.capture());
        assertThat(saved.getValue().fileContents()).isEqualTo(stored.fileContents());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedDiffCoordinatesDiscardCachedFileBodies(boolean changeBase)
    {
        String base = changeBase ? "c".repeat(40) : BASE;
        String head = changeBase ? HEAD : "c".repeat(40);
        StoredPrDiff previous = diff();
        when(details.find(1L)).thenReturn(Optional.of(new StoredPrDetail(raw(base, head), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false)));
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(previous));
        when(gitHub.fetchPrDiffFiles("pat", REF, previous.files(), Optional.empty()))
                .thenReturn(new ConditionalList<>(Optional.of(previous.files()), Optional.empty()));
        when(gitHub.fetchPrDiffCommits("pat", REF, previous.commits(), Optional.empty()))
                .thenReturn(new ConditionalList<>(Optional.of(previous.commits()), Optional.empty()));
        when(desktop.refresh("pat", REF, true)).thenReturn(coordinates(base, head));

        snapshots.refreshDefaultDiff("pat", REF, 1L);

        ArgumentCaptor<StoredPrDiff> saved = ArgumentCaptor.forClass(StoredPrDiff.class);
        verify(diffs).saveDefault(eq(1L), saved.capture(), any());
        assertThat(saved.getValue().fileContents()).isEmpty();
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(saved.getValue()));
        DiffFileContent content = new DiffFileContent("new before", "new after");
        when(gitHub.fetchPrDiffFileContent("pat", REF, "a.txt", base, head)).thenReturn(content);
        when(diffs.saveDefaultFileContent(1L, base, head, "a.txt", content)).thenReturn(true);

        snapshots.refreshFileContent("pat", REF, 1L, "a.txt", null, null);

        verify(gitHub).fetchPrDiffFileContent("pat", REF, "a.txt", base, head);
        verify(diffs).saveDefaultFileContent(1L, base, head, "a.txt", content);
    }

    @Test
    void rangedDiffWithoutMetadataStoresItsCredentialOwnerBeforeFetchingFiles()
    {
        when(details.findCredentialScope(1L)).thenReturn(Optional.empty());
        when(desktop.refresh("pat", REF, false)).thenReturn(coordinates(HEAD));
        when(gitHub.fetchPrDiffFiles("pat", REF, BASE, HEAD)).thenReturn(diff().files());

        snapshots.refreshDiff("pat", REF, 1L, BASE, HEAD);

        InOrder order = inOrder(desktop, details, gitHub, diffs);
        order.verify(desktop).refresh("pat", REF, false);
        order.verify(details).saveMetadata(eq(1L), any());
        order.verify(details).setCredentialScope(1L, SCOPE);
        order.verify(gitHub).fetchPrDiffFiles("pat", REF, BASE, HEAD);
        order.verify(diffs).save(eq(1L), eq(BASE), eq(HEAD), any());
    }

    @Test
    void credentialRotationDuringRangedDiffFetchRejectsTheOldBytes()
    {
        AtomicBoolean current = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (!current.get()) {
                throw new HostingFailure(REVISION_CONFLICT, "Credential changed");
            }
            return invocation.<Supplier<?>>getArgument(1).get();
        }).when(desktop).withCurrent(eq(SCOPE), any());
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(gitHub.fetchPrDiffFiles("pat", REF, BASE, HEAD)).thenAnswer(invocation -> {
            current.set(false);
            return diff().files();
        });

        assertThatThrownBy(() -> snapshots.refreshDiff("pat", REF, 1L, BASE, HEAD))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));

        verify(diffs, never()).save(anyLong(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"checks,401", "checks,403", "checks,404", "files,401", "files,403", "files,404", "commits,401", "commits,403", "commits,404",
            "range,401", "range,403", "range,404", "content,401", "content,403", "content,404"})
    void nativeAccessRejectionClearsRemoteProjection(String source, int status)
    {
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(diff()));
        when(diffs.findDefaultEtags(1L)).thenReturn(PrDiffEtags.none());
        ResponseStatusException failure = new ResponseStatusException(HttpStatusCode.valueOf(status), "Access rejected");
        Runnable refresh = switch (source) {
            case "checks" -> {
                when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq(HEAD), any(), any(), any())).thenThrow(failure);
                yield () -> snapshots.refreshChecks("pat", REF, 1L);
            }
            case "files", "commits" -> {
                if (source.equals("files")) {
                    when(gitHub.fetchPrDiffFiles(eq("pat"), eq(REF), anyList(), any())).thenThrow(failure);
                }
                else {
                    when(gitHub.fetchPrDiffCommits(eq("pat"), eq(REF), any(), any())).thenThrow(failure);
                }
                yield () -> snapshots.refreshDefaultDiff("pat", REF, 1L);
            }
            case "range" -> {
                when(gitHub.fetchPrDiffFiles("pat", REF, BASE, HEAD)).thenThrow(failure);
                yield () -> snapshots.refreshDiff("pat", REF, 1L, BASE, HEAD);
            }
            case "content" -> {
                when(gitHub.fetchPrDiffFileContent("pat", REF, "new.txt", BASE, HEAD)).thenThrow(failure);
                yield () -> snapshots.refreshFileContent("pat", REF, 1L, "new.txt", null, null);
            }
            default -> throw new AssertionError(source);
        };

        assertThatThrownBy(refresh::run).isSameAs(failure);

        verify(details).clearRemote(1L);
        verify(diffs).deleteByPrIds(ImmutableSet.of(1L));
        verify(details, never()).saveStatus(anyLong(), any());
        verify(diffs, never()).saveDefault(anyLong(), any(), any());
        verify(diffs, never()).save(anyLong(), any(), any(), any());
        verify(diffs, never()).saveDefaultFileContent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void transientNativeFailureKeepsRemoteProjection()
    {
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        ResponseStatusException failure = new ResponseStatusException(HttpStatusCode.valueOf(503), "Temporarily unavailable");
        when(gitHub.fetchPrDiffFiles("pat", REF, BASE, HEAD)).thenThrow(failure);

        assertThatThrownBy(() -> snapshots.refreshDiff("pat", REF, 1L, BASE, HEAD)).isSameAs(failure);

        verify(details, never()).clearRemote(anyLong());
        verify(diffs, never()).deleteByPrIds(any());
    }

    @Test
    void oldNativeRejectionCannotClearReplacementProjection()
    {
        AtomicBoolean current = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (!current.get()) {
                throw new HostingFailure(REVISION_CONFLICT, "Credential changed");
            }
            return invocation.<Supplier<?>>getArgument(1).get();
        }).when(desktop).withCurrent(eq(SCOPE), any());
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(gitHub.fetchPrDiffFiles("pat", REF, BASE, HEAD)).thenAnswer(invocation -> {
            current.set(false);
            throw new ResponseStatusException(HttpStatusCode.valueOf(403), "Access rejected");
        });

        assertThatThrownBy(() -> snapshots.refreshDiff("pat", REF, 1L, BASE, HEAD))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));

        verify(details, never()).clearRemote(anyLong());
        verify(diffs, never()).deleteByPrIds(any());
    }

    @Test
    void writeDuringCheckFetchCannotRestoreOldChecks()
    {
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq(HEAD), any(), any(), any())).thenAnswer(ignored -> {
            snapshots.write(REF, () -> {});
            return new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty());
        });

        assertThatThrownBy(() -> snapshots.refreshChecks("pat", REF, 1L))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        verify(details, never()).saveStatus(anyLong(), any());
    }

    @Test
    void writeDuringDiffFetchCannotRestoreOldBytes()
    {
        when(details.find(1L)).thenReturn(Optional.of(stored()));
        when(gitHub.fetchPrDiffFiles("pat", REF, BASE, HEAD)).thenAnswer(ignored -> {
            snapshots.write(REF, () -> {});
            return diff().files();
        });

        assertThatThrownBy(() -> snapshots.refreshDiff("pat", REF, 1L, BASE, HEAD))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        verify(diffs, never()).save(anyLong(), any(), any(), any());
    }

    @Test
    void writeDuringFileFetchCannotRestoreOldContent()
    {
        when(diffs.find(1L, null, null)).thenReturn(Optional.of(diff()));
        when(gitHub.fetchPrDiffFileContent("pat", REF, "new.txt", BASE, HEAD)).thenAnswer(ignored -> {
            snapshots.write(REF, () -> {});
            return new DiffFileContent("old", "old");
        });

        assertThatThrownBy(() -> snapshots.refreshFileContent("pat", REF, 1L, "new.txt", null, null))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        verify(diffs, never()).saveDefaultFileContent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void evictionFailureCannotMaskWriteResultOrKeepReadsCurrent()
    {
        PullRequestReadGuard.Read old = snapshots.captureRead(REF);
        doThrow(new IllegalStateException("database busy")).when(desktop).evict("owner/repo", 7);

        assertThat(snapshots.write(REF, () -> "accepted")).isEqualTo("accepted");
        assertThatThrownBy(() -> old.accept(() -> "stale")).isInstanceOf(HostingFailure.class);
        assertThat(snapshots.captureRead(REF).accept(() -> "current")).isEqualTo("current");
    }

    private static HostingPullRequestSnapshot coordinates(String head)
    {
        return coordinates(BASE, head);
    }

    private static HostingPullRequestSnapshot coordinates(String base, String head)
    {
        PrRawDetail detail = raw(base, head);
        FetchedPullRequest summary = new FetchedPullRequest(77L, REF.repoFullName(), REF.number(), detail.title(), detail.author(), detail.htmlUrl(), UPDATED, UPDATED,
                PullRequest.Origin.OTHER, ImmutableList.of(), ImmutableMap.of(), false, "open", null, null, ImmutableList.of(), true, "clean", "feature");
        return new HostingPullRequestSnapshot(new HostingPullRequest(100, 200, REF.number(), "77"), 300, SCOPE, 1, summary, detail, Optional.empty(), UPDATED);
    }

    private static StoredPrDetail stored()
    {
        return new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false);
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
        return raw(BASE, head);
    }

    private static PrRawDetail raw(String base, String head)
    {
        return new PrRawDetail("Title", "https://github.com/owner/repo/pull/7", "open", "alice", null, UPDATED, UPDATED, null, "body", ImmutableList.of(),
                ImmutableMap.of(), ImmutableList.of(), null, false, true, "clean", 1, 0, 1, 0, ImmutableList.of(), head, "feature", "main", base);
    }
}
