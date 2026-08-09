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
package com.bytequay.app.developmentflow.stage;

import com.google.common.collect.ImmutableSet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Typed, fail-closed evidence comparing failed PR checks with the exact base. */
public record RemoteCiProvenance(
        int schemaVersion,
        String repositoryId,
        int pullRequestNumber,
        String observedHeadSha,
        String observedBaseSha,
        String observedMergeSha,
        boolean complete,
        List<String> incompleteReasons,
        List<CheckComparison> checks)
{
    public static final String ACTIONS_JOB_LOG_SOURCE = "ACTIONS_JOB_LOG_V1";
    public static final String MAVEN_COMPILER_PARSER = "MAVEN_COMPILER_V1";
    public static final int MAVEN_COMPILER_PARSER_VERSION = 1;

    public RemoteCiProvenance
    {
        if (schemaVersion < 3 || schemaVersion > 5) {
            throw new IllegalArgumentException(
                    "Unsupported Remote CI provenance schema");
        }
        requireNonNull(repositoryId, "repositoryId is null");
        requireNonNull(observedHeadSha, "observedHeadSha is null");
        requireNonNull(observedBaseSha, "observedBaseSha is null");
        incompleteReasons = List.copyOf(requireNonNull(
                incompleteReasons, "incompleteReasons is null"));
        checks = List.copyOf(requireNonNull(checks, "checks is null"));
        if (repositoryId.isBlank() || observedHeadSha.isBlank()
                || observedBaseSha.isBlank() || pullRequestNumber < 1) {
            throw new IllegalArgumentException(
                    "Remote CI provenance identity is invalid");
        }
        if (schemaVersion == 3 && checks.stream().anyMatch(
                comparison -> comparison.head().aggregateEvidence() != null
                        || (comparison.base() != null
                            && comparison.base().aggregateEvidence() != null))) {
            throw new IllegalArgumentException(
                    "Aggregate CI provenance requires schema v4 or later");
        }
        if (schemaVersion < 5 && checks.stream().anyMatch(
                comparison -> comparison.head().actionsJobLogEvidence() != null
                        || (comparison.base() != null
                            && comparison.base().actionsJobLogEvidence() != null))) {
            throw new IllegalArgumentException(
                    "Actions job-log CI provenance requires schema v5");
        }
    }

    public record CheckComparison(CheckEvidence head, CheckEvidence base)
    {
        public CheckComparison
        {
            requireNonNull(head, "head is null");
        }
    }

    public record CheckProfile(
            Long appId,
            String appSlug,
            Long workflowId,
            String workflowPath,
            String checkName) {}

    public record CheckEvidence(
            String externalId,
            CheckProfile profile,
            Long checkSuiteId,
            Long workflowCheckSuiteId,
            Long workflowRunId,
            Integer workflowRunAttempt,
            String checkTestedSha,
            String workflowTestedSha,
            String workflowEvent,
            RemoteCiPolicy.CheckState state,
            boolean complete,
            Set<String> failureFingerprints,
            PullRequestAssociation pullRequestAssociation,
            AggregateEvidence aggregateEvidence,
            ActionsJobLogEvidence actionsJobLogEvidence)
    {
        public CheckEvidence
        {
            failureFingerprints = ImmutableSet.copyOf(requireNonNull(
                    failureFingerprints, "failureFingerprints is null"));
        }

        /** Compatibility constructor for schema-v3/v4 evidence. */
        public CheckEvidence(
                String externalId,
                CheckProfile profile,
                Long checkSuiteId,
                Long workflowCheckSuiteId,
                Long workflowRunId,
                Integer workflowRunAttempt,
                String checkTestedSha,
                String workflowTestedSha,
                String workflowEvent,
                RemoteCiPolicy.CheckState state,
                boolean complete,
                Set<String> failureFingerprints,
                PullRequestAssociation pullRequestAssociation,
                AggregateEvidence aggregateEvidence)
        {
            this(externalId, profile, checkSuiteId, workflowCheckSuiteId,
                    workflowRunId, workflowRunAttempt, checkTestedSha,
                    workflowTestedSha, workflowEvent, state, complete,
                    failureFingerprints, pullRequestAssociation,
                    aggregateEvidence, null);
        }

        /** Compatibility constructor for concrete schema-v3 evidence. */
        public CheckEvidence(
                String externalId,
                CheckProfile profile,
                Long checkSuiteId,
                Long workflowCheckSuiteId,
                Long workflowRunId,
                Integer workflowRunAttempt,
                String checkTestedSha,
                String workflowTestedSha,
                String workflowEvent,
                RemoteCiPolicy.CheckState state,
                boolean complete,
                Set<String> failureFingerprints,
                PullRequestAssociation pullRequestAssociation)
        {
            this(externalId, profile, checkSuiteId, workflowCheckSuiteId,
                    workflowRunId, workflowRunAttempt, checkTestedSha,
                    workflowTestedSha, workflowEvent, state, complete,
                    failureFingerprints, pullRequestAssociation, null, null);
        }
    }

    /** Complete, versioned parser result for one exact GitHub Actions job log. */
    public record ActionsJobLogEvidence(
            String source,
            String parserId,
            int parserVersion,
            Long workflowRunId,
            Integer workflowRunAttempt,
            Long jobId,
            Long checkRunId,
            String testedSha,
            long rawByteCount,
            String rawSha256,
            boolean captureComplete,
            boolean parseComplete,
            List<CanonicalDiagnostic> diagnostics)
    {
        public ActionsJobLogEvidence
        {
            diagnostics = List.copyOf(requireNonNull(
                    diagnostics, "diagnostics is null"));
        }
    }

    /** Stable parser output; source line and column are deliberately absent. */
    public record CanonicalDiagnostic(
            String file,
            String kind,
            String code,
            String message,
            String symbol,
            String location) {}

    public static String canonicalFingerprint(CanonicalDiagnostic diagnostic)
    {
        requireNonNull(diagnostic, "diagnostic is null");
        String canonical = String.join("\u0000",
                text(diagnostic.file()), text(diagnostic.kind()),
                text(diagnostic.code()), text(diagnostic.message()),
                text(diagnostic.symbol()), text(diagnostic.location()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String text(String value)
    {
        return value == null ? "" : value;
    }

    public record AggregateEvidence(
            String workflowBlobSha,
            String workflowPath,
            Long workflowRunId,
            Integer workflowRunAttempt,
            Long aggregateJobId,
            String aggregateJobKey,
            List<AggregateDependency> dependencies)
    {
        public AggregateEvidence
        {
            dependencies = List.copyOf(requireNonNull(
                    dependencies, "dependencies is null"));
        }
    }

    public record AggregateDependency(
            String jobKey,
            String externalCheckId,
            RemoteCiPolicy.CheckState state) {}

    public record PullRequestAssociation(
            int pullRequestNumber, String headSha, String baseSha) {}
}
