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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrCheckRunState.GitHubMetadata;
import com.bytequay.app.domain.PrCheckRunState.PullRequestSubject;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestRepository.ActionsJobLogCapture;
import com.bytequay.app.repository.PullRequestRepository.ActionsWorkflowJob;
import com.bytequay.app.repository.PullRequestRepository.ActionsWorkflowJobSetEvidence;
import com.bytequay.app.repository.PullRequestRepository.ActionsWorkflowJobStep;
import com.bytequay.app.repository.PullRequestRepository.ActionsWorkflowRun;
import com.bytequay.app.repository.PullRequestRepository.CheckRunAnnotation;
import com.bytequay.app.repository.PullRequestRepository.CheckRunAnnotationEvidence;
import com.bytequay.app.repository.PullRequestRepository.FileBlob;
import com.bytequay.app.repository.PullRequestRepository.MergeQueueInfo;
import com.bytequay.app.repository.PullRequestRepository.ReviewThreadMeta;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.pr.CollaboratorPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubRemoteObserver
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final RepoRef REPOSITORY = RepoRef.parse("acme/widget");
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of(
            "acme", "widget", 17);

    @Test
    void capturesOneExactFailClosedSnapshot()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        when(pats.resolve("acme/widget")).thenReturn("pat");
        when(pullRequests.fetchUserProfile("pat")).thenReturn(user("me"));
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST)).thenReturn(
                new PrRawDetail(
                        "body", List.of(), false, true, "clean", 1, 1, 1,
                        2, List.of("carol", "dave"), "head-1", "feature",
                        "acme/widget", "main", "acme/widget", "open", false,
                        "base-1", null));
        when(pullRequests.fetchPrReviews("pat", PULL_REQUEST)).thenReturn(List.of(
                new PrReviewState("Alice", "APPROVED", NOW.minusSeconds(30)),
                new PrReviewState("alice", "CHANGES_REQUESTED", NOW.minusSeconds(10)),
                new PrReviewState("Bob", "APPROVED", NOW.minusSeconds(5))));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "head-1")).thenReturn(List.of(
                    new PrCheckRunState(11L, "build", "completed", "success",
                            null, null, null),
                    new PrCheckRunState(12L, "test", "in_progress", null,
                            null, null, null)));
        when(pullRequests.fetchReviewThreadResolution("pat", PULL_REQUEST))
                .thenReturn(List.of(
                        new ReviewThreadMeta(41L, "thread-41", false, null),
                        new ReviewThreadMeta(42L, "thread-42", true, "alice")));
        when(pullRequests.fetchPrReviewComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrTimeline(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrIssueComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchMergeQueueInfo("pat", PULL_REQUEST))
                .thenReturn(new MergeQueueInfo(true, "QUEUED"));
        when(collaborators.countWriteApprovals(eq("pat"), eq(REPOSITORY), any()))
                .thenReturn(1);

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of("build")),
                execution);

        assertThat(observed.headSha()).isEqualTo("head-1");
        assertThat(observed.schemaVersion()).isEqualTo(5);
        assertThat(observed.ciProvenance()).isNotNull();
        assertThat(observed.ciProvenance().checks()).isEmpty();
        assertThat(observed.baseSha()).isEqualTo("base-1");
        assertThat(observed.prState())
                .isEqualTo(RemoteObservationOperationHandler.PrState.OPEN);
        assertThat(observed.mergeability())
                .isEqualTo(RemoteObservationOperationHandler.Mergeability.MERGEABLE);
        assertThat(observed.mergeQueueState())
                .isEqualTo(RemoteObservationOperationHandler.MergeQueueState.QUEUED);
        assertThat(observed.mergeQueueCapability())
                .isEqualTo(RemoteObservationOperationHandler
                        .MergeQueueCapability.SUPPORTED);
        assertThat(observed.effectiveApprovalCount()).isOne();
        assertThat(observed.writeApprovalCount()).isOne();
        assertThat(observed.changesRequestedCount()).isOne();
        assertThat(observed.unresolvedThreadCount()).isOne();
        assertThat(observed.checks())
                .extracting(RemoteCiPolicy.Check::state)
                .containsExactly(
                        RemoteCiPolicy.CheckState.PASSED,
                        RemoteCiPolicy.CheckState.PENDING);
        assertThat(observed.checks())
                .extracting(RemoteCiPolicy.Check::kind)
                .containsOnly("CHECK_RUN");
        assertThat(observed.observedAtMs()).isEqualTo(NOW.toEpochMilli());
        assertThat(observed.rawEvidence()).contains("acme/widget", "head-1");
    }

    @Test
    void rejectsAPullRequestThatClosesDuringObservation()
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail draft = new PrRawDetail(
                "body", List.of(), true, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", null);
        PrRawDetail closed = new PrRawDetail(
                "body", List.of(), true, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "closed", false, "base-1", null);
        stubObservationShell(
                pullRequests, pats, collaborators, draft, List.of());
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST))
                .thenReturn(draft, closed);

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub pull request changed during exact observation");
    }

    @Test
    void mapsMergeQueueConfigurationToTypedCapability()
    {
        assertThat(GitHubRemoteObserver.mergeQueueCapability(
                new MergeQueueInfo(true, null)))
                .isEqualTo(RemoteObservationOperationHandler
                        .MergeQueueCapability.SUPPORTED);
        assertThat(GitHubRemoteObserver.mergeQueueCapability(
                new MergeQueueInfo(false, null)))
                .isEqualTo(RemoteObservationOperationHandler
                        .MergeQueueCapability.UNSUPPORTED);
    }

    @Test
    void inconsistentMergeQueueEvidenceCannotProduceObservation()
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail detail = new PrRawDetail(
                "body", List.of(), false, true, "clean", 0, 0, 0,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", null);
        stubObservationShell(
                pullRequests, pats, collaborators, detail, List.of());
        when(pullRequests.fetchMergeQueueInfo("pat", PULL_REQUEST))
                .thenReturn(new MergeQueueInfo(false, "AWAITING_CHECKS"));
        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub merge queue observation is inconsistent");
    }

    @Test
    void capturesCompleteSyntheticMergeVersusExactBaseProof()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail detail = new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", "merge-1");
        PrCheckRunState head = check(
                11L, "build", "failure", "merge-1", 31L, 101L,
                1, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        PrCheckRunState base = check(
                12L, "build", "success", "base-1", 32L, 102L,
                0, List.of());
        ActionsWorkflowRun headRun = workflow(
                101L, "pull_request", "merge-1", 31L, "failure");
        ActionsWorkflowRun baseRun = workflow(
                102L, "push", "base-1", 32L, "success");

        when(pats.resolve("acme/widget")).thenReturn("pat");
        when(pullRequests.fetchUserProfile("pat")).thenReturn(user("me"));
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST)).thenReturn(detail);
        when(pullRequests.fetchPrReviews("pat", PULL_REQUEST))
                .thenReturn(List.of());
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "head-1"))
                .thenReturn(List.of(head));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "base-1"))
                .thenReturn(List.of(base));
        stubExactRun(pullRequests, headRun,
                job(headRun, 1L, 11L, "build", "failure"));
        stubExactRun(pullRequests, baseRun,
                job(baseRun, 1L, 12L, "build", "success"));
        when(pullRequests.fetchCheckRunAnnotationsStrict(
                "pat", REPOSITORY, 11L, 1)).thenReturn(
                    new CheckRunAnnotationEvidence(
                        List.of(new CheckRunAnnotation(
                                "ESLint", "src/App.tsx:4 error no-unused-vars",
                                "src/App.tsx", 4)),
                        1, 1, true));
        when(pullRequests.fetchReviewThreadResolution("pat", PULL_REQUEST))
                .thenReturn(List.of());
        when(pullRequests.fetchPrReviewComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrTimeline(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrIssueComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchMergeQueueInfo("pat", PULL_REQUEST))
                .thenReturn(new MergeQueueInfo(true, null));
        when(collaborators.countWriteApprovals(
                eq("pat"), eq(REPOSITORY), any())).thenReturn(0);

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution);

        assertThat(observed.ciProvenance().complete()).isTrue();
        assertThat(observed.ciProvenance().observedMergeSha())
                .isEqualTo("merge-1");
        assertThat(observed.ciProvenance().checks()).singleElement()
                .satisfies(comparison -> {
                    assertThat(comparison.head().checkTestedSha())
                            .isEqualTo("merge-1");
                    assertThat(comparison.head().pullRequestAssociation())
                            .isNotNull();
                    assertThat(comparison.head().failureFingerprints())
                            .hasSize(1);
                    assertThat(comparison.base().state())
                            .isEqualTo(CheckState.PASSED);
                });
    }

    @Test
    void capturesExactInfrastructureProofWithoutAnnotationFingerprints()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail detail = new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", "merge-1");
        PrCheckRunState timedOut = check(
                11L, "build", "timed_out", "merge-1", 31L, 101L,
                0, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        PrCheckRunState canceled = check(
                13L, "test", "cancelled", "merge-1", 33L, 103L,
                0, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        PrCheckRunState baseBuild = check(
                12L, "build", "success", "base-1", 32L, 102L,
                0, List.of());
        PrCheckRunState baseTest = check(
                14L, "test", "success", "base-1", 34L, 104L,
                0, List.of());
        ActionsWorkflowRun timedOutRun = workflow(
                101L, "pull_request", "merge-1", 31L, "timed_out");
        ActionsWorkflowRun baseBuildRun = workflow(
                102L, "push", "base-1", 32L, "success");
        ActionsWorkflowRun canceledRun = workflow(
                103L, "pull_request", "merge-1", 33L, "cancelled");
        ActionsWorkflowRun baseTestRun = workflow(
                104L, "push", "base-1", 34L, "success");

        when(pats.resolve("acme/widget")).thenReturn("pat");
        when(pullRequests.fetchUserProfile("pat")).thenReturn(user("me"));
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST)).thenReturn(detail);
        when(pullRequests.fetchPrReviews("pat", PULL_REQUEST))
                .thenReturn(List.of());
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "head-1"))
                .thenReturn(List.of(timedOut, canceled));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "base-1"))
                .thenReturn(List.of(baseBuild, baseTest));
        stubExactRun(pullRequests, timedOutRun,
                job(timedOutRun, 1L, 11L, "build", "timed_out"));
        stubExactRun(pullRequests, baseBuildRun,
                job(baseBuildRun, 1L, 12L, "build", "success"));
        stubExactRun(pullRequests, canceledRun,
                job(canceledRun, 1L, 13L, "test", "cancelled"));
        stubExactRun(pullRequests, baseTestRun,
                job(baseTestRun, 1L, 14L, "test", "success"));
        when(pullRequests.fetchReviewThreadResolution("pat", PULL_REQUEST))
                .thenReturn(List.of());
        when(pullRequests.fetchPrReviewComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrTimeline(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrIssueComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchMergeQueueInfo("pat", PULL_REQUEST))
                .thenReturn(new MergeQueueInfo(true, null));
        when(collaborators.countWriteApprovals(
                eq("pat"), eq(REPOSITORY), any())).thenReturn(0);

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution);

        assertThat(observed.ciProvenance().complete()).isTrue();
        assertThat(observed.ciProvenance().checks())
                .extracting(comparison -> comparison.head().state())
                .containsExactly(CheckState.FAILED, CheckState.CANCELED);
        assertThat(observed.ciProvenance().checks())
                .allSatisfy(comparison -> {
                    assertThat(comparison.head().complete()).isTrue();
                    assertThat(comparison.head().failureFingerprints()).isEmpty();
                });
        verify(pullRequests, never()).fetchCheckRunAnnotationsStrict(
                any(), any(), anyLong(), anyInt());
    }

    @Test
    void capturesFailedHeadAndFailedExactBaseAttempts()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail detail = new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", "merge-1");
        PrCheckRunState head = check(
                11L, "build", "failure", "merge-1", 31L, 101L,
                1, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        PrCheckRunState base = check(
                12L, "build", "failure", "base-1", 32L, 102L,
                1, List.of());
        ActionsWorkflowRun headRun = workflow(
                101L, "pull_request", "merge-1", 31L, "failure");
        ActionsWorkflowRun baseRun = workflow(
                102L, "push", "base-1", 32L, "failure");

        stubObservationShell(
                pullRequests, pats, collaborators, detail, List.of(head));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "base-1")).thenReturn(List.of(base));
        stubExactRun(pullRequests, headRun,
                job(headRun, 1L, 11L, "build", "failure"));
        stubExactRun(pullRequests, baseRun,
                job(baseRun, 1L, 12L, "build", "failure"));
        stubFailureAnnotation(pullRequests, 11L);
        stubFailureAnnotation(pullRequests, 12L);

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution);

        assertThat(observed.ciProvenance().complete()).isTrue();
        assertThat(observed.ciProvenance().checks()).singleElement()
                .satisfies(comparison -> {
                    assertThat(comparison.head().complete()).isTrue();
                    assertThat(comparison.base().complete()).isTrue();
                    assertThat(comparison.base().state())
                            .isEqualTo(CheckState.FAILED);
                });
    }

    @Test
    void rejectsRunAttemptThatChangesBeforeProvenanceEmission()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail detail = new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", "merge-1");
        PrCheckRunState head = check(
                11L, "build", "failure", "merge-1", 31L, 101L,
                1, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        PrCheckRunState base = check(
                12L, "build", "success", "base-1", 32L, 102L,
                0, List.of());
        ActionsWorkflowRun firstAttempt = workflow(
                101L, "pull_request", "merge-1", 31L, "failure");
        ActionsWorkflowRun secondAttempt = new ActionsWorkflowRun(
                101L, 7L, ".github/workflows/ci.yml@main", "pull_request",
                "merge-1", 31L, 2, "completed", "failure");
        ActionsWorkflowRun baseRun = workflow(
                102L, "push", "base-1", 32L, "success");

        stubObservationShell(
                pullRequests, pats, collaborators, detail, List.of(head));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "base-1")).thenReturn(List.of(base));
        stubExactRun(pullRequests, firstAttempt,
                job(firstAttempt, 1L, 11L, "build", "failure"));
        stubExactRun(pullRequests, baseRun,
                job(baseRun, 1L, 12L, "build", "success"));
        when(pullRequests.fetchActionsWorkflowRun(
                "pat", REPOSITORY, 101L)).thenReturn(
                    Optional.of(firstAttempt), Optional.of(secondAttempt));
        stubFailureAnnotation(pullRequests, 11L);

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution);

        assertThat(observed.ciProvenance().complete()).isFalse();
        assertThat(observed.ciProvenance().incompleteReasons()).contains(
                "workflow run changed during CI evidence capture: 101");
    }

    @Test
    void provesStaticDependencyAggregateAndSkipsItsBaseComparison()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail detail = new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", "merge-1");
        PrCheckRunState aggregate = check(
                13L, 1003L, "CI success", "failure", "merge-1", 31L,
                101L, 0, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        PrCheckRunState backend = check(
                11L, 1001L, "Backend", "failure", "merge-1", 31L,
                101L, 1, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        PrCheckRunState commit = check(
                12L, 1002L, "Check commit abc123", "success", "merge-1",
                31L, 101L, 0, List.of());
        PrCheckRunState frontend = check(
                14L, 1004L, "Frontend", "skipped", "merge-1", 31L,
                101L, 0, List.of());
        PrCheckRunState baseBackend = check(
                21L, 2001L, "Backend", "success", "base-1", 41L,
                201L, 0, List.of());
        ActionsWorkflowRun headRun = new ActionsWorkflowRun(
                101L, 7L, ".github/workflows/ci.yml@main", "pull_request",
                "merge-1", 31L, 1, "completed", "failure");
        ActionsWorkflowRun baseRun = new ActionsWorkflowRun(
                201L, 7L, ".github/workflows/ci.yml@main", "push",
                "base-1", 41L, 1, "completed", "success");

        stubObservationShell(
                pullRequests, pats, collaborators, detail,
                List.of(aggregate, backend, commit, frontend));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "base-1"))
                .thenReturn(List.of(baseBackend));
        stubExactRun(
                pullRequests,
                headRun,
                job(headRun, 1001L, 11L, "Backend", "failure"),
                job(headRun, 1002L, 12L,
                        "Check commit abc123", "success"),
                job(headRun, 1003L, 13L, "CI success", "failure"),
                job(headRun, 1004L, 14L, "Frontend", "skipped"));
        stubExactRun(pullRequests, baseRun,
                job(baseRun, 2001L, 21L, "Backend", "success"));
        when(pullRequests.fetchCheckRunAnnotationsStrict(
                "pat", REPOSITORY, 13L, 0)).thenReturn(
                    new CheckRunAnnotationEvidence(List.of(), 0, 0, true));
        when(pullRequests.fetchCheckRunAnnotationsStrict(
                "pat", REPOSITORY, 11L, 1)).thenReturn(
                    new CheckRunAnnotationEvidence(
                            List.of(new CheckRunAnnotation(
                                    "Maven", "backend test failed",
                                    "backend/pom.xml", 1)),
                            1, 1, true));
        when(pullRequests.fetchFileBlob(
                "pat", REPOSITORY, ".github/workflows/ci.yml", "merge-1"))
                .thenReturn(Optional.of(new FileBlob(
                        "a".repeat(40), aggregateWorkflow())));
        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution);

        assertThat(observed.schemaVersion()).isEqualTo(5);
        assertThat(observed.ciProvenance().schemaVersion()).isEqualTo(5);
        assertThat(observed.ciProvenance().complete()).isTrue();
        assertThat(observed.ciProvenance().checks()).hasSize(2);
        assertThat(observed.ciProvenance().checks())
                .filteredOn(comparison -> comparison.head().externalId()
                        .equals("github-check:13"))
                .singleElement()
                .satisfies(comparison -> {
                    assertThat(comparison.base()).isNull();
                    assertThat(comparison.head().failureFingerprints()).isEmpty();
                    assertThat(comparison.head().aggregateEvidence())
                            .satisfies(evidence -> {
                                assertThat(evidence.workflowBlobSha())
                                        .isEqualTo("a".repeat(40));
                                assertThat(evidence.aggregateJobId())
                                        .isEqualTo(1003L);
                                assertThat(evidence.aggregateJobKey())
                                        .isEqualTo("ci-success");
                                assertThat(evidence.dependencies())
                                        .extracting(dependency -> dependency.jobKey()
                                                + ":" + dependency.externalCheckId()
                                                + ":" + dependency.state())
                                        .containsExactly(
                                                "backend:github-check:11:FAILED",
                                                "check-commit:github-check:12:PASSED",
                                                "frontend:github-check:14:SKIPPED");
                            });
                });
        assertThat(observed.ciProvenance().checks())
                .filteredOn(comparison -> comparison.head().externalId()
                        .equals("github-check:11"))
                .singleElement()
                .satisfies(comparison -> assertThat(comparison.base()).isNotNull());
        verify(pullRequests, never()).fetchActionsJobLogStrict(
                any(), any(), anyLong());
    }

    @Test
    void capturesExactMavenCompilerLogsAfterAnnotationAndAggregateMiss()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail detail = new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", "merge-1");
        PrCheckRunState head = check(
                11L, 1001L, "Backend", "failure", "merge-1", 31L,
                101L, 0, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        PrCheckRunState base = check(
                21L, 2001L, "Backend", "failure", "base-1", 41L,
                201L, 0, List.of());
        ActionsWorkflowRun headRun = workflow(
                101L, "pull_request", "merge-1", 31L, "failure");
        ActionsWorkflowRun baseRun = workflow(
                201L, "push", "base-1", 41L, "failure");
        String rawLog = """
                2026-08-01T19:22:25.8607359Z [ERROR] COMPILATION ERROR :
                2026-08-01T19:22:25.8609622Z [ERROR] /home/runner/work/widget/widget/backend/src/test/java/acme/TestThing.java:[10,5] cannot find symbol
                2026-08-01T19:22:25.8639483Z   symbol: class MissingType
                2026-08-01T19:22:25.8643240Z   location: class acme.TestThing
                2026-08-01T19:22:25.8650000Z [ERROR] 1 error
                """;

        stubObservationShell(
                pullRequests, pats, collaborators, detail, List.of(head));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "base-1"))
                .thenReturn(List.of(base));
        stubExactRun(pullRequests, headRun,
                job(headRun, 1001L, 11L, "Backend", "failure"));
        stubExactRun(pullRequests, baseRun,
                job(baseRun, 2001L, 21L, "Backend", "failure"));
        when(pullRequests.fetchCheckRunAnnotationsStrict(
                "pat", REPOSITORY, 11L, 0)).thenReturn(
                    new CheckRunAnnotationEvidence(List.of(), 0, 0, true));
        when(pullRequests.fetchCheckRunAnnotationsStrict(
                "pat", REPOSITORY, 21L, 0)).thenReturn(
                    new CheckRunAnnotationEvidence(List.of(), 0, 0, true));
        when(pullRequests.fetchActionsJobLogStrict(
                "pat", REPOSITORY, 1001L)).thenReturn(
                    ActionsJobLogCapture.complete(
                            1001L, rawLog,
                            rawLog.getBytes(StandardCharsets.UTF_8).length,
                            "a".repeat(64)));
        when(pullRequests.fetchActionsJobLogStrict(
                "pat", REPOSITORY, 2001L)).thenReturn(
                    ActionsJobLogCapture.complete(
                            2001L, rawLog,
                            rawLog.getBytes(StandardCharsets.UTF_8).length,
                            "b".repeat(64)));

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution);

        assertThat(observed.ciProvenance().complete()).isTrue();
        assertThat(observed.ciProvenance().checks()).singleElement()
                .satisfies(comparison -> {
                    assertThat(comparison.head().actionsJobLogEvidence())
                            .satisfies(proof -> {
                                assertThat(proof.jobId()).isEqualTo(1001L);
                                assertThat(proof.testedSha())
                                        .isEqualTo("merge-1");
                                assertThat(proof.diagnostics()).singleElement()
                                        .satisfies(diagnostic -> {
                                            assertThat(diagnostic.file())
                                                    .isEqualTo("backend/src/test/java/acme/TestThing.java");
                                            assertThat(diagnostic.code())
                                                    .isEqualTo("CANNOT_FIND_SYMBOL");
                                        });
                            });
                    assertThat(comparison.base().actionsJobLogEvidence())
                            .satisfies(proof -> {
                                assertThat(proof.jobId()).isEqualTo(2001L);
                                assertThat(proof.testedSha())
                                        .isEqualTo("base-1");
                            });
                    assertThat(comparison.head().failureFingerprints())
                            .isEqualTo(comparison.base().failureFingerprints());
                });
        assertThat(new ObjectMapper().writeValueAsString(
                observed.ciProvenance())).doesNotContain(rawLog);
    }

    @Test
    void rejectsAmbiguousOrSubstantiveAggregateWorkflowShapes()
    {
        var workflow = GitHubRemoteObserver.staticWorkflow(
                aggregateWorkflow(), "CI success");
        assertThat(workflow).isNotNull();
        assertThat(GitHubRemoteObserver.aggregateFailedOnlyAtDeclaredStep(
                workflow.aggregate(), job(
                        1003L, 13L, "CI success", "failure"))).isTrue();
        assertThat(GitHubRemoteObserver.aggregateFailedOnlyAtDeclaredStep(
                workflow.aggregate(), new ActionsWorkflowJob(
                        1003L, 13L, 101L, 1, "merge-1", "CI success",
                        "completed", "failure",
                        List.of(
                                new ActionsWorkflowJobStep(
                                        1, "Set up job", "completed", "failure"),
                                new ActionsWorkflowJobStep(
                                        2, "Check results", "completed", "failure")))))
                .isFalse();
        assertThat(List.of(
                aggregateWorkflow().replace(
                        "name: CI success", "name: CI ${{ github.ref }}"),
                aggregateWorkflow().replace(
                        "name: Backend", "uses: acme/ci/.github/workflows/test.yml@main"),
                aggregateWorkflow().replace(
                        "name: \"Check commit ${{ matrix.sha }}\"",
                        "name: \"${{ matrix.os }} ${{ matrix.sha }}\""),
                aggregateWorkflow().replace(
                        "      - name: Check results",
                        "      - uses: actions/checkout@v6\n"
                                + "      - name: Check results"),
                aggregateWorkflow().replace(
                        "          echo '${{ needs.frontend.result }}'",
                        "          make release\n"
                                + "          echo '${{ needs.frontend.result }}'"),
                aggregateWorkflow().replace(
                        "          echo '${{ needs.frontend.result }}'",
                        "          echo '${{ needs.backend.result }}'")))
                .allSatisfy(source -> assertThat(
                        GitHubRemoteObserver.staticWorkflow(
                                source, "CI success")).isNull());
        assertThat(GitHubRemoteObserver.staticWorkflow(
                aggregateWorkflow().replace(
                        "bash --noprofile --norc -euo pipefail {0}",
                        "sh -c {0}"),
                "CI success")).isNull();
    }

    @Test
    void recognizesTheTrackedCiAggregateWorkflow()
            throws Exception
    {
        Path path = Path.of("..", ".github", "workflows", "ci.yml");
        if (!Files.exists(path)) {
            path = Path.of(".github", "workflows", "ci.yml");
        }
        assertThat(GitHubRemoteObserver.staticWorkflow(
                Files.readString(path), "CI success")).isNotNull();
    }

    @Test
    void rejectsUnstableRunAttemptsAndMismatchedJobChecks()
    {
        ActionsWorkflowRun run = new ActionsWorkflowRun(
                101L, 7L, ".github/workflows/ci.yml@main", "pull_request",
                "merge-1", 31L, 1, "completed", "failure");
        ActionsWorkflowRun rerun = new ActionsWorkflowRun(
                101L, 7L, ".github/workflows/ci.yml@main", "pull_request",
                "merge-1", 31L, 2, "completed", "failure");
        ActionsWorkflowJob aggregate = job(
                1003L, 13L, "CI success", "failure");
        PrCheckRunState wrongCheck = check(
                12L, 1002L, "CI success", "failure", "merge-1", 31L,
                101L, 0, List.of());

        assertThat(GitHubRemoteObserver.sameCompletedFailedRun(run, rerun))
                .isFalse();
        assertThat(GitHubRemoteObserver.validJobSet(
                run, new ActionsWorkflowJobSetEvidence(
                        101L, 1, List.of(aggregate), 1, 2, false)))
                .isFalse();
        assertThat(GitHubRemoteObserver.exactJobCheck(
                run, aggregate, wrongCheck)).isFalse();
    }

    @Test
    void rejectsStaleCheckIdentityFromAnEarlierRerunAttempt()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail detail = new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", "merge-1");
        PrCheckRunState stale = check(
                11L, 1001L, "build", "failure", "merge-1", 31L,
                101L, 1, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        ActionsWorkflowRun rerun = new ActionsWorkflowRun(
                101L, 7L, ".github/workflows/ci.yml@main", "pull_request",
                "merge-1", 31L, 2, "completed", "failure");

        stubObservationShell(
                pullRequests, pats, collaborators, detail, List.of(stale));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "base-1")).thenReturn(List.of());
        stubExactRun(pullRequests, rerun,
                job(rerun, 1002L, 12L, "build", "failure"));
        stubFailureAnnotation(pullRequests, 11L);

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution);

        assertThat(observed.ciProvenance().complete()).isFalse();
        assertThat(observed.ciProvenance().checks()).singleElement()
                .satisfies(comparison ->
                        assertThat(comparison.head().complete()).isFalse());
    }

    @Test
    void rejectsConcreteCheckWithMismatchedAttemptJobId()
            throws Exception
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        CollaboratorPermissionService collaborators = mock(
                CollaboratorPermissionService.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        PrRawDetail detail = new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), "head-1", "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", "merge-1");
        PrCheckRunState check = check(
                11L, 1001L, "build", "failure", "merge-1", 31L,
                101L, 1, List.of(new PullRequestSubject(
                        17, "head-1", "base-1")));
        ActionsWorkflowRun run = workflow(
                101L, "pull_request", "merge-1", 31L, "failure");

        stubObservationShell(
                pullRequests, pats, collaborators, detail, List.of(check));
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "base-1")).thenReturn(List.of());
        stubExactRun(pullRequests, run,
                job(run, 1002L, 11L, "build", "failure"));
        stubFailureAnnotation(pullRequests, 11L);

        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, collaborators,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationOperationHandler.Observation observed = observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution);

        assertThat(observed.ciProvenance().complete()).isFalse();
        assertThat(observed.ciProvenance().checks()).singleElement()
                .satisfies(comparison ->
                        assertThat(comparison.head().complete()).isFalse());
    }

    @Test
    void fingerprintsIgnoreVolatileTextButRejectGenericFailures()
    {
        CheckRunAnnotation first = new CheckRunAnnotation(
                "ESLint", "\u001B[31m2026-07-31T01:02:03Z "
                        + "/home/runner/work/widget/widget/src/App.tsx:4 "
                        + "run_id=123 abcdefabcdefabcdefabcdefabcdefabcdefabcd"
                        + " no-unused-vars\u001B[0m",
                "src/App.tsx", 4);
        CheckRunAnnotation second = new CheckRunAnnotation(
                "ESLint", "2026-08-01T02:03:04Z "
                        + "/home/runner/work/widget/widget/src/App.tsx:4 "
                        + "run_id=999 1234512345123451234512345123451234512345"
                        + " no-unused-vars",
                "src/App.tsx", 4);

        assertThat(GitHubRemoteObserver.annotationFingerprints(List.of(first)))
                .isEqualTo(GitHubRemoteObserver.annotationFingerprints(
                        List.of(second)));
        assertThat(GitHubRemoteObserver.annotationFingerprints(List.of(
                new CheckRunAnnotation(
                        "", "Process completed with exit code 1.",
                        ".github", 20))))
                .isEqualTo(Set.of());
    }

    @Test
    void normalizesAllAddressableFeedbackWithExactTargets()
    {
        PrReviewThreadMessage root = comment(
                41L, null, "alice", "please fix", "thread-41");
        PrReviewThreadMessage ownReply = comment(
                43L, 41L, "me", "fixed", "thread-41");
        PrTimelineEvent topLevel = event(
                51L, "commented", "carol", null, "one more thing", null, null);
        PrTimelineEvent review = event(
                61L, "reviewed", "bob", "changes_requested",
                "please update tests", null, 61L);
        PrTimelineEvent requested = event(
                62L, "review_requested", "carol", null, null, "dave", null);

        List<RemoteObservationOperationHandler.FeedbackFact> facts =
                GitHubRemoteObserver.feedbackFacts(
                        List.of(root, ownReply),
                        List.of(
                                new ReviewThreadMeta(41L, "thread-41", false, null),
                                new ReviewThreadMeta(42L, "thread-42", true, "alice")),
                        List.of(review, requested), List.of(topLevel), "me",
                        new ObjectMapper().findAndRegisterModules());

        assertThat(facts)
                .extracting(RemoteObservationOperationHandler.FeedbackFact::kind)
                .containsExactlyInAnyOrder(
                        RemoteObservationOperationHandler.FeedbackKind.INLINE_COMMENT,
                        RemoteObservationOperationHandler.FeedbackKind.INLINE_COMMENT,
                        RemoteObservationOperationHandler.FeedbackKind.THREAD_REOPENED,
                        RemoteObservationOperationHandler.FeedbackKind.THREAD_RESOLVED,
                        RemoteObservationOperationHandler.FeedbackKind.TOP_LEVEL_COMMENT,
                        RemoteObservationOperationHandler.FeedbackKind.REVIEW_BODY,
                        RemoteObservationOperationHandler.FeedbackKind.REVIEW_VERDICT,
                        RemoteObservationOperationHandler.FeedbackKind.REQUESTED_REVIEW);
        assertThat(facts)
                .filteredOn(fact -> fact.externalKey().equals("inline-comment:43"))
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.ownAction()).isTrue();
                    assertThat(fact.commentId()).isEqualTo("41");
                    assertThat(fact.threadId()).isEqualTo("thread-41");
                });
        assertThat(facts)
                .filteredOn(fact -> fact.kind()
                        == RemoteObservationOperationHandler.FeedbackKind.REVIEW_VERDICT)
                .singleElement()
                .extracting(RemoteObservationOperationHandler.FeedbackFact::verdict)
                .isEqualTo(
                        RemoteObservationOperationHandler.FeedbackVerdict.CHANGES_REQUESTED);
    }

    @Test
    void cancellationStopsBeforeAnyRemoteRead()
    {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        when(pats.resolve("acme/widget")).thenReturn("pat");
        when(execution.isCancellationRequested()).thenReturn(true);
        GitHubRemoteObserver observer = new GitHubRemoteObserver(
                pullRequests, pats, mock(CollaboratorPermissionService.class),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> observer.observe(
                new RemoteObservationOperationHandler.Request(
                        "acme/widget", 17, "head-1", "base-1", List.of()),
                execution))
                .isInstanceOf(ExecutionPorts.OperationCanceledException.class);
        verify(pullRequests, never()).fetchPrDetail(any(), any());
    }

    private static PrReviewThreadMessage comment(
            long id, Long inReplyTo, String author, String body, String threadId)
    {
        return new PrReviewThreadMessage(
                id, inReplyTo, null, author, body, "src/App.java", 10, "RIGHT",
                "@@", "head-1", NOW.minusSeconds(10), null, false, null, null,
                10, null, "MEMBER", threadId, false, null);
    }

    private static PrTimelineEvent event(
            Long id,
            String kind,
            String actor,
            String state,
            String body,
            String requestedReviewer,
            Long reviewId)
    {
        return new PrTimelineEvent(
                id, kind, actor, state, NOW.minusSeconds(5), body, null, null,
                requestedReviewer, reviewId, "MEMBER", null);
    }

    private static UserProfile user(String login)
    {
        return new UserProfile(
                login, login, "avatar", "html", 0, 0, 0,
                null, null, null, null, false);
    }

    private static PrCheckRunState check(
            long id,
            String name,
            String conclusion,
            String testedSha,
            long suiteId,
            long runId,
            int annotationCount,
            List<PullRequestSubject> pullRequests)
    {
        return check(
                id, 1L, name, conclusion, testedSha, suiteId, runId,
                annotationCount, pullRequests);
    }

    private static PrCheckRunState check(
            long id,
            long jobId,
            String name,
            String conclusion,
            String testedSha,
            long suiteId,
            long runId,
            int annotationCount,
            List<PullRequestSubject> pullRequests)
    {
        return new PrCheckRunState(
                id, name, "completed", conclusion,
                "https://github.com/acme/widget/actions/runs/" + runId
                        + "/job/" + jobId,
                null, null, new GitHubMetadata(
                        testedSha, "external-" + id,
                        "https://github.com/acme/widget/actions/runs/" + runId
                                + "/job/" + jobId,
                        suiteId, 15368L, "github-actions", annotationCount,
                        pullRequests));
    }

    private static ActionsWorkflowRun workflow(
            long runId,
            String event,
            String sha,
            long suiteId,
            String conclusion)
    {
        return new ActionsWorkflowRun(
                runId, 7L, ".github/workflows/ci.yml@main", event, sha,
                suiteId, 1, "completed", conclusion);
    }

    private static ActionsWorkflowJob job(
            long jobId, long checkRunId, String name, String conclusion)
    {
        return job(new ActionsWorkflowRun(
                        101L, 7L, ".github/workflows/ci.yml@main",
                        "pull_request", "merge-1", 31L, 1,
                        "completed", "failure"),
                jobId, checkRunId, name, conclusion);
    }

    private static ActionsWorkflowJob job(
            ActionsWorkflowRun run,
            long jobId,
            long checkRunId,
            String name,
            String conclusion)
    {
        List<ActionsWorkflowJobStep> steps = name.equals("CI success")
                ? List.of(
                        new ActionsWorkflowJobStep(
                                1, "Set up job", "completed", "success"),
                        new ActionsWorkflowJobStep(
                                2, "Check results", "completed", "failure"),
                        new ActionsWorkflowJobStep(
                                3, "Complete job", "completed", "success"))
                : List.of(new ActionsWorkflowJobStep(
                        1, "Work", "completed", conclusion));
        return new ActionsWorkflowJob(
                jobId, checkRunId, run.runId(), run.runAttempt(), run.headSha(), name,
                "completed", conclusion, steps);
    }

    private static void stubExactRun(
            PullRequestRepository pullRequests,
            ActionsWorkflowRun run,
            ActionsWorkflowJob... jobs)
    {
        when(pullRequests.fetchActionsWorkflowRun(
                "pat", REPOSITORY, run.runId())).thenReturn(
                    Optional.of(run), Optional.of(run));
        when(pullRequests.fetchActionsWorkflowRunAttemptStrict(
                "pat", REPOSITORY, run.runId(), run.runAttempt()))
                .thenReturn(run);
        when(pullRequests.fetchActionsWorkflowAttemptJobsStrict(
                "pat", REPOSITORY, run.runId(), run.runAttempt())).thenReturn(
                    new ActionsWorkflowJobSetEvidence(
                            run.runId(), run.runAttempt(), List.of(jobs),
                            jobs.length, jobs.length, true));
    }

    private static void stubFailureAnnotation(
            PullRequestRepository pullRequests, long checkRunId)
    {
        when(pullRequests.fetchCheckRunAnnotationsStrict(
                "pat", REPOSITORY, checkRunId, 1)).thenReturn(
                    new CheckRunAnnotationEvidence(
                            List.of(new CheckRunAnnotation(
                                    "Build", "compile error in src/App.java",
                                    "src/App.java", 1)),
                            1, 1, true));
    }

    private static String aggregateWorkflow()
    {
        return """
                defaults:
                  run:
                    shell: bash --noprofile --norc -euo pipefail {0}
                jobs:
                  backend:
                    name: Backend
                    runs-on: ubuntu-latest
                  check-commit:
                    name: "Check commit ${{ matrix.sha }}"
                    strategy:
                      fail-fast: false
                      matrix: ${{ fromJSON(needs.dispatch.outputs.matrix) }}
                    runs-on: ubuntu-latest
                  frontend:
                    name: Frontend
                    runs-on: ubuntu-latest
                  ci-success:
                    name: CI success
                    if: ${{ always() }}
                    needs: [backend, check-commit, frontend]
                    runs-on: ubuntu-latest
                    steps:
                      - name: Check results
                        run: |
                          echo '${{ needs.backend.result }}'|grep -xE 'success|skipped'||exit 1
                          echo '${{ needs.check-commit.result }}'|grep -xE 'success|skipped'||exit 1
                          echo '${{ needs.frontend.result }}'|grep -xE 'success|skipped'||exit 1
                """;
    }

    private static void stubObservationShell(
            PullRequestRepository pullRequests,
            PatResolver pats,
            CollaboratorPermissionService collaborators,
            PrRawDetail detail,
            List<PrCheckRunState> checks)
    {
        when(pats.resolve("acme/widget")).thenReturn("pat");
        when(pullRequests.fetchUserProfile("pat")).thenReturn(user("me"));
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST)).thenReturn(detail);
        when(pullRequests.fetchPrReviews("pat", PULL_REQUEST))
                .thenReturn(List.of());
        when(pullRequests.fetchPrCheckRunsStrict(
                "pat", "acme", "widget", "head-1")).thenReturn(checks);
        when(pullRequests.fetchReviewThreadResolution("pat", PULL_REQUEST))
                .thenReturn(List.of());
        when(pullRequests.fetchPrReviewComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrTimeline(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchPrIssueComments(
                eq("pat"), eq(PULL_REQUEST), any())).thenReturn(List.of());
        when(pullRequests.fetchMergeQueueInfo("pat", PULL_REQUEST))
                .thenReturn(new MergeQueueInfo(true, null));
        when(collaborators.countWriteApprovals(
                eq("pat"), eq(REPOSITORY), any())).thenReturn(0);
    }
}
