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
package com.bytequay.app.service.harness;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Domain and wire records for the local-only CI autofix harness. */
public final class HarnessModels
{
    private static final Pattern BUCKET_LABEL = Pattern.compile(
            "(?:style|build|test|resource|infra|flake|unknown)(?::[a-z0-9_.-]+)?");
    private static final Pattern VERIFY_HINT = Pattern.compile(
            "(?:style|build|test|regen)(?::[A-Za-z0-9_.$#-]+)?");

    private HarnessModels() {}

    public enum WatchStatus
    {
        BOOTSTRAP,
        WATCHING,
        RUNNING,
        NEEDS_ATTENTION,
        HANDOFF,
        GREEN,
        STOPPED;

        public String wire()
        {
            return name().toLowerCase(Locale.ROOT);
        }

        public static WatchStatus from(String value)
        {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public enum CycleStatus
    {
        QUEUED,
        RUNNING,
        GREEN,
        HANDOFF,
        FAILED,
        NO_CHANGE,
        CANCELLED;

        public String wire()
        {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Phase
    {
        PROBE,
        PARSE,
        CLASSIFY,
        FIX,
        VERIFY,
        COMMIT,
        REBASE,
        DONE;

        public String wire()
        {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Phase from(String value)
        {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public enum Bucket
    {
        STYLE,
        BUILD,
        TEST,
        RESOURCE,
        INFRA,
        FLAKE,
        UNKNOWN;

        public String wire()
        {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Bucket from(String value)
        {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            }
            catch (RuntimeException ignored) {
                return UNKNOWN;
            }
        }
    }

    public enum FailureStatus
    {
        OBSERVED,
        DEFERRED,
        DIAGNOSING,
        PROPOSED,
        FIXED,
        VERIFIED,
        ESCALATED,
        RESOLVED,
        FAILED;

        public String wire()
        {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Watch(
            String id,
            String workspaceId,
            String owner,
            String repo,
            int prNumber,
            String localPrId,
            String localPath,
            String branch,
            String title,
            WatchStatus status,
            String headSha,
            String bootstrapStatus,
            String bootstrapProfileJson,
            long budgetMilliUsd,
            long spentMilliUsd,
            String handoffJson,
            long createdAtMs,
            long updatedAtMs,
            Long lastPolledAtMs,
            Long stoppedAtMs,
            /** The run's one agent session, opened by the picks and resumed every round. */
            String agentSessionId) {}

    public record Cycle(
            String id,
            String watchId,
            int ordinal,
            String triggerKind,
            String steeringText,
            CycleStatus status,
            Phase phase,
            String headSha,
            String runRef,
            long costMilliUsd,
            String backupRef,
            String originalHead,
            String netNeutralProofJson,
            String runStatusTail,
            long startedAtMs,
            long updatedAtMs,
            Long finishedAtMs,
            String errorMessage) {}

    public record Failure(
            String id,
            String cycleId,
            String runId,
            Long checkRunId,
            String jobName,
            String module,
            String testClass,
            String testMethod,
            String signature,
            String logExcerpt,
            String bucketLabel,
            String ruleId,
            FailureStatus status,
            String targetSubject,
            String diagnosisJson,
            String fixJson,
            String verificationJson,
            long createdAtMs,
            long updatedAtMs)
    {
        public Failure
        {
            bucketLabel = normalizeBucketLabel(bucketLabel);
        }

        @JsonIgnore
        public Bucket bucket()
        {
            return baseBucket(bucketLabel);
        }
    }

    public record Event(
            long id,
            String watchId,
            String cycleId,
            Phase phase,
            String kind,
            String message,
            String detailJson,
            long createdAtMs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BootstrapProfile(
            String forge,
            Set<String> ecosystems,
            List<String> workflowFiles,
            Map<String, List<String>> verifySteps,
            Set<String> aggregatorJobs,
            Set<String> infraJobs,
            Map<String, String> modules,
            Map<String, String> runtimeMetadata,
            Map<String, String> verificationEnvironment,
            List<String> warnings)
    {
        public static BootstrapProfile empty()
        {
            return new BootstrapProfile("github-actions", Set.of(), List.of(), Map.of(),
                    Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Diagnosis(
            String rootCause,
            String culpritCommit,
            String targetSubject,
            List<Edit> edits,
            String signaturePattern,
            @JsonProperty("bucket") String bucketLabel,
            String binding,
            List<String> verifyHint,
            double confidence,
            boolean needsHuman,
            String rationale)
    {
        public Diagnosis
        {
            bucketLabel = normalizeBucketLabel(bucketLabel);
        }

        @JsonIgnore
        public Bucket bucket()
        {
            return baseBucket(bucketLabel);
        }
    }

    public record Edit(String path, String find, String replace) {}

    public record FixResult(
            List<String> filesChanged,
            String targetSubject,
            List<String> verifyCommands,
            String source) {}

    public record VerificationResult(
            boolean passed,
            boolean reproducible,
            List<CommandResult> commands,
            String reason) {}

    public record VerifiedFix(
            FixResult fix,
            VerificationResult verification) {}

    public record CommandResult(
            String command,
            int exitCode,
            boolean timedOut,
            String outputTail) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitSafetyProof(
            String beforeHead,
            String afterHead,
            String beforeTree,
            String afterTree,
            boolean emptyTreeDiff,
            boolean rangeEquivalent,
            boolean remoteUndiverged,
            String detail) {}

    public record HandoffDto(
            String reason,
            String failureId,
            String command,
            String detail) {}

    public record BudgetDto(
            long limitMilliUsd,
            long spentMilliUsd,
            long cycleMilliUsd,
            long remainingMilliUsd) {}

    public record PhaseStateDto(String phase, String status) {}

    public record CycleDto(
            String id,
            int ordinal,
            String triggerKind,
            String steeringText,
            String status,
            String phase,
            String headSha,
            long costMilliUsd,
            String backupRef,
            GitSafetyProof netNeutralProof,
            String runStatusTail,
            long startedAtMs,
            Long finishedAtMs,
            List<PhaseStateDto> phaseStates) {}

    public record EventDto(
            long id,
            String cycleId,
            String phase,
            String kind,
            String message,
            String detailJson,
            long createdAtMs) {}

    public record FailureDto(
            String id,
            String cycleId,
            String status,
            String bucket,
            String jobName,
            String module,
            String testClass,
            String testMethod,
            String signature,
            String logExcerpt,
            String targetSubject,
            String ruleId,
            Diagnosis diagnosis,
            FixResult fix,
            VerificationResult verification,
            long updatedAtMs) {}

    public record StatsDto(
            Map<String, Long> failuresByState,
            long cycleCostMilliUsd,
            long watchCostMilliUsd) {}

    public record WatchSummary(
            String id,
            String status,
            String owner,
            String repo,
            int prNumber,
            String localPrId,
            String branch,
            String title,
            String headSha,
            String bootstrapStatus,
            long budgetMilliUsd,
            long costMilliUsd,
            long updatedAtMs) {}

    public record HarnessDashboard(
            String watchId,
            String workspaceId,
            String status,
            String owner,
            String repo,
            int prNumber,
            String localPrId,
            String branch,
            String title,
            String headSha,
            String bootstrapStatus,
            BootstrapProfile bootstrapProfile,
            BudgetDto budget,
            CycleDto activeCycle,
            List<CycleDto> cycles,
            List<EventDto> milestones,
            List<FailureDto> failures,
            StatsDto stats,
            String backupRef,
            GitSafetyProof netNeutralProof,
            HandoffDto handoff,
            String handoffCommand,
            String runStatusTail) {}

    public record CycleDetail(
            CycleDto cycle,
            List<EventDto> milestones,
            List<FailureDto> failures) {}

    public static String normalizeBucketLabel(String value)
    {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!BUCKET_LABEL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid harness bucket label: " + value);
        }
        return normalized;
    }

    public static Bucket baseBucket(String label)
    {
        String normalized = normalizeBucketLabel(label);
        int separator = normalized.indexOf(':');
        return Bucket.from(separator < 0 ? normalized : normalized.substring(0, separator));
    }

    public static String verifyVerb(String hint)
    {
        if (hint == null || !VERIFY_HINT.matcher(hint).matches()) {
            throw new IllegalArgumentException("invalid generic verification hint: " + hint);
        }
        int separator = hint.indexOf(':');
        return separator < 0 ? hint : hint.substring(0, separator);
    }
}
