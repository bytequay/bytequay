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

import com.bytequay.app.config.WriteVersion;
import com.bytequay.app.repository.github.ConditionalList;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubActionsClient.PrCheckRunsFetch;
import com.bytequay.app.repository.github.GitHubNotificationClient;
import com.bytequay.app.repository.github.GitHubNotificationRef;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestSummary;
import com.bytequay.app.repository.github.GitHubPullRequestSummaryClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.repository.github.actions.CiHeadSnapshot;
import com.bytequay.app.repository.github.actions.CiJob;
import com.bytequay.app.repository.github.actions.CiRerunResult;
import com.bytequay.app.repository.github.actions.CiWorkflowRun;
import com.bytequay.app.repository.github.actions.RerunFailedJobsCommand;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingPullRequestService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrCommitLine;
import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetailRefresh;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.RepositoryPickerUser;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.hosting.DiscussionProbe;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestPullRequestService
{
    @Mock private GitHubNotificationClient notifications;
    @Mock private AppSettingsStore syncSettings;
    @Mock private WorkspaceIssueService issues;
    @Mock private GitHubPullRequestReadClient gitHub;
    @Mock private GitHubPullRequestSummaryClient summaries;
    @Mock private GitHubPullRequestWriteClient writes;
    @Mock private GitHubRepoClient repositories;
    @Mock private GitHubActionsClient actions;
    @Mock private GitHubAccountClient accounts;
    @Mock private PullRequestStore store;
    @Mock private PrDetailStore detailStore;
    private final WriteVersion writeVersion = new WriteVersion();
    @Mock private PrDiffStore diffStore;
    @Mock private PrViewStateStore viewStateStore;
    @Mock private CredentialService credentialService;
    @Mock private HostingConnectionService connections;
    @Mock private HostingPullRequestService requests;
    private HostingReadSession session;
    @Mock private WorkspaceHostingService workspaceHosting;
    @Mock private WorkspaceMetadataService metadata;
    @Mock private WorkspaceStore workspaces;
    @Mock private RecentTouchRecorder recent;
    @Mock private WebServerApplicationContext applicationContext;
    @Mock private WebServer server;

    private PullRequestService service;
    private PullRequestSnapshotService snapshots;

    private static final Instant REVIEWED_AT = Instant.parse("2026-08-27T00:00:00Z");

    @BeforeEach
    void setUp()
    {
        snapshots = new PullRequestSnapshotService(gitHub, actions, requests, detailStore, diffStore);
        service = newService(Clock.systemUTC());
        HostingInstance instance = new HostingInstance(1, GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
        session = new HostingReadSession(10, "desktop-scope", new HostingAccess(instance, "pat"));
        lenient().when(credentialService.captureHostingRead()).thenReturn(session);
        lenient().when(connections.account(session)).thenReturn(new HostingAccount(10, 1, "100", "me"));
        lenient().when(connections.resolveRepository(eq(session), anyString())).thenAnswer(invocation -> hostingRepository(invocation.getArgument(1)));
        lenient().when(connections.findRepository(eq(session), anyString())).thenAnswer(invocation -> Optional.of(hostingRepository(invocation.getArgument(1))));
        lenient().when(detailStore.findCredentialScope(anyLong())).thenReturn(Optional.of(read(PullRequestRef.of("owner", "repo", 7)).scope()));
        org.mockito.Mockito.lenient().when(diffStore.findDefaultEtags(anyLong())).thenReturn(PrDiffEtags.none());
        // Without stored ETags every source answers with a body.
        lenient().when(gitHub.probeDiscussion(anyString(), any(), any())).thenReturn(new DiscussionProbe(false, ImmutableMap.of()));
        org.mockito.Mockito.lenient().when(credentialService.resolveGitHubToken()).thenReturn("pat");
        lenient().when(credentialService.resolveGitHubCredential()).thenReturn(
                new GitHubCredential("pat", new UserProfile("me", "Me", "", "", 0, 0, 0, "", "", "", "")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"me", "ME"})
    void attentionRestoresSavedApprovalBeforeStartupSyncWithoutWriting(String login)
    {
        PullRequest row = attentionPullRequest(12, REVIEWED_AT, REVIEWED_AT.plusSeconds(30), REVIEWED_AT.plusSeconds(60),
                PullRequestDetail.CiStatus.FAILING, REVIEWED_AT.plusSeconds(3600));
        PrReviewState review = new PrReviewState(1L, "me", null, false, "APPROVED", REVIEWED_AT, null, null, "reviewed-head");
        cacheClearedApproval(row, ImmutableList.of(review));
        when(credentialService.cachedLogin()).thenReturn(Optional.ofNullable(login));
        // Summary/check activity can be newer than an otherwise fully current discussion snapshot.
        when(store.findDiscussionActivityAt(12L)).thenReturn(Optional.of(REVIEWED_AT.plusSeconds(60)));

        PullRequest projected = service.listAttentionCandidates().getFirst();

        assertThat(projected.handledAction()).isEqualTo(HandledAction.APPROVED);
        assertThat(projected).usingRecursiveComparison().ignoringFields("handledAction").isEqualTo(row);
        assertThat(row.handledAction()).isNull();
        verify(viewStateStore).findByPrIds(ImmutableSet.of(12L));
        verifyNoMoreInteractions(viewStateStore);
        verify(credentialService).cachedLogin();
        verifyNoMoreInteractions(credentialService);
        verify(detailStore).findFreshness(12L);
        verify(detailStore).find(12L);
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, summaries, writes, notifications, accounts, actions, diffStore);
    }

    @Test
    void attentionReusesTimelineActivityUntilATimelineIsWritten()
    {
        PullRequest row = storedPullRequest(pullRequestNumber(91));
        when(detailStore.findLatestActivities(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of());

        service.findCachedLatestActivities(ImmutableList.of(row));
        writeVersion.written("pull_requests");
        service.findCachedLatestActivities(ImmutableList.of(row));
        verify(detailStore).findLatestActivities(ImmutableSet.of(row.id()));
        writeVersion.written("pr_timeline");
        service.findCachedLatestActivities(ImmutableList.of(row));
        verify(detailStore, times(2)).findLatestActivities(ImmutableSet.of(row.id()));
    }

    @Test
    void attentionVersionChangesWithInMemoryInputsTheStoreCannotSee()
    {
        when(credentialService.cachedLogin()).thenReturn(Optional.of("me"));
        when(notifications.mentions("me")).thenReturn(ImmutableSet.of());
        String unchanged = service.attentionVersion();

        assertThat(service.attentionVersion()).isEqualTo(unchanged);
        when(notifications.mentions("me")).thenReturn(ImmutableSet.of(PullRequestRef.of("owner", "repo", 12)));
        assertThat(service.attentionVersion()).isNotEqualTo(unchanged);
        when(credentialService.cachedLogin()).thenReturn(Optional.empty());
        assertThat(service.attentionVersion()).isNotEqualTo(unchanged);
    }

    @Test
    void attentionVersionMovesOnlyWithTheTablesAttentionReads()
    {
        String unchanged = service.attentionVersion();

        // A running agent writes its event log and turns many times a minute.
        writeVersion.written("agent_event_outbox");
        writeVersion.written("agent_turn");
        writeVersion.written();
        assertThat(service.attentionVersion()).isEqualTo(unchanged);
        writeVersion.written("pr_view_state");
        String viewed = service.attentionVersion();
        assertThat(viewed).isNotEqualTo(unchanged);
        writeVersion.written(WriteVersion.UNNAMED);
        assertThat(service.attentionVersion()).isNotEqualTo(viewed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"different-viewer", "ambiguous-viewer", "ambiguous-viewer-comment", "different-head", "different-time", "unknown-time"})
    void attentionDoesNotGuessAnApprovalFromUnmatchedReviewEvidence(String mismatch)
    {
        PullRequest row = attentionPullRequest(12, REVIEWED_AT, null, null, null);
        Instant submittedAt = switch (mismatch) {
            case "different-time" -> REVIEWED_AT.minusMillis(100);
            case "unknown-time" -> null;
            default -> REVIEWED_AT;
        };
        PrReviewState review = new PrReviewState(1L, "me", null, false, mismatch.equals("ambiguous-viewer-comment") ? "COMMENTED" : "APPROVED", submittedAt, null, null,
                mismatch.equals("different-head") ? "another-head" : "reviewed-head");
        cacheClearedApproval(row, mismatch.startsWith("ambiguous-viewer") ? ImmutableList.of(review,
                new PrReviewState(2L, "other", null, false, "APPROVED", REVIEWED_AT, null, null, "reviewed-head")) : ImmutableList.of(review));
        if (mismatch.equals("different-viewer")) {
            when(credentialService.cachedLogin()).thenReturn(Optional.of("other"));
        }

        assertThat(service.listAttentionCandidates()).containsExactly(row);
        verify(viewStateStore, never()).markReviewed(anyLong(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"COMMENTED,60,false", "CHANGES_REQUESTED,60,false", "DISMISSED,60,false", "APPROVED,60,false", "COMMENTED,0,false", "COMMENTED,,false", "PENDING,60,true"})
    void attentionRespectsReviewEvidenceAfterTheSavedApproval(String reviewState, Long offset, boolean restored)
    {
        PullRequest row = attentionPullRequest(12, REVIEWED_AT, null, null, null);
        cacheClearedApproval(row, ImmutableList.of(
                new PrReviewState(1L, "me", null, false, "APPROVED", REVIEWED_AT, null, null, "reviewed-head"),
                new PrReviewState(2L, "me", null, false, reviewState, offset == null ? null : REVIEWED_AT.plusSeconds(offset), null, null, "head")));

        assertThat(service.listAttentionCandidates().getFirst().handledAction()).isEqualTo(restored ? HandledAction.APPROVED : null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "older-source", "new-notification", "missing-detail", "reviews", "timeline"})
    void attentionRequiresCompleteCurrentDiscussionBeforeRecoveringApproval(String missing)
    {
        PullRequest row = attentionPullRequest(12, REVIEWED_AT, null, null, null);
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(row));
        when(viewStateStore.findByPrIds(ImmutableSet.of(12L)))
                .thenReturn(ImmutableMap.of(12L, new PrViewState(12L, null, REVIEWED_AT, null, "reviewed-head")));
        Instant sourceAt = missing.equals("older-source") ? REVIEWED_AT.minusSeconds(1) : REVIEWED_AT;
        when(detailStore.findFreshness(12L)).thenReturn(missing.equals("unknown") ? PrDetailFreshness.unknown()
                : new PrDetailFreshness(Optional.empty(), Optional.of(REVIEWED_AT.plusSeconds(120)), Optional.empty(), Optional.of(sourceAt)));
        if (missing.equals("new-notification")) {
            when(store.findDiscussionActivityAt(12L)).thenReturn(Optional.of(REVIEWED_AT.plusSeconds(1)));
        }
        if (missing.equals("reviews") || missing.equals("timeline")) {
            when(detailStore.find(12L)).thenReturn(Optional.of(new StoredPrDetail(raw(),
                    ImmutableList.of(new PrReviewState(1L, "me", null, false, "APPROVED", REVIEWED_AT, null, null, "reviewed-head")),
                    ImmutableList.of(), ImmutableList.of(), ImmutableList.of(missing), true)));
        }

        assertThat(service.listAttentionCandidates()).containsExactly(row);
        verify(viewStateStore, never()).markReviewed(anyLong(), any(), any(), any());
        verifyNoInteractions(gitHub, summaries, writes);
    }

    @ParameterizedTest
    @CsvSource({"me,true,false", "me,false,true", "other,true,true"})
    void attentionKeepsActualReviewRequestsAheadOfCachedApproval(String requestedReviewer, boolean active, boolean restored)
    {
        PullRequest row = attentionPullRequest(12, REVIEWED_AT, null, null, null);
        cacheClearedApproval(row, ImmutableList.of());
        PrRawDetail raw = new PrRawDetail(null, null, "open", "alice", null, null, REVIEWED_AT, null, "body", ImmutableList.of(), null, ImmutableList.of(), null,
                false, true, "clean", 4, 2, 1, active ? 1 : 0, active ? ImmutableList.of("me") : ImmutableList.of(), "head", "feature", "main", "base");
        when(detailStore.find(12L)).thenReturn(Optional.of(new StoredPrDetail(raw,
                ImmutableList.of(new PrReviewState(1L, "me", null, false, "APPROVED", REVIEWED_AT, null, null, "reviewed-head")),
                ImmutableList.of(new PrTimelineEvent(2L, "review_requested", "alice", REVIEWED_AT.plusSeconds(60), null,
                        PrTimelinePayload.builder().requestedReviewer(requestedReviewer).build(), Optional.empty())),
                ImmutableList.of(), ImmutableList.of(), true)));

        assertThat(service.listAttentionCandidates().getFirst().handledAction()).isEqualTo(restored ? HandledAction.APPROVED : null);
    }

    @Test
    void attentionDoesNotRecoverOverACanonicalActiveReviewRequest()
    {
        PullRequest row = new PullRequest(12L, "owner/repo", 12, "Title", "alice", "https://github.com/owner/repo/pull/12", REVIEWED_AT, REVIEWED_AT,
                PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of(), ImmutableMap.of(), false, null, REVIEWED_AT, null, ImmutableList.of("ME"),
                null, 0, 0, 0, "open", null, null, null, null, null, "feature", null, null, REVIEWED_AT.plusSeconds(60), null);
        cacheClearedApproval(row, ImmutableList.of(new PrReviewState(1L, "me", null, false, "APPROVED", REVIEWED_AT, null, null, "reviewed-head")));

        assertThat(service.listAttentionCandidates()).containsExactly(row);
    }

    @Test
    void attentionLeavesExistingActionsAndUnreviewedRowsUnchanged()
    {
        PullRequest handled = new PullRequest(12L, "owner/repo", 12, "Title", "alice", "https://github.com/owner/repo/pull/12", REVIEWED_AT, REVIEWED_AT,
                PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of(), ImmutableMap.of(), false, null, REVIEWED_AT, HandledAction.CHANGES_REQUESTED, ImmutableList.of(),
                null, 0, 0, 0, "open", null, null, null, null, null, "feature", null, null, null, null);
        PullRequest unreviewed = attentionPullRequest(13, null, REVIEWED_AT, null, null);
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(handled, unreviewed));

        assertThat(service.listAttentionCandidates()).containsExactly(handled, unreviewed);
        verifyNoInteractions(viewStateStore, detailStore, credentialService, gitHub, summaries, writes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void attentionStopsRecoveringApprovalAfterThisSessionReconcilesARequestOrDismissal(boolean dismissed)
    {
        service = newService(Clock.fixed(REVIEWED_AT, ZoneOffset.UTC));
        PullRequest row = attentionPullRequest(12, REVIEWED_AT, null, null, null);
        PrReviewState approval = new PrReviewState(1L, "me", null, false, "APPROVED", REVIEWED_AT, null, null, "reviewed-head");
        cacheClearedApproval(row, ImmutableList.of(approval));
        assertThat(service.listAttentionCandidates().getFirst().handledAction()).isEqualTo(HandledAction.APPROVED);
        verify(viewStateStore, never()).markReviewed(anyLong(), any(), any(), any());
        verifyNoInteractions(gitHub, summaries, writes);

        AtomicReference<PrViewState> state = new AtomicReference<>(new PrViewState(12L, null, REVIEWED_AT, HandledAction.APPROVED, "reviewed-head"));
        when(viewStateStore.findByPrIds(ImmutableSet.of(12L))).thenAnswer(ignored -> ImmutableMap.of(12L, state.get()));
        doAnswer(ignored -> {
            state.set(new PrViewState(12L, null, REVIEWED_AT, null, "reviewed-head"));
            return null;
        }).when(viewStateStore).markNeedsReview(12L);
        FetchedPullRequest fetched = pullRequestNumber(12);
        when(requests.refreshPage(eq(session), eq(20L), anyBoolean(), eq(1), eq(100)))
                .thenReturn(new PullRequestListPage(ImmutableList.of(fetched), false));
        when(store.saveObserved(any())).thenReturn(row);
        PrReviewState latest = dismissed ? new PrReviewState(1L, "me", null, false, "DISMISSED", REVIEWED_AT, null, null, "reviewed-head") : approval;
        when(summaries.fetchSummaries(eq("pat"), anyList(), eq("me"))).thenReturn(ImmutableList.of(new GitHubPullRequestSummary(
                fetched, PullRequestDetail.CiStatus.PASSING, 4, 2, 1, "head", Optional.of(REVIEWED_AT), Optional.empty(),
                Optional.of(latest), dismissed ? Optional.empty() : Optional.of(REVIEWED_AT.plusSeconds(60)), "v2", fetched.updatedAt(), Optional.empty())));

        service.syncRepository(RepoRef.of("owner", "repo"));

        assertThat(service.listAttentionCandidates()).containsExactly(row);
        verify(viewStateStore).markNeedsReview(12L);
        verify(detailStore).find(12L);
        verify(detailStore).findFreshness(12L);
        verifyNoMoreInteractions(detailStore);
    }

    private void cacheClearedApproval(PullRequest row, List<PrReviewState> reviews)
    {
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(row));
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id())))
                .thenReturn(ImmutableMap.of(row.id(), new PrViewState(row.id(), null, row.reviewedAt(), null, "reviewed-head")));
        when(detailStore.findFreshness(row.id())).thenReturn(new PrDetailFreshness(Optional.empty(), Optional.of(REVIEWED_AT.plusSeconds(120)),
                Optional.empty(), Optional.of(REVIEWED_AT.plusSeconds(60))));
        when(detailStore.find(row.id())).thenReturn(Optional.of(new StoredPrDetail(raw(), reviews, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true)));
    }

    @Test
    void exactLookupReusesTheSelectedCredentialSnapshotAndPreservesLocalIdentity()
    {
        PullRequest cached = storedPullRequest();
        when(requests.cached(session, 20, 7)).thenReturn(Optional.of(snapshot(pullRequest())));
        when(store.saveObserved(pullRequest().withViewerOrigin("me"))).thenReturn(cached);

        assertThat(service.lookupPullRequest("owner/repo", 7)).isEqualTo(cached);

        verify(requests, never()).refresh(any(), anyLong(), anyInt(), anyBoolean());
        verifyNoInteractions(gitHub, viewStateStore, detailStore, diffStore);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exactLookupKeepsTheExplicitAccountForCacheAndRefresh(boolean cached)
    {
        HostingInstance instance = new HostingInstance(1, GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
        HostingReadSession selected = new HostingReadSession(11, "connected-account", new HostingAccess(instance, "selected-token"));
        PullRequestRead route = new PullRequestRead(selected, hostingRepository("owner/repo"), 7, "alice");
        HostingPullRequestSnapshot metadata = new HostingPullRequestSnapshot(new HostingPullRequest(112, 20, 7, pullRequest().githubId().toString()), 11, "connected-account",
                pullRequest(), raw(), Optional.of("selected-etag"), Instant.now());
        PullRequest row = storedPullRequest();
        when(requests.cached(selected, 20, 7)).thenReturn(cached ? Optional.of(metadata) : Optional.empty());
        if (!cached) {
            when(requests.refresh(selected, 20, 7, false)).thenReturn(metadata);
        }
        when(store.saveObserved(pullRequest().withViewerOrigin("alice"))).thenReturn(row);

        assertThat(service.lookupPullRequest(route)).isEqualTo(row);

        verify(requests, times(cached ? 0 : 1)).refresh(selected, 20, 7, false);
        verifyNoInteractions(credentialService, connections, gitHub, detailStore, diffStore, viewStateStore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://gitlab.com", "https://gitlab.example"})
    void legacyLookupRejectsAnotherServerBeforeReadingOrProjecting(String server)
    {
        HostingInstance instance = new HostingInstance(2, GITLAB, URI.create(server), URI.create(server + "/api/v4"));
        HostingReadSession selected = new HostingReadSession(11, "connected-account", new HostingAccess(instance, "gitlab-token"));
        URI url = URI.create(server + "/group/repo");
        HostingRepository repository = new HostingRepository(21, 2, "200", "group/repo", url, url, Optional.empty(), Optional.of("main"));
        PullRequestRead route = new PullRequestRead(selected, repository, 7, "alice");

        assertThatThrownBy(() -> service.lookupPullRequest(route)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GitHub.com");

        verifyNoInteractions(credentialService, connections, requests, store, detailStore, diffStore, gitHub, accounts, actions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "closed", "merged"})
    void exactLookupFetchesAndCachesOnlyTheMissingPullRequest(String state)
    {
        FetchedPullRequest fetched = pullRequest(state, REVIEWED_AT, null).withViewerOrigin("me");
        PullRequest row = storedPullRequest(fetched);
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(requests.refresh(session, 20, ref.number(), false)).thenReturn(snapshot(fetched));
        when(store.saveObserved(fetched)).thenReturn(row);

        assertThat(service.lookupPullRequest("owner/repo", 7)).isEqualTo(row);

        verify(requests).refresh(session, 20, ref.number(), false);
        verifyNoMoreInteractions(gitHub);
        verify(store).saveObserved(fetched);
        verifyNoInteractions(viewStateStore, detailStore, diffStore, summaries);
    }

    @Test
    void exactLookupPreservesRemoteFailureWithoutCachingAMissingPullRequest()
    {
        ResponseStatusException missing = new ResponseStatusException(HttpStatus.NOT_FOUND, "Pull request not found");
        when(requests.refresh(session, 20, 7, false)).thenThrow(missing);

        assertThatThrownBy(() -> service.lookupPullRequest("owner/repo", 7)).isSameAs(missing);

        verify(store, never()).saveObserved(any());
        verifyNoInteractions(viewStateStore, detailStore, diffStore);
    }

    @Test
    void readsAttentionAvatarsFromTheCachedProjectionWithoutFetchingDetails()
    {
        String avatar = "https://avatars.githubusercontent.com/u/123?v=4";
        when(detailStore.findAuthorAvatarUrls(ImmutableSet.of(12L, 13L)))
                .thenReturn(ImmutableMap.of(12L, avatar));

        assertThat(service.findCachedAuthorAvatarUrls(ImmutableSet.of(12L, 13L)))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, avatar));

        verify(detailStore).findAuthorAvatarUrls(ImmutableSet.of(12L, 13L));
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, writes, repositories, actions, accounts, diffStore, metadata);
    }

    @Test
    void readsTheStoredDescriptionWithoutFetchingOrMarkingThePullRequestViewed()
    {
        PrRawDetail undescribed = new PrRawDetail(null, null, null, null, null, null, REVIEWED_AT, null, null, ImmutableList.of(), null, ImmutableList.of(), null, false, true,
                "clean", 4, 2, 1, 0, ImmutableList.of(), "head", "feature", "main", "base");
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(store.findIdByRepoAndNumber("owner/repo", 8)).thenReturn(Optional.of(13L));
        when(store.findIdByRepoAndNumber("owner/repo", 9)).thenReturn(Optional.of(14L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(detailStore.find(13L)).thenReturn(Optional.of(new StoredPrDetail(undescribed, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false)));

        assertThat(service.findStoredDescription("owner/repo", 7)).contains("body");
        assertThat(service.findStoredDescription("owner/repo", 8)).contains("");
        assertThat(service.findStoredDescription("owner/repo", 9)).isEmpty();
        assertThat(service.findStoredDescription("owner/repo", 10)).isEmpty();

        verifyNoInteractions(gitHub, summaries, writes, actions, accounts, viewStateStore, diffStore);
    }

    @Test
    void cacheOnlyReadsDoNotRequireAnAccount()
    {
        assertThat(service.findStoredDescription("owner/repo", 7)).isEmpty();
        assertThat(service.findHeadSha("owner/repo", 7)).isEmpty();
        assertThat(service.findPullRequestDetail("owner/repo", 7)).isEmpty();
        assertThatThrownBy(() -> service.getPullRequestDiffFiles("owner/repo", 7, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(credentialService, never()).captureHostingRead();
        verifyNoInteractions(connections, requests, gitHub, actions, accounts, detailStore, diffStore);
    }

    @Test
    void readsCachedActivityWithoutAttributingAnUnexplainedLaterUpdateToItsActor()
    {
        PrActivitySummary activity = new PrActivitySummary("commented", Optional.of("reviewer"), REVIEWED_AT,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), OptionalInt.empty());
        when(detailStore.findLatestActivities(ImmutableSet.of(12L))).thenReturn(ImmutableMap.of(12L, activity));

        assertThat(service.findCachedLatestActivities(ImmutableList.of(storedPullRequest("open", REVIEWED_AT, null))))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, activity));
        assertThat(service.findCachedLatestActivities(ImmutableList.of(storedPullRequest("open", REVIEWED_AT.minusSeconds(1), null))))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, activity));
        assertThat(service.findCachedLatestActivities(ImmutableList.of(storedPullRequest("open", REVIEWED_AT.plusSeconds(1), null)))).isEmpty();

        // No timeline was written in between, so the three reads share one load.
        verify(detailStore).findLatestActivities(ImmutableSet.of(12L));
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, writes, repositories, actions, accounts, diffStore, metadata);
    }

    @Test
    void readsCommitsSinceTheLastReviewOnlyForPullRequestsWhoseHeadMovedSince()
    {
        Instant requested = REVIEWED_AT.minusSeconds(3600);
        PullRequest reviewedThenPushed = attentionPullRequest(12, REVIEWED_AT, requested, REVIEWED_AT.plusSeconds(60), null, REVIEWED_AT.plusSeconds(60));
        PullRequest requestedThenPushed = attentionPullRequest(13, null, requested, requested.plusSeconds(60), null);
        PullRequest untouchedSinceReview = attentionPullRequest(14, REVIEWED_AT, requested, REVIEWED_AT.minusSeconds(60), null);
        PullRequest neverAsked = attentionPullRequest(15, null, null, REVIEWED_AT, null);
        List<PrCommitLine> commits = ImmutableList.of(new PrCommitLine("abc", "Fix it", Optional.of("alice"), REVIEWED_AT.plusSeconds(60)));
        when(detailStore.findFreshness(ImmutableSet.of(12L)))
                .thenReturn(ImmutableMap.of(12L, new PrDetailFreshness(Optional.empty(), Optional.of(REVIEWED_AT.plusSeconds(60)), Optional.empty(), Optional.empty())));
        when(detailStore.findCommitsAfter(ImmutableMap.of(12L, REVIEWED_AT, 13L, requested))).thenReturn(ImmutableMap.of(12L, commits));

        assertThat(service.findCachedCommitsSinceReview(ImmutableList.of(reviewedThenPushed, requestedThenPushed, untouchedSinceReview, neverAsked)))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, commits));

        verify(detailStore).findCommitsAfter(ImmutableMap.of(12L, REVIEWED_AT, 13L, requested));
        verify(detailStore).findFreshness(ImmutableSet.of(12L));
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, writes, repositories, actions, accounts, diffStore, metadata);
    }

    @Test
    void namesTheLatestFailingChecksOfFailingPullRequestsOnly()
    {
        PullRequest failing = attentionPullRequest(12, null, null, null, PullRequestDetail.CiStatus.FAILING, REVIEWED_AT);
        PullRequest passing = attentionPullRequest(13, null, null, null, PullRequestDetail.CiStatus.PASSING);
        when(detailStore.findFreshness(ImmutableSet.of(12L)))
                .thenReturn(ImmutableMap.of(12L, new PrDetailFreshness(Optional.empty(), Optional.empty(), Optional.of(REVIEWED_AT.plusSeconds(1)), Optional.empty())));
        when(detailStore.findCheckRuns(ImmutableSet.of(12L))).thenReturn(ImmutableMap.of(12L, ImmutableList.of(
                new PrCheckRunState(1L, "ci / test", "completed", "failure", null, "check_run", REVIEWED_AT, REVIEWED_AT),
                new PrCheckRunState(2L, "ci / lint", "completed", "failure", null, "check_run", REVIEWED_AT, REVIEWED_AT),
                new PrCheckRunState(3L, "ci / lint", "completed", "success", null, "check_run", REVIEWED_AT, REVIEWED_AT))));

        assertThat(service.findCachedFailingChecks(ImmutableList.of(failing, passing)))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, ImmutableList.of("ci / test")));

        verify(detailStore).findCheckRuns(ImmutableSet.of(12L));
        verify(detailStore).findFreshness(ImmutableSet.of(12L));
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, writes, repositories, actions, accounts, diffStore, metadata);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void summaryChangesSuppressStaleOrMissingCheckNamesAndCommitLists(boolean detailExists)
    {
        Instant activityAt = REVIEWED_AT.plusSeconds(60);
        PullRequest changed = attentionPullRequest(12, REVIEWED_AT, null, activityAt, PullRequestDetail.CiStatus.FAILING, activityAt);
        when(detailStore.findFreshness(ImmutableSet.of(12L))).thenReturn(ImmutableMap.of(12L, new PrDetailFreshness(Optional.of(REVIEWED_AT.plusSeconds(120)),
                detailExists ? Optional.of(REVIEWED_AT) : Optional.empty(), detailExists ? Optional.of(REVIEWED_AT) : Optional.empty(), Optional.empty())));

        assertThat(service.findCachedCommitsSinceReview(ImmutableList.of(changed))).isEmpty();
        assertThat(service.findCachedFailingChecks(ImmutableList.of(changed))).isEmpty();

        verify(detailStore, times(2)).findFreshness(ImmutableSet.of(12L));
        verify(detailStore).findCommitsAfter(ImmutableMap.of());
        verify(detailStore).findCheckRuns(ImmutableSet.of());
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, summaries, writes, repositories, actions, accounts, diffStore, metadata);
    }

    @Test
    void refreshesTaskBranchRowsImmediatelyWithoutSearchOrDetailReads()
    {
        workspaceRoute();
        RepoRef repository = RepoRef.of("owner", "repo");
        FetchedPullRequest found = pullRequest();
        when(gitHub.fetchPullRequestsByHead("pat", repository, "contributor:feature")).thenReturn(ImmutableList.of(found));

        service.syncRepositoryBranch("workspace", "contributor", "feature");
        service.syncRepositoryBranch("workspace", "contributor", "feature");

        verify(gitHub, times(2)).fetchPullRequestsByHead("pat", repository, "contributor:feature");
        verify(store, times(2)).saveRepositorySyncPage(eq("owner/repo"), eq(ImmutableList.of(found.withViewerOrigin("me"))));
        verifyNoInteractions(detailStore, diffStore);
    }

    @Test
    void preservesCachedBranchRowsWhenGitHubReturnsNoMatches()
    {
        workspaceRoute();
        RepoRef repository = RepoRef.of("owner", "repo");
        when(gitHub.fetchPullRequestsByHead("pat", repository, "contributor:feature")).thenReturn(ImmutableList.of());

        service.syncRepositoryBranch("workspace", "contributor", "feature");

        verifyNoInteractions(store, detailStore, diffStore);
    }

    @Test
    void monitorEstablishesCardBaselineBeforeEnablingNotificationsWithoutLoadingDetail()
    {
        PullRequest row = storedPullRequest();
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        GitHubPullRequestSummary summary = summary(pullRequest(), PullRequestDetail.CiStatus.FAILING, "checks-v1");
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(summary.pullRequest())).thenReturn(row);

        assertThat(service.setMonitored(row.id(), true)).isEqualTo(row);

        var order = inOrder(store);
        order.verify(store).saveObserved(summary.pullRequest());
        order.verify(store).updateEnrichment(row.id(), summary.pullRequest().updatedAt(), PullRequestDetail.CiStatus.FAILING, 4, 2, 1,
                null, null, REVIEWED_AT, "feature", ImmutableList.of(), row.origin(), Optional.empty(), Optional.of("APPROVED"),
                ImmutableMap.of("summary", "checks-v1"));
        order.verify(store).setMonitored(row.id(), true);
        verifyNoInteractions(gitHub, actions, accounts, detailStore, diffStore, writes);
    }

    @Test
    void mergedPullRequestsCannotBeMonitored()
    {
        PullRequest row = storedPullRequest("merged", REVIEWED_AT, REVIEWED_AT);
        when(store.findById(row.id())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.setMonitored(row.id(), true))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(store, never()).setMonitored(anyLong(), anyBoolean());
        verifyNoInteractions(credentialService, summaries);
    }

    @Test
    void mergeDiscoveredWhileEnablingMonitoringIsSavedWithoutEnablingMonitoring()
    {
        PullRequest row = storedPullRequest();
        FetchedPullRequest merged = pullRequest("merged", row.updatedAt().plusSeconds(60), row.updatedAt().plusSeconds(60));
        GitHubPullRequestSummary summary = summary(merged, PullRequestDetail.CiStatus.PASSING, "merged");
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(summaries.fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 7)), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(merged)).thenReturn(storedPullRequest(merged));

        assertThatThrownBy(() -> service.setMonitored(row.id(), true))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(store).saveObserved(merged);
        verify(store, never()).setMonitored(anyLong(), anyBoolean());
    }

    @Test
    void disablingMonitoringStaysLocal()
    {
        PullRequest row = storedPullRequest();
        when(store.findById(row.id())).thenReturn(Optional.of(row));

        service.setMonitored(row.id(), false);

        verify(store).setMonitored(row.id(), false);
        verifyNoInteractions(credentialService, gitHub, summaries, actions, accounts, detailStore, diffStore, viewStateStore);
    }

    @Test
    void monitoringRemainsDisabledWhenTheFreshSummaryIsUnavailable()
    {
        PullRequest row = storedPullRequest();
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(summaries.fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 7)), "me")).thenReturn(ImmutableList.of());

        assertThatThrownBy(() -> service.setMonitored(row.id(), true))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(store).findById(row.id());
        verifyNoMoreInteractions(store);
        verifyNoInteractions(gitHub, actions, detailStore, diffStore, viewStateStore, accounts);
    }

    @Test
    void newerExternalReviewUpdatesTheBaselineBeforeCheckingForNewCommits()
    {
        Instant latestReviewAt = REVIEWED_AT.plusSeconds(30);
        PrReviewState review = new PrReviewState(1L, "me", null, false, "APPROVED", latestReviewAt, null, null, "head");
        StoredPrDetail latest = new StoredPrDetail(raw(), ImmutableList.of(review), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(store.findById(12L)).thenReturn(Optional.of(storedPullRequest()));
        when(detailStore.find(12L)).thenReturn(Optional.of(latest));
        when(requests.refresh(session, 20, 7, false)).thenReturn(snapshot(pullRequest()));
        when(store.saveObserved(any())).thenReturn(storedPullRequest());
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty()));
        PrViewState previous = new PrViewState(12L, REVIEWED_AT, REVIEWED_AT, HandledAction.APPROVED, "old-head");
        PrViewState updated = new PrViewState(12L, REVIEWED_AT, latestReviewAt, HandledAction.APPROVED, "head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(12L)))
                .thenReturn(ImmutableMap.of(12L, previous), ImmutableMap.of(12L, updated));

        service.refreshPullRequestDetailFromGitHub("owner/repo", 7);

        verify(viewStateStore).markReviewed(12L, HandledAction.APPROVED, "head", latestReviewAt);
        verify(viewStateStore, never()).markNeedsReview(12L);
    }

    @Test
    void backgroundSyncClearsExistingMergedMonitorsBeforeRequestingSummaries()
    {
        PullRequest merged = storedPullRequest("merged", REVIEWED_AT, REVIEWED_AT);
        List<PullRequest> candidates = new ArrayList<>(ImmutableList.of(monitored(merged)));
        when(store.findAttentionCandidates()).thenAnswer(ignored -> ImmutableList.copyOf(candidates));
        doAnswer(ignored -> {
            candidates.set(0, merged);
            return null;
        }).when(store).setMonitored(merged.id(), false);
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));

        service.syncAttentionSummaries();
        service.syncAttentionSummaries();

        verify(store).setMonitored(merged.id(), false);
        verifyNoInteractions(summaries);
    }

    @Test
    void notificationPollingDoesNotWaitForRepositorySummariesAndRetainsConcurrentInvalidation()
            throws Exception
    {
        service = newService(Clock.fixed(REVIEWED_AT, ZoneOffset.UTC));
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        FetchedPullRequest fetched = pullRequest();
        PullRequest row = storedPullRequest();
        CountDownLatch summarizing = new CountDownLatch(1);
        CountDownLatch releaseSummary = new CountDownLatch(1);
        CountDownLatch polled = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger polls = new AtomicInteger();
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(row));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(row.id()));
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.saveObserved(any())).thenReturn(row);
        when(requests.refresh(session, 20, ref.number(), false)).thenReturn(snapshot(fetched));
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100))).thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        when(requests.refreshPage(eq(session), eq(20L), anyBoolean(), eq(1), eq(100))).thenReturn(new PullRequestListPage(ImmutableList.of(fetched), false));
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenAnswer(invocation -> {
            summarizing.countDown();
            assertThat(releaseSummary.await(5, TimeUnit.SECONDS)).isTrue();
            return ImmutableList.of(summary(fetched, PullRequestDetail.CiStatus.PASSING, "checks-v1"));
        });
        doAnswer(invocation -> {
            if (polls.getAndIncrement() == 0) {
                invocation.<Consumer<GitHubNotificationRef>>getArgument(2).accept(new GitHubNotificationRef(
                        "thread-1", ref, true, "Title", "ci_activity", REVIEWED_AT, Optional.empty()));
            }
            return null;
        }).when(notifications).poll(eq("pat"), eq("me"), any());
        Thread repository = Thread.ofVirtual().start(() -> {
            try {
                service.syncRepository(RepoRef.of("owner", "repo"));
            }
            catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        assertThat(summarizing.await(5, TimeUnit.SECONDS)).isTrue();
        Thread attention = Thread.ofVirtual().start(() -> {
            try {
                service.syncAttentionSummaries();
                polled.countDown();
            }
            catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        try {
            assertThat(polled.await(5, TimeUnit.SECONDS)).isTrue();
            verify(requests).refresh(session, 20, ref.number(), false);
            verify(summaries).fetchSummaries("pat", ImmutableList.of(ref), "me");
        }
        finally {
            releaseSummary.countDown();
            repository.join(5000);
            attention.join(5000);
        }
        assertThat(failure.get()).isNull();
        assertThat(repository.isAlive()).isFalse();
        assertThat(attention.isAlive()).isFalse();

        // CI notifications can leave updatedAt unchanged; the older in-flight response must not make this card fresh.
        service.syncAttentionSummaries();

        verify(summaries, times(2)).fetchSummaries("pat", ImmutableList.of(ref), "me");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attentionSummariesAreBoundedAndAdvancePastFailedOrOmittedCards(boolean failed)
    {
        List<PullRequest> candidates = IntStream.rangeClosed(1, 12)
                .mapToObj(number -> storedPullRequest(pullRequestNumber(number))).collect(toImmutableList());
        when(store.findAttentionCandidates()).thenReturn(candidates);
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        if (failed) {
            when(summaries.fetchSummaries(eq("pat"), anyList(), eq("me")))
                    .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Summary unavailable"));
        }

        service.syncAttentionSummaries();
        service.syncAttentionSummaries();
        service.syncAttentionSummaries();

        ArgumentCaptor<List<PullRequestRef>> batches = ArgumentCaptor.forClass(List.class);
        verify(summaries, times(3)).fetchSummaries(eq("pat"), batches.capture(), eq("me"));
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSize(GitHubPullRequestSummaryClient.BATCH_SIZE));
        assertThat(batches.getAllValues().stream().flatMap(List::stream).map(PullRequestRef::number))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 1, 2, 3);
    }

    @Test
    void olderMonitoredRowsStayCoveredByFastCardSyncWithoutWarmingDetails()
    {
        PullRequest monitored = monitoredPullRequest(91);
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(monitored));
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        service.syncAttentionSummaries();
        verify(summaries).fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 91)), "me");
        verifyNoInteractions(detailStore, diffStore, actions);
    }

    @Test
    void aRestartSummarizesClosedAndOtherPeoplesCardsOnlyAfterNewActivity()
    {
        PullRequest open = storedPullRequest(pullRequestNumber(91));
        // Someone else's open card is summarized again when discovery or the inbox reports activity, so a restart keeps it too.
        PullRequest others = storedPullRequest(pullRequestNumber(92).withOrigin(PullRequest.Origin.OTHER));
        List<PullRequest> candidates = new ArrayList<>(ImmutableList.of(open, storedPullRequest("merged", REVIEWED_AT, REVIEWED_AT), others));
        when(store.findAttentionCandidates()).thenAnswer(ignored -> ImmutableList.copyOf(candidates));
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));

        service.syncAttentionSummaries();
        verify(summaries).fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 91)), "me");

        candidates.set(1, storedPullRequest("merged", REVIEWED_AT.plusSeconds(60), REVIEWED_AT));
        service.syncAttentionSummaries();
        verify(summaries).fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 91), PullRequestRef.of("owner", "repo", 7)), "me");
        verify(summaries, never()).fetchSummaries(eq("pat"), org.mockito.ArgumentMatchers.argThat(refs -> refs.contains(PullRequestRef.of("owner", "repo", 92))), eq("me"));
    }

    @Test
    void dashboardDiscoversEveryPageWithoutWarmingDetails()
    {
        service = spy(service);
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 120).mapToObj(TestPullRequestService::pullRequestNumber).toList();
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), anyInt(), eq(100))).thenAnswer(invocation -> {
            String query = invocation.getArgument(1);
            int page = invocation.getArgument(2);
            if (!query.equals("is:pr is:open author:@me")) {
                return new PullRequestHistoryPage(ImmutableList.of(), false);
            }
            return new PullRequestHistoryPage(page == 1 ? rows.subList(0, 100) : rows.subList(100, 120), page == 1);
        });

        service.syncFromGitHub();

        verify(store).saveDashboardSyncPage(eq(rows));
        verify(store, times(120)).saveObserved(any());
        ArgumentCaptor<List<PullRequestRef>> summarized = ArgumentCaptor.forClass(List.class);
        verify(summaries, times(24)).fetchSummaries(eq("pat"), summarized.capture(), eq("me"));
        assertThat(summarized.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSize(5));
        assertThat(summarized.getAllValues().stream().flatMap(List::stream)).hasSize(120);
        verify(gitHub).searchPullRequestsPaged("pat", "is:pr is:open author:@me", 2, 100);
        verify(gitHub, times(6)).searchPullRequestsPaged(eq("pat"), anyString(), anyInt(), eq(100));
        verifyNoMoreInteractions(gitHub);
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void discoveryLimitFailureRetainsObservedRowsWithoutClaimingACompleteSweep()
    {
        when(gitHub.searchPullRequestsPaged(eq("pat"), eq("is:pr is:open author:@me"), anyInt(), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(pullRequest()), true));

        assertThatThrownBy(service::syncFromGitHub).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("search result limit");

        verify(gitHub, times(10)).searchPullRequestsPaged(eq("pat"), eq("is:pr is:open author:@me"), anyInt(), eq(100));
        verify(store).saveObserved(pullRequest());
        verify(store, never()).saveDashboardSyncPage(anyList());
        verifyNoInteractions(summaries, detailStore, diffStore, actions, accounts);
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 504})
    void failedMiddleCardBatchKeepsEarlierAndLaterSummariesWithoutRetrying(int upstreamStatus)
    {
        service = spy(service);
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 31).mapToObj(TestPullRequestService::pullRequestNumber).collect(toImmutableList());
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100))).thenAnswer(invocation ->
                new PullRequestHistoryPage(invocation.getArgument(1).equals("is:pr is:open author:@me") ? rows : ImmutableList.of(), false));
        when(store.saveObserved(any())).thenAnswer(invocation -> storedPullRequest(invocation.getArgument(0)));
        when(summaries.fetchSummaries(eq("pat"), anyList(), eq("me"))).thenAnswer(invocation -> {
            List<PullRequestRef> batch = invocation.getArgument(1);
            if (batch.getFirst().number() == 6) {
                throw new ResponseStatusException(HttpStatus.valueOf(upstreamStatus), "GitHub API request failed with status " + upstreamStatus + ".");
            }
            return batch.stream().map(ref -> summary(rows.get(ref.number() - 1), PullRequestDetail.CiStatus.PASSING, "summary-" + ref.number()))
                    .collect(toImmutableList());
        });

        service.syncFromGitHub();

        verify(store).saveDashboardSyncPage(eq(rows));
        ArgumentCaptor<FetchedPullRequest> saved = ArgumentCaptor.forClass(FetchedPullRequest.class);
        verify(store, times(57)).saveObserved(saved.capture());
        assertThat(saved.getAllValues().subList(0, 31)).containsExactlyElementsOf(rows);
        assertThat(saved.getAllValues().subList(31, 57)).containsExactlyElementsOf(rows.stream().filter(row -> row.number() < 6 || row.number() > 10).collect(toImmutableList()));
        ArgumentCaptor<List<PullRequestRef>> summarized = ArgumentCaptor.forClass(List.class);
        verify(summaries, times(7)).fetchSummaries(eq("pat"), summarized.capture(), eq("me"));
        assertThat(summarized.getAllValues()).extracting(List::size).containsExactly(5, 5, 5, 5, 5, 5, 1);
        assertThat(summarized.getAllValues().stream().flatMap(List::stream).map(PullRequestRef::number))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 31).boxed().collect(toImmutableList()));
        verifyNoMoreInteractions(summaries);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void rejectedCardBatchStopsFurtherRequestsAndWarmingButRetainsDiscovery(int status)
    {
        service = spy(service);
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 6).mapToObj(TestPullRequestService::pullRequestNumber).collect(toImmutableList());
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100))).thenAnswer(invocation ->
                new PullRequestHistoryPage(invocation.getArgument(1).equals("is:pr is:open author:@me") ? rows : ImmutableList.of(), false));
        List<PullRequestRef> firstBatch = rows.subList(0, 5).stream().map(row -> PullRequestRef.of("owner", "repo", row.number())).collect(toImmutableList());
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.valueOf(status), "GitHub rejected the request");
        when(summaries.fetchSummaries("pat", firstBatch, "me")).thenThrow(failure);

        assertThatThrownBy(service::syncFromGitHub).isSameAs(failure);

        verify(store).saveDashboardSyncPage(eq(rows));
        ArgumentCaptor<FetchedPullRequest> saved = ArgumentCaptor.forClass(FetchedPullRequest.class);
        verify(store, times(6)).saveObserved(saved.capture());
        assertThat(saved.getAllValues()).containsExactlyElementsOf(rows);
        verify(summaries).fetchSummaries("pat", firstBatch, "me");
        verifyNoMoreInteractions(summaries);
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void fullDetailWarmingUsesTenMinuteGroupsAndContinuesAfterAnUpstreamFailure()
    {
        service = spy(service);
        List<PullRequest> rows = IntStream.rangeClosed(1, 4).mapToObj(number -> storedPullRequest(pullRequestNumber(number))).toList();
        when(store.findAttentionCandidates()).thenReturn(rows);
        doReturn(null).when(service).refreshPullRequestDetailFromGitHub(anyString(), anyInt(), eq(Duration.ofMinutes(10)));
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub unavailable"))
                .when(service).refreshPullRequestDetailFromGitHub("owner/repo", 3, Duration.ofMinutes(10));

        service.warmRecentDetails(10);

        var order = inOrder(service);
        for (int number = 4; number >= 1; number--) {
            order.verify(service).refreshPullRequestDetailFromGitHub("owner/repo", number, Duration.ofMinutes(10));
        }
        verifyNoInteractions(gitHub, actions, accounts, diffStore);
    }

    @Test
    void fullDetailWarmingRetriesOnlyThePartialDiscussionGroup()
    {
        StoredPrDetail partial = new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(StoredPrDetail.COMMENTS), false);
        cacheDetail(partial, freshTimes());
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(storedPullRequest()));

        service.warmRecentDetails(10);

        verify(gitHub).fetchPrIssueComments("pat", PullRequestRef.of("owner", "repo", 7));
        verify(requests, never()).refresh(any(), anyLong(), anyInt(), anyBoolean());
        verify(detailStore).saveDiscussion(eq(12L), any(), eq(pullRequest().updatedAt()));
    }

    @ParameterizedTest
    @CsvSource({"APPROVED,false", "COMMENTED,true", "CHANGES_REQUESTED,true"})
    void backgroundSummaryKeepsApprovalWhenTheHeadChanges(HandledAction action, boolean needsReview)
    {
        service = spy(service);
        FetchedPullRequest fetched = pullRequest("open", REVIEWED_AT.plusSeconds(60), null);
        PullRequest row = storedPullRequest(fetched);
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        GitHubPullRequestSummary summary = summary(fetched, PullRequestDetail.CiStatus.FAILING, "checks-v2");
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(fetched), false));
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(fetched)).thenReturn(row);
        PrViewState reviewed = new PrViewState(row.id(), REVIEWED_AT, REVIEWED_AT, action, "previous-head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of(row.id(), reviewed));

        service.syncFromGitHub();

        verify(store).updateEnrichment(eq(row.id()), eq(fetched.updatedAt()), eq(PullRequestDetail.CiStatus.FAILING), eq(4), eq(2), eq(1),
                isNull(), isNull(), any(Instant.class), eq("feature"), eq(ImmutableList.of()), eq(row.origin()), eq(Optional.empty()), eq(Optional.of("APPROVED")),
                eq(ImmutableMap.of("summary", "checks-v2")));
        verify(viewStateStore, times(needsReview ? 1 : 0)).markNeedsReview(row.id());
        verify(summaries).fetchSummaries("pat", ImmutableList.of(ref), "me");
        verifyNoMoreInteractions(summaries);
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void backgroundSummaryReconcilesANewerViewerReviewBeforeCheckingForAnotherReview()
    {
        service = spy(service);
        PullRequest row = storedPullRequest();
        Instant reviewedAt = REVIEWED_AT.plusSeconds(60);
        GitHubPullRequestSummary summary = new GitHubPullRequestSummary(pullRequest(), PullRequestDetail.CiStatus.PASSING, 4, 2, 1, "head",
                Optional.of(REVIEWED_AT), Optional.of("APPROVED"),
                Optional.of(new PrReviewState(1L, "me", null, false, "APPROVED", reviewedAt, null, null, "head")), Optional.empty(), "review-v2", REVIEWED_AT, Optional.empty());
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(pullRequest()), false));
        when(summaries.fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 7)), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(pullRequest())).thenReturn(row);
        PrViewState previous = new PrViewState(row.id(), REVIEWED_AT, REVIEWED_AT, HandledAction.APPROVED, "old-head");
        PrViewState updated = new PrViewState(row.id(), REVIEWED_AT, reviewedAt, HandledAction.APPROVED, "head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of(row.id(), previous), ImmutableMap.of(row.id(), updated));

        service.syncFromGitHub();

        verify(viewStateStore).markReviewed(row.id(), HandledAction.APPROVED, "head", reviewedAt);
        verify(viewStateStore, never()).markNeedsReview(row.id());
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void backgroundSummaryLeavesARecordedReviewClearedOnceItNeedsReviewAgain()
    {
        service = spy(service);
        PullRequest row = storedPullRequest();
        GitHubPullRequestSummary summary = new GitHubPullRequestSummary(pullRequest(), PullRequestDetail.CiStatus.PASSING, 4, 2, 1, "head",
                Optional.of(REVIEWED_AT), Optional.empty(),
                Optional.of(new PrReviewState(1L, "me", null, false, "COMMENTED", REVIEWED_AT, null, null, "previous-head")), Optional.empty(), "review-v2", REVIEWED_AT,
                Optional.empty());
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(pullRequest()), false));
        when(summaries.fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 7)), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(pullRequest())).thenReturn(row);
        PrViewState cleared = new PrViewState(row.id(), REVIEWED_AT, REVIEWED_AT, null, "previous-head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of(row.id(), cleared));

        service.syncFromGitHub();

        // Re-applying the recorded review would be cleared again at once, two writes a refresh that each rebuild the attention list.
        verify(viewStateStore, never()).markReviewed(anyLong(), any(), any(), any());
        verify(viewStateStore, never()).markNeedsReview(row.id());
    }

    @ParameterizedTest
    @CsvSource({"APPROVED,true", "APPROVED,false", "COMMENTED,true", "COMMENTED,false", "CHANGES_REQUESTED,true", "CHANGES_REQUESTED,false"})
    void backgroundSummaryKeepsApprovalWhenTheAuthorReplies(HandledAction action, boolean authorReplied)
    {
        service = spy(service);
        FetchedPullRequest fetched = pullRequest("open", REVIEWED_AT.plusSeconds(60), null).withViewerOrigin("me");
        PullRequest row = storedPullRequest(fetched);
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        GitHubPullRequestSummary summary = summary(fetched, PullRequestDetail.CiStatus.PASSING, "reply-v2");
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(fetched), false));
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(any())).thenReturn(row);
        PrViewState reviewed = new PrViewState(row.id(), REVIEWED_AT, REVIEWED_AT, action, "head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of(row.id(), reviewed));
        if (action != HandledAction.APPROVED) {
            when(summaries.hasAuthorReplyAfter("pat", ref, "me", "alice", REVIEWED_AT, "reply-v2")).thenReturn(authorReplied);
        }

        service.syncFromGitHub();

        verify(summaries, times(action == HandledAction.APPROVED ? 0 : 1)).hasAuthorReplyAfter("pat", ref, "me", "alice", REVIEWED_AT, "reply-v2");
        verify(viewStateStore, times(action != HandledAction.APPROVED && authorReplied ? 1 : 0)).markNeedsReview(row.id());
        verify(store).updateEnrichment(row.id(), fetched.updatedAt(), PullRequestDetail.CiStatus.PASSING, 4, 2, 1,
                null, null, REVIEWED_AT, "feature", ImmutableList.of(), row.origin(), Optional.empty(), Optional.of("APPROVED"),
                ImmutableMap.of("summary", "reply-v2"));
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void backgroundSummaryReopensApprovalWhenRequestedAgainOrDismissed(boolean requestedAgain)
    {
        PullRequest row = storedPullRequest();
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        Instant changedAt = REVIEWED_AT.plusSeconds(60);
        GitHubPullRequestSummary summary = new GitHubPullRequestSummary(pullRequest(), PullRequestDetail.CiStatus.PASSING, 4, 2, 1, "head",
                Optional.of(REVIEWED_AT), Optional.empty(),
                requestedAgain ? Optional.empty() : Optional.of(new PrReviewState(1L, "me", null, false, "DISMISSED", changedAt, null, null, "head")),
                requestedAgain ? Optional.of(changedAt) : Optional.empty(), "review-v2", changedAt, Optional.empty());
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(pullRequest()), false));
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(pullRequest())).thenReturn(row);
        PrViewState reviewed = new PrViewState(row.id(), REVIEWED_AT, REVIEWED_AT, HandledAction.APPROVED, "head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of(row.id(), reviewed));

        service.syncFromGitHub();

        verify(viewStateStore).markNeedsReview(row.id());
    }

    @Test
    void incrementalDiscoveryReadsEveryPageAndCommitsOnlyCompleteQueries()
    {
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 120).mapToObj(TestPullRequestService::pullRequestNumber).toList();
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), anyInt(), eq(100))).thenAnswer(invocation -> {
            if (!invocation.<String>getArgument(1).startsWith("is:pr author:@me updated:>=")) {
                return new PullRequestHistoryPage(ImmutableList.of(), false);
            }
            int page = invocation.getArgument(2);
            return new PullRequestHistoryPage(page == 1 ? rows.subList(0, 100) : rows.subList(100, 120), page == 1);
        });
        service.syncAttentionSummaries();
        verify(store, times(120)).saveObserved(any());
        verify(syncSettings).put(anyString(), anyString());
        verify(gitHub, times(2)).searchPullRequestsPaged(eq("pat"), anyString(), anyInt(), eq(100));
        verifyNoInteractions(detailStore, diffStore, actions);
    }

    @Test
    void failedIncrementalPageKeepsItsCheckpointAndEarlierRows()
    {
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), anyInt(), eq(100))).thenAnswer(invocation -> {
            if (!invocation.<String>getArgument(1).startsWith("is:pr author:@me updated:>=")) {
                return new PullRequestHistoryPage(ImmutableList.of(), false);
            }
            if (invocation.<Integer>getArgument(2) == 2) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Page unavailable");
            }
            return new PullRequestHistoryPage(ImmutableList.of(pullRequest()), true);
        });
        service.syncAttentionSummaries();
        verify(store).saveObserved(pullRequest().withViewerOrigin("me"));
        verify(syncSettings, never()).put(anyString(), anyString());

        // The failed query used its turn; the next tick moves on instead of retrying it first.
        service.syncAttentionSummaries();
        verify(syncSettings).put(anyString(), anyString());
        verify(gitHub, times(2)).searchPullRequestsPaged(eq("pat"), startsWith("is:pr author:@me updated:>="), anyInt(), eq(100));
        verifyNoInteractions(detailStore, diffStore, actions);
    }

    @Test
    void eachAttentionTickSearchesOneQueryInTurnFromItsOwnCheckpoint()
    {
        Instant completed = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);
        when(syncSettings.get(anyString())).thenReturn(Optional.of(completed.toString()));
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100))).thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));

        for (int tick = 0; tick < 5; tick++) {
            service.syncAttentionSummaries();
        }

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(gitHub, times(5)).searchPullRequestsPaged(eq("pat"), queries.capture(), eq(1), eq(100));
        String since = " updated:>=" + completed.minusSeconds(300);
        assertThat(queries.getAllValues()).containsExactly(
                "is:pr author:@me" + since, "is:pr user-review-requested:@me" + since, "is:pr reviewed-by:@me" + since, "is:pr involves:@me" + since, "is:pr author:@me" + since);
    }

    @Test
    void aSummaryBehindTheNotificationMarkerRetriesUntilDiscussionCatchesUp()
    {
        FetchedPullRequest fetched = pullRequest();
        PullRequest row = storedPullRequest(fetched);
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        GitHubPullRequestSummary stale = summary(fetched, PullRequestDetail.CiStatus.PASSING, "old-discussion");
        Instant notificationAt = fetched.updatedAt().plusSeconds(60);
        GitHubPullRequestSummary caughtUp = new GitHubPullRequestSummary(fetched, stale.ciStatus(), 4, 2, 1, "head", stale.headCommittedAt(),
                stale.reviewDecision(), Optional.empty(), Optional.empty(), "new-discussion", notificationAt, Optional.empty());
        service = serviceAt(new AtomicReference<>(notificationAt.plusSeconds(5)));
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(row));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(row.id()));
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.findDiscussionActivityAt(row.id())).thenReturn(Optional.of(notificationAt));
        when(store.findDiscussionActivities(anySet())).thenReturn(ImmutableMap.of(row.id(), notificationAt));
        when(store.saveObserved(fetched)).thenReturn(row);
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(stale), ImmutableList.of(caughtUp));

        service.syncAttentionSummaries();
        service.syncAttentionSummaries();
        service.syncAttentionSummaries();

        verify(summaries, times(2)).fetchSummaries("pat", ImmutableList.of(ref), "me");
        verify(store).observeActivity(row.id(), Optional.empty(), notificationAt);
        verifyNoInteractions(detailStore, diffStore, actions);
    }

    @Test
    void aNotificationMarkerTheTimelineNeverReachesStopsRetryingOnceItSettles()
    {
        FetchedPullRequest fetched = pullRequest();
        PullRequest row = storedPullRequest(fetched);
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        // GitHub stamps the notification after the activity it reports, so the timeline stays behind this marker.
        Instant notificationAt = fetched.updatedAt().plusSeconds(20);
        AtomicReference<Instant> now = new AtomicReference<>(notificationAt.plusSeconds(5));
        service = serviceAt(now);
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(row));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(row.id()));
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.findDiscussionActivityAt(row.id())).thenReturn(Optional.of(notificationAt));
        when(store.findDiscussionActivities(anySet())).thenReturn(ImmutableMap.of(row.id(), notificationAt));
        when(store.saveObserved(fetched)).thenReturn(row);
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary(fetched, PullRequestDetail.CiStatus.PASSING, "v1")));

        service.syncAttentionSummaries();
        now.set(notificationAt.plus(Duration.ofMinutes(3)));
        service.syncAttentionSummaries();
        service.syncAttentionSummaries();

        verify(summaries, times(2)).fetchSummaries("pat", ImmutableList.of(ref), "me");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedAuthorReplyCheckRemainsEligibleForTheNextFastPass(boolean rateLimited)
    {
        // A requested review is summarized on its own clock, also right after a restart.
        FetchedPullRequest fetched = pullRequest("open", REVIEWED_AT.plusSeconds(60), null).withOrigin(PullRequest.Origin.REVIEW_REQUESTED);
        PullRequest row = storedPullRequest(fetched);
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(row));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(row.id()));
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.saveObserved(fetched)).thenReturn(row);
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me"))
                .thenReturn(ImmutableList.of(summary(fetched, PullRequestDetail.CiStatus.PASSING, "reply-v2")));
        PrViewState reviewed = new PrViewState(row.id(), REVIEWED_AT, REVIEWED_AT, HandledAction.COMMENTED, "head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of(row.id(), reviewed));
        ResponseStatusException failure = new ResponseStatusException(rateLimited ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY, "Reply unavailable");
        when(summaries.hasAuthorReplyAfter("pat", ref, "me", "alice", REVIEWED_AT, "reply-v2")).thenThrow(failure).thenReturn(true);

        if (rateLimited) {
            assertThatThrownBy(service::syncAttentionSummaries).isSameAs(failure);
        }
        else {
            service.syncAttentionSummaries();
        }
        service.syncAttentionSummaries();
        service.syncAttentionSummaries();

        verify(summaries, times(2)).fetchSummaries("pat", ImmutableList.of(ref), "me");
        verify(summaries, times(2)).hasAuthorReplyAfter("pat", ref, "me", "alice", REVIEWED_AT, "reply-v2");
        verify(viewStateStore).markNeedsReview(row.id());
        verifyNoInteractions(detailStore, diffStore, actions);
    }

    @Test
    void notificationsSaveCardsAndDiscussionMarkersEvenWhenSearchIsRateLimited()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        GitHubNotificationRef notification = new GitHubNotificationRef("thread-1", ref, true, "Title", "review_requested", REVIEWED_AT, Optional.empty());
        doAnswer(invocation -> {
            invocation.<Consumer<GitHubNotificationRef>>getArgument(2).accept(notification);
            return null;
        }).when(notifications).poll(eq("pat"), eq("me"), any());
        when(requests.refresh(session, 20, ref.number(), false)).thenReturn(snapshot(pullRequest()));
        when(store.saveObserved(any())).thenReturn(storedPullRequest());
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Search limit reached"));

        service.syncAttentionSummaries();

        verify(store).saveObserved(pullRequest().withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        verify(store).observeActivity(12L, Optional.empty(), REVIEWED_AT);
        verify(syncSettings, never()).put(anyString(), anyString());
        verifyNoInteractions(detailStore, diffStore, actions);
    }

    @Test
    void watchedRepositoryNotificationsDoNotSavePullRequests()
    {
        GitHubNotificationRef notification = new GitHubNotificationRef("thread-1", PullRequestRef.of("owner", "repo", 7), true,
                "Title", "subscribed", REVIEWED_AT, Optional.empty());
        doAnswer(invocation -> {
            invocation.<Consumer<GitHubNotificationRef>>getArgument(2).accept(notification);
            return null;
        }).when(notifications).poll(eq("pat"), eq("me"), any());
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));

        service.syncAttentionSummaries();

        verify(requests, never()).refresh(session, 20, 7, false);
        verify(store, never()).saveObserved(any());
    }

    @Test
    void notificationIssuesUseTheirCardProjectionWithoutFetchingPullRequestDetails()
    {
        GitHubNotificationRef notification = new GitHubNotificationRef("issue-1", PullRequestRef.of("owner", "repo", 7), false, "Issue", "mention", REVIEWED_AT, Optional.empty());
        doAnswer(invocation -> {
            invocation.<Consumer<GitHubNotificationRef>>getArgument(2).accept(notification);
            return null;
        }).when(notifications).poll(eq("pat"), eq("me"), any());
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        service.syncAttentionSummaries();
        verify(issues).refreshNotifiedIssue(RepoRef.of("owner", "repo"), 7);
        verifyNoInteractions(detailStore, diffStore, actions);
    }

    @Test
    void activityFingerprintDetectsIndividualCheckChangesEvenWithSameAggregateAndUpdateTime()
    {
        PrCheckRunState first = new PrCheckRunState(1L, "one", "completed", "failure", null, "check_run", null, null);
        PrCheckRunState second = new PrCheckRunState(2L, "two", "completed", "success", null, "check_run", null, null);
        StoredPrDetail baseline = new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(first, second), ImmutableList.of(), false);
        StoredPrDetail reordered = new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(second, first), ImmutableList.of(), false);
        StoredPrDetail changed = new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(first, new PrCheckRunState(2L, "two", "completed", "failure", null, "check_run", null, null)), ImmutableList.of(), false);
        assertThat(PullRequestService.activityFingerprints(reordered)).isEqualTo(PullRequestService.activityFingerprints(baseline));
        assertThat(PullRequestService.activityFingerprints(changed)).isNotEqualTo(PullRequestService.activityFingerprints(baseline));
        assertThat(PullRequestDetailMapper.aggregateCiStatus(changed.checkRuns())).isEqualTo(PullRequestDetailMapper.aggregateCiStatus(baseline.checkRuns()));
    }

    @Test
    void partialTimelineStillProducesCiFingerprintAndCollectionOrderingDoesNotChangeRawFingerprint()
    {
        PrRawDetail first = new PrRawDetail(
                "title", "url", "open", "author", null, REVIEWED_AT, REVIEWED_AT, null, "body",
                ImmutableList.of("a", "b"), ImmutableMap.of("a", "111", "b", "222"), ImmutableList.of("a", "b"), null,
                false, true, "clean", 0, 0, 0, 2, ImmutableList.of("a", "b"), "head", "feature", "main");
        PrRawDetail reordered = new PrRawDetail(
                "title", "url", "open", "author", null, REVIEWED_AT, REVIEWED_AT, null, "body",
                ImmutableList.of("b", "a"), ImmutableMap.of("b", "222", "a", "111"), ImmutableList.of("b", "a"), null,
                false, true, "clean", 0, 0, 0, 2, ImmutableList.of("b", "a"), "head", "feature", "main");
        StoredPrDetail baseline = new StoredPrDetail(first, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false);
        StoredPrDetail partial = new StoredPrDetail(reordered, ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(1L, "ci", "completed", "failure", null, "check_run", null, null)), ImmutableList.of(StoredPrDetail.TIMELINE), false);

        assertThat(PullRequestService.activityFingerprints(partial)).containsKey("checks").doesNotContainKey("timeline");
        assertThat(PullRequestService.activityFingerprints(partial).get("raw")).isEqualTo(PullRequestService.activityFingerprints(baseline).get("raw"));
        assertThat(PullRequestService.activityFingerprints(partial).get("checks")).isNotEqualTo(PullRequestService.activityFingerprints(baseline).get("checks"));
    }

    @Test
    void attentionActivitySkipsQuietEventsBotsAndOtherReviewers()
    {
        PrTimelinePayload bot = PrTimelinePayload.builder().actorBot(true).build();
        Instant human = REVIEWED_AT.plusSeconds(10);
        List<PrTimelineEvent> quiet = ImmutableList.of(
                new PrTimelineEvent(1L, "commented", "alice", human, "hi", Optional.empty()),
                new PrTimelineEvent(2L, "head_ref_force_pushed", "alice", human.plusSeconds(1), null, Optional.empty()),
                new PrTimelineEvent(3L, "labeled", "github-actions[bot]", human.plusSeconds(2), null, Optional.empty()),
                new PrTimelineEvent(4L, "commented", "coderabbitai[bot]", human.plusSeconds(3), "Walkthrough", bot, Optional.empty()),
                new PrTimelineEvent(5L, "reviewed", "coderabbit", human.plusSeconds(4), null, bot, Optional.empty()),
                new PrTimelineEvent(6L, "review_requested", "alice", human.plusSeconds(5), null, PrTimelinePayload.builder().requestedReviewer("bob").build(), Optional.empty()),
                new PrTimelineEvent(7L, "convert_to_draft", "alice", human.plusSeconds(6), null, Optional.empty()),
                new PrTimelineEvent(8L, "review_thread", "coderabbitai[bot]", human.plusSeconds(7), null, PrTimelinePayload.builder().comments(ImmutableList.of(
                        new PrTimelineComment(9L, "coderabbitai[bot]", null, true, human.plusSeconds(7), "nit", null, null, null, ImmutableList.of()))).build(), Optional.empty()),
                new PrTimelineEvent(null, "commented", "me", human.plusSeconds(8), "posting", Optional.of("local")));

        assertThat(PullRequestService.attentionActivityAt(quiet, "Me")).contains(human);
        PrTimelinePayload requestedFromMe = PrTimelinePayload.builder().requestedReviewer("me").build();
        assertThat(PullRequestService.attentionActivityAt(ImmutableList.<PrTimelineEvent>builder().addAll(quiet)
                .add(new PrTimelineEvent(10L, "review_requested", "alice", human.plusSeconds(9), null, requestedFromMe, Optional.empty()))
                .build(), "Me")).contains(human.plusSeconds(9));
        PrTimelineComment reply = new PrTimelineComment(12L, "bob", null, false, human.plusSeconds(20), "why?", null, null, null, ImmutableList.of());
        assertThat(PullRequestService.attentionActivityAt(ImmutableList.of(new PrTimelineEvent(11L, "review_thread", "bob", human, null,
                PrTimelinePayload.builder().comments(ImmutableList.of(reply)).build(), Optional.empty())), "me")).contains(human.plusSeconds(20));
        assertThat(PullRequestService.attentionActivityAt(ImmutableList.of(new PrTimelineEvent(13L, "added_to_merge_queue", "alice", human, null, Optional.empty())), "me"))
                .contains(human);
        assertThat(PullRequestService.attentionActivityAt(ImmutableList.of(quiet.get(1)), "me")).isEmpty();
    }

    @Test
    void reviewDecisionUsesActualLatestVerdictsAndDismissals()
    {
        assertThat(PullRequestService.reviewDecision(ImmutableList.of(
                new PrReviewState(null, "reviewer", null, false, "CHANGES_REQUESTED", REVIEWED_AT, null, null, null),
                new PrReviewState(null, "reviewer", null, false, "APPROVED", REVIEWED_AT.plusSeconds(1), null, null, null),
                new PrReviewState(null, "reviewer", null, false, "COMMENTED", REVIEWED_AT.plusSeconds(2), null, null, null))))
                .contains("APPROVED");
        assertThat(PullRequestService.reviewDecision(ImmutableList.of(
                new PrReviewState(null, "reviewer", null, false, "CHANGES_REQUESTED", REVIEWED_AT, null, null, null),
                new PrReviewState(null, "reviewer", null, false, "DISMISSED", REVIEWED_AT.plusSeconds(1), null, null, null))))
                .isEmpty();
    }

    @Test
    void rejectsMalformedRepositoryNames()
    {
        assertThatThrownBy(() -> service.getPullRequestDetail("repo", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatesPullRequestAssigneesAndLabelsThenMarksDetailForRefresh()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.updateMetadata("owner/repo", 7, ImmutableList.of(" alice "), ImmutableList.of("bug"));

        verify(writes).updateIssueMetadata("pat", PullRequestRef.of("owner", "repo", 7), ImmutableList.of("alice"), ImmutableList.of("bug"));
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void updatesPullRequestReviewerThenMarksDetailForRefresh()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.updateReviewerRequest("owner/repo", 7, " alice ", true);

        verify(writes).updateReviewerRequest("pat", PullRequestRef.of("owner", "repo", 7), "alice", true);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void listsRepositoryLabelsForMetadataPicker()
    {
        RepositoryLabel label = new RepositoryLabel(1, "bug", "d73a4a");
        when(repositories.fetchLabels("pat", RepoRef.of("owner", "repo")))
                .thenReturn(ImmutableList.of(label));

        assertThat(service.listRepositoryLabels("owner/repo")).containsExactly(label);
    }

    @Test
    void readsWorkspacePickerUsersAndAvatarsFromLocalMetadata()
    {
        when(metadata.getSnapshot("workspace")).thenReturn(pickerSnapshot());
        when(applicationContext.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(41234);

        assertThat(service.listRepositoryUsers("owner/repo", "workspace"))
                .extracting(user -> user.login() + ":" + user.avatarUrl() + ":" + user.bot())
                .containsExactly("alice:http://127.0.0.1:41234/api/avatars/avatar-key:false", "robot[bot]:null:true");
        verify(gitHub, never()).fetchAssignableUsers(anyString(), any());
    }

    @Test
    void readsWatchedRepositoryPickerUsersFromLocalMetadataWithoutWorkspaceId()
    {
        RepoRef repository = RepoRef.of("owner", "repo");
        when(metadata.findSnapshot(repository)).thenReturn(Optional.of(pickerSnapshot()));
        when(applicationContext.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(41234);

        assertThat(service.listRepositoryUsers("owner/repo", null))
                .extracting(RepoUser::login)
                .containsExactly("alice", "robot[bot]");
        verify(gitHub, never()).fetchAssignableUsers(anyString(), any());
    }

    @Test
    void fetchesPickerUsersFromGitHubForUnwatchedRepository()
    {
        RepoRef repository = RepoRef.of("owner", "repo");
        RepoUser user = new RepoUser("alice", "https://avatars.example/alice", false);
        when(metadata.findSnapshot(repository)).thenReturn(Optional.empty());
        when(gitHub.fetchAssignableUsers("pat", repository)).thenReturn(ImmutableList.of(user));

        assertThat(service.listRepositoryUsers("owner/repo", null)).containsExactly(user);
        verify(gitHub).fetchAssignableUsers("pat", repository);
    }

    @Test
    void readsOnlyAFailedJobOnTheRequestedPullRequest()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        String headSha = "1111111111111111111111111111111111111111";
        CiJob failed = new CiJob(301, 101, "test", 3, "completed", Optional.of("failure"), "https://github.com/owner/repo/actions/runs/101/job/301", ImmutableList.of());
        CiJob passing = new CiJob(302, 101, "build", 3, "completed", Optional.of("success"), "https://github.com/owner/repo/actions/runs/101/job/302", ImmutableList.of());
        CiWorkflowRun run = new CiWorkflowRun(101, 201, "CI", 3, "completed", Optional.of("failure"), headSha,
                "https://github.com/owner/repo/actions/runs/101", ImmutableList.of(failed, passing));
        when(actions.inspect(ref)).thenReturn(new CiHeadSnapshot(
                ref, true, "alice", RepoRef.of("owner", "repo"), "feature", headSha, "main",
                "2222222222222222222222222222222222222222", "https://github.com/owner/repo/pull/7", ImmutableList.of(run)));
        when(actions.readJobLog(ref, 301)).thenReturn("test failed");

        assertThat(service.readCheckLog("owner/repo", 7, 301)).isEqualTo(new PullRequestCheckLog("test failed", Optional.empty()));
        when(actions.readFailureAnnotations(ref, 301)).thenReturn(ImmutableList.of("The self-hosted runner lost communication with the server."));
        assertThat(service.readCheckLog("owner/repo", 7, 301))
                .isEqualTo(new PullRequestCheckLog("GitHub annotation: The self-hosted runner lost communication with the server.\n\ntest failed", Optional.empty()));
        for (long jobId : ImmutableList.of(302L, 999L)) {
            assertThatThrownBy(() -> service.readCheckLog("owner/repo", 7, jobId))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("no longer part of the current PR checks");
            verify(actions, never()).readJobLog(ref, jobId);
        }
        assertThatThrownBy(() -> service.readCheckLog("owner/repo", 7, 0)).isInstanceOf(IllegalArgumentException.class);
        verify(actions, never()).rerunFailedJobs(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 403, 502})
    void preservesAnnotationsAndUnavailableReasonWhenTheLogCannotBeDownloaded(int status)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        String headSha = "1111111111111111111111111111111111111111";
        CiJob failed = new CiJob(301, 101, "test", 1, "completed", Optional.of("failure"), "https://github.com/owner/repo/actions/runs/101/job/301", ImmutableList.of());
        CiWorkflowRun run = new CiWorkflowRun(101, 201, "CI", 1, "completed", Optional.of("failure"), headSha,
                "https://github.com/owner/repo/actions/runs/101", ImmutableList.of(failed));
        when(actions.inspect(ref)).thenReturn(new CiHeadSnapshot(ref, true, "alice", RepoRef.of("owner", "repo"), "feature", headSha, "main",
                "2222222222222222222222222222222222222222", "https://github.com/owner/repo/pull/7", ImmutableList.of(run)));
        when(actions.readFailureAnnotations(ref, 301)).thenReturn(ImmutableList.of("The self-hosted runner lost communication with the server."), ImmutableList.of());
        when(actions.readJobLog(ref, 301)).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status), "Log unavailable"));

        assertThat(service.readCheckLog("owner/repo", 7, 301))
                .isEqualTo(new PullRequestCheckLog("GitHub annotation: The self-hosted runner lost communication with the server.\n", Optional.of("Log unavailable")));
        assertThat(service.readCheckLog("owner/repo", 7, 301)).isEqualTo(new PullRequestCheckLog("", Optional.of("Log unavailable")));
        verify(actions, never()).rerunFailedJobs(any());
    }

    @Test
    void rerunsFailedGitHubActionsForTheCurrentHead()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        String headSha = "1111111111111111111111111111111111111111";
        CiJob failedJob = new CiJob(301, 101, "test", 3, "completed", Optional.of("failure"), "https://github.com/owner/repo/actions/runs/101/job/301", ImmutableList.of());
        CiWorkflowRun failedRun = new CiWorkflowRun(
                101, 201, "CI", 3, "completed", Optional.of("failure"), headSha,
                "https://github.com/owner/repo/actions/runs/101", ImmutableList.of(failedJob));
        when(actions.inspect(ref)).thenReturn(new CiHeadSnapshot(
                ref, true, "alice", RepoRef.of("owner", "repo"), "feature", headSha, "main",
                "2222222222222222222222222222222222222222",
                "https://github.com/owner/repo/pull/7", ImmutableList.of(failedRun)));
        when(actions.rerunFailedJobs(any())).thenReturn(new CiRerunResult(CiRerunResult.Outcome.ACCEPTED, "accepted"));

        service.rerunFailedChecks("owner/repo", 7);

        ArgumentCaptor<RerunFailedJobsCommand> command = ArgumentCaptor.forClass(RerunFailedJobsCommand.class);
        verify(actions).rerunFailedJobs(command.capture());
        assertThat(command.getValue()).isEqualTo(new RerunFailedJobsCommand(ref, 101, headSha, 3, true));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void workspaceSyncPreservesNotificationReviewRequestsWithoutPruningRows(boolean notified)
    {
        WorkspaceHostingRead route = workspaceRoute();
        service = newService(Clock.fixed(REVIEWED_AT, ZoneOffset.UTC));
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        FetchedPullRequest fetched = pullRequest().withViewerOrigin("me");
        GitHubPullRequestSummary summary = summary(fetched, PullRequestDetail.CiStatus.PASSING, "v1");
        when(requests.refreshPage(eq(route.session()), eq(1L), anyBoolean(), eq(1), eq(100))).thenAnswer(invocation ->
                new PullRequestListPage(invocation.<Boolean>getArgument(2) ? ImmutableList.of() : ImmutableList.of(pullRequest()), false));
        when(notifications.reviewRequests("me")).thenReturn(notified ? ImmutableSet.of(ref) : ImmutableSet.of());
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveRepositorySyncPage(eq("owner/repo"), anyList())).thenAnswer(invocation ->
                invocation.<List<FetchedPullRequest>>getArgument(1).stream().map(TestPullRequestService::storedPullRequest).toList());

        when(store.saveObserved(any())).thenAnswer(invocation -> storedPullRequest(invocation.getArgument(0)));

        service.syncWorkspace("workspace");

        verify(requests).refreshPage(route.session(), 1, false, 1, 100);
        verify(requests).refreshPage(route.session(), 1, true, 1, 100);
        verifyNoInteractions(gitHub);
        ArgumentCaptor<List<FetchedPullRequest>> saved = ArgumentCaptor.forClass(List.class);
        verify(store, times(2)).saveRepositorySyncPage(eq("owner/repo"), saved.capture());
        assertThat(saved.getAllValues().getFirst()).containsExactly(fetched);
        verify(store).saveObserved(fetched.withOrigin(notified ? PullRequest.Origin.REVIEW_REQUESTED : PullRequest.Origin.OTHER));
        verify(store, never()).saveDashboardSyncPage(anyList());
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void workspaceDiscoversAllRecentAndOpenPagesWithoutWarmingDetails()
    {
        WorkspaceHostingRead route = workspaceRoute();
        service = newService(Clock.fixed(REVIEWED_AT, ZoneOffset.UTC));
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 120).mapToObj(number -> pullRequestNumber(121 - number)).collect(toImmutableList());
        FetchedPullRequest olderOpen = pullRequestNumber(121, REVIEWED_AT.minus(Duration.ofDays(60))).withViewerOrigin("me");
        when(requests.refreshPage(eq(route.session()), eq(1L), anyBoolean(), anyInt(), eq(100))).thenAnswer(invocation -> {
            if (invocation.<Boolean>getArgument(2)) {
                return new PullRequestListPage(ImmutableList.of(olderOpen), false);
            }
            int page = invocation.getArgument(3);
            return new PullRequestListPage(page == 1 ? rows.subList(0, 100) : rows.subList(100, 120), page == 1);
        });

        service.syncWorkspace("workspace");

        ArgumentCaptor<List<FetchedPullRequest>> saved = ArgumentCaptor.forClass(List.class);
        verify(store, times(3)).saveRepositorySyncPage(eq("owner/repo"), saved.capture());
        assertThat(saved.getAllValues().stream().flatMap(List::stream)).hasSize(121);
        ArgumentCaptor<List<PullRequestRef>> summarized = ArgumentCaptor.forClass(List.class);
        verify(summaries, times(25)).fetchSummaries(eq("pat"), summarized.capture(), eq("me"));
        assertThat(summarized.getAllValues().subList(0, 24)).allSatisfy(batch -> assertThat(batch).hasSize(5));
        assertThat(summarized.getValue()).containsExactly(PullRequestRef.of("owner", "repo", 121));
        assertThat(summarized.getAllValues().stream().flatMap(List::stream)).hasSize(121);
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void workspaceRechecksOnlyTheViewersUnchangedCardsOnATimer()
    {
        WorkspaceHostingRead route = workspaceRoute();
        AtomicReference<Instant> now = new AtomicReference<>(REVIEWED_AT);
        service = serviceAt(now);
        PullRequest mine = storedPullRequest(pullRequestNumber(7));
        PullRequest others = storedPullRequest(pullRequestNumber(8).withOrigin(PullRequest.Origin.OTHER));
        when(requests.refreshPage(eq(route.session()), eq(1L), anyBoolean(), eq(1), eq(100)))
                .thenReturn(new PullRequestListPage(ImmutableList.of(pullRequestNumber(8), pullRequestNumber(7)), false));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(7L));
        when(store.findIdByRepoAndNumber("owner/repo", 8)).thenReturn(Optional.of(8L));
        when(store.findById(7L)).thenReturn(Optional.of(mine));
        when(store.findById(8L)).thenReturn(Optional.of(others));
        when(store.saveObserved(any())).thenAnswer(invocation -> invocation.<FetchedPullRequest>getArgument(0).number() == 7 ? mine : others);
        when(summaries.fetchSummaries(eq("pat"), anyList(), eq("me"))).thenAnswer(invocation -> invocation.<List<PullRequestRef>>getArgument(1).stream()
                .map(ref -> summary(pullRequestNumber(ref.number()), PullRequestDetail.CiStatus.PASSING, "v1")).toList());

        service.syncWorkspace("workspace");
        now.set(REVIEWED_AT.plus(Duration.ofMinutes(11)));
        service.syncWorkspace("workspace");

        verify(summaries).fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 8), PullRequestRef.of("owner", "repo", 7)), "me");
        verify(summaries).fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 7)), "me");
        verifyNoMoreInteractions(summaries);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void workspaceSyncReadsOnlyChangesSinceItsLastCompletePass(boolean rowsVisible)
    {
        WorkspaceHostingRead route = workspaceRoute();
        AtomicReference<Instant> now = new AtomicReference<>(REVIEWED_AT);
        service = serviceAt(now);
        Map<String, String> settings = new HashMap<>();
        when(syncSettings.get(anyString())).thenAnswer(invocation -> Optional.ofNullable(settings.get(invocation.<String>getArgument(0))));
        doAnswer(invocation -> {
            settings.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(syncSettings).put(anyString(), anyString());
        doAnswer(invocation -> {
            settings.remove(invocation.<String>getArgument(0));
            return null;
        }).when(syncSettings).delete(anyString());
        // Deleting the repository's last workspace hides its rows.
        when(store.findRepositoryPage("owner/repo", 0, 1))
                .thenReturn(new PullRequestPage(rowsVisible ? ImmutableList.of(storedPullRequest()) : ImmutableList.of(), 0, 1, rowsVisible ? 1 : 0, 0, false));
        when(requests.refreshPage(eq(route.session()), eq(1L), anyBoolean(), eq(1), eq(100))).thenReturn(new PullRequestListPage(ImmutableList.of(), false));

        service.syncWorkspace("workspace");
        now.set(REVIEWED_AT.plus(Duration.ofMinutes(30)));
        service.syncWorkspace("workspace");

        ArgumentCaptor<Boolean> openOnly = ArgumentCaptor.forClass(Boolean.class);
        verify(requests, times(rowsVisible ? 3 : 4)).refreshPage(eq(route.session()), eq(1L), openOnly.capture(), eq(1), eq(100));
        assertThat(openOnly.getAllValues()).containsExactlyElementsOf(rowsVisible ? ImmutableList.of(false, true, false) : ImmutableList.of(false, true, false, true));
        assertThat(settings).containsEntry("hosting.repository-sync." + route.session().cacheScope() + ".1", now.get().toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void workspaceStopsPagingAfterTheUpdateCutoff(boolean incremental)
    {
        WorkspaceHostingRead route = workspaceRoute();
        service = newService(Clock.fixed(REVIEWED_AT, ZoneOffset.UTC));
        String key = "hosting.repository-sync." + route.session().cacheScope() + ".1";
        Instant cutoff = incremental ? REVIEWED_AT.minusSeconds(300) : REVIEWED_AT.minus(Duration.ofDays(30));
        if (incremental) {
            when(syncSettings.get(key)).thenReturn(Optional.of(REVIEWED_AT.toString()));
            when(store.findRepositoryPage("owner/repo", 0, 1)).thenReturn(new PullRequestPage(ImmutableList.of(storedPullRequest()), 0, 1, 1, 0, false));
        }
        else {
            when(requests.refreshPage(route.session(), 1, true, 1, 100)).thenReturn(new PullRequestListPage(ImmutableList.of(), false));
        }
        FetchedPullRequest boundary = pullRequestNumber(7, cutoff);
        FetchedPullRequest older = pullRequestNumber(8, cutoff.minusSeconds(1));
        when(requests.refreshPage(route.session(), 1, false, 1, 100)).thenReturn(new PullRequestListPage(ImmutableList.of(boundary, older), true));

        service.syncWorkspace("workspace");

        verify(requests, never()).refreshPage(route.session(), 1, false, 2, 100);
        verify(store).saveRepositorySyncPage("owner/repo", ImmutableList.of(boundary.withViewerOrigin("me")));
        verify(syncSettings).put(key, REVIEWED_AT.toString());
    }

    @Test
    void workspaceKeepsSavedPagesWithoutAdvancingTheCheckpointWhenALaterPageFails()
    {
        WorkspaceHostingRead route = workspaceRoute();
        service = newService(Clock.fixed(REVIEWED_AT, ZoneOffset.UTC));
        String key = "hosting.repository-sync." + route.session().cacheScope() + ".1";
        when(syncSettings.get(key)).thenReturn(Optional.of(REVIEWED_AT.minusSeconds(60).toString()));
        when(store.findRepositoryPage("owner/repo", 0, 1)).thenReturn(new PullRequestPage(ImmutableList.of(storedPullRequest()), 0, 1, 1, 0, false));
        FetchedPullRequest fetched = pullRequestNumber(7);
        when(requests.refreshPage(route.session(), 1, false, 1, 100)).thenReturn(new PullRequestListPage(ImmutableList.of(fetched), true));
        HostingFailure failure = new HostingFailure(HostingFailure.Kind.UNAVAILABLE, "Temporarily unavailable");
        when(requests.refreshPage(route.session(), 1, false, 2, 100)).thenThrow(failure);

        assertThatThrownBy(() -> service.syncWorkspace("workspace")).isSameAs(failure);

        verify(store).saveRepositorySyncPage("owner/repo", ImmutableList.of(fetched.withViewerOrigin("me")));
        verify(syncSettings, never()).put(eq(key), anyString());
        verify(syncSettings, never()).delete(key);
        verifyNoInteractions(summaries);
    }

    @Test
    void workspaceSyncUsesTheSelectedAccountInsteadOfTheGlobalCredential()
    {
        service = newService(Clock.fixed(REVIEWED_AT, ZoneOffset.UTC));
        WorkspaceHostingRead route = workspaceRoute("selected-account", "workspace-pat", "alice");
        when(workspaceHosting.captureRead("workspace")).thenReturn(route);
        when(requests.refreshPage(eq(route.session()), eq(1L), anyBoolean(), eq(1), eq(100)))
                .thenReturn(new PullRequestListPage(ImmutableList.of(pullRequest()), false));

        service.syncWorkspace("workspace");

        verify(requests).refreshPage(route.session(), 1, false, 1, 100);
        verify(requests).refreshPage(route.session(), 1, true, 1, 100);
        verify(store, times(2)).saveRepositorySyncPage("owner/repo", ImmutableList.of(pullRequest().withViewerOrigin("alice")));
        verify(summaries).fetchSummaries("workspace-pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 7)), "alice");
        verifyNoInteractions(credentialService, connections, gitHub);
    }

    @Test
    void workspaceSyncDoesNotReuseAnotherCredentialScopesCheckpoint()
    {
        AtomicReference<Instant> now = new AtomicReference<>(REVIEWED_AT);
        service = serviceAt(now);
        WorkspaceHostingRead first = workspaceRoute("scope-one", "pat-one", "me");
        WorkspaceHostingRead second = workspaceRoute("scope-two", "pat-two", "me");
        when(workspaceHosting.captureRead("workspace")).thenReturn(first, first, second);
        Map<String, String> settings = new HashMap<>();
        when(syncSettings.get(anyString())).thenAnswer(invocation -> Optional.ofNullable(settings.get(invocation.<String>getArgument(0))));
        doAnswer(invocation -> {
            settings.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(syncSettings).put(anyString(), anyString());
        when(store.findRepositoryPage("owner/repo", 0, 1)).thenReturn(new PullRequestPage(ImmutableList.of(storedPullRequest()), 0, 1, 1, 0, false));
        when(requests.refreshPage(any(), eq(1L), anyBoolean(), eq(1), eq(100)))
                .thenReturn(new PullRequestListPage(ImmutableList.of(), false));

        service.syncWorkspace("workspace");
        now.set(REVIEWED_AT.plusSeconds(60));
        service.syncWorkspace("workspace");
        now.set(REVIEWED_AT.plusSeconds(120));
        service.syncWorkspace("workspace");

        verify(requests, times(2)).refreshPage(first.session(), 1, false, 1, 100);
        verify(requests).refreshPage(first.session(), 1, true, 1, 100);
        verify(requests).refreshPage(second.session(), 1, false, 1, 100);
        verify(requests).refreshPage(second.session(), 1, true, 1, 100);
        assertThat(settings.keySet()).hasSize(2).anySatisfy(key -> assertThat(key).contains("scope-one"))
                .anySatisfy(key -> assertThat(key).contains("scope-two"));
    }

    @Test
    void exactFetchesCreatedPullRequestIntoWorkspaceProjection()
    {
        WorkspaceHostingRead route = workspaceRoute();
        FetchedPullRequest fetched = pullRequest();
        when(workspaceHosting.readPullRequest(route, 7)).thenReturn(snapshot(fetched));

        service.observeWorkspacePullRequest("workspace", 7);

        verify(store).saveRepositorySyncPage(eq("owner/repo"), eq(ImmutableList.of(fetched.withViewerOrigin("me"))));
        verify(gitHub, never()).searchPullRequestsPaged(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void unchangedDefaultDiffRetainsBytesAndAdvancesItsOwnValidation()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        StoredPrDiff stored = new StoredPrDiff(
                ImmutableList.of(new DiffFile("a.txt", "modified", 1, 0, "+line")), ImmutableList.of(), ImmutableMap.of(),
                Optional.of("base"), Optional.of("head"), Optional.of(REVIEWED_AT));
        when(diffStore.find(12L, null, null)).thenReturn(Optional.of(stored));
        when(diffStore.findDefaultEtags(12L))
                .thenReturn(new PrDiffEtags(Optional.of("\"files-1\""), Optional.of("\"commits-1\"")));
        when(gitHub.fetchPrDiffFiles(eq("pat"), eq(ref), anyList(), eq(Optional.of("\"files-1\""))))
                .thenReturn(new ConditionalList<>(Optional.empty(), Optional.of("\"files-1\"")));
        when(gitHub.fetchPrDiffCommits(eq("pat"), eq(ref), anyList(), eq(Optional.of("\"commits-1\""))))
                .thenReturn(new ConditionalList<>(Optional.empty(), Optional.of("\"commits-1\"")));

        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(requests.refresh(session, 20, ref.number(), true)).thenReturn(snapshot(pullRequest()));
        snapshots.refreshDefaultDiff(read(ref), 12L);

        verify(diffStore).saveDefault(eq(12L), any(), any());
    }

    @Test
    void changedCommitsKeepUnchangedFilesAndTheirExpandedBodies()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        List<DiffFile> files = ImmutableList.of(new DiffFile("a.txt", "modified", 1, 0, "+line"));
        StoredPrDiff stored = new StoredPrDiff(
                files, ImmutableList.of(), ImmutableMap.of("a.txt", new DiffFileContent("old", "new")), Optional.of("base"), Optional.of("head"), Optional.of(REVIEWED_AT));
        List<DiffCommit> commits = ImmutableList.of(new DiffCommit("sha1", null, "reworded", "me", Instant.parse("2026-08-27T00:00:00Z")));
        when(diffStore.find(12L, null, null)).thenReturn(Optional.of(stored));
        when(diffStore.findDefaultEtags(12L))
                .thenReturn(new PrDiffEtags(Optional.of("\"files-1\""), Optional.of("\"commits-1\"")));
        when(gitHub.fetchPrDiffFiles(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.empty(), Optional.of("\"files-1\"")));
        when(gitHub.fetchPrDiffCommits(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.of(commits), Optional.of("\"commits-2\"")));

        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(requests.refresh(session, 20, ref.number(), true)).thenReturn(snapshot(pullRequest()));
        snapshots.refreshDefaultDiff(read(ref), 12L);

        ArgumentCaptor<StoredPrDiff> saved = ArgumentCaptor.forClass(StoredPrDiff.class);
        verify(diffStore).saveDefault(eq(12L), saved.capture(), eq(new PrDiffEtags(Optional.of("\"files-1\""), Optional.of("\"commits-2\""))));
        assertThat(saved.getValue().files()).isEqualTo(files);
        assertThat(saved.getValue().commits()).isEqualTo(commits);
        assertThat(saved.getValue().fileContents()).containsKey("a.txt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "previous-scope"})
    void explicitReadsClearOnlyRemoteProjectionsWhenTheirCredentialOwnerIsMissingOrDifferent(String owner)
    {
        AtomicReference<Optional<StoredPrDetail>> cached = new AtomicReference<>(Optional.of(stored()));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.findCredentialScope(12L)).thenReturn(owner.isEmpty() ? Optional.empty() : Optional.of(owner));
        when(detailStore.find(12L)).thenAnswer(ignored -> cached.get());
        doAnswer(ignored -> {
            cached.set(Optional.empty());
            return null;
        }).when(detailStore).clearRemote(12L);

        assertThat(service.findPullRequestDetail(read(PullRequestRef.of("owner", "repo", 7)))).isEmpty();

        verify(detailStore).clearRemote(12L);
        verify(diffStore).deleteByPrIds(ImmutableSet.of(12L));
        verify(detailStore, never()).deleteByPrIds(any());
        verifyNoInteractions(viewStateStore, writes, recent);
    }

    @Test
    void explicitDetailReadClearsAnotherAccountsRemoteProjection()
    {
        HostingInstance instance = new HostingInstance(1, GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
        HostingReadSession selected = new HostingReadSession(11, "connected-account", new HostingAccess(instance, "selected-token"));
        PullRequestRead route = new PullRequestRead(selected, hostingRepository("owner/repo"), 7, "alice");
        AtomicReference<Optional<StoredPrDetail>> cached = new AtomicReference<>(Optional.of(stored()));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenAnswer(ignored -> cached.get());
        doAnswer(ignored -> {
            cached.set(Optional.empty());
            return null;
        }).when(detailStore).clearRemote(12L);

        assertThat(service.findPullRequestDetail(route)).isEmpty();

        verify(detailStore).clearRemote(12L);
        verify(diffStore).deleteByPrIds(ImmutableSet.of(12L));
        verify(detailStore, never()).deleteByPrIds(any());
        verifyNoInteractions(credentialService, connections, requests, writes, viewStateStore);
    }

    @ParameterizedTest
    @EnumSource(value = HostingFailure.Kind.class, names = {"AUTHENTICATION_REQUIRED", "FORBIDDEN", "MISSING_OR_INACCESSIBLE"})
    void inaccessibleMetadataClearsDesktopRemoteProjectionsWithoutDeletingLocalIdentity(HostingFailure.Kind kind)
    {
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(Instant.EPOCH), Optional.of(Instant.now()), Optional.of(Instant.now()),
                Optional.of(pullRequest().updatedAt())));
        HostingFailure denied = new HostingFailure(kind, "request inaccessible");
        when(requests.refresh(session, 20, 7, false)).thenThrow(denied);

        assertThatThrownBy(() -> service.refreshPullRequestDetailFromGitHub("owner/repo", 7)).isSameAs(denied);

        verify(detailStore).clearRemote(12L);
        verify(diffStore).deleteByPrIds(ImmutableSet.of(12L));
        verify(detailStore, never()).deleteByPrIds(any());
        verify(store, never()).saveObserved(any());
        verifyNoInteractions(viewStateStore, writes, recent);
    }

    @Test
    void readsCachedDetail()
    {
        StoredPrDetail stored = stored();
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored));

        PullRequestDetail result = service.getPullRequestDetail("owner/repo", 7);

        assertThat(result.body()).isEqualTo("body");
        assertThat(result.approvalCount()).isEqualTo(1);
        assertThat(result.viewerCanWrite()).isTrue();
        verifyNoInteractions(credentialService);
    }

    @Test
    void onlyOpeningDetailMarksThePullRequestViewed()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));

        service.getPullRequestDetail("owner/repo", 7);
        verify(viewStateStore, never()).markViewed(12L);

        service.openPullRequestDetail("owner/repo", 7);
        verify(viewStateStore).markViewed(12L);
    }

    @Test
    void detailReadDoesNotMutateTheStoredListRow()
    {
        Instant updatedAt = Instant.parse("2026-08-21T01:00:00Z");
        Instant mergedAt = Instant.parse("2026-08-21T00:59:00Z");
        StoredPrDetail stored = new StoredPrDetail(
                new PrRawDetail(
                        "title", "url", "merged", "alice", null, null, updatedAt, mergedAt,
                        "body", ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, false, "unknown",
                        1, 1, 1, 0, ImmutableList.of(), "head", "feature", "main"),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored));

        service.getPullRequestDetail("owner/repo", 7);

        verify(store, never()).updateDetails(anyLong(), any());
    }

    @Test
    void missingDetailReturnsTheFreshResultWithoutRereadingTheCache()
    {
        PullRequestDetail refreshed = PullRequestDetailMapper.toPullRequestDetail(12L, "owner/repo", 7, stored(), true);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.empty());
        service = spy(service);
        doReturn(new PullRequestService.DetailRefresh(true, refreshed))
                .when(service).refreshPullRequestDetailFromGitHub("owner/repo", 7);

        PullRequestDetail result = service.getPullRequestDetail("owner/repo", 7);

        assertThat(result.body()).isEqualTo("body");
        verify(service).refreshPullRequestDetailFromGitHub("owner/repo", 7);
        verify(detailStore).find(12L);
    }

    @Test
    void missingDetailReturnsGitHubNotFound()
    {
        ResponseStatusException notFound = new ResponseStatusException(HttpStatus.NOT_FOUND, "Pull request does not exist");
        service = spy(service);
        doThrow(notFound).when(service).refreshPullRequestDetailFromGitHub("owner/repo", 7);

        assertThatThrownBy(() -> service.getPullRequestDetail("owner/repo", 7))
                .isSameAs(notFound);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void fullDetailRefreshPreservesStoredIdentityAndNotificationReviewRequests(boolean backgroundWarm, boolean reviewRequested)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        FetchedPullRequest pullRequest = pullRequest(99, "open", Instant.parse("2026-08-21T00:00:00Z"), null);
        List<DiffFile> files = ImmutableList.of(new DiffFile("a.txt", "modified", 1, 0, "+line"));
        cacheDetail(null, PrDetailFreshness.unknown());
        when(diffStore.find(12L, null, null)).thenReturn(Optional.empty());
        when(diffStore.defaultDiffValidated(12L, "base", "head")).thenReturn(false);
        // the store resolves the row on repo and number and hands back the stored row
        when(store.saveObserved(any())).thenAnswer(invocation -> storedPullRequest(invocation.getArgument(0)));
        when(notifications.reviewRequests("me")).thenReturn(reviewRequested ? ImmutableSet.of(ref) : ImmutableSet.of());
        when(requests.refresh(session, 20, ref.number(), false)).thenReturn(snapshot(pullRequest));
        when(requests.refresh(session, 20, ref.number(), true)).thenReturn(snapshot(pullRequest));
        when(accounts.fetchViewerCanWrite("pat", RepoRef.of("owner", "repo"))).thenReturn(true);
        when(gitHub.fetchPrReviews("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrTimeline("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrCommits("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrReviewThreads("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrDescriptionReactions("pat", ref)).thenReturn(ImmutableList.of());
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty()));
        when(viewStateStore.findByPrIds(ImmutableSet.of(12L)))
                .thenReturn(ImmutableMap.of());
        when(gitHub.fetchPrDiffFiles(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.of(files), Optional.of("\"files-1\"")));
        when(gitHub.fetchPrDiffCommits(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.of(ImmutableList.of()), Optional.of("\"commits-1\"")));

        if (backgroundWarm) {
            when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(storedPullRequest(pullRequest)));
            service.warmRecentDetails(10);
        }
        else {
            PullRequestService.DetailRefresh refreshed = service.refreshPullRequestDetailFromGitHub("owner/repo", 7);
            assertThat(refreshed.full()).isTrue();
            assertThat(refreshed.detail().body()).isEqualTo("body");
        }

        ArgumentCaptor<FetchedPullRequest> observed = ArgumentCaptor.forClass(FetchedPullRequest.class);
        verify(store).saveObserved(observed.capture());
        assertThat(observed.getValue().githubId()).isEqualTo(99L);
        assertThat(observed.getValue().origin()).isEqualTo(reviewRequested ? PullRequest.Origin.REVIEW_REQUESTED : PullRequest.Origin.OTHER);
        ArgumentCaptor<PullRequest.Origin> enrichedOrigin = ArgumentCaptor.forClass(PullRequest.Origin.class);
        verify(store).updateEnrichment(eq(12L), any(), any(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any(), anyList(), enrichedOrigin.capture(), any(), any(), any());
        assertThat(enrichedOrigin.getValue()).isEqualTo(reviewRequested ? PullRequest.Origin.REVIEW_REQUESTED : PullRequest.Origin.OTHER);
        ArgumentCaptor<StoredPrDetail> detail = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).saveDiscussion(eq(12L), detail.capture(), any());
        assertThat(detail.getValue().viewerCanWrite()).isTrue();
        verify(detailStore, never()).updateEtag(anyLong(), any());
        verify(detailStore, times(3)).setCredentialScope(12L, read(PullRequestRef.of("owner", "repo", 7)).scope());
        verify(diffStore).saveDefault(eq(12L), any(StoredPrDiff.class), eq(new PrDiffEtags(Optional.of("\"files-1\""), Optional.of("\"commits-1\""))));
    }

    @Test
    void failedDiffRefreshRetriesOnlyTheDiffAndPreservesItsCachedBytes()
    {
        cacheDetail(stored(), freshTimes());
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        List<DiffFile> oldFiles = ImmutableList.of(new DiffFile("old.txt", "modified", 1, 0, "+old"));
        List<DiffFile> newFiles = ImmutableList.of(new DiffFile("new.txt", "modified", 1, 0, "+new"));
        when(diffStore.find(12L, null, null)).thenReturn(Optional.of(new StoredPrDiff(oldFiles, ImmutableList.of(), ImmutableMap.of(),
                Optional.of("base"), Optional.of("old-head"), Optional.of(REVIEWED_AT))));
        when(diffStore.defaultDiffValidated(12L, "base", "head")).thenReturn(false);
        when(gitHub.fetchPrDiffFiles("pat", ref, oldFiles, Optional.empty()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Diff unavailable"))
                .thenReturn(new ConditionalList<>(Optional.of(newFiles), Optional.of("new-files")));
        when(gitHub.fetchPrDiffCommits(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.of(ImmutableList.of()), Optional.of("commits")));
        when(requests.refresh(session, 20, ref.number(), true)).thenReturn(snapshot(pullRequest()));

        service.refreshPullRequestDetailFromGitHub("owner/repo", 7);
        verify(diffStore, never()).saveDefault(anyLong(), any(), any());
        service.refreshPullRequestDetailFromGitHub("owner/repo", 7);

        verify(gitHub, times(2)).fetchPrDiffFiles("pat", ref, oldFiles, Optional.empty());
        verify(diffStore).saveDefault(eq(12L), any(), eq(new PrDiffEtags(Optional.of("new-files"), Optional.of("commits"))));
        verify(detailStore, never()).updateEtag(anyLong(), any());
        verify(gitHub, never()).fetchPrIssueComments(anyString(), any());
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void refreshesChangedPermissionsEvenForUnchangedMetadata(boolean force, boolean canWrite)
    {
        StoredPrDetail old = stored();
        cacheDetail(new StoredPrDetail(old.raw(), old.reviews(), old.timeline(), old.checkRuns(), old.unavailableSections(), !canWrite),
                new PrDetailFreshness(Optional.of(Instant.EPOCH), Optional.of(Instant.now()), Optional.of(Instant.EPOCH), Optional.of(pullRequest().updatedAt())));
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(requests.refresh(session, 20, ref.number(), force)).thenReturn(snapshot(pullRequest()));
        when(accounts.fetchViewerCanWrite("pat", RepoRef.of("owner", "repo"))).thenReturn(canWrite);
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty()));
        if (force) {
            when(store.saveObserved(any())).thenReturn(storedPullRequest());
            when(gitHub.fetchPrDiffFiles(eq("pat"), eq(ref), anyList(), any()))
                    .thenReturn(new ConditionalList<>(Optional.of(ImmutableList.of()), Optional.empty()));
            when(gitHub.fetchPrDiffCommits(eq("pat"), eq(ref), anyList(), any()))
                    .thenReturn(new ConditionalList<>(Optional.of(ImmutableList.of()), Optional.empty()));
        }

        PullRequestDetail refreshed = force
                ? service.refreshAllPullRequestDetailFromGitHub("owner/repo", 7)
                : service.refreshPullRequestDetailFromGitHub("owner/repo", 7).detail();

        assertThat(refreshed.viewerCanWrite()).isEqualTo(canWrite);
        assertThat(detailStore.find(12L).orElseThrow().viewerCanWrite()).isEqualTo(canWrite);
        assertThat(PullRequestDetailRefresh.status(refreshed).status().viewerCanWrite()).isEqualTo(canWrite);
        verify(accounts).fetchViewerCanWrite("pat", RepoRef.of("owner", "repo"));
    }

    @Test
    void unchangedSharedMetadataKeepsDiscussionAndRefreshesOnlyStatuses()
    {
        PrCheckRunState latest = new PrCheckRunState(2L, "build", "completed", "failure", null, "check_run", null, null);
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(Instant.EPOCH), Optional.of(Instant.now()), Optional.of(Instant.EPOCH), Optional.of(pullRequest().updatedAt())));
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(requests.refresh(session, 20, ref.number(), false)).thenReturn(snapshot(pullRequest()));
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(latest), Optional.of("checks"), Optional.of("statuses")));

        PullRequestService.DetailRefresh refreshed = service.refreshPullRequestDetailFromGitHub("owner/repo", 7);

        assertThat(refreshed.full()).isFalse();
        assertThat(refreshed.detail().body()).isEqualTo("body");
        verify(gitHub, never()).fetchPrIssueComments(anyString(), any());
        verify(store).saveObserved(pullRequest().withViewerOrigin("me"));
        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).saveStatus(eq(12L), saved.capture());
        assertThat(saved.getValue().raw().mergeable()).isTrue();
        assertThat(saved.getValue().reviews()).isEqualTo(stored().reviews());
        assertThat(saved.getValue().checkRuns()).containsExactly(latest);
        verify(detailStore, never()).updateEtag(anyLong(), any());
        verify(detailStore).updateStatusEtags(12L, Optional.of("checks"), Optional.of("statuses"));
        verify(gitHub, never()).fetchPrReviews(anyString(), any());
        verify(gitHub, never()).fetchPrDiffFiles(anyString(), any(), anyList(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-60, 60})
    void detailRefreshNeverRegressesTheLatestObservedHeadTimestamp(Long commitOffset)
    {
        Instant observedPush = REVIEWED_AT.plusSeconds(3600);
        PullRequest beforeSummary = attentionPullRequest(12, null, null, null, null);
        PullRequest current = attentionPullRequest(12, null, null, observedPush, null);
        List<PrTimelineEvent> timeline = commitOffset == null ? ImmutableList.of() : ImmutableList.of(
                new PrTimelineEvent(1L, "committed", "alice", observedPush.plusSeconds(commitOffset), "Commit", Optional.empty()));
        StoredPrDetail cached = new StoredPrDetail(rawAt(REVIEWED_AT), ImmutableList.of(), timeline, ImmutableList.of(), ImmutableList.of(), false);
        when(store.findIdByRepoAndNumber("owner/repo", 12)).thenReturn(Optional.of(12L));
        when(store.findById(12L)).thenReturn(Optional.of(beforeSummary), Optional.of(current));
        when(requests.cached(session, 20, 12)).thenReturn(Optional.of(snapshot(pullRequestNumber(12), cached.raw())));
        when(detailStore.find(12L)).thenReturn(Optional.of(cached));
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty()));

        service.refreshPullRequestChecksFromGitHub("owner/repo", 12);

        ArgumentCaptor<Instant> pushedAt = ArgumentCaptor.forClass(Instant.class);
        verify(store).updateEnrichment(eq(12L), any(), any(), anyInt(), anyInt(), anyInt(), any(), any(), pushedAt.capture(), any(), anyList(), any(), any(), any(), any());
        assertThat(pushedAt.getValue()).isEqualTo(commitOffset != null && commitOffset > 0 ? observedPush.plusSeconds(commitOffset) : observedPush);
    }

    @Test
    void manualDetailRefreshForcesSharedMetadataRevalidation()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        IllegalStateException failure = new IllegalStateException("stop after request");
        when(requests.refresh(session, 20, ref.number(), true)).thenThrow(failure);

        assertThatThrownBy(() -> service.refreshAllPullRequestDetailFromGitHub("owner/repo", 7))
                .isSameAs(failure);

        verify(detailStore, never()).findEtag(anyLong());
    }

    @Test
    void detailRefreshAppliesChangedMergeabilityFromThePrEndpoint()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        FetchedPullRequest conflicted = pullRequest("open", pullRequest().updatedAt(), null, false, "dirty");
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(Instant.EPOCH), Optional.of(Instant.now()), Optional.of(Instant.now()), Optional.of(conflicted.updatedAt())));
        when(requests.refresh(session, 20, ref.number(), false)).thenReturn(snapshot(conflicted, rawAt(pullRequest().updatedAt(), false, "dirty")));
        when(store.saveObserved(any())).thenReturn(storedPullRequest(conflicted));
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty()));

        PullRequestDetail refreshed = service.refreshPullRequestDetailFromGitHub("owner/repo", 7).detail();

        assertThat(refreshed.mergeable()).isFalse();
        assertThat(refreshed.mergeableState()).isEqualTo("dirty");
        verify(detailStore, never()).updateEtag(anyLong(), any());
        verify(gitHub, never()).fetchPrReviews(anyString(), any());
    }

    @Test
    void postsCommentAndPatchesCachedTimeline()
    {
        PrTimelineEvent posted = new PrTimelineEvent(99L, "commented", "me", Instant.now(), "hello", Optional.empty());
        when(writes.createIssueComment("pat", PullRequestRef.of("owner", "repo", 7), "hello"))
                .thenReturn(posted);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));

        PullRequestDetail detail = service.commentOnPullRequest("owner/repo", 7, "hello");

        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).saveCached(eq(12L), saved.capture());
        assertThat(saved.getValue().timeline()).contains(posted);
        assertThat(detail.recentActivity())
                .extracting(PullRequestDetail.ActivityItem::githubId)
                .containsExactly(99L);
        verify(detailStore, never()).deleteByPrIds(any());
        verifyNoInteractions(diffStore, gitHub);
    }

    @Test
    void postsInlineCommentAndReturnsPatchedCachedTimeline()
    {
        InlineReviewComment comment = new InlineReviewComment("src/Main.java", "Please simplify this", 18, "RIGHT", 16, "RIGHT", "head", OptionalLong.empty());
        PrTimelineEvent posted = new PrTimelineEvent(99L, "review_thread", "me", Instant.now(), null, Optional.empty());
        StoredPrDetail cached = stored();
        cached = new StoredPrDetail(cached.raw(), cached.reviews(), ImmutableList.of(posted), cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite());
        when(writes.createReviewComment("pat", PullRequestRef.of("owner", "repo", 7), comment))
                .thenReturn(posted);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(cached));

        PullRequestDetail detail = service.createReviewComment("owner/repo", 7, comment).detail();

        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).saveCached(eq(12L), saved.capture());
        assertThat(saved.getValue().timeline()).containsExactly(posted);
        assertThat(detail.recentActivity())
                .extracting(PullRequestDetail.ActivityItem::githubId)
                .containsExactly(99L);
        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.COMMENTED), eq("head"), any(Instant.class));
        verify(detailStore, never()).deleteByPrIds(any());
        verifyNoInteractions(diffStore, gitHub);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {true, false})
    void routesEachSupportedReactionTargetThroughTheViewerCredential(Boolean viewerHasReacted)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);

        service.toggleReaction("owner/repo", 7, " pull_request ", null, " HEART ", viewerHasReacted);
        service.toggleReaction("owner/repo", 7, "issue_comment", 41L, "+1", viewerHasReacted);
        service.toggleReaction("owner/repo", 7, "review_comment", 42L, "eyes", viewerHasReacted);

        verify(writes).togglePullRequestReaction("pat", ref, "heart", viewerHasReacted);
        verify(writes).toggleIssueCommentReaction("pat", ref, 41, "+1", viewerHasReacted);
        verify(writes).toggleReviewCommentReaction("pat", ref, 42, "eyes", viewerHasReacted);
    }

    @Test
    void setsKnownReactionStateAndMarksCachedDetailForRefresh()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(writes.toggleIssueCommentReaction("pat", ref, 41, "heart", false)).thenReturn(false);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        assertThat(service.toggleReaction("owner/repo", 7, "issue_comment", 41L, "heart", false)).isFalse();

        verify(writes).toggleIssueCommentReaction("pat", ref, 41, "heart", false);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void preservesCachedDetailWhenReactionWriteFails()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(writes.toggleIssueCommentReaction("pat", ref, 41, "heart", false))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "issue_comment", 41L, "heart", false))
                .isInstanceOf(ResponseStatusException.class);

        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepsTheReactionResultWhenLocalInvalidationFails(boolean reacted)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(writes.toggleIssueCommentReaction("pat", ref, 41, "heart", false)).thenReturn(reacted);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        doThrow(new IllegalStateException("database is locked")).when(detailStore).updateEtag(12L, Optional.empty());

        assertThat(service.toggleReaction("owner/repo", 7, "issue_comment", 41L, "heart", false)).isEqualTo(reacted);

        verify(writes).toggleIssueCommentReaction("pat", ref, 41, "heart", false);
        verify(detailStore).updateEtag(12L, Optional.empty());
    }

    @Test
    void keepsTheGitHubReactionFailureWhenLocalInvalidationAlsoFails()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.FORBIDDEN, "Reaction was refused");
        when(writes.toggleIssueCommentReaction("pat", ref, 41, "heart", false)).thenThrow(failure);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        doThrow(new IllegalStateException("database is locked")).when(detailStore).updateEtag(12L, Optional.empty());

        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "issue_comment", 41L, "heart", false)).isSameAs(failure);

        verify(detailStore).updateEtag(12L, Optional.empty());
    }

    @Test
    void updatesTheTitleOnGitHubAndInBothCachedProjections()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        StoredPrDetail cached = stored();
        PrRawDetail expected = cached.raw().withTitle("Updated title");
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(cached));

        service.updateTitle("owner/repo", 7, "  Updated title  ");

        var order = inOrder(writes, detailStore, store);
        order.verify(writes).updatePullRequestTitle("pat", ref, "Updated title");
        order.verify(detailStore).saveCached(12L, new StoredPrDetail(expected, cached.reviews(), cached.timeline(), cached.checkRuns(),
                cached.unavailableSections(), cached.viewerCanWrite()));
        order.verify(store).updateTitle(12L, "Updated title");
        verify(recent).pullRequestAction("owner/repo", 7, RecentReason.EDITED, 0);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(requests).invalidate(GITHUB_SERVER, "owner/repo", 7);
        verifyNoInteractions(diffStore);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void rejectsEmptyTitlesBeforeWriting(String title)
    {
        assertThatThrownBy(() -> service.updateTitle("owner/repo", 7, title)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(writes, store, detailStore, recent);
    }

    @Test
    void preservesTheTitleWhenGitHubRejectsTheEdit()
    {
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.FORBIDDEN);
        doThrow(failure).when(writes).updatePullRequestTitle("pat", PullRequestRef.of("owner", "repo", 7), "Updated title");

        assertThatThrownBy(() -> service.updateTitle("owner/repo", 7, "Updated title")).isSameAs(failure);

        verifyNoInteractions(store, detailStore, recent);
    }

    @Test
    void updatesDescriptionAndMarksCachedDetailForRefresh()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.updateDescription("owner/repo", 7, "Updated description");

        verify(writes).updatePullRequestDescription("pat", ref, "Updated description");
        verify(recent).pullRequestAction("owner/repo", 7, RecentReason.EDITED, 0);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void rejectsInvalidReactionTargetsAndContentBeforeWriting()
    {
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "pull_request", null, "smile", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "pull_request", 41L, "heart", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "issue_comment", null, "heart", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "review_comment", 0L, "heart", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "commit", 41L, "heart", null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(writes);
    }

    @ParameterizedTest
    @CsvSource({"issue_comment, COMMENT, comment:41", "review_comment, COMMENT, review-comment:41", "review, REVIEW_BODY, review:41"})
    void editsACommentOnGitHubBeforePatchingOnlyThatTargetsCachedCopy(String target, RecentTextKind kind, String textKey)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        // Every cached copy shares id 41, so a patch that ignored the target type would change another body too.
        StoredPrDetail expected = switch (target) {
            case "issue_comment" -> editableDetail("edited", "old reply", "old review");
            case "review_comment" -> editableDetail("old comment", "edited", "old review");
            default -> editableDetail("old comment", "old reply", "edited");
        };
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(editableDetail("old comment", "old reply", "old review")));

        PullRequestDetail detail = service.editComment("owner/repo", 7, " " + target.toUpperCase(Locale.ROOT) + " ", 41L, "edited");

        var order = inOrder(writes, detailStore);
        switch (target) {
            case "issue_comment" -> order.verify(writes).updateIssueComment("pat", ref, 41L, "edited");
            case "review_comment" -> order.verify(writes).updateReviewComment("pat", ref, 41L, "edited");
            default -> order.verify(writes).updateReviewBody("pat", ref, 41L, "edited");
        }
        order.verify(detailStore).saveCached(12L, expected);
        assertThat(detail).isEqualTo(PullRequestDetailMapper.toPullRequestDetail(12L, "owner/repo", 7, expected, true));
        verify(recent).pullRequestAction("owner/repo", 7, RecentReason.EDITED, 0);
        verify(recent).wroteOnPullRequest("owner/repo", 7, kind, textKey, "edited");
        verifyNoMoreInteractions(writes);
        verifyNoInteractions(gitHub, diffStore);
    }

    @Test
    void failedCommentEditLeavesTheCachedDetailAndRecentUntouched()
    {
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.FORBIDDEN, "Must be the comment author");
        doThrow(failure).when(writes).updateReviewComment("pat", PullRequestRef.of("owner", "repo", 7), 41L, "edited");
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));

        assertThatThrownBy(() -> service.editComment("owner/repo", 7, "review_comment", 41L, "edited")).isSameAs(failure);

        verify(detailStore, never()).saveCached(anyLong(), any());
        verifyNoInteractions(diffStore, recent);
    }

    @Test
    void keepsAnEditedCommentWhenTheLocalDetailBecomesUnavailableAfterTheWrite()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        StoredPrDetail cached = editableDetail("old comment", "old reply", "old review");
        AtomicBoolean written = new AtomicBoolean();
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenAnswer(invocation -> {
            if (written.get()) {
                throw new IllegalStateException("database is locked");
            }
            return Optional.of(cached);
        });
        doAnswer(invocation -> {
            written.set(true);
            return null;
        }).when(writes).updateReviewComment("pat", ref, 41L, "edited");

        PullRequestDetail detail = service.editComment("owner/repo", 7, "review_comment", 41L, "edited");

        assertThat(detail).isEqualTo(PullRequestDetailMapper.toPullRequestDetail(12L, "owner/repo", 7, cached, true));
        verify(writes).updateReviewComment("pat", ref, 41L, "edited");
        verify(recent).wroteOnPullRequest("owner/repo", 7, RecentTextKind.COMMENT, "review-comment:41", "edited");
        verifyNoInteractions(gitHub);
    }

    @Test
    void readsTheFallbackDetailBeforeEditingSoAFailedReadSendsNothing()
    {
        IllegalStateException failure = new IllegalStateException("database is locked");
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenThrow(failure);

        assertThatThrownBy(() -> service.editComment("owner/repo", 7, "review_comment", 41L, "edited")).isSameAs(failure);

        verifyNoInteractions(writes, recent);
    }

    @Test
    void rejectsInvalidCommentEditsBeforeWriting()
    {
        for (ThrowingCallable edit : ImmutableList.<ThrowingCallable>of(
                () -> service.editComment("owner/repo", 7, null, 41L, "edited"),
                () -> service.editComment("owner/repo", 7, "review_comment", 41L, " "),
                () -> service.editComment("owner/repo", 7, "review_comment", 41L, null))) {
            assertThatThrownBy(edit).isInstanceOf(IllegalArgumentException.class);
        }
        for (ThrowingCallable edit : ImmutableList.<ThrowingCallable>of(
                () -> service.editComment("owner/repo", 7, "pull_request", 41L, "edited"),
                () -> service.editComment("owner/repo", 7, "issue_comment", 0L, "edited"),
                () -> service.editComment("owner/repo", 7, "review", -1L, "edited"))) {
            assertThatThrownBy(edit).isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(credentialService, writes, store, detailStore, recent);
    }

    private static StoredPrDetail editableDetail(String commentBody, String replyBody, String reviewBody)
    {
        PrTimelinePayload thread = PrTimelinePayload.builder()
                .path("a.txt")
                .line(4)
                .side("RIGHT")
                .comments(ImmutableList.of(
                        new PrTimelineComment(40L, "me", null, false, REVIEWED_AT, "root", null, 41L, null, ImmutableList.of()),
                        new PrTimelineComment(41L, "me", null, false, REVIEWED_AT, replyBody, null, 41L, 40L, ImmutableList.of())))
                .build();
        return new StoredPrDetail(
                raw(),
                ImmutableList.of(new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null), review(41L, "COMMENTED", reviewBody)),
                ImmutableList.of(
                        new PrTimelineEvent(41L, "commented", "me", REVIEWED_AT, commentBody, Optional.empty()),
                        new PrTimelineEvent(41L, "reviewed", "me", REVIEWED_AT, reviewBody, PrTimelinePayload.builder().state("COMMENTED").build(), Optional.empty()),
                        new PrTimelineEvent(40L, "review_thread", "me", REVIEWED_AT, null, thread, Optional.empty())),
                ImmutableList.of(),
                ImmutableList.of(),
                true);
    }

    @Test
    void closesPullRequestAndInvalidatesDetail()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.closePullRequest("owner/repo", 7);

        verify(writes).closePullRequest("pat", PullRequestRef.of("owner", "repo", 7));
        verify(detailStore).clearRemote(12L);
    }

    @Test
    void marksDraftReadyForReviewAndPreservesTheSnapshotForRefresh()
    {
        when(writes.pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(Optional.of("PR_7"));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.markReadyForReview("owner/repo", 7);

        verify(writes).pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7));
        verify(writes).markReadyForReview("pat", "PR_7");
        verify(recent).pullRequestAction("owner/repo", 7, RecentReason.EDITED, 0);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(writes, detailStore);
        verifyNoInteractions(diffStore);
    }

    @Test
    void failedReadyForReviewInvalidatesTheSnapshotWithoutRetryingTheWrite()
    {
        when(writes.pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(Optional.of("PR_7"));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ready-for-review response is uncertain");
        doThrow(failure).when(writes).markReadyForReview("pat", "PR_7");

        assertThatThrownBy(() -> service.markReadyForReview("owner/repo", 7)).isSameAs(failure);

        verify(writes).pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7));
        verify(writes).markReadyForReview("pat", "PR_7");
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(writes, detailStore);
        verifyNoInteractions(recent, diffStore);
    }

    @Test
    void rejectsReadyForReviewWhenGitHubReturnsNoNodeId()
    {
        when(writes.pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markReadyForReview("owner/repo", 7))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        verify(writes).pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7));
        verifyNoMoreInteractions(writes);
        verifyNoInteractions(detailStore, recent);
    }

    @Test
    void repliesToReviewCommentAndInvalidatesDetail()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.replyToReviewComment("owner/repo", 7, 99L, "fixed");

        verify(writes).replyToReviewComment("pat", PullRequestRef.of("owner", "repo", 7), 99L, "fixed");
        verify(detailStore).clearRemote(12L);
    }

    @Test
    void changesReviewThreadResolutionAndMarksDetailForRefresh()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(gitHub.findReviewThreadId("pat", ref, 99L)).thenReturn("PRRT_1");
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.setReviewThreadResolved("owner/repo", 7, 99L, true);

        verify(writes).setReviewThreadResolved("pat", "PRRT_1", true);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void submitsReviewVerdictsAndPatchesTheCachedDetail()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(writes.submitReview("pat", PullRequestRef.of("owner", "repo", 7), "REQUEST_CHANGES", "Please fix this", ImmutableList.of(), "head"))
                .thenReturn(review(501L, "REQUEST_CHANGES", "Please fix this"));

        PullRequestDetail detail = service.submitReview("owner/repo", 7, 12L, "request_changes", "Please fix this", ImmutableList.of(), "head").detail();

        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.CHANGES_REQUESTED), eq("head"), any(Instant.class));
        // The submitted review is spliced into the cache, so nothing is thrown away
        // and the caller needs no follow-up fetch.
        verify(detailStore, never()).deleteByPrIds(any());
        verify(gitHub, never()).fetchReviewComments(anyString(), any(), anyLong());
        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).saveCached(eq(12L), saved.capture());
        assertThat(saved.getValue().reviews()).extracting(PrReviewState::githubId).contains(501L);
        assertThat(saved.getValue().timeline()).singleElement().satisfies(review -> {
            assertThat(review.githubId()).isEqualTo(501L);
            assertThat(review.event()).isEqualTo("reviewed");
            assertThat(review.actor()).isEqualTo("me");
            assertThat(review.body()).isEqualTo("Please fix this");
            assertThat(review.payload().state()).isEqualTo("REQUEST_CHANGES");
        });
        assertThat(detail.recentActivity()).singleElement().satisfies(review -> {
            assertThat(review.githubId()).isEqualTo(501L);
            assertThat(review.eventType()).isEqualTo("reviewed");
            assertThat(review.body()).isEqualTo("Please fix this");
            assertThat(review.payload().state()).isEqualTo("REQUEST_CHANGES");
        });
    }

    @Test
    void approvesTheViewedCommitEvenWhenTheCacheHasANewerHead()
    {
        String viewedCommit = "a".repeat(40);
        String newerCommit = "b".repeat(40);
        PrRawDetail current = new PrRawDetail(null, null, null, null, null, null, REVIEWED_AT, null, "body", ImmutableList.of(), null, ImmutableList.of(), null,
                false, true, "clean", 4, 2, 1, 0, ImmutableList.of(), newerCommit, "feature", "main", "base");
        StoredPrDetail cached = new StoredPrDetail(current, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(cached));
        when(writes.submitReview("pat", ref, "APPROVE", "", ImmutableList.of(), viewedCommit))
                .thenReturn(new PrReviewState(501L, "me", null, false, "APPROVED", REVIEWED_AT, "", null, viewedCommit));

        PullRequestService.Posted posted = service.submitReview("owner/repo", 7, 12L, "APPROVE", "", ImmutableList.of(), viewedCommit);

        verify(writes).submitReview("pat", ref, "APPROVE", "", ImmutableList.of(), viewedCommit);
        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.APPROVED), eq(viewedCommit), any(Instant.class));
        assertThat(posted.detail().headSha()).isEqualTo(newerCommit);
    }

    @Test
    void readsBackTheInlineCommentsAReviewWasSubmittedWith()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        InlineReviewComment comment = new InlineReviewComment("a.txt", "note", 4, "RIGHT", null, null, "head", OptionalLong.empty());
        PrTimelineEvent thread = new PrTimelineEvent(901L, "review_thread", "me", REVIEWED_AT, null, Optional.empty());
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(writes.submitReview("pat", ref, "COMMENT", "", ImmutableList.of(comment), "head"))
                .thenReturn(review(502L, "COMMENTED", ""));
        when(gitHub.fetchReviewComments("pat", ref, 502L)).thenReturn(ImmutableList.of(thread));

        service.submitReview("owner/repo", 7, 12L, "comment", "", ImmutableList.of(comment), "head");

        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).saveCached(eq(12L), saved.capture());
        assertThat(saved.getValue().timeline()).extracting(PrTimelineEvent::githubId).contains(901L);
        assertThat(saved.getValue().reviews()).extracting(PrReviewState::githubId).contains(502L);
    }

    @Test
    void submitsRepliesToTheirExistingThreadsAndRefreshesTheWholeDiscussion()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        InlineReviewComment reply = new InlineReviewComment("a.txt", "Reply", 1, "RIGHT", null, null, null, OptionalLong.of(61));
        PrTimelineEvent thread = new PrTimelineEvent(61L, "review_thread", "me", REVIEWED_AT, null, Optional.empty());
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(gitHub.findReviewThreadId("pat", ref, 61)).thenReturn("PRRT_61");
        when(writes.submitReviewWithReplies("pat", ref, "COMMENT", "", ImmutableList.of(reply), ImmutableMap.of(61L, "PRRT_61"), "head"))
                .thenReturn(review(502L, "COMMENTED", ""));
        when(gitHub.fetchPrReviewThreads("pat", ref)).thenReturn(ImmutableList.of(thread));

        service.submitReview("owner/repo", 7, 12L, "COMMENT", "", ImmutableList.of(reply), "head");

        verify(gitHub, never()).fetchReviewComments(anyString(), any(), anyLong());
        verify(writes, never()).replyToReviewComment(anyString(), any(), anyLong(), anyString());
        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).saveCached(eq(12L), saved.capture());
        assertThat(saved.getValue().timeline()).extracting(PrTimelineEvent::githubId).contains(61L);
    }

    @Test
    void marksACommentReviewAsHandled()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(writes.submitReview("pat", PullRequestRef.of("owner", "repo", 7), "COMMENT", "Looks good with one note", ImmutableList.of(), "head"))
                .thenReturn(review(503L, "COMMENTED", "Looks good with one note"));

        service.submitReview("owner/repo", 7, 12L, "comment", "Looks good with one note", ImmutableList.of(), "head");

        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.COMMENTED), eq("head"), any(Instant.class));
    }

    @Test
    void keepsASubmittedReviewWhenItsCommentsCannotBeReadBack()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        InlineReviewComment comment = new InlineReviewComment("a.txt", "note", 4, "RIGHT", null, null, "head", OptionalLong.empty());
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(writes.submitReview("pat", ref, "COMMENT", "", ImmutableList.of(comment), "head")).thenReturn(review(502L, "COMMENTED", ""));
        when(gitHub.fetchReviewComments("pat", ref, 502L)).thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));

        PullRequestService.Posted posted = service.submitReview("owner/repo", 7, 12L, "comment", "", ImmutableList.of(comment), "head");

        assertThat(posted.reviewId()).hasValue(502L);
        assertThat(posted.threads()).isEmpty();
        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).saveCached(eq(12L), saved.capture());
        assertThat(saved.getValue().reviews()).extracting(PrReviewState::githubId).contains(502L);
        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.COMMENTED), eq("head"), any(Instant.class));
    }

    @Test
    void keepsAPostedCommentWhenTheLocalCopyCannotBeUpdated()
    {
        InlineReviewComment comment = new InlineReviewComment("src/Main.java", "Please simplify this", 18, "RIGHT", null, null, "head", OptionalLong.empty());
        PrTimelineEvent posted = new PrTimelineEvent(99L, "review_thread", "me", Instant.now(), null, Optional.empty());
        when(writes.createReviewComment("pat", PullRequestRef.of("owner", "repo", 7), comment)).thenReturn(posted);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        doThrow(new IllegalStateException("database is locked")).when(detailStore).saveCached(eq(12L), any());

        PullRequestService.Posted result = service.createReviewComment("owner/repo", 7, comment);

        assertThat(result.threads()).containsExactly(posted);
        assertThat(result.detail().recentActivity()).isEmpty();
    }

    @Test
    void keepsAPostedReplyWhenTheReloadFails()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        InlineReviewComment reply = new InlineReviewComment("a.txt", "Reply", 1, "RIGHT", null, null, "head", OptionalLong.of(61));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        // The reply drops the cached detail, so the reload goes to GitHub.
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()), Optional.empty());
        when(requests.refresh(session, 20, ref.number(), false)).thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));

        PullRequestService.Posted result = service.createReviewComment("owner/repo", 7, reply);

        verify(writes).replyToReviewComment("pat", ref, 61L, "Reply");
        assertThat(result.detail().body()).isEqualTo("body");
        assertThat(result.threads()).isEmpty();
    }

    @Test
    void readsTheFallbackDetailBeforeWritingSoAFailedReadSendsNothing()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        ResponseStatusException limited = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        when(requests.refresh(session, 20, ref.number(), false)).thenThrow(limited);

        assertThatThrownBy(() -> service.submitReview("owner/repo", 7, 12L, "comment", "Looks good", ImmutableList.of(), "head")).isSameAs(limited);

        verifyNoInteractions(writes);
    }

    private static PrReviewState review(long githubId, String state, String body)
    {
        return new PrReviewState(githubId, "me", null, false, state, REVIEWED_AT, body, null, "head");
    }

    @Test
    void rejectsOneReviewCarryingCommentsFromDifferentCommits()
    {
        List<InlineReviewComment> comments = ImmutableList.of(
                new InlineReviewComment("a.txt", "note", 4, "RIGHT", null, null, "older", OptionalLong.empty()),
                new InlineReviewComment("b.txt", "note", 9, "RIGHT", null, null, "head", OptionalLong.empty()));

        assertThatThrownBy(() -> service.submitReview("owner/repo", 7, 12L, "COMMENT", "", comments, "head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("comments written on one commit");
        verify(writes, never()).submitReview(anyString(), any(), anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void rejectsCommentReviewWithoutBody()
    {
        assertThatThrownBy(() -> service.submitReview("owner/repo", 7, 12L, "COMMENT", " ", ImmutableList.of(), "head"))
                .isInstanceOf(ResponseStatusException.class);
        verify(writes, never()).submitReview(anyString(), any(), anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void mergesDirectlyWhenNoQueueIsConfigured()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(writes.mergePullRequest(eq("pat"), eq(ref), any(MergePullRequestCommand.class)))
                .thenReturn(new MergeResult("sha", true, "merged"));

        MergeResult result = service.mergePullRequest("owner/repo", 7, 12L, "squash", "displayed-head");

        assertThat(result.merged()).isTrue();
        verify(writes).mergePullRequest("pat", ref, new MergePullRequestCommand("squash", Optional.empty(), Optional.empty(), Optional.of("displayed-head")));
        verify(store).setMonitored(12L, false);
        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.MERGED), eq("displayed-head"), any(Instant.class));
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verifyNoInteractions(diffStore);
    }

    @Test
    void keepsALandedMergeWhenTheLocalCopyCannotBeUpdated()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(writes.mergePullRequest(eq("pat"), eq(ref), any(MergePullRequestCommand.class)))
                .thenReturn(new MergeResult("sha", true, "merged"));
        doThrow(new IllegalStateException("database is locked")).when(viewStateStore).markReviewed(anyLong(), any(), any(), any());

        MergeResult result = service.mergePullRequest("owner/repo", 7, 12L, "rebase", "displayed-head");

        assertThat(result.merged()).isTrue();
        verify(writes).mergePullRequest(eq("pat"), eq(ref), any(MergePullRequestCommand.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rebase", "queue", "when-ready"})
    void rejectsMissingMergeHeadBeforeWriting(String strategy)
    {
        assertThatThrownBy(() -> service.mergePullRequest("owner/repo", 7, 12L, strategy, " "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(writes);
    }

    @ParameterizedTest
    @CsvSource({"squash,false", "squash,true", "rebase,false", "rebase,true", "queue,false", "queue,true", "when-ready,false", "when-ready,true"})
    void rejectsMergeWithoutTheMatchingLegacyIdentity(String strategy, boolean collidingLegacyRequest)
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(collidingLegacyRequest ? Optional.of(99L) : Optional.empty());

        assertThatThrownBy(() -> service.mergePullRequest("owner/repo", 7, 12L, strategy, "displayed-head"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(credentialService, writes, gitHub, actions, viewStateStore, recent, detailStore, diffStore);
    }

    @Test
    void doesNotMarkReviewedOrEnqueueWhenTheMergeHeadChanged()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(writes.mergePullRequest(eq("pat"), eq(ref), any(MergePullRequestCommand.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Head changed"));

        assertThatThrownBy(() -> service.mergePullRequest("owner/repo", 7, 12L, "rebase", "displayed-head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Head changed");
        verify(writes).mergePullRequest("pat", ref, new MergePullRequestCommand("rebase", Optional.empty(), Optional.empty(), Optional.of("displayed-head")));
        verifyNoMoreInteractions(writes);
        verifyNoInteractions(viewStateStore, recent);
    }

    @Test
    void enqueuesWhenGitHubRequiresMergeQueue()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(writes.mergePullRequest(eq("pat"), eq(ref), any(MergePullRequestCommand.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Merge queue is required"));
        when(writes.pullRequestNodeId("pat", ref)).thenReturn(Optional.of("PR_1"));
        when(writes.enqueuePullRequest("pat", "PR_1", "displayed-head")).thenReturn(MergeResult.enqueued("queued"));

        MergeResult result = service.mergePullRequest("owner/repo", 7, 12L, "squash", "displayed-head");

        assertThat(result.queued()).isTrue();
        verify(store, never()).setMonitored(anyLong(), anyBoolean());
        verify(viewStateStore, never()).markReviewed(anyLong(), any(HandledAction.class), any(), any(Instant.class));
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(diffStore, gitHub, actions);
    }

    @Test
    void enqueuesDirectlyForQueueAction()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(writes.pullRequestNodeId("pat", ref)).thenReturn(Optional.of("PR_1"));
        when(writes.enqueuePullRequest("pat", "PR_1", "displayed-head")).thenReturn(MergeResult.enqueued("queued"));

        MergeResult result = service.mergePullRequest("owner/repo", 7, 12L, "queue", "displayed-head");

        assertThat(result.queued()).isTrue();
        verify(writes, never()).mergePullRequest(anyString(), any(), any());
        verify(viewStateStore, never()).markReviewed(anyLong(), any(HandledAction.class), any(), any(Instant.class));
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(diffStore, gitHub, actions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void schedulesManualMergeWithoutRequiringChecksToFinish(boolean queued)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(writes.pullRequestNodeId("pat", ref)).thenReturn(Optional.of("PR_1"));
        MergeResult accepted = queued ? MergeResult.enqueued("queued") : new MergeResult(null, false, "Waiting for required checks");
        when(writes.enablePullRequestAutoMerge("pat", "PR_1", "displayed-head")).thenReturn(accepted);

        MergeResult result = service.mergePullRequest("owner/repo", 7, 12L, "when-ready", "displayed-head");

        assertThat(result).isEqualTo(accepted);
        verify(writes).enablePullRequestAutoMerge("pat", "PR_1", "displayed-head");
        verify(writes, never()).mergePullRequest(anyString(), any(), any());
        verify(writes, never()).enqueuePullRequest(anyString(), anyString(), anyString());
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(diffStore, gitHub, actions, viewStateStore, recent);
    }

    @ParameterizedTest
    @CsvSource({"409,Head changed", "502,Response lost"})
    void refreshesManualMergeStatusAfterARejectedOrUncertainRequest(int status, String reason)
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(writes.pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(Optional.of("PR_1"));
        when(writes.enablePullRequestAutoMerge("pat", "PR_1", "displayed-head"))
                .thenThrow(new ResponseStatusException(HttpStatus.valueOf(status), reason));

        assertThatThrownBy(() -> service.mergePullRequest("owner/repo", 7, 12L, "when-ready", "displayed-head"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining(reason);

        verify(writes, never()).mergePullRequest(anyString(), any(), any());
        verify(writes, never()).enqueuePullRequest(anyString(), anyString(), anyString());
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoInteractions(diffStore, viewStateStore, recent);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopsScheduledMergeWithoutMergingOrDiscardingCachedDiffs(boolean autoMerge)
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(writes.pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(Optional.of("PR_1"));

        service.removeFromMergeQueue("owner/repo", 7, autoMerge);

        if (autoMerge) {
            verify(writes).disablePullRequestAutoMerge("pat", "PR_1");
            verify(writes, never()).dequeuePullRequest(anyString(), anyString());
        }
        else {
            verify(writes).dequeuePullRequest("pat", "PR_1");
            verify(writes, never()).disablePullRequestAutoMerge(anyString(), anyString());
        }
        verify(writes, never()).mergePullRequest(anyString(), any(), any());
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(diffStore, viewStateStore);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidatesQueueStatusAfterAnUncertainRemoval(boolean autoMerge)
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(writes.pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(Optional.of("PR_1"));
        if (autoMerge) {
            doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Response lost")).when(writes).disablePullRequestAutoMerge("pat", "PR_1");
        }
        else {
            doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Response lost")).when(writes).dequeuePullRequest("pat", "PR_1");
        }

        assertThatThrownBy(() -> service.removeFromMergeQueue("owner/repo", 7, autoMerge))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Response lost");

        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoInteractions(diffStore, viewStateStore);
    }

    @Test
    void readsDiffFilesFromLocalStore()
    {
        List<DiffFile> files = ImmutableList.of(new DiffFile("a.txt", "modified", 1, 0, "+line"));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(diffStore.find(12L, null, null))
                .thenReturn(Optional.of(new StoredPrDiff(files, ImmutableList.of())));

        assertThat(service.getPullRequestDiffFiles("owner/repo", 7, null, null)).isEqualTo(files);

        verify(gitHub, never()).fetchPrDiffFiles(anyString(), any(), anyList(), any());
    }

    @Test
    void doesNotTreatEveryRepositoryPullRequestAsAReviewRequest()
    {
        assertThat(pullRequest().withViewerOrigin("me").origin())
                .isEqualTo(PullRequest.Origin.OTHER);
    }

    @ParameterizedTest
    @CsvSource({"APPROVED,false", "COMMENTED,true", "CHANGES_REQUESTED,true"})
    void preservesApprovalWhenTheHeadChanges(HandledAction action, boolean needsReview)
    {
        PrViewState reviewed = new PrViewState(12L, REVIEWED_AT, REVIEWED_AT, action, "previous-head");

        assertThat(PullRequestService.needsReviewAgain(storedPullRequest(), stored(), "me", reviewed)).isEqualTo(needsReview);
    }

    @ParameterizedTest
    @CsvSource({"APPROVED,false", "COMMENTED,true", "CHANGES_REQUESTED,true"})
    void preservesApprovalWhenTheAuthorReplies(HandledAction action, boolean needsReview)
    {
        PrViewState reviewed = new PrViewState(12L, REVIEWED_AT, REVIEWED_AT, action, "head");
        PrTimelinePayload thread = PrTimelinePayload.builder()
                .comments(ImmutableList.of(
                        new PrTimelineComment(40L, "me", null, false, REVIEWED_AT, "Question", null, 41L, null, ImmutableList.of()),
                        new PrTimelineComment(41L, "alice", null, false, REVIEWED_AT.plusSeconds(60), "Answer", null, 41L, 40L, ImmutableList.of())))
                .build();
        StoredPrDetail detail = new StoredPrDetail(raw(), ImmutableList.of(),
                ImmutableList.of(new PrTimelineEvent(40L, "review_thread", "me", REVIEWED_AT, null, thread, Optional.empty())),
                ImmutableList.of(), ImmutableList.of(), true);

        assertThat(PullRequestService.needsReviewAgain(storedPullRequest(), detail, "me", reviewed)).isEqualTo(needsReview);
    }

    @ParameterizedTest
    @CsvSource({"ME,true", "another-reviewer,false", ",true"})
    void reopensApprovalOnlyWhenTheViewerIsRequestedAgainOrDismissed(String requestedReviewer, boolean needsReview)
    {
        Instant changedAt = REVIEWED_AT.plusSeconds(60);
        PrViewState reviewed = new PrViewState(12L, REVIEWED_AT, REVIEWED_AT, HandledAction.APPROVED, "head");
        PrRawDetail raw = new PrRawDetail(null, null, "open", "alice", null, null, changedAt, null, "body", ImmutableList.of(), null, ImmutableList.of(), null,
                false, true, "clean", 4, 2, 1, 1, ImmutableList.of("me"), "head", "feature", "main", "base");
        StoredPrDetail detail = new StoredPrDetail(raw,
                requestedReviewer != null ? ImmutableList.of() : ImmutableList.of(new PrReviewState(1L, "me", null, false, "DISMISSED", changedAt, null, null, "head")),
                requestedReviewer == null ? ImmutableList.of() : ImmutableList.of(new PrTimelineEvent(1L, "review_requested", "alice", changedAt, null,
                        PrTimelinePayload.builder().requestedReviewer(requestedReviewer).build(), Optional.empty())),
                ImmutableList.of(), ImmutableList.of(), true);

        assertThat(PullRequestService.needsReviewAgain(storedPullRequest(), detail, "me", reviewed)).isEqualTo(needsReview);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 30})
    void warmsOnlyTheConfiguredNewestDetailsPerRepository(int limit)
    {
        service = spy(service);
        List<PullRequest> rows = IntStream.rangeClosed(1, 70).mapToObj(number -> {
            FetchedPullRequest row = pullRequestNumber(number);
            return storedPullRequest(new FetchedPullRequest(row.githubId(), number <= 35 ? "owner/repo" : "owner/second", row.number(), row.title(), row.author(), row.htmlUrl(),
                    row.createdAt(), row.updatedAt(), row.origin(), row.labels(), row.labelColors(), row.draft(), row.state(), row.closedAt(), row.mergedAt(),
                    row.requestedReviewers(), row.mergeable(), row.mergeableState(), row.headRef()));
        }).toList();
        when(store.findAttentionCandidates()).thenReturn(rows);
        doReturn(null).when(service).refreshPullRequestDetailFromGitHub(anyString(), anyInt(), eq(Duration.ofMinutes(10)));

        service.warmRecentDetails(limit);

        ArgumentCaptor<Integer> first = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> second = ArgumentCaptor.forClass(Integer.class);
        verify(service, times(limit)).refreshPullRequestDetailFromGitHub(eq("owner/repo"), first.capture(), eq(Duration.ofMinutes(10)));
        verify(service, times(limit)).refreshPullRequestDetailFromGitHub(eq("owner/second"), second.capture(), eq(Duration.ofMinutes(10)));
        assertThat(first.getAllValues()).containsExactlyElementsOf(IntStream.iterate(35, value -> value - 1).limit(limit).boxed().toList());
        assertThat(second.getAllValues()).containsExactlyElementsOf(IntStream.iterate(70, value -> value - 1).limit(limit).boxed().toList());
        verifyNoInteractions(gitHub, actions);
    }

    @Test
    void discussionSourceChangeRefreshesCommentsEvenWhenPrMetadataReturnsNotModified()
    {
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(Instant.EPOCH), Optional.of(Instant.now()), Optional.of(Instant.now()), Optional.of(pullRequest().updatedAt())));
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        Instant sourceAt = pullRequest().updatedAt().plusSeconds(30);
        when(store.findDiscussionActivityAt(12L)).thenReturn(Optional.of(sourceAt));
        when(requests.refresh(session, 20, ref.number(), false)).thenReturn(snapshot(pullRequest()));
        PrTimelineEvent comment = new PrTimelineEvent(3L, "commented", "alice", sourceAt, "New reply", Optional.empty());
        when(gitHub.fetchPrIssueComments("pat", ref)).thenReturn(ImmutableList.of(comment));

        PullRequestService.DetailRefresh result = service.refreshPullRequestDetailFromGitHub("owner/repo", 7);

        assertThat(result.full()).isTrue();
        verify(detailStore).saveDiscussion(eq(12L), any(), eq(sourceAt));
        verify(gitHub).fetchPrIssueComments("pat", ref);
        verify(gitHub, never()).fetchPrDiffFiles(anyString(), any(), anyList(), any());
        verifyNoInteractions(actions);
    }

    @Test
    void freshUnchangedWarmDetailsMakeNoRemoteRequests()
    {
        cacheDetail(stored(), freshTimes());

        service.refreshPullRequestDetailFromGitHub("owner/repo", 7, Duration.ofMinutes(10));

        verifyNoInteractions(gitHub, actions, accounts);
        verify(detailStore, never()).saveDiscussion(anyLong(), any(), any());
        verify(diffStore, never()).saveDefault(anyLong(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"OTHER, open", "AUTHORED, closed"})
    void warmingLeavesAQuietUntimedPullRequestUntilActivity(PullRequest.Origin origin, String state)
    {
        Instant stale = Instant.now().minus(Duration.ofMinutes(31));
        PullRequest row = storedPullRequest(pullRequest(state, pullRequest().updatedAt(), null).withOrigin(origin));
        cacheDetail(row, stored(), new PrDetailFreshness(Optional.of(stale), Optional.of(stale), Optional.of(stale), Optional.of(row.updatedAt())));

        service.refreshPullRequestDetailFromGitHub("owner/repo", 7, Duration.ofMinutes(10));

        verifyNoInteractions(gitHub, actions, accounts);
    }

    @ParameterizedTest
    @CsvSource({"60, 2, 0", "600, 11, 0", "60, 31, 1", "600, 31, 1", "60, -1, 1"})
    void quietDiscussionsUseTheirOwnReconciliationInterval(long freshnessSeconds, long discussionAgeMinutes, int expectedFetches)
    {
        Instant now = Instant.now();
        Optional<Instant> discussionAt = discussionAgeMinutes < 0 ? Optional.empty() : Optional.of(now.minus(Duration.ofMinutes(discussionAgeMinutes)));
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(now), discussionAt, Optional.of(now), Optional.of(pullRequest().updatedAt())));

        service.refreshPullRequestDetailFromGitHub("owner/repo", 7, Duration.ofSeconds(freshnessSeconds));

        verify(gitHub, times(expectedFetches)).fetchPrIssueComments("pat", PullRequestRef.of("owner", "repo", 7));
        verify(detailStore, times(expectedFetches)).saveDiscussion(eq(12L), any(), eq(pullRequest().updatedAt()));
        verify(requests, never()).refresh(any(), anyLong(), anyInt(), anyBoolean());
        verifyNoInteractions(actions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void quietWarmReconcileAsksWithEtagsBeforeRereadingTheDiscussion(boolean unchanged)
    {
        Instant now = Instant.now();
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(now), Optional.of(now.minus(Duration.ofMinutes(31))), Optional.of(now), Optional.of(pullRequest().updatedAt())));
        Map<String, String> etags = ImmutableMap.of("timeline", "\"t1\"");
        when(detailStore.findDiscussionEtags(12L)).thenReturn(etags);
        when(gitHub.probeDiscussion("pat", ref, etags)).thenReturn(new DiscussionProbe(unchanged, ImmutableMap.of("timeline", "\"t2\"")));

        service.refreshPullRequestDetailFromGitHub("owner/repo", 7, Duration.ofSeconds(60));
        service.refreshPullRequestDetailFromGitHub("owner/repo", 7, Duration.ofSeconds(60));

        // Either way the discussion counts as validated, so the second pass asks nothing.
        verify(gitHub).probeDiscussion("pat", ref, etags);
        verify(detailStore).saveDiscussion(eq(12L), any(), eq(pullRequest().updatedAt()));
        verify(gitHub, times(unchanged ? 0 : 1)).fetchPrIssueComments("pat", ref);
        verify(gitHub, times(unchanged ? 0 : 1)).fetchPrReviewThreads("pat", ref);
        verify(detailStore, times(unchanged ? 0 : 1)).updateDiscussionEtags(12L, ImmutableMap.of("timeline", "\"t2\""));
    }

    @ParameterizedTest
    @CsvSource({"open, quiet, 1, 1", "merged, quiet, 0, 0", "closed, quiet, 0, 0", "merged, monitored, 1, 1", "merged, pending, 0, 1", "merged, activity, 1, 0"})
    void warmingLeavesSettledPullRequestsUntilActivityOrOpening(String state, String variant, int warmDiscussions, int warmChecks)
    {
        Instant now = Instant.now();
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        PullRequest row = storedPullRequest(state, pullRequest().updatedAt(), null);
        List<PrCheckRunState> checks = variant.equals("pending")
                ? ImmutableList.of(new PrCheckRunState(1L, "build", "in_progress", null, null, "check_run", null, null))
                : stored().checkRuns();
        // Fresh metadata, with a discussion and checks that are past the warm pass's windows.
        PrDetailFreshness times = new PrDetailFreshness(
                Optional.of(now), Optional.of(now.minus(Duration.ofMinutes(31))), Optional.of(now.minus(Duration.ofMinutes(11))), Optional.of(row.updatedAt()));
        cacheDetail(variant.equals("monitored") ? monitored(row) : row, new StoredPrDetail(raw(), stored().reviews(), ImmutableList.of(), checks, ImmutableList.of(), true), times);
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(row));
        if (variant.equals("activity")) {
            when(store.findDiscussionActivityAt(12L)).thenReturn(Optional.of(row.updatedAt().plusSeconds(30)));
        }
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(checks, Optional.of("checks"), Optional.of("statuses")));

        service.warmRecentDetails(30);

        verify(gitHub, times(warmDiscussions)).fetchPrIssueComments("pat", ref);
        verify(actions, times(warmChecks)).refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any());

        // Opening reconciles whatever the warm pass left, and only that.
        service.refreshOpenedPullRequestDetailFromGitHub("owner/repo", 7);

        verify(gitHub).fetchPrIssueComments("pat", ref);
        verify(actions).refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any());
        verify(requests, never()).refresh(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void checksOnlyRefreshLeavesDiscussionAndDiffUntouched()
    {
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(Instant.now()), Optional.of(Instant.EPOCH), Optional.of(Instant.EPOCH), Optional.empty()));
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(stored().checkRuns(), Optional.of("checks"), Optional.of("statuses")));

        PullRequestService.DetailRefresh result = service.refreshPullRequestChecksFromGitHub("owner/repo", 7);

        assertThat(result.full()).isFalse();
        verify(actions).refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any());
        verifyNoInteractions(gitHub, diffStore);
        verify(detailStore, never()).saveDiscussion(anyLong(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND"})
    void rejectedChecksCannotReturnCachedDetail(HttpStatus status)
    {
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(Instant.now()), Optional.of(Instant.now()), Optional.of(Instant.EPOCH), Optional.empty()));
        ResponseStatusException failure = new ResponseStatusException(status, "Access rejected");
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.refreshPullRequestChecksFromGitHub("owner/repo", 7)).isSameAs(failure);

        verify(detailStore).clearRemote(12L);
        verify(diffStore).deleteByPrIds(ImmutableSet.of(12L));
        verify(detailStore, never()).saveStatus(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 120})
    void openingRefreshesQuietDiscussionWithOneMinuteDeduplication(int discussionAgeSeconds)
    {
        Instant now = Instant.now();
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(now), Optional.of(now.minusSeconds(discussionAgeSeconds)),
                Optional.of(now), Optional.of(pullRequest().updatedAt())));

        assertThat(service.refreshOpenedPullRequestDetailFromGitHub("owner/repo", 7).full()).isEqualTo(discussionAgeSeconds > 60);
        assertThat(service.refreshOpenedPullRequestDetailFromGitHub("owner/repo", 7).full()).isFalse();

        verify(gitHub, times(discussionAgeSeconds > 60 ? 1 : 0)).fetchPrIssueComments("pat", PullRequestRef.of("owner", "repo", 7));
        // Thread resolutions change no REST response, so opening never trusts ETags.
        verify(gitHub, never()).probeDiscussion(anyString(), any(), any());
        verify(requests, never()).refresh(any(), anyLong(), anyInt(), anyBoolean());
        verifyNoInteractions(actions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overlappingDetailRefreshesShareChecksAndHonorOpeningFreshness(boolean opened)
            throws Exception
    {
        cacheDetail(stored(), new PrDetailFreshness(Optional.of(Instant.now()), Optional.of(Instant.now().minusSeconds(120)),
                Optional.of(Instant.EPOCH), Optional.of(pullRequest().updatedAt())));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new PrCheckRunsFetch(stored().checkRuns(), Optional.empty(), Optional.empty());
        });
        Runnable refresh = () -> {
            try {
                service.refreshPullRequestDetailFromGitHub("owner/repo", 7);
            }
            catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        };
        Thread first = Thread.ofVirtual().start(refresh);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread second = Thread.ofVirtual().start(() -> {
            try {
                if (opened) {
                    service.refreshOpenedPullRequestDetailFromGitHub("owner/repo", 7);
                }
                else {
                    service.refreshPullRequestDetailFromGitHub("Owner/Repo", 7);
                }
            }
            catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        try {
            Instant deadline = Instant.now().plusSeconds(5);
            while (second.getState() != Thread.State.WAITING && Instant.now().isBefore(deadline)) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertThat(second.getState()).isEqualTo(Thread.State.WAITING);
            verify(actions).refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any());
        }
        finally {
            release.countDown();
            first.join(5000);
            second.join(5000);
        }
        assertThat(failure.get()).isNull();
        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        verify(actions).refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any());
        verify(gitHub, times(opened ? 1 : 0)).fetchPrIssueComments("pat", PullRequestRef.of("owner", "repo", 7));
    }

    private void cacheDetail(StoredPrDetail initial, PrDetailFreshness initialTimes)
    {
        cacheDetail(storedPullRequest(), initial, initialTimes);
    }

    private void cacheDetail(PullRequest row, StoredPrDetail initial, PrDetailFreshness initialTimes)
    {
        AtomicReference<StoredPrDetail> cached = new AtomicReference<>(initial);
        AtomicReference<PrDetailFreshness> times = new AtomicReference<>(initialTimes);
        PullRequest withCi = new PullRequest(row.id(), row.repo(), row.number(), row.title(), row.author(), row.htmlUrl(), row.createdAt(), row.updatedAt(), row.origin(),
                row.labels(), row.labelColors(), row.draft(), row.viewedAt(), row.reviewedAt(), row.handledAction(), row.requestedReviewers(),
                initial == null ? PullRequestDetail.CiStatus.NONE : PullRequestDetailMapper.aggregateCiStatus(initial.checkRuns()),
                row.additions(), row.deletions(), row.commentCount(),
                row.state(), row.closedAt(), row.mergedAt(), row.mergeable(), row.mergeableState(), row.headPushedAt(), row.headRef(), row.monitoredAt(), row.activityAt(),
                row.reviewRequestedAt(), row.reviewDecision());
        lenient().when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        lenient().when(store.findById(12L)).thenReturn(Optional.of(withCi));
        lenient().when(store.saveObserved(any())).thenReturn(withCi);
        lenient().when(requests.cached(session, 20, 7))
                .thenAnswer(invocation -> Optional.ofNullable(cached.get()).map(value -> snapshot(pullRequest(), value.raw())));
        lenient().when(detailStore.find(12L)).thenAnswer(invocation -> Optional.ofNullable(cached.get()));
        lenient().when(detailStore.findEtag(12L)).thenReturn(Optional.of("etag-1"));
        lenient().when(detailStore.findFreshness(12L)).thenAnswer(invocation -> times.get());
        lenient().doAnswer(invocation -> {
            cached.set(invocation.getArgument(1));
            PrDetailFreshness old = times.get();
            times.set(new PrDetailFreshness(Optional.of(Instant.now()), old.discussionAt(), old.checksAt(), old.discussionSourceUpdatedAt()));
            return null;
        }).when(detailStore).saveMetadata(eq(12L), any());
        lenient().doAnswer(invocation -> {
            StoredPrDetail value = invocation.getArgument(1);
            cached.set(value);
            if (value.reviewsComplete() && value.timelineComplete()) {
                PrDetailFreshness old = times.get();
                times.set(new PrDetailFreshness(old.metadataAt(), Optional.of(Instant.now()), old.checksAt(), Optional.ofNullable(invocation.getArgument(2))));
            }
            return null;
        }).when(detailStore).saveDiscussion(eq(12L), any(), any());
        lenient().doAnswer(invocation -> {
            cached.set(invocation.getArgument(1));
            PrDetailFreshness old = times.get();
            times.set(new PrDetailFreshness(old.metadataAt(), old.discussionAt(), Optional.of(Instant.now()), old.discussionSourceUpdatedAt()));
            return null;
        }).when(detailStore).saveStatus(eq(12L), any());
        lenient().when(diffStore.find(12L, null, null)).thenReturn(Optional.of(new StoredPrDiff(ImmutableList.of(), ImmutableList.of(), ImmutableMap.of(),
                Optional.of("base"), Optional.of("head"), Optional.of(Instant.now()))));
        lenient().when(diffStore.defaultDiffValidated(12L, "base", "head")).thenReturn(true);
    }

    private static PrDetailFreshness freshTimes()
    {
        return new PrDetailFreshness(Optional.of(Instant.now()), Optional.of(Instant.now()), Optional.of(Instant.now()), Optional.of(pullRequest().updatedAt()));
    }

    private PullRequestRead read(PullRequestRef ref)
    {
        return new PullRequestRead(session, hostingRepository(ref.repoFullName()), ref.number(), "me");
    }

    private static HostingRepository hostingRepository(String path)
    {
        URI url = URI.create("https://github.com/" + path);
        return new HostingRepository(20, 1, "200", path, url, url, Optional.empty(), Optional.of("main"));
    }

    private static HostingPullRequestSnapshot snapshot(FetchedPullRequest summary)
    {
        return snapshot(summary, raw());
    }

    private static HostingPullRequestSnapshot snapshot(FetchedPullRequest summary, PrRawDetail detail)
    {
        return new HostingPullRequestSnapshot(new HostingPullRequest(112, 20, summary.number(), summary.githubId().toString()), 10, "desktop-scope",
                summary, detail, Optional.of("etag-1"), Instant.now());
    }

    private static StoredPrDetail stored()
    {
        return new StoredPrDetail(
                raw(), ImmutableList.of(new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null)), ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(1L, "build", "completed", "success", null, "check_run", null, null)),
                ImmutableList.of(),
                true);
    }

    private static RepositoryMetadataSnapshot pickerSnapshot()
    {
        return new RepositoryMetadataSnapshot(
                1,
                RepoRef.of("owner", "repo"),
                null,
                0,
                ImmutableMap.of(),
                ImmutableList.of(),
                ImmutableList.of(new RepositoryPickerUser(2, "alice", Optional.of("avatar-key"), false), new RepositoryPickerUser(3, "robot[bot]", Optional.empty(), true)),
                ImmutableList.of(),
                ImmutableList.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Instant.parse("2026-08-21T00:00:00Z")),
                Optional.empty());
    }

    private static PrRawDetail raw()
    {
        return rawAt(pullRequest().updatedAt());
    }

    private static PrRawDetail rawAt(Instant updatedAt)
    {
        return rawAt(updatedAt, true, "clean");
    }

    private static PrRawDetail rawAt(Instant updatedAt, boolean mergeable, String mergeableState)
    {
        return new PrRawDetail(null, null, null, null, null, null, updatedAt, null, "body", ImmutableList.of(), null, ImmutableList.of(), null,
                false, mergeable, mergeableState, 4, 2, 1, 0, ImmutableList.of(), "head", "feature", "main", "base");
    }

    private WorkspaceHostingRead workspaceRoute()
    {
        WorkspaceHostingRead route = workspaceRoute("workspace-scope", "pat", "me");
        when(workspaceHosting.captureRead("workspace")).thenReturn(route);
        return route;
    }

    private static WorkspaceHostingRead workspaceRoute(String scope, String token, String login)
    {
        HostingInstance instance = new HostingInstance(1, GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
        HostingReadSession session = new HostingReadSession(1, scope, new HostingAccess(instance, token));
        URI url = URI.create("https://github.com/owner/repo");
        HostingRepository repository = new HostingRepository(1, instance.id(), "123", "owner/repo", url, url, Optional.empty(), Optional.of("main"));
        return new WorkspaceHostingRead(session, repository, login);
    }

    private PullRequestService newService(Clock clock)
    {
        return new PullRequestService(gitHub, writes, repositories, accounts, store, snapshots, detailStore, new PullRequestDetailFetcher(
                gitHub,
                Runnable::run), viewStateStore, recent, credentialService, connections, requests, workspaceHosting, metadata, summaries, workspaces, applicationContext,
                notifications, syncSettings, issues, writeVersion, clock);
    }

    private PullRequestService serviceAt(AtomicReference<Instant> now)
    {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        return newService(clock);
    }

    private static PullRequest storedPullRequest()
    {
        return storedPullRequest(pullRequest());
    }

    private static PullRequest attentionPullRequest(long id, Instant reviewedAt, Instant reviewRequestedAt, Instant headPushedAt, PullRequestDetail.CiStatus ciStatus)
    {
        return attentionPullRequest(id, reviewedAt, reviewRequestedAt, headPushedAt, ciStatus, null);
    }

    private static PullRequest attentionPullRequest(
            long id, Instant reviewedAt, Instant reviewRequestedAt, Instant headPushedAt, PullRequestDetail.CiStatus ciStatus, Instant activityAt)
    {
        return new PullRequest(
                id, "owner/repo", (int) id, "Title", "alice", "https://github.com/owner/repo/pull/" + id,
                REVIEWED_AT, REVIEWED_AT, PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of(),
                ImmutableMap.of(), false, null, reviewedAt, null, ImmutableList.of(),
                ciStatus, 0, 0, 0, "open", null, null,
                null, null, headPushedAt, "feature", null, activityAt, reviewRequestedAt, null);
    }

    private static PullRequest storedPullRequest(String state, Instant updatedAt, Instant closedAt)
    {
        return storedPullRequest(pullRequest(state, updatedAt, closedAt));
    }

    private static PullRequest storedPullRequest(FetchedPullRequest fetched)
    {
        return new PullRequest(
                12L, fetched.repo(), fetched.number(), fetched.title(), fetched.author(), fetched.htmlUrl(),
                fetched.createdAt(), fetched.updatedAt(), fetched.origin(), fetched.labels(),
                fetched.labelColors(), fetched.draft(), null, null, null, fetched.requestedReviewers(),
                null, 0, 0, 0, fetched.state(), fetched.closedAt(), fetched.mergedAt(),
                fetched.mergeable(), fetched.mergeableState(), null, fetched.headRef(), null, null, null, null);
    }

    private static GitHubPullRequestSummary summary(FetchedPullRequest fetched, PullRequestDetail.CiStatus ciStatus, String version)
    {
        return new GitHubPullRequestSummary(fetched, ciStatus, 4, 2, 1, "head", Optional.of(REVIEWED_AT), Optional.of("APPROVED"),
                Optional.empty(), Optional.empty(), version, fetched.updatedAt(), Optional.empty());
    }

    private static FetchedPullRequest pullRequestNumber(int number)
    {
        return pullRequestNumber(number, REVIEWED_AT.plusSeconds(number));
    }

    private static FetchedPullRequest pullRequestNumber(int number, Instant updatedAt)
    {
        FetchedPullRequest original = pullRequest();
        return new FetchedPullRequest((long) number, original.repo(), number, original.title(), original.author(), original.htmlUrl(), original.createdAt(),
                updatedAt, original.origin(), original.labels(), original.labelColors(), original.draft(), original.state(),
                original.closedAt(), original.mergedAt(), original.requestedReviewers(), original.mergeable(), original.mergeableState(), original.headRef());
    }

    private static PullRequest monitoredPullRequest(int number)
    {
        return monitored(storedPullRequest(pullRequestNumber(number)));
    }

    private static PullRequest monitored(PullRequest row)
    {
        return new PullRequest(row.id(), row.repo(), row.number(), row.title(), row.author(), row.htmlUrl(), row.createdAt(), row.updatedAt(), row.origin(),
                row.labels(), row.labelColors(), row.draft(), row.viewedAt(), row.reviewedAt(), row.handledAction(), row.requestedReviewers(), row.ciStatus(),
                row.additions(), row.deletions(), row.commentCount(), row.state(), row.closedAt(), row.mergedAt(), row.mergeable(), row.mergeableState(),
                row.headPushedAt(), row.headRef(), REVIEWED_AT, row.activityAt(), row.reviewRequestedAt(), row.reviewDecision());
    }

    private static FetchedPullRequest pullRequest()
    {
        return pullRequest("open", Instant.parse("2026-08-21T00:00:00Z"), null);
    }

    private static FetchedPullRequest pullRequest(String state, Instant updatedAt, Instant closedAt)
    {
        return pullRequest(12, state, updatedAt, closedAt);
    }

    private static FetchedPullRequest pullRequest(long githubId, String state, Instant updatedAt, Instant closedAt)
    {
        return pullRequest(githubId, state, updatedAt, closedAt, null, null);
    }

    private static FetchedPullRequest pullRequest(String state, Instant updatedAt, Instant closedAt, Boolean mergeable, String mergeableState)
    {
        return pullRequest(12, state, updatedAt, closedAt, mergeable, mergeableState);
    }

    private static FetchedPullRequest pullRequest(long githubId, String state, Instant updatedAt, Instant closedAt, Boolean mergeable, String mergeableState)
    {
        return new FetchedPullRequest(
                githubId,
                "owner/repo",
                7,
                "Title",
                "alice",
                "https://github.com/owner/repo/pull/7",
                Instant.parse("2026-08-20T00:00:00Z"),
                updatedAt,
                PullRequest.Origin.AUTHORED,
                ImmutableList.of(),
                ImmutableMap.of(),
                false,
                state,
                closedAt,
                null,
                ImmutableList.of(),
                mergeable,
                mergeableState,
                "feature");
    }
}
