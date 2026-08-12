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
package com.bytequay.app.repository;

import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.RepoRef;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** GitHub checks and Actions API operations. */
public interface GitHubActionsRepository {
    default List<PrCheckRunState> fetchPrCheckRuns(
            String pat, String owner, String repo, String sha) {
        throw new UnsupportedOperationException("fetchPrCheckRuns not implemented");
    }

    default List<PrCheckRunState> fetchPrCheckRunsStrict(
            String pat, String owner, String repo, String sha) {
        return fetchPrCheckRuns(pat, owner, repo, sha);
    }

    /** Re-runs failed jobs for workflow runs on the supplied head SHA. */
    default int rerunFailedChecks(String pat, RepoRef repo, String headSha) {
        throw new UnsupportedOperationException("rerunFailedChecks not implemented");
    }

    /**
     * Fetches the raw log text for an Actions check-run job. Maps to {@code GET
     * /repos/{owner}/{repo}/actions/jobs/{checkRunId}/logs}, which returns a 302 to a presigned
     * blob URL with the plain-text log. Returns {@link Optional#empty()} when the check isn't an
     * Actions job (external CIs use the Checks API but don't expose logs to GitHub) or when the log
     * has expired / isn't accessible with the supplied PAT.
     */
    default Optional<String> fetchCheckRunLog(String pat, RepoRef repo, long checkRunId) {
        return Optional.empty();
    }

    enum ActionsJobLogStatus {
        COMPLETE,
        UNAVAILABLE,
        INCOMPLETE
    }

    /** Bounded, strict capture of one exact GitHub Actions job log. */
    record ActionsJobLogCapture(
            long jobId,
            ActionsJobLogStatus status,
            String rawText,
            Long rawByteLength,
            String sha256Digest,
            String detail) {
        private static final String MALFORMED_NON_COMPLETE_CAPTURE =
                "non-complete Actions job log capture is malformed";

        public ActionsJobLogCapture {
            if (jobId < 1) {
                throw new IllegalArgumentException("jobId must be positive");
            }
            requireNonNull(status, "status is null");
            if (status == ActionsJobLogStatus.COMPLETE) {
                requireNonNull(rawText, "rawText is null");
                requireNonNull(rawByteLength, "rawByteLength is null");
                requireNonNull(sha256Digest, "sha256Digest is null");
                if (rawText.isEmpty()
                        || rawByteLength < 1
                        || !sha256Digest.matches("[0-9a-f]{64}")
                        || detail != null) {
                    throw new IllegalArgumentException(
                            "complete Actions job log capture is malformed");
                }
            }
            else if (rawText != null
                    || rawByteLength != null
                    || sha256Digest != null
                    || detail == null
                    || detail.isBlank()) {
                throw new IllegalArgumentException(MALFORMED_NON_COMPLETE_CAPTURE);
            }
        }

        public static ActionsJobLogCapture complete(
                long jobId, String rawText, long rawByteLength, String sha256Digest) {
            return new ActionsJobLogCapture(
                    jobId,
                    ActionsJobLogStatus.COMPLETE,
                    rawText,
                    rawByteLength,
                    sha256Digest,
                    null);
        }

        public static ActionsJobLogCapture unavailable(long jobId, String detail) {
            return new ActionsJobLogCapture(
                    jobId, ActionsJobLogStatus.UNAVAILABLE, null, null, null, detail);
        }

        public static ActionsJobLogCapture incomplete(long jobId, String detail) {
            return new ActionsJobLogCapture(
                    jobId, ActionsJobLogStatus.INCOMPLETE, null, null, null, detail);
        }
    }

    /**
     * Downloads one already-bound Actions job directly. Provider transport failures remain
     * exceptions so the caller can retry the read-only operation; unavailable or malformed evidence
     * is returned explicitly.
     */
    default ActionsJobLogCapture fetchActionsJobLogStrict(String pat, RepoRef repo, long jobId) {
        throw new UnsupportedOperationException("fetchActionsJobLogStrict not implemented");
    }

    /**
     * One entry from a check run's "Annotations" list. {@code title} is the workflow step that
     * emitted it ("Upload test results"), {@code message} the text underneath ("Expecting actual:
     * 1L to be less than: 1L"), and {@code path}/{@code startLine} the source location — null for
     * annotations GitHub attaches to the workflow file rather than to code.
     */
    record CheckRunAnnotation(String title, String message, String path, Integer startLine) {}

    /** Complete, paginated annotation capture for one exact check run. */
    record CheckRunAnnotationEvidence(
            List<CheckRunAnnotation> failureAnnotations,
            int observedAnnotationCount,
            int expectedAnnotationCount,
            boolean complete) {
        public CheckRunAnnotationEvidence {
            failureAnnotations =
                    List.copyOf(requireNonNull(failureAnnotations, "failureAnnotations is null"));
        }
    }

    /**
     * Fetches the failure annotations GitHub published for a check run ({@code GET
     * /repos/{owner}/{repo}/check-runs/{id}/annotations}), keeping only those that point at real
     * source. This is the only structured failure text an Actions-generated check run exposes:
     * {@code output.title} / {@code output.summary} are left null by Actions and only ever
     * populated by apps that drive the Checks API themselves (CodeQL, Codecov, Sonar). Empty for
     * the many jobs whose sole annotation is the contentless "Process completed with exit code 1."
     * — for those the failure text has to come from the log instead.
     */
    default List<CheckRunAnnotation> fetchCheckRunAnnotations(
            String pat, RepoRef repo, long checkRunId) {
        return List.of();
    }

    /**
     * Fetches every annotation page for an exact check run. Unlike the UI helper above, this
     * retains generic failure annotations so the Remote CI proof builder can reject generic-only
     * evidence instead of mistaking it for a specific failure fingerprint.
     */
    default CheckRunAnnotationEvidence fetchCheckRunAnnotationsStrict(
            String pat, RepoRef repo, long checkRunId, int expectedAnnotationCount) {
        List<CheckRunAnnotation> annotations = fetchCheckRunAnnotations(pat, repo, checkRunId);
        return new CheckRunAnnotationEvidence(
                annotations,
                annotations.size(),
                expectedAnnotationCount,
                expectedAnnotationCount == annotations.size());
    }

    /** Exact GitHub Actions workflow lineage for a check run's run id. */
    record ActionsWorkflowRun(
            long runId,
            Long workflowId,
            String workflowPath,
            String event,
            String headSha,
            Long checkSuiteId,
            Integer runAttempt,
            String status,
            String conclusion) {}

    /** One immutable step from an exact GitHub Actions run attempt. */
    record ActionsWorkflowJobStep(int number, String name, String status, String conclusion) {}

    /** One job proven to belong to an exact GitHub Actions run attempt. */
    record ActionsWorkflowJob(
            long jobId,
            long checkRunId,
            long runId,
            int runAttempt,
            String headSha,
            String name,
            String status,
            String conclusion,
            List<ActionsWorkflowJobStep> steps) {
        public ActionsWorkflowJob {
            steps = List.copyOf(requireNonNull(steps, "steps is null"));
        }
    }

    /** Complete, paginated job capture for one exact workflow run attempt. */
    record ActionsWorkflowJobSetEvidence(
            long runId,
            int runAttempt,
            List<ActionsWorkflowJob> jobs,
            int observedJobCount,
            int expectedJobCount,
            boolean complete) {
        public ActionsWorkflowJobSetEvidence {
            jobs = List.copyOf(requireNonNull(jobs, "jobs is null"));
        }
    }

    default Optional<ActionsWorkflowRun> fetchActionsWorkflowRun(
            String pat, RepoRef repo, long runId) {
        return Optional.empty();
    }

    /** Fetches one exact GitHub Actions run attempt without a latest-attempt fallback. */
    default ActionsWorkflowRun fetchActionsWorkflowRunAttemptStrict(
            String pat, RepoRef repo, long runId, int runAttempt) {
        throw new UnsupportedOperationException(
                "fetchActionsWorkflowRunAttemptStrict not implemented");
    }

    /** Fetches every job from one exact GitHub Actions run attempt. */
    default ActionsWorkflowJobSetEvidence fetchActionsWorkflowAttemptJobsStrict(
            String pat, RepoRef repo, long runId, int runAttempt) {
        throw new UnsupportedOperationException(
                "fetchActionsWorkflowAttemptJobsStrict not implemented");
    }
}
