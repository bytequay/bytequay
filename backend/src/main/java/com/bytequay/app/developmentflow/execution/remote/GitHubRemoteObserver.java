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
import com.bytequay.app.developmentflow.stage.MavenCompilerLogParser;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.ActionsJobLogEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.AggregateDependency;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.AggregateEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CanonicalDiagnostic;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckComparison;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckProfile;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.PullRequestAssociation;
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
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestRepository.ActionsJobLogCapture;
import com.bytequay.app.repository.PullRequestRepository.ActionsJobLogStatus;
import com.bytequay.app.repository.PullRequestRepository.ActionsWorkflowJob;
import com.bytequay.app.repository.PullRequestRepository.ActionsWorkflowJobSetEvidence;
import com.bytequay.app.repository.PullRequestRepository.ActionsWorkflowRun;
import com.bytequay.app.repository.PullRequestRepository.CheckRunAnnotation;
import com.bytequay.app.repository.PullRequestRepository.CheckRunAnnotationEvidence;
import com.bytequay.app.repository.PullRequestRepository.FileBlob;
import com.bytequay.app.repository.PullRequestRepository.MergeQueueInfo;
import com.bytequay.app.repository.PullRequestRepository.ReviewThreadMeta;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.pr.CollaboratorPermissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.CANCELED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.FAILED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.NEUTRAL;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.PASSED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.PENDING;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.QUEUED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.SKIPPED;
import static java.util.Objects.requireNonNull;

/** Exact, fail-closed GitHub snapshot used by the V2 Remote owner. */
@Component
public final class GitHubRemoteObserver
        implements RemoteObservationOperationHandler.Observer
{
    private static final Pattern ACTIONS_RUN = Pattern.compile(
            "/actions/runs/(\\d+)(?:[/?#]|$)");
    private static final Pattern ACTIONS_JOB = Pattern.compile(
            "/actions/runs/(\\d+)/job/(\\d+)(?:[/?#]|$)");
    private static final Pattern WORKFLOW_PATH = Pattern.compile(
            "^(\\.github/workflows/[A-Za-z0-9._/-]+\\.ya?ml)"
                    + "(?:@[^@\\s]+)?$");
    private static final Pattern MATRIX_NAME = Pattern.compile(
            "^([^$]*)\\$\\{\\{\\s*matrix\\.([A-Za-z_][A-Za-z0-9_]*)"
                    + "\\s*}}([^$]*)$");
    private static final Pattern JOB_KEY = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_-]*$");
    private static final Pattern RESULT_GATE = Pattern.compile(
            "^echo\\s+(['\"])\\$\\{\\{\\s*needs\\."
                    + "([A-Za-z_][A-Za-z0-9_-]*)\\.result\\s*}}\\1"
                    + "\\s*\\|\\s*grep\\s+-xE\\s+(['\"])"
                    + "(?:success\\|skipped|skipped\\|success)\\3"
                    + "\\s*\\|\\|\\s*(?:exit\\s+1|\\{\\s*(?:echo\\s+"
                    + "(?:'[^'\\r\\n$`]*'|\"[^\"\\r\\n$`]*\")"
                    + "\\s+>&2;\\s*)?exit\\s+1;?\\s*})\\s*;?$");
    private static final Set<String> AGGREGATE_JOB_KEYS = ImmutableSet.of(
            "name", "if", "needs", "runs-on", "timeout-minutes", "steps",
            "permissions");
    private static final Set<String> AGGREGATE_STEP_KEYS = ImmutableSet.of("name", "run");
    private static final Pattern ANSI = Pattern.compile(
            "\\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\b");
    private static final Pattern WORKSPACE_PATH = Pattern.compile(
            "/home/runner/work/[^/\\s]+/[^/\\s]+/");
    private static final Pattern SHA = Pattern.compile(
            "(?i)\\b[0-9a-f]{40,64}\\b");
    private static final Pattern ACTIONS_ID = Pattern.compile(
            "(?i)(actions/runs/|actions/jobs/|run[_ -]?id[=: ]+|job[_ -]?id[=: ]+)\\d+");
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern GENERIC_FAILURE = Pattern.compile(
            "(?i)^(?:process completed with exit code \\d+\\.?|"
                    + "the operation was canceled\\.?|"
                    + "no job summary was generated\\.?)$");

    private final PullRequestRepository pullRequests;
    private final PatResolver pats;
    private final CollaboratorPermissionService collaborators;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public GitHubRemoteObserver(
            PullRequestRepository pullRequests,
            PatResolver pats,
            CollaboratorPermissionService collaborators,
            ObjectMapper json)
    {
        this(pullRequests, pats, collaborators, json, Clock.systemUTC());
    }

    GitHubRemoteObserver(
            PullRequestRepository pullRequests,
            PatResolver pats,
            CollaboratorPermissionService collaborators,
            ObjectMapper json,
            Clock clock)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.collaborators = requireNonNull(collaborators, "collaborators is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public RemoteObservationOperationHandler.Observation observe(
            RemoteObservationOperationHandler.Request request,
            ExecutionContext execution)
            throws ExecutionPorts.OperationCanceledException
    {
        requireNonNull(request, "request is null");
        requireNonNull(execution, "execution is null");
        RepoRef repository = RepoRef.parse(request.repositoryId());
        PullRequestRef pullRequest = PullRequestRef.of(
                repository.owner(), repository.repo(), request.pullRequestNumber());
        String pat = pats.resolve(repository.fullName());

        requireActive(execution);
        String viewer = requireText(
                pullRequests.fetchUserProfile(pat).login(), "GitHub viewer login");
        requireActive(execution);
        boolean viewerCanMerge = pullRequests.fetchViewerCanWrite(pat, repository);
        requireActive(execution);
        PrRawDetail detail = requireNonNull(
                pullRequests.fetchPrDetail(pat, pullRequest),
                "GitHub returned no pull request detail");
        requireText(detail.headSha(), "observed headSha");
        requireText(detail.baseSha(), "observed baseSha");
        requireActive(execution);
        List<PrReviewState> reviews = List.copyOf(
                pullRequests.fetchPrReviews(pat, pullRequest));
        requireActive(execution);
        List<PrCheckRunState> checks = List.copyOf(
                pullRequests.fetchPrCheckRunsStrict(
                        pat, repository.owner(), repository.repo(), detail.headSha()));
        RemoteCiProvenance ciProvenance = ciProvenance(
                request, execution, repository, pat, detail, checks);
        requireActive(execution);
        List<ReviewThreadMeta> threads = List.copyOf(
                pullRequests.fetchReviewThreadResolution(pat, pullRequest));
        requireActive(execution);
        List<PrReviewThreadMessage> comments = List.copyOf(
                pullRequests.fetchPrReviewComments(pat, pullRequest, Instant.EPOCH));
        requireActive(execution);
        List<PrTimelineEvent> timeline = List.copyOf(
                pullRequests.fetchPrTimeline(pat, pullRequest, Instant.EPOCH));
        requireActive(execution);
        List<PrTimelineEvent> issueComments = List.copyOf(
                pullRequests.fetchPrIssueComments(pat, pullRequest, Instant.EPOCH));
        requireActive(execution);
        MergeQueueInfo queue = requireNonNull(
                pullRequests.fetchMergeQueueInfo(pat, pullRequest),
                "GitHub returned no merge queue observation");
        requireActive(execution);
        PrRawDetail stableDetail = requireNonNull(
                pullRequests.fetchPrDetail(pat, pullRequest),
                "GitHub returned no pull request stability detail");
        if (!Objects.equals(detail.headSha(), stableDetail.headSha())
                || !Objects.equals(detail.baseSha(), stableDetail.baseSha())
                || !Objects.equals(
                        detail.mergeCommitSha(), stableDetail.mergeCommitSha())
                || !Objects.equals(detail.state(), stableDetail.state())
                || detail.merged() != stableDetail.merged()
                || detail.draft() != stableDetail.draft()) {
            throw new IllegalStateException(
                    "GitHub pull request changed during exact observation");
        }

        List<PrReviewState> effective = effectiveReviews(reviews);
        int approvals = (int) effective.stream()
                .filter(review -> "APPROVED".equals(normalize(review.state())))
                .count();
        int changesRequested = (int) effective.stream()
                .filter(review -> "CHANGES_REQUESTED".equals(normalize(review.state())))
                .count();
        int writeApprovals = collaborators.countWriteApprovals(
                pat, repository, effective);
        Set<Long> unresolvedRoots = new HashSet<>();
        threads.stream()
                .filter(thread -> !thread.resolved())
                .map(ReviewThreadMeta::rootCommentDatabaseId)
                .forEach(unresolvedRoots::add);
        int unresolvedComments = (int) comments.stream()
                .filter(comment -> unresolvedRoots.contains(
                        comment.inReplyTo() == null
                                ? comment.githubId() : comment.inReplyTo()))
                .count();
        List<RemoteObservationOperationHandler.FeedbackFact> feedback =
                feedbackFacts(
                        comments, threads, timeline, issueComments, viewer, json);
        long observedAt = clock.instant().toEpochMilli();

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("repository", repository.fullName());
        raw.put("pullRequest", request.pullRequestNumber());
        raw.put("detail", detail);
        raw.put("reviews", reviews);
        raw.put("checks", checks);
        raw.put("ciProvenance", ciProvenance);
        raw.put("reviewThreads", threads);
        raw.put("reviewComments", comments);
        raw.put("timeline", timeline);
        raw.put("issueComments", issueComments);
        raw.put("viewer", viewer);
        raw.put("viewerCanMerge", viewerCanMerge);
        raw.put("mergeQueue", queue);
        raw.put("observedAtMs", observedAt);
        String rawEvidence = write(raw);
        String key = digest(repository.fullName() + "#"
                + request.pullRequestNumber() + ":" + detail.headSha() + ":"
                + detail.baseSha() + ":" + observedAt + ":" + digest(rawEvidence));
        return new RemoteObservationOperationHandler.Observation(
                5,
                key,
                detail.headSha(),
                detail.baseSha(),
                prState(detail),
                mergeability(detail),
                mergeQueueState(detail, queue),
                mergeQueueCapability(queue),
                approvals,
                writeApprovals,
                changesRequested,
                detail.requestedReviewerCount(),
                unresolvedRoots.size(),
                unresolvedComments,
                normalizeChecks(checks, json),
                feedback,
                viewer,
                viewerCanMerge,
                ciProvenance,
                rawEvidence,
                observedAt);
    }

    private RemoteCiProvenance ciProvenance(
            RemoteObservationOperationHandler.Request request,
            ExecutionContext execution,
            RepoRef repository,
            String pat,
            PrRawDetail detail,
            List<PrCheckRunState> headChecks)
            throws ExecutionPorts.OperationCanceledException
    {
        List<IndexedCheck> failures = new ArrayList<>();
        for (int index = 0; index < headChecks.size(); index++) {
            PrCheckRunState check = requireNonNull(
                    headChecks.get(index), "check is null");
            RemoteCiPolicy.CheckState state = checkState(
                    check.status(), check.conclusion());
            if (state == FAILED || infrastructureFailure(
                    state, check.conclusion())) {
                failures.add(new IndexedCheck(index, check));
            }
        }
        if (failures.isEmpty()) {
            return new RemoteCiProvenance(
                    5, repository.fullName(), request.pullRequestNumber(),
                    detail.headSha(), detail.baseSha(), detail.mergeCommitSha(),
                    true, List.of(), List.of());
        }

        requireActive(execution);
        List<PrCheckRunState> baseChecks = List.copyOf(
                pullRequests.fetchPrCheckRunsStrict(
                        pat, repository.owner(), repository.repo(),
                        detail.baseSha()));
        Map<Long, Optional<ActionsWorkflowRun>> workflows = new HashMap<>();
        List<String> reasons = new ArrayList<>();
        List<CheckComparison> comparisons = new ArrayList<>();
        Set<CheckProfile> headProfiles = new HashSet<>();
        Map<WorkflowBlobKey, Optional<FileBlob>> workflowBlobs = new HashMap<>();
        Map<RunAttemptKey, Optional<ExactRunEvidence>> exactRuns =
                new HashMap<>();
        for (IndexedCheck failed : failures) {
            EvidenceResult built = checkEvidence(
                    execution, repository, pat, request.pullRequestNumber(),
                    detail, failed.check(), failed.index(), true, workflows,
                    exactRuns);
            CheckEvidence head = built.evidence();
            AggregateEvidence aggregate = aggregateEvidence(
                    execution, repository, pat, headChecks, failed, head,
                    built.annotationsComplete(), workflows, workflowBlobs,
                    exactRuns);
            if (aggregate != null) {
                head = withAggregate(head, aggregate);
            }
            else {
                head = withActionsJobLogEvidence(
                        execution, repository, pat, failed.check(), head,
                        built.annotationsComplete(), exactRuns);
            }
            if (!head.complete()) {
                reasons.add("incomplete head evidence for "
                        + checkName(failed.check()));
            }
            if (head.profile() == null || !headProfiles.add(head.profile())) {
                reasons.add("duplicate or incomplete head profile for "
                        + checkName(failed.check()));
            }
            if (aggregate != null) {
                comparisons.add(new CheckComparison(head, null));
                continue;
            }

            List<CheckEvidence> matches = new ArrayList<>();
            for (int index = 0; index < baseChecks.size(); index++) {
                PrCheckRunState base = requireNonNull(
                        baseChecks.get(index), "base check is null");
                if (!possibleProfileMatch(failed.check(), base)) {
                    continue;
                }
                EvidenceResult builtBase = checkEvidence(
                        execution, repository, pat,
                        request.pullRequestNumber(), detail, base, index,
                        false, workflows, exactRuns);
                CheckEvidence evidence = withActionsJobLogEvidence(
                        execution, repository, pat, base,
                        builtBase.evidence(), builtBase.annotationsComplete(),
                        exactRuns);
                if (head.profile() != null
                        && head.profile().equals(evidence.profile())) {
                    matches.add(evidence);
                }
            }
            CheckEvidence base = matches.size() == 1 ? matches.getFirst() : null;
            if (base == null) {
                reasons.add(matches.isEmpty()
                        ? "missing exact-base profile for "
                                + checkName(failed.check())
                        : "duplicate exact-base profile for "
                                + checkName(failed.check()));
            }
            else if (!base.complete()) {
                reasons.add("incomplete exact-base evidence for "
                        + checkName(failed.check()));
            }
            comparisons.add(new CheckComparison(head, base));
        }
        for (Optional<ExactRunEvidence> cached : exactRuns.values()) {
            if (cached.isEmpty()) {
                continue;
            }
            ExactRunEvidence exact = cached.get();
            requireActive(execution);
            Optional<ActionsWorkflowRun> latest =
                    pullRequests.fetchActionsWorkflowRun(
                            pat, repository, exact.run().runId());
            if (latest == null || latest.isEmpty()
                    || !sameCompletedRun(exact.run(), latest.get())) {
                reasons.add("workflow run changed during CI evidence capture: "
                        + exact.run().runId());
            }
        }
        boolean complete = reasons.isEmpty();
        return new RemoteCiProvenance(
                5, repository.fullName(), request.pullRequestNumber(),
                detail.headSha(), detail.baseSha(), detail.mergeCommitSha(),
                complete, reasons, comparisons);
    }

    private EvidenceResult checkEvidence(
            ExecutionContext execution,
            RepoRef repository,
            String pat,
            int pullRequestNumber,
            PrRawDetail detail,
            PrCheckRunState check,
            int index,
            boolean head,
            Map<Long, Optional<ActionsWorkflowRun>> workflows,
            Map<RunAttemptKey, Optional<ExactRunEvidence>> exactRuns)
            throws ExecutionPorts.OperationCanceledException
    {
        GitHubMetadata metadata = check.githubMetadata();
        Long runId = metadata == null
                ? null : actionsRunId(metadata.detailsUrl());
        Optional<ActionsWorkflowRun> workflow = Optional.empty();
        if (runId != null) {
            workflow = workflows.get(runId);
            if (workflow == null) {
                requireActive(execution);
                workflow = pullRequests.fetchActionsWorkflowRun(
                        pat, repository, runId);
                if (workflow == null) {
                    workflow = Optional.empty();
                }
                workflows.put(runId, workflow);
            }
        }
        ActionsWorkflowRun run = workflow.orElse(null);
        CheckProfile profile = metadata == null || run == null
                ? null : new CheckProfile(
                        metadata.appId(), metadata.appSlug(), run.workflowId(),
                        workflowFile(run.workflowPath()), check.name());
        RemoteCiPolicy.CheckState state = checkState(
                check.status(), check.conclusion());
        boolean infrastructureFailure = infrastructureFailure(
                state, check.conclusion());
        PullRequestAssociation association = head && metadata != null
                ? matchingAssociation(
                        metadata.pullRequests(), pullRequestNumber,
                        detail.headSha(), detail.baseSha())
                : null;
        boolean subjectExact = metadata != null && run != null
                && Objects.equals(metadata.checkSuiteId(), run.checkSuiteId())
                && Objects.equals(metadata.testedSha(), run.headSha())
                && (head
                        ? Objects.equals(metadata.testedSha(), detail.headSha())
                                || Objects.equals(
                                        metadata.testedSha(),
                                        detail.mergeCommitSha())
                                        && association != null
                        : Objects.equals(
                                metadata.testedSha(), detail.baseSha()));
        Optional<ExactRunEvidence> exact = loadExactRun(
                execution, repository, pat, run, exactRuns);
        List<ActionsWorkflowJob> exactJobs = check.githubId() == null
                || exact.isEmpty() ? List.of()
                : exact.get().jobs().jobs().stream()
                        .filter(job -> job.checkRunId() == check.githubId())
                        .toList();
        boolean exactJobBound = exactJobs.size() == 1
                && exactJobCheck(exact.get().run(), exactJobs.getFirst(), check);
        boolean identityComplete = check.githubId() != null
                && profileComplete(profile)
                && runId != null
                && run.runAttempt() != null
                && subjectExact
                && exactJobBound;

        Set<String> fingerprints = ImmutableSet.of();
        boolean failureEvidenceComplete = infrastructureFailure;
        boolean annotationsComplete = false;
        if (state == FAILED && !infrastructureFailure
                && check.githubId() != null && metadata != null
                && metadata.annotationCount() != null) {
            requireActive(execution);
            CheckRunAnnotationEvidence annotations =
                    pullRequests.fetchCheckRunAnnotationsStrict(
                            pat, repository, check.githubId(),
                            metadata.annotationCount());
            if (annotations != null) {
                fingerprints = annotationFingerprints(
                        annotations.failureAnnotations());
                annotationsComplete = annotations.complete();
                failureEvidenceComplete = annotations.complete()
                        && !fingerprints.isEmpty();
            }
        }
        boolean complete = identityComplete
                && (state == PASSED || infrastructureFailure
                    || state == FAILED && failureEvidenceComplete);
        return new EvidenceResult(new CheckEvidence(
                externalCheckId(check, index), profile,
                metadata == null ? null : metadata.checkSuiteId(),
                run == null ? null : run.checkSuiteId(),
                runId, run == null ? null : run.runAttempt(),
                metadata == null ? null : metadata.testedSha(),
                run == null ? null : run.headSha(),
                run == null ? null : run.event(), state, complete,
                fingerprints, association), annotationsComplete);
    }

    private AggregateEvidence aggregateEvidence(
            ExecutionContext execution,
            RepoRef repository,
            String pat,
            List<PrCheckRunState> headChecks,
            IndexedCheck failed,
            CheckEvidence head,
            boolean annotationsComplete,
            Map<Long, Optional<ActionsWorkflowRun>> workflows,
            Map<WorkflowBlobKey, Optional<FileBlob>> workflowBlobs,
            Map<RunAttemptKey, Optional<ExactRunEvidence>> exactRuns)
            throws ExecutionPorts.OperationCanceledException
    {
        if (head.state() != FAILED
                || infrastructureFailure(head.state(), failed.check().conclusion())
                || !annotationsComplete || !head.failureFingerprints().isEmpty()
                || head.profile() == null || head.workflowRunId() == null
                || head.workflowRunAttempt() == null
                || head.workflowRunAttempt() < 1
                || !hasText(head.checkTestedSha())) {
            return null;
        }
        ActionsWorkflowRun latest = workflows.getOrDefault(
                head.workflowRunId(), Optional.empty()).orElse(null);
        String path = latest == null ? null : workflowFile(latest.workflowPath());
        if (!hasText(path) || !Objects.equals(path, head.profile().workflowPath())) {
            return null;
        }

        WorkflowBlobKey blobKey = new WorkflowBlobKey(path, head.checkTestedSha());
        Optional<FileBlob> blob = workflowBlobs.get(blobKey);
        if (blob == null) {
            requireActive(execution);
            blob = pullRequests.fetchFileBlob(
                    pat, repository, path, head.checkTestedSha());
            if (blob == null) {
                blob = Optional.empty();
            }
            workflowBlobs.put(blobKey, blob);
        }
        if (blob.isEmpty() || !validBlob(blob.get())) {
            return null;
        }
        FileBlob exactBlob = blob.get();
        StaticWorkflow staticWorkflow = staticWorkflow(
                exactBlob.text(), checkName(failed.check()));
        if (staticWorkflow == null) {
            return null;
        }

        Optional<ExactRunEvidence> runtime = loadExactRun(
                execution, repository, pat, latest, exactRuns);
        return runtime.map(value -> bindAggregate(
                staticWorkflow, value, exactBlob, headChecks, failed, head))
                .orElse(null);
    }

    private CheckEvidence withActionsJobLogEvidence(
            ExecutionContext execution,
            RepoRef repository,
            String pat,
            PrCheckRunState check,
            CheckEvidence evidence,
            boolean annotationsComplete,
            Map<RunAttemptKey, Optional<ExactRunEvidence>> exactRuns)
            throws ExecutionPorts.OperationCanceledException
    {
        if (evidence.state() != FAILED
                || infrastructureFailure(
                        evidence.state(), check.conclusion())
                || !annotationsComplete
                || !evidence.failureFingerprints().isEmpty()
                || evidence.aggregateEvidence() != null
                || evidence.workflowRunId() == null
                || evidence.workflowRunAttempt() == null
                || evidence.workflowRunAttempt() < 1
                || check.githubId() == null) {
            return evidence;
        }
        Optional<ExactRunEvidence> exact = exactRuns.getOrDefault(
                new RunAttemptKey(
                        evidence.workflowRunId(),
                        evidence.workflowRunAttempt()),
                Optional.empty());
        if (exact.isEmpty()) {
            return evidence;
        }
        List<ActionsWorkflowJob> jobs = exact.get().jobs().jobs().stream()
                .filter(job -> job.checkRunId() == check.githubId())
                .filter(job -> exactJobCheck(exact.get().run(), job, check))
                .toList();
        if (jobs.size() != 1) {
            return evidence;
        }

        requireActive(execution);
        ActionsJobLogCapture capture = pullRequests.fetchActionsJobLogStrict(
                pat, repository, jobs.getFirst().jobId());
        if (capture == null
                || capture.status() != ActionsJobLogStatus.COMPLETE) {
            return evidence;
        }
        MavenCompilerLogParser.Proof parsed =
                MavenCompilerLogParser.parse(capture.rawText());
        if (!parsed.complete()
                || !RemoteCiProvenance.ACTIONS_JOB_LOG_SOURCE.equals(
                        parsed.source())
                || !RemoteCiProvenance.MAVEN_COMPILER_PARSER.equals(
                        parsed.parser())
                || parsed.version()
                        != RemoteCiProvenance.MAVEN_COMPILER_PARSER_VERSION) {
            return evidence;
        }
        List<CanonicalDiagnostic> diagnostics = parsed.canonicalDiagnostics()
                .stream()
                .map(diagnostic -> new CanonicalDiagnostic(
                        diagnostic.file(), "COMPILATION_ERROR",
                        diagnostic.kind(), diagnostic.message(),
                        diagnostic.symbol(), diagnostic.location()))
                .sorted(Comparator.comparing(
                        RemoteCiProvenance::canonicalFingerprint))
                .toList();
        Set<String> fingerprints = diagnostics.stream()
                .map(RemoteCiProvenance::canonicalFingerprint)
                .collect(Collectors.toUnmodifiableSet());
        if (diagnostics.isEmpty()
                || fingerprints.size() != diagnostics.size()
                || !fingerprints.equals(ImmutableSet.copyOf(parsed.fingerprints()))) {
            return evidence;
        }
        ActionsJobLogEvidence proof = new ActionsJobLogEvidence(
                parsed.source(), parsed.parser(), parsed.version(),
                evidence.workflowRunId(), evidence.workflowRunAttempt(),
                jobs.getFirst().jobId(), check.githubId(),
                evidence.checkTestedSha(), capture.rawByteLength(),
                capture.sha256Digest(), true, true, diagnostics);
        return new CheckEvidence(
                evidence.externalId(), evidence.profile(),
                evidence.checkSuiteId(), evidence.workflowCheckSuiteId(),
                evidence.workflowRunId(), evidence.workflowRunAttempt(),
                evidence.checkTestedSha(), evidence.workflowTestedSha(),
                evidence.workflowEvent(), evidence.state(), true,
                fingerprints, evidence.pullRequestAssociation(), null, proof);
    }

    private Optional<ExactRunEvidence> loadExactRun(
            ExecutionContext execution,
            RepoRef repository,
            String pat,
            ActionsWorkflowRun latest,
            Map<RunAttemptKey, Optional<ExactRunEvidence>> exactRuns)
            throws ExecutionPorts.OperationCanceledException
    {
        if (latest == null || latest.runId() < 1
                || latest.runAttempt() == null || latest.runAttempt() < 1) {
            return Optional.empty();
        }
        RunAttemptKey key = new RunAttemptKey(
                latest.runId(), latest.runAttempt());
        Optional<ExactRunEvidence> cached = exactRuns.get(key);
        if (cached != null) {
            return cached.filter(value -> sameCompletedRun(
                    latest, value.run()));
        }
        requireActive(execution);
        ActionsWorkflowRun exact = pullRequests.fetchActionsWorkflowRunAttemptStrict(
                pat, repository, latest.runId(), latest.runAttempt());
        if (!sameCompletedRun(latest, exact)) {
            exactRuns.put(key, Optional.empty());
            return Optional.empty();
        }
        requireActive(execution);
        ActionsWorkflowJobSetEvidence jobs =
                pullRequests.fetchActionsWorkflowAttemptJobsStrict(
                        pat, repository, exact.runId(), exact.runAttempt());
        if (!validJobSet(exact, jobs)) {
            exactRuns.put(key, Optional.empty());
            return Optional.empty();
        }
        Optional<ExactRunEvidence> result = Optional.of(
                new ExactRunEvidence(exact, jobs));
        exactRuns.put(key, result);
        return result;
    }

    private static AggregateEvidence bindAggregate(
            StaticWorkflow workflow,
            ExactRunEvidence runtime,
            FileBlob blob,
            List<PrCheckRunState> headChecks,
            IndexedCheck failed,
            CheckEvidence head)
    {
        ActionsWorkflowRun run = runtime.run();
        if (!"FAILURE".equals(normalize(run.conclusion()))) {
            return null;
        }
        Map<Long, IndexedCheck> checksById = new HashMap<>();
        for (int index = 0; index < headChecks.size(); index++) {
            PrCheckRunState check = headChecks.get(index);
            if (check.githubId() != null
                    && checksById.put(check.githubId(),
                            new IndexedCheck(index, check)) != null) {
                return null;
            }
        }

        Map<Long, ActionsWorkflowJob> jobsByCheck = new HashMap<>();
        Map<String, List<RuntimeJob>> jobsByKey = new LinkedHashMap<>();
        for (ActionsWorkflowJob job : runtime.jobs().jobs()) {
            IndexedCheck check = checksById.get(job.checkRunId());
            if (!exactJobCheck(run, job, check == null ? null : check.check())
                    || jobsByCheck.put(job.checkRunId(), job) != null) {
                return null;
            }
            List<StaticJob> matching = workflow.jobs().values().stream()
                    .filter(candidate -> candidate.matches(job.name()))
                    .toList();
            if (matching.size() != 1) {
                return null;
            }
            StaticJob definition = matching.getFirst();
            List<RuntimeJob> values = jobsByKey.computeIfAbsent(
                    definition.key(), ignored -> new ArrayList<>());
            if (!definition.matrix() && !values.isEmpty()) {
                return null;
            }
            values.add(new RuntimeJob(job, check));
        }
        if (!jobsByKey.keySet().equals(workflow.jobs().keySet())) {
            return null;
        }
        for (PrCheckRunState check : headChecks) {
            GitHubMetadata metadata = check.githubMetadata();
            Long checkRunId = check.githubId();
            ActionsJobIdentity identity = metadata == null
                    ? null : actionsJobIdentity(metadata.detailsUrl());
            if (identity != null && identity.runId() == run.runId()
                    && (checkRunId == null || !jobsByCheck.containsKey(checkRunId))) {
                return null;
            }
        }

        List<RuntimeJob> aggregateJobs = jobsByKey.get(
                workflow.aggregate().key());
        if (aggregateJobs == null || aggregateJobs.size() != 1) {
            return null;
        }
        RuntimeJob aggregate = aggregateJobs.getFirst();
        if (failed.check().githubId() == null
                || aggregate.job().checkRunId() != failed.check().githubId()
                || aggregate.job().conclusion() == null
                || !"FAILURE".equals(normalize(aggregate.job().conclusion()))
                || jobState(aggregate.job()) != FAILED
                || !aggregateFailedOnlyAtDeclaredStep(
                        workflow.aggregate(), aggregate.job())
                || !Objects.equals(
                        externalCheckId(failed.check(), failed.index()),
                        head.externalId())) {
            return null;
        }

        List<AggregateDependency> dependencies = new ArrayList<>();
        boolean failedDependency = false;
        for (String dependencyKey : workflow.aggregate().needs()) {
            List<RuntimeJob> matches = jobsByKey.get(dependencyKey);
            if (matches == null || matches.isEmpty()) {
                return null;
            }
            for (RuntimeJob match : matches) {
                RemoteCiPolicy.CheckState state = jobState(match.job());
                if (state == null || state == PENDING || state == QUEUED) {
                    return null;
                }
                failedDependency |= state == FAILED;
                dependencies.add(new AggregateDependency(
                        dependencyKey,
                        externalCheckId(
                                match.check().check(), match.check().index()),
                        state));
            }
        }
        if (!failedDependency) {
            return null;
        }
        return new AggregateEvidence(
                blob.sha(), head.profile().workflowPath(), run.runId(),
                run.runAttempt(), aggregate.job().jobId(),
                workflow.aggregate().key(), dependencies);
    }

    private static CheckEvidence withAggregate(
            CheckEvidence evidence, AggregateEvidence aggregate)
    {
        return new CheckEvidence(
                evidence.externalId(), evidence.profile(), evidence.checkSuiteId(),
                evidence.workflowCheckSuiteId(), evidence.workflowRunId(),
                evidence.workflowRunAttempt(), evidence.checkTestedSha(),
                evidence.workflowTestedSha(), evidence.workflowEvent(),
                evidence.state(), true, evidence.failureFingerprints(),
                evidence.pullRequestAssociation(), aggregate);
    }

    static StaticWorkflow staticWorkflow(String source, String checkName)
    {
        if (!hasText(source) || !hasText(checkName)) {
            return null;
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(40);
            options.setCodePointLimit(1_000_000);
            Object loaded = new Yaml(new SafeConstructor(options)).load(source);
            if (!(loaded instanceof Map<?, ?> root)
                    || !safeRootDefaults(root.get("defaults"))
                    || !(root.get("jobs") instanceof Map<?, ?> jobsNode)
                    || jobsNode.isEmpty()) {
                return null;
            }
            Map<String, StaticJob> jobs = new LinkedHashMap<>();
            Set<String> names = new HashSet<>();
            for (Map.Entry<?, ?> entry : jobsNode.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || !JOB_KEY.matcher(key).matches()
                        || !(entry.getValue() instanceof Map<?, ?> jobNode)) {
                    return null;
                }
                StaticJob job = staticJob(key, jobNode);
                if (job == null || !names.add(job.name())) {
                    return null;
                }
                jobs.put(key, job);
            }
            List<StaticJob> candidates = jobs.values().stream()
                    .filter(job -> !job.matrix() && job.matches(checkName))
                    .toList();
            if (candidates.size() != 1) {
                return null;
            }
            StaticJob aggregate = candidates.getFirst();
            Map<?, ?> aggregateNode = (Map<?, ?>) jobsNode.get(aggregate.key());
            Set<String> keys = stringKeys(aggregateNode);
            if (keys == null || !AGGREGATE_JOB_KEYS.containsAll(keys)
                    || !always(aggregateNode.get("if"))) {
                return null;
            }
            List<String> needs = literalNeeds(aggregateNode.get("needs"));
            if (needs == null || needs.contains(aggregate.key())
                    || needs.stream().anyMatch(key -> !jobs.containsKey(key))) {
                return null;
            }
            Object stepsNode = aggregateNode.get("steps");
            if (!(stepsNode instanceof List<?> steps) || steps.size() != 1
                    || !(steps.getFirst() instanceof Map<?, ?> step)) {
                return null;
            }
            Set<String> stepKeys = stringKeys(step);
            if (stepKeys == null || !AGGREGATE_STEP_KEYS.containsAll(stepKeys)
                    || !(step.get("name") instanceof String stepName)
                    || stepName.isBlank() || stepName.contains("${{")
                    || !(step.get("run") instanceof String run)
                    || !resultGate(run, needs)) {
                return null;
            }
            return new StaticWorkflow(
                    Map.copyOf(jobs), new StaticAggregate(
                            aggregate.key(), needs, stepName));
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private static StaticJob staticJob(String key, Map<?, ?> node)
    {
        if (node.containsKey("uses")) {
            return null;
        }
        boolean matrix = false;
        Object strategyNode = node.get("strategy");
        if (strategyNode != null) {
            if (!(strategyNode instanceof Map<?, ?> strategy)) {
                return null;
            }
            matrix = strategy.containsKey("matrix");
            if (matrix) {
                Set<String> strategyKeys = stringKeys(strategy);
                if (strategyKeys == null
                        || !ImmutableSet.of("matrix", "fail-fast", "max-parallel")
                                .containsAll(strategyKeys)
                        || strategy.get("matrix") == null) {
                    return null;
                }
            }
        }
        Object nameNode = node.get("name");
        if (nameNode == null) {
            return matrix ? null : new StaticJob(key, key, false, key, "");
        }
        if (!(nameNode instanceof String name) || name.isBlank()) {
            return null;
        }
        Matcher template = MATRIX_NAME.matcher(name);
        if (matrix) {
            if (!template.matches()
                    || template.group(1).isBlank() && template.group(3).isBlank()) {
                return null;
            }
            return new StaticJob(
                    key, name, true, template.group(1), template.group(3));
        }
        if (name.contains("${{") || name.contains("}}")) {
            return null;
        }
        return new StaticJob(key, name, false, name, "");
    }

    private static Set<String> stringKeys(Map<?, ?> values)
    {
        Set<String> keys = new HashSet<>();
        for (Object key : values.keySet()) {
            if (!(key instanceof String text)) {
                return null;
            }
            keys.add(text);
        }
        return keys;
    }

    private static boolean safeRootDefaults(Object value)
    {
        if (value == null) {
            return true;
        }
        if (!(value instanceof Map<?, ?> defaults)
                || !ImmutableSet.of("run").equals(stringKeys(defaults))
                || !(defaults.get("run") instanceof Map<?, ?> run)
                || !ImmutableSet.of("shell").equals(stringKeys(run))) {
            return false;
        }
        return "bash --noprofile --norc -euo pipefail {0}".equals(
                run.get("shell"));
    }

    private static List<String> literalNeeds(Object value)
    {
        List<?> values;
        if (value instanceof String text) {
            values = List.of(text);
        }
        else if (value instanceof List<?> list) {
            values = list;
        }
        else {
            return null;
        }
        List<String> needs = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (Object candidate : values) {
            if (!(candidate instanceof String key)
                    || !JOB_KEY.matcher(key).matches() || !unique.add(key)) {
                return null;
            }
            needs.add(key);
        }
        return needs.isEmpty() ? null : List.copyOf(needs);
    }

    private static boolean always(Object value)
    {
        if (!(value instanceof String condition)) {
            return false;
        }
        String normalized = condition.strip();
        if (normalized.startsWith("${{") && normalized.endsWith("}}")) {
            normalized = normalized.substring(3, normalized.length() - 2);
        }
        return "always()".equals(normalized.replaceAll("\\s+", ""));
    }

    private static boolean resultGate(String run, List<String> needs)
    {
        List<String> references = new ArrayList<>();
        for (String line : run.lines().toList()) {
            String command = line.strip();
            if (command.isEmpty() || command.startsWith("#")) {
                continue;
            }
            Matcher matcher = RESULT_GATE.matcher(command);
            if (!matcher.matches()) {
                return false;
            }
            references.add(matcher.group(2));
        }
        return references.size() == needs.size()
                && new HashSet<>(references).size() == references.size()
                && new HashSet<>(references).equals(new HashSet<>(needs));
    }

    private static boolean validBlob(FileBlob blob)
    {
        return blob != null && blob.sha() != null
                && blob.sha().matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
                && hasText(blob.text());
    }

    static boolean sameCompletedRun(
            ActionsWorkflowRun left, ActionsWorkflowRun right)
    {
        if (left == null || right == null || left.runId() < 1
                || left.runId() != right.runId()
                || left.runAttempt() == null || left.runAttempt() < 1
                || !Objects.equals(left.runAttempt(), right.runAttempt())
                || left.workflowId() == null || left.workflowId() < 1
                || !Objects.equals(left.workflowId(), right.workflowId())
                || !hasText(workflowFile(left.workflowPath()))
                || !Objects.equals(left.workflowPath(), right.workflowPath())
                || !hasText(left.headSha())
                || !Objects.equals(left.headSha(), right.headSha())
                || left.checkSuiteId() == null || left.checkSuiteId() < 1
                || !Objects.equals(left.checkSuiteId(), right.checkSuiteId())
                || !hasText(left.event())
                || !Objects.equals(left.event(), right.event())
                || !"COMPLETED".equals(normalize(left.status()))
                || !"COMPLETED".equals(normalize(right.status()))) {
            return false;
        }
        String conclusion = normalize(left.conclusion());
        return ImmutableSet.of(
                "SUCCESS", "FAILURE", "CANCELLED", "CANCELED",
                "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED", "STALE",
                "NEUTRAL", "SKIPPED").contains(conclusion)
                && conclusion.equals(normalize(right.conclusion()));
    }

    static boolean sameCompletedFailedRun(
            ActionsWorkflowRun left, ActionsWorkflowRun right)
    {
        return sameCompletedRun(left, right)
                && "FAILURE".equals(normalize(left.conclusion()));
    }

    static boolean validJobSet(
            ActionsWorkflowRun run, ActionsWorkflowJobSetEvidence jobs)
    {
        return jobs != null && jobs.complete()
                && jobs.runId() == run.runId()
                && jobs.runAttempt() == run.runAttempt()
                && jobs.observedJobCount() > 0
                && jobs.observedJobCount() == jobs.expectedJobCount()
                && jobs.jobs().size() == jobs.observedJobCount();
    }

    static boolean exactJobCheck(
            ActionsWorkflowRun run,
            ActionsWorkflowJob job,
            PrCheckRunState check)
    {
        if (job == null || check == null || check.githubId() == null
                || job.jobId() < 1 || job.checkRunId() != check.githubId()
                || job.runId() != run.runId()
                || job.runAttempt() != run.runAttempt()
                || !Objects.equals(job.headSha(), run.headSha())
                || !hasText(job.name())) {
            return false;
        }
        GitHubMetadata metadata = check.githubMetadata();
        ActionsJobIdentity identity = metadata == null
                ? null : actionsJobIdentity(metadata.detailsUrl());
        return identity != null && identity.runId() == run.runId()
                && identity.jobId() == job.jobId()
                && Objects.equals(metadata.testedSha(), run.headSha())
                && Objects.equals(metadata.checkSuiteId(), run.checkSuiteId())
                && Objects.equals(check.name(), job.name())
                && normalize(check.status()).equals(normalize(job.status()))
                && normalize(check.conclusion()).equals(
                        normalize(job.conclusion()))
                && jobState(job) != null
                && jobState(job) == checkState(
                        check.status(), check.conclusion());
    }

    private static RemoteCiPolicy.CheckState jobState(ActionsWorkflowJob job)
    {
        if (job == null || !"COMPLETED".equals(normalize(job.status()))) {
            return null;
        }
        return switch (normalize(job.conclusion())) {
            case "SUCCESS" -> PASSED;
            case "SKIPPED" -> SKIPPED;
            case "FAILURE", "TIMED_OUT", "STARTUP_FAILURE",
                    "ACTION_REQUIRED", "STALE" -> FAILED;
            case "CANCELLED", "CANCELED" -> CANCELED;
            case "NEUTRAL" -> NEUTRAL;
            default -> null;
        };
    }

    static boolean aggregateFailedOnlyAtDeclaredStep(
            StaticAggregate aggregate, ActionsWorkflowJob job)
    {
        if (aggregate == null || job == null || job.steps().isEmpty()) {
            return false;
        }
        int aggregateSteps = 0;
        for (var step : job.steps()) {
            if (!"COMPLETED".equals(normalize(step.status()))) {
                return false;
            }
            String conclusion = normalize(step.conclusion());
            if (aggregate.stepName().equals(step.name())) {
                aggregateSteps++;
                if (!"FAILURE".equals(conclusion)) {
                    return false;
                }
            }
            else if (!"SUCCESS".equals(conclusion)
                    && !"SKIPPED".equals(conclusion)) {
                return false;
            }
        }
        return aggregateSteps == 1;
    }

    static Set<String> annotationFingerprints(
            List<CheckRunAnnotation> annotations)
    {
        requireNonNull(annotations, "annotations is null");
        Set<String> fingerprints = new TreeSet<>();
        for (CheckRunAnnotation annotation : annotations) {
            requireNonNull(annotation, "annotation is null");
            String message = normalizeFailureText(annotation.message());
            String title = normalizeFailureText(annotation.title());
            if (GENERIC_FAILURE.matcher(message).matches()
                    || message.isBlank() && title.isBlank()) {
                continue;
            }
            String evidence = String.join("|",
                    normalizeFailureText(annotation.path()),
                    annotation.startLine() == null
                            ? "" : annotation.startLine().toString(),
                    title, message);
            fingerprints.add(digest(evidence));
        }
        return ImmutableSet.copyOf(fingerprints);
    }

    private static String normalizeFailureText(String value)
    {
        if (value == null) {
            return "";
        }
        String normalized = ANSI.matcher(value).replaceAll("");
        normalized = ISO_TIMESTAMP.matcher(normalized).replaceAll("<timestamp>");
        normalized = WORKSPACE_PATH.matcher(normalized)
                .replaceAll("<workspace>/");
        normalized = ACTIONS_ID.matcher(normalized).replaceAll("$1<id>");
        normalized = UUID.matcher(normalized).replaceAll("<uuid>");
        normalized = SHA.matcher(normalized).replaceAll("<sha>");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private static boolean possibleProfileMatch(
            PrCheckRunState head, PrCheckRunState base)
    {
        GitHubMetadata left = head.githubMetadata();
        GitHubMetadata right = base.githubMetadata();
        return left != null && right != null
                && Objects.equals(head.name(), base.name())
                && Objects.equals(left.appId(), right.appId())
                && Objects.equals(left.appSlug(), right.appSlug());
    }

    private static boolean profileComplete(CheckProfile profile)
    {
        return profile != null && profile.appId() != null
                && hasText(profile.appSlug()) && profile.workflowId() != null
                && hasText(profile.workflowPath())
                && hasText(profile.checkName());
    }

    private static PullRequestAssociation matchingAssociation(
            List<PullRequestSubject> associations,
            int pullRequestNumber,
            String headSha,
            String baseSha)
    {
        if (associations == null) {
            return null;
        }
        List<PullRequestSubject> matching = associations.stream()
                .filter(subject -> subject.pullRequestNumber()
                        == pullRequestNumber)
                .filter(subject -> Objects.equals(subject.headSha(), headSha)
                        && Objects.equals(subject.baseSha(), baseSha))
                .toList();
        if (matching.size() != 1) {
            return null;
        }
        PullRequestSubject subject = matching.getFirst();
        return new PullRequestAssociation(
                subject.pullRequestNumber(), subject.headSha(),
                subject.baseSha());
    }

    private static String workflowFile(String path)
    {
        if (path == null || path.contains("..") || path.contains("\\")) {
            return null;
        }
        Matcher matcher = WORKFLOW_PATH.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static Long actionsRunId(String url)
    {
        if (url == null) {
            return null;
        }
        Matcher matcher = ACTIONS_RUN.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ActionsJobIdentity actionsJobIdentity(String url)
    {
        if (url == null) {
            return null;
        }
        Matcher matcher = ACTIONS_JOB.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new ActionsJobIdentity(
                    Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)));
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String externalCheckId(PrCheckRunState check, int index)
    {
        return check.githubId() == null
                ? "github-check:" + digest(checkName(check) + ":" + index)
                : "github-check:" + check.githubId();
    }

    private static String checkName(PrCheckRunState check)
    {
        return check.name() == null ? "unnamed check" : check.name();
    }

    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    private record IndexedCheck(int index, PrCheckRunState check) {}

    record StaticWorkflow(
            Map<String, StaticJob> jobs, StaticAggregate aggregate) {}

    record StaticAggregate(String key, List<String> needs, String stepName) {}

    record StaticJob(
            String key,
            String name,
            boolean matrix,
            String prefix,
            String suffix)
    {
        boolean matches(String runtimeName)
        {
            if (runtimeName == null) {
                return false;
            }
            if (!matrix) {
                return name.equals(runtimeName);
            }
            return runtimeName.startsWith(prefix)
                    && runtimeName.endsWith(suffix)
                    && runtimeName.length() > prefix.length() + suffix.length();
        }
    }

    private record EvidenceResult(
            CheckEvidence evidence, boolean annotationsComplete) {}

    private record WorkflowBlobKey(String path, String testedSha) {}

    private record RunAttemptKey(long runId, int runAttempt) {}

    private record ExactRunEvidence(
            ActionsWorkflowRun run,
            ActionsWorkflowJobSetEvidence jobs) {}

    private record RuntimeJob(ActionsWorkflowJob job, IndexedCheck check) {}

    private record ActionsJobIdentity(long runId, long jobId) {}

    static List<RemoteObservationOperationHandler.FeedbackFact> feedbackFacts(
            List<PrReviewThreadMessage> comments,
            List<ReviewThreadMeta> threads,
            List<PrTimelineEvent> timeline,
            List<PrTimelineEvent> issueComments,
            String viewer,
            ObjectMapper json)
    {
        requireNonNull(comments, "comments is null");
        requireNonNull(threads, "threads is null");
        requireNonNull(timeline, "timeline is null");
        requireNonNull(issueComments, "issueComments is null");
        requireText(viewer, "viewer");
        requireNonNull(json, "json is null");

        Map<Long, ReviewThreadMeta> threadByRoot = new HashMap<>();
        for (ReviewThreadMeta thread : threads) {
            threadByRoot.put(thread.rootCommentDatabaseId(), thread);
        }
        List<RemoteObservationOperationHandler.FeedbackFact> facts =
                new ArrayList<>();
        for (PrReviewThreadMessage comment : comments) {
            long root = comment.inReplyTo() == null
                    ? comment.githubId() : comment.inReplyTo();
            ReviewThreadMeta thread = threadByRoot.get(root);
            if (thread != null && thread.resolved()) {
                continue;
            }
            String threadId = thread == null
                    ? firstText(comment.graphqlNodeId(), "review-thread:" + root)
                    : thread.graphqlNodeId();
            if (comment.body() == null || comment.body().isBlank()) {
                continue;
            }
            facts.add(fact(
                    RemoteObservationOperationHandler.FeedbackKind.INLINE_COMMENT,
                    "inline-comment:" + comment.githubId(), comment.author(),
                    sameLogin(comment.author(), viewer), threadId,
                    Long.toString(root),
                    comment.reviewId() == null
                            ? null : Long.toString(comment.reviewId()),
                    null, comment.body(), null, write(json, comment)));
        }
        for (ReviewThreadMeta thread : threads) {
            facts.add(fact(
                    thread.resolved()
                            ? RemoteObservationOperationHandler.FeedbackKind.THREAD_RESOLVED
                            : RemoteObservationOperationHandler.FeedbackKind.THREAD_REOPENED,
                    "thread-state:" + thread.graphqlNodeId(), thread.resolvedBy(),
                    false, thread.graphqlNodeId(), null, null, null, null, null,
                    write(json, thread)));
        }
        for (PrTimelineEvent event : issueComments) {
            if (!"COMMENTED".equals(normalize(event.event()))
                    || event.githubId() == null
                    || event.body() == null || event.body().isBlank()) {
                continue;
            }
            facts.add(fact(
                    RemoteObservationOperationHandler.FeedbackKind.TOP_LEVEL_COMMENT,
                    "top-level-comment:" + event.githubId(), event.actor(),
                    sameLogin(event.actor(), viewer), null,
                    Long.toString(event.githubId()), null, null, event.body(), null,
                    write(json, event)));
        }
        for (PrTimelineEvent event : timeline) {
            String kind = normalize(event.event());
            if ("REVIEWED".equals(kind)) {
                String reviewId = event.reviewId() == null
                        ? event.githubId() == null ? null
                                : Long.toString(event.githubId())
                        : Long.toString(event.reviewId());
                RemoteObservationOperationHandler.FeedbackVerdict verdict =
                        feedbackVerdict(event.state());
                if (reviewId == null || verdict == null) {
                    continue;
                }
                boolean own = sameLogin(event.actor(), viewer);
                if (event.body() != null && !event.body().isBlank()) {
                    facts.add(fact(
                            RemoteObservationOperationHandler.FeedbackKind.REVIEW_BODY,
                            "review-body:" + reviewId, event.actor(), own,
                            null, null, reviewId, null, event.body(), null,
                            write(json, event)));
                }
                facts.add(fact(
                        RemoteObservationOperationHandler.FeedbackKind.REVIEW_VERDICT,
                        "review-verdict:" + reviewId, event.actor(), own,
                        null, null, reviewId, null, null, verdict,
                        write(json, event)));
            }
            else if ("REVIEW_REQUESTED".equals(kind)
                    && event.requestedReviewer() != null
                    && !event.requestedReviewer().isBlank()) {
                String eventId = event.githubId() == null
                        ? digest(write(json, event))
                        : Long.toString(event.githubId());
                facts.add(fact(
                        RemoteObservationOperationHandler.FeedbackKind.REQUESTED_REVIEW,
                        "requested-review:" + eventId, event.actor(),
                        sameLogin(event.actor(), viewer), null, null, null,
                        event.requestedReviewer(), null, null, write(json, event)));
            }
        }
        return facts.stream()
                .sorted(Comparator.comparing(
                                RemoteObservationOperationHandler.FeedbackFact::externalKey)
                        .thenComparing(fact -> fact.kind().name()))
                .toList();
    }

    private static RemoteObservationOperationHandler.FeedbackFact fact(
            RemoteObservationOperationHandler.FeedbackKind kind,
            String externalKey,
            String actor,
            boolean ownAction,
            String threadId,
            String commentId,
            String reviewId,
            String requestedReviewer,
            String body,
            RemoteObservationOperationHandler.FeedbackVerdict verdict,
            String rawEvidence)
    {
        return new RemoteObservationOperationHandler.FeedbackFact(
                kind, externalKey, actor, ownAction, threadId, commentId,
                reviewId, requestedReviewer, body, verdict, rawEvidence);
    }

    private static RemoteObservationOperationHandler.FeedbackVerdict
            feedbackVerdict(String state)
    {
        return switch (normalize(state)) {
            case "APPROVED" ->
                    RemoteObservationOperationHandler.FeedbackVerdict.APPROVED;
            case "CHANGES_REQUESTED" ->
                    RemoteObservationOperationHandler.FeedbackVerdict.CHANGES_REQUESTED;
            case "COMMENTED" ->
                    RemoteObservationOperationHandler.FeedbackVerdict.COMMENTED;
            case "DISMISSED" ->
                    RemoteObservationOperationHandler.FeedbackVerdict.DISMISSED;
            default -> null;
        };
    }

    private static boolean sameLogin(String left, String right)
    {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String firstText(String first, String fallback)
    {
        return first == null || first.isBlank() ? fallback : first;
    }

    static List<PrReviewState> effectiveReviews(List<PrReviewState> reviews)
    {
        requireNonNull(reviews, "reviews is null");
        Map<String, IndexedReview> latest = new HashMap<>();
        for (int index = 0; index < reviews.size(); index++) {
            PrReviewState review = requireNonNull(reviews.get(index), "review is null");
            requireText(review.login(), "review login");
            String login = review.login().toLowerCase(Locale.ROOT);
            IndexedReview candidate = new IndexedReview(review, index);
            latest.merge(login, candidate, GitHubRemoteObserver::later);
        }
        return latest.values().stream()
                .sorted(Comparator.comparing(value ->
                        value.review().login().toLowerCase(Locale.ROOT)))
                .map(IndexedReview::review)
                .toList();
    }

    static List<RemoteCiPolicy.Check> normalizeChecks(
            List<PrCheckRunState> checks, ObjectMapper json)
    {
        requireNonNull(checks, "checks is null");
        requireNonNull(json, "json is null");
        List<RemoteCiPolicy.Check> normalized = new ArrayList<>();
        for (int index = 0; index < checks.size(); index++) {
            PrCheckRunState check = requireNonNull(checks.get(index), "check is null");
            requireText(check.name(), "check name");
            normalized.add(new RemoteCiPolicy.Check(
                    "CHECK_RUN",
                    externalCheckId(check, index),
                    check.name(),
                    checkState(check.status(), check.conclusion()),
                    check.status(),
                    check.conclusion(),
                    null,
                    null,
                    write(json, check)));
        }
        return List.copyOf(normalized);
    }

    static RemoteCiPolicy.CheckState checkState(String status, String conclusion)
    {
        String normalizedStatus = normalize(status);
        if ("QUEUED".equals(normalizedStatus)
                || "WAITING".equals(normalizedStatus)
                || "REQUESTED".equals(normalizedStatus)) {
            return QUEUED;
        }
        if ("IN_PROGRESS".equals(normalizedStatus)
                || "PENDING".equals(normalizedStatus)) {
            return PENDING;
        }
        if (!"COMPLETED".equals(normalizedStatus)) {
            return FAILED;
        }
        return switch (normalize(conclusion)) {
            case "SUCCESS" -> PASSED;
            case "NEUTRAL" -> NEUTRAL;
            case "SKIPPED" -> SKIPPED;
            case "CANCELLED", "CANCELED" -> CANCELED;
            default -> FAILED;
        };
    }

    private static boolean infrastructureFailure(
            RemoteCiPolicy.CheckState state, String conclusion)
    {
        return state == CANCELED || ImmutableSet.of(
                "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED", "STALE")
                .contains(normalize(conclusion));
    }

    static RemoteObservationOperationHandler.PrState prState(PrRawDetail detail)
    {
        if (detail.merged()) {
            return RemoteObservationOperationHandler.PrState.MERGED;
        }
        if ("closed".equalsIgnoreCase(detail.state())) {
            return RemoteObservationOperationHandler.PrState.CLOSED;
        }
        return detail.draft()
                ? RemoteObservationOperationHandler.PrState.DRAFT
                : RemoteObservationOperationHandler.PrState.OPEN;
    }

    static RemoteObservationOperationHandler.Mergeability mergeability(
            PrRawDetail detail)
    {
        String state = normalize(detail.mergeableState());
        if (detail.mergeable() == null || "UNKNOWN".equals(state)) {
            return RemoteObservationOperationHandler.Mergeability.UNKNOWN;
        }
        if ("DIRTY".equals(state)) {
            return RemoteObservationOperationHandler.Mergeability.CONFLICTING;
        }
        if (Boolean.TRUE.equals(detail.mergeable())
                && ImmutableSet.of("CLEAN", "UNSTABLE", "HAS_HOOKS").contains(state)) {
            return RemoteObservationOperationHandler.Mergeability.MERGEABLE;
        }
        return RemoteObservationOperationHandler.Mergeability.BLOCKED;
    }

    static RemoteObservationOperationHandler.MergeQueueState mergeQueueState(
            PrRawDetail detail, MergeQueueInfo queue)
    {
        if (detail.merged()) {
            return RemoteObservationOperationHandler.MergeQueueState.MERGED;
        }
        String state = normalize(queue.entryState());
        if (state.isEmpty()) {
            return RemoteObservationOperationHandler.MergeQueueState.NONE;
        }
        if ("MERGED".equals(state)) {
            return RemoteObservationOperationHandler.MergeQueueState.MERGED;
        }
        if (ImmutableSet.of("DEQUEUED", "REMOVED", "CANCELLED", "CANCELED")
                .contains(state)) {
            return RemoteObservationOperationHandler.MergeQueueState.DEQUEUED;
        }
        return RemoteObservationOperationHandler.MergeQueueState.QUEUED;
    }

    static RemoteObservationOperationHandler.MergeQueueCapability
            mergeQueueCapability(MergeQueueInfo queue)
    {
        requireNonNull(queue, "queue is null");
        if ((!queue.queueConfigured() && queue.entryState() != null)
                || (queue.entryState() != null
                    && queue.entryState().isBlank())) {
            throw new IllegalStateException(
                    "GitHub merge queue observation is inconsistent");
        }
        return queue.queueConfigured()
                ? RemoteObservationOperationHandler.MergeQueueCapability.SUPPORTED
                : RemoteObservationOperationHandler.MergeQueueCapability.UNSUPPORTED;
    }

    private static IndexedReview later(IndexedReview left, IndexedReview right)
    {
        Instant leftAt = left.review().submittedAt();
        Instant rightAt = right.review().submittedAt();
        if (leftAt == null && rightAt == null) {
            return left.index() < right.index() ? right : left;
        }
        if (leftAt == null) {
            return right;
        }
        if (rightAt == null) {
            return left;
        }
        int compared = leftAt.compareTo(rightAt);
        return compared < 0 || (compared == 0 && left.index() < right.index())
                ? right : left;
    }

    private static void requireActive(ExecutionContext execution)
            throws ExecutionPorts.OperationCanceledException
    {
        if (execution.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Remote observation was canceled");
        }
    }

    private String write(Object value)
    {
        return write(json, value);
    }

    private static String write(ObjectMapper json, Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Serializing GitHub evidence failed", e);
        }
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record IndexedReview(PrReviewState review, int index) {}
}
