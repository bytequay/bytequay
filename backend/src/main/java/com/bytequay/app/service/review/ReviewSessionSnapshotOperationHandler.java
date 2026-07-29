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
package com.bytequay.app.service.review;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData.ReviewCapabilities;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.InvestigationReviewContext.Snapshot;
import com.bytequay.app.service.review.ReviewSessionSnapshotRuntime.ExecutionSubject;
import com.bytequay.app.service.review.ReviewSessionSnapshotRuntime.Scope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.REMOTE_OBSERVATION;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.REVIEW_SESSION;
import static java.util.Objects.requireNonNull;

/** Captures one exact standalone review subject under dispatcher capacity. */
@Component
public final class ReviewSessionSnapshotOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "CAPTURE_REVIEW_SESSION_SNAPSHOT";
    public static final String CALLBACK_ROUTE = "REVIEW_SESSION_SNAPSHOT_RESULT";

    private final ReviewSessionSnapshotRuntime operations;
    private final PRService prs;
    private final PullRequestService pullRequests;
    private final InvestigationReviewContext contexts;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public ReviewSessionSnapshotOperationHandler(
            ReviewSessionSnapshotRuntime operations, PRService prs,
            PullRequestService pullRequests, InvestigationReviewContext contexts,
            ObjectMapper json)
    {
        this(operations, prs, pullRequests, contexts, json, Clock.systemUTC());
    }

    ReviewSessionSnapshotOperationHandler(
            ReviewSessionSnapshotRuntime operations, PRService prs,
            PullRequestService pullRequests, InvestigationReviewContext contexts,
            ObjectMapper json, Clock clock)
    {
        this.operations = requireNonNull(operations, "operations is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.contexts = requireNonNull(contexts, "contexts is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        ExecutionSubject subject = operations.requireExecutionSubject(
                envelope.fence().operationId());
        requireEnvelope(envelope, subject);
        if (!subject.current()) {
            Instant now = clock.instant();
            SnapshotResult result = new SnapshotResult(
                    1, subject.operationId(), subject.reviewId(), subject.prId(),
                    subject.repository(), subject.remotePrNumber(),
                    subject.baseBranch(), subject.prTitle(),
                    subject.prDescription(), subject.scope().wire(),
                    subject.workspaceId(), subject.repositoryRoot(),
                    subject.baseSha(), subject.headSha(), false, "", List.of(),
                    Map.of(), null, null, ReviewCapabilities.remoteOnly(), null,
                    subject.baseSha(), subject.headSha(), subject.baseSha(),
                    subject.headSha(), now.toEpochMilli(), now.toEpochMilli());
            String encoded = write(result);
            return new DispatchTicket.DispatchResult(
                    envelope.fence(), SUCCEEDED, encoded, encoded, null);
        }
        context.onCancellation(Thread.currentThread()::interrupt);
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "ReviewSession snapshot was canceled");
        }

        Instant started = clock.instant();
        PR pr = prs.findById(subject.prId()).orElseThrow(() ->
                new IllegalStateException("review PR no longer exists"));
        if (!subject.repository().equals(pr.repo())
                || subject.remotePrNumber() != pr.remotePrNumber()
                || !subject.baseBranch().equals(pr.baseBranch())
                || !subject.prTitle().equals(pr.title())
                || !subject.prDescription().equals(pr.description())) {
            throw new IllegalStateException(
                    "review PR identity changed before snapshot capture");
        }
        RemoteSubject before = observeRemote(pr);
        Snapshot snapshot;
        String captureError = null;
        try {
            if (subject.scope() == Scope.FULL) {
                contexts.prepareWatchedPr(pr);
                if (context.isCancellationRequested()
                        || Thread.currentThread().isInterrupted()) {
                    throw new ExecutionPorts.OperationCanceledException(
                            "ReviewSession snapshot was canceled");
                }
            }
            snapshot = subject.scope() == Scope.QUICK
                    ? contexts.loadRemoteOnly(pr)
                    : contexts.load(pr, true);
            if (subject.scope() == Scope.FULL
                    && snapshot.repositoryRoot() == null) {
                captureError = "watched repository does not contain the reviewed commit";
            }
            else if (subject.scope() == Scope.FULL
                    && !snapshot.repositoryRoot().toString().equals(
                            subject.repositoryRoot())) {
                captureError = "watched repository changed during snapshot capture";
            }
            else if (subject.scope() == Scope.QUICK
                    && (snapshot.localRoot() != null
                        || snapshot.repositoryRoot() != null
                        || !"remote-only".equals(
                            snapshot.capabilities().sourceMode()))) {
                captureError = "quick review capture was not remote-only";
            }
            else if (subject.scope() == Scope.FULL) {
                snapshot = contexts.freezeChangedFiles(snapshot);
            }
        }
        catch (RuntimeException e) {
            snapshot = new Snapshot(
                    pr, subject.baseSha(), subject.headSha(), "", List.of(),
                    null, null, ReviewCapabilities.remoteOnly());
            captureError = e.getMessage() == null
                    ? "review snapshot capture failed" : e.getMessage();
        }
        if (context.isCancellationRequested()
                || Thread.currentThread().isInterrupted()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "ReviewSession snapshot was canceled");
        }
        RemoteSubject after = observeRemote(pr);
        boolean current = subject.current()
                && before.equals(after)
                && before.matches(subject)
                && snapshot.baseCommit().equals(subject.baseSha())
                && snapshot.headCommit().equals(subject.headSha());
        SnapshotResult result = new SnapshotResult(
                1, subject.operationId(), subject.reviewId(), subject.prId(),
                subject.repository(), subject.remotePrNumber(),
                subject.baseBranch(), subject.prTitle(),
                subject.prDescription(),
                subject.scope().wire(), subject.workspaceId(),
                subject.repositoryRoot(), subject.baseSha(), subject.headSha(),
                current, current ? snapshot.diff() : "",
                current ? snapshot.files() : List.of(),
                current ? snapshot.fileContents() : Map.of(),
                current ? path(snapshot.localRoot()) : null,
                current ? path(snapshot.repositoryRoot()) : null,
                current ? snapshot.capabilities() : ReviewCapabilities.remoteOnly(),
                captureError, before.baseSha(), before.headSha(),
                after.baseSha(), after.headSha(), started.toEpochMilli(),
                clock.instant().toEpochMilli());
        String encoded = write(result);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), SUCCEEDED, encoded, encoded, null);
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            throws Exception
    {
        return execute(context);
    }

    private RemoteSubject observeRemote(PR pr)
    {
        if (pr.repo() == null || pr.remotePrNumber() == null) {
            return new RemoteSubject("unknown-base", "unknown-head");
        }
        List<PullRequestCommit> commits = pullRequests.getPullRequestCommits(
                pr.repo(), pr.remotePrNumber());
        return commits.isEmpty()
                ? new RemoteSubject("unknown-base", "unknown-head")
                : new RemoteSubject(
                        commits.getFirst().sha(), commits.getLast().sha());
    }

    private static void requireEnvelope(
            DispatchTicket.DispatchEnvelope envelope, ExecutionSubject subject)
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        boolean quick = subject.scope() == Scope.QUICK;
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.family() != (quick ? REMOTE_OBSERVATION : LOCAL_GIT)
                || envelope.owner().kind() != REVIEW_SESSION
                || !subject.reviewId().equals(envelope.owner().id())
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !subject.operationId().equals(fence.operationId())
                || fence.taskEpoch() != null || fence.stageId() != null
                || fence.stageGeneration() != null || fence.attempt() != 1
                || fence.expectedCodeFingerprint() != null
                || !subject.headSha().equals(fence.expectedHeadSha())
                || !subject.baseSha().equals(fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "ReviewSession snapshot ticket differs from its exact subject");
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not encode ReviewSession snapshot", e);
        }
    }

    private static String path(Path value)
    {
        return value == null ? null : value.toString();
    }

    public record SnapshotResult(
            int schemaVersion, String operationId, String reviewId, String prId,
            String repository, int remotePrNumber, String baseBranch,
            String prTitle, String prDescription,
            String scope, String workspaceId, String requestedRepositoryRoot,
            String baseSha, String headSha, boolean subjectCurrent, String diff,
            List<DiffFile> files, Map<String, String> fileContents,
            String localRoot, String repositoryRoot,
            ReviewCapabilities capabilities, String captureError,
            String beforeBaseSha, String beforeHeadSha,
            String afterBaseSha, String afterHeadSha,
            long startedAtMs, long completedAtMs)
    {
        public SnapshotResult
        {
            if (schemaVersion != 1 || startedAtMs < 0
                    || completedAtMs < startedAtMs) {
                throw new IllegalArgumentException(
                        "ReviewSession snapshot identity is invalid");
            }
            requireNonNull(diff, "diff is null");
            requireNonNull(prTitle, "prTitle is null");
            requireNonNull(prDescription, "prDescription is null");
            files = List.copyOf(requireNonNull(files, "files is null"));
            fileContents = Map.copyOf(requireNonNull(
                    fileContents, "fileContents is null"));
            requireNonNull(capabilities, "capabilities is null");
            if (!subjectCurrent && (!diff.isEmpty() || !files.isEmpty()
                    || !fileContents.isEmpty())) {
                throw new IllegalArgumentException(
                        "A superseded ReviewSession snapshot cannot carry evidence");
            }
            if ("quick".equals(scope)
                    && (localRoot != null || repositoryRoot != null
                        || !"remote-only".equals(capabilities.sourceMode()))) {
                throw new IllegalArgumentException(
                        "Quick ReviewSession snapshot must be remote-only");
            }
        }
    }

    private record RemoteSubject(String baseSha, String headSha)
    {
        private boolean matches(ExecutionSubject subject)
        {
            return Objects.equals(baseSha, subject.baseSha())
                    && Objects.equals(headSha, subject.headSha());
        }
    }
}
