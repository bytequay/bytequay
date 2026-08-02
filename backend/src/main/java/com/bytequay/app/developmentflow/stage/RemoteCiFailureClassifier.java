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

import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.Check;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.ActionsJobLogEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.AggregateDependency;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.AggregateEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckComparison;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckProfile;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.PullRequestAssociation;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.Classification;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Conservative production classification: code edits require exact provenance. */
@Component
public final class RemoteCiFailureClassifier
        implements RemoteCiRepairRuntimeCoordinator.FailureClassifier
{
    private static final long MAX_ACTIONS_LOG_BYTES = 8L * 1024 * 1024;

    @Override
    public Classification classify(Candidate candidate)
    {
        requireNonNull(candidate, "candidate is null");
        RemoteCiProvenance provenance =
                candidate.observation().ciProvenance();
        if (!validEnvelope(candidate, provenance)) {
            return Classification.UNKNOWN;
        }
        List<Check> failed = candidate.ciEvaluation().checks().stream()
                .filter(RemoteCiFailureClassifier::failureRequiringProvenance)
                .toList();
        if (failed.isEmpty() || provenance.checks().size() != failed.size()) {
            return Classification.UNKNOWN;
        }

        Map<String, CheckComparison> byExternalId = new HashMap<>();
        Set<CheckProfile> profiles = new HashSet<>();
        Set<String> baseExternalIds = new HashSet<>();
        Set<Long> headSuiteIds = new HashSet<>();
        Set<Long> baseSuiteIds = new HashSet<>();
        Set<Long> headRunIds = new HashSet<>();
        Set<Long> baseRunIds = new HashSet<>();
        for (CheckComparison comparison : provenance.checks()) {
            CheckEvidence head = comparison.head();
            if (head.externalId() == null
                    || byExternalId.put(head.externalId(), comparison) != null
                    || head.profile() == null
                    || !profiles.add(head.profile())) {
                return Classification.UNKNOWN;
            }
            headSuiteIds.add(head.checkSuiteId());
            headRunIds.add(head.workflowRunId());
            if (comparison.base() != null
                    && !baseExternalIds.add(comparison.base().externalId())) {
                return Classification.UNKNOWN;
            }
            if (comparison.base() != null) {
                baseSuiteIds.add(comparison.base().checkSuiteId());
                baseRunIds.add(comparison.base().workflowRunId());
            }
        }
        if (!Collections.disjoint(byExternalId.keySet(), baseExternalIds)
                || !Collections.disjoint(headSuiteIds, baseSuiteIds)
                || !Collections.disjoint(headRunIds, baseRunIds)) {
            return Classification.UNKNOWN;
        }
        boolean hasAggregate = provenance.checks().stream().anyMatch(
                comparison -> comparison.head().aggregateEvidence() != null);
        Map<String, Check> candidateChecks = indexCandidateChecks(
                candidate.ciEvaluation().checks());
        if (candidateChecks == null) {
            return Classification.UNKNOWN;
        }
        if (hasAggregate && (provenance.schemaVersion() < 4
                || !validAggregateGraph(
                        byExternalId, candidateChecks))) {
            return Classification.UNKNOWN;
        }

        Set<Classification> classifications = new HashSet<>();
        boolean taskBranchRepairable = true;
        for (Check check : failed) {
            CheckComparison comparison = byExternalId.get(check.externalId());
            taskBranchRepairable &= validTaskBranchRepairEvidence(
                    provenance, check, comparison, byExternalId,
                    candidateChecks, new HashSet<>());
            Classification classification = classifyEvidence(
                    provenance, check, comparison, byExternalId,
                    candidateChecks, new HashSet<>());
            classifications.add(classification);
        }
        if (classifications.size() == 1
                && !classifications.contains(Classification.UNKNOWN)) {
            return classifications.iterator().next();
        }
        return taskBranchRepairable
                ? Classification.TASK_BRANCH_REPAIRABLE
                : Classification.UNKNOWN;
    }

    /**
     * Proves only that an append-only repair on the current Task branch is
     * safe to attempt. It deliberately does not authorize base-history
     * rewriting or claim that the Task introduced every failure.
     */
    private static boolean validTaskBranchRepairEvidence(
            RemoteCiProvenance provenance,
            Check check,
            CheckComparison comparison,
            Map<String, CheckComparison> comparisons,
            Map<String, Check> candidateChecks,
            Set<String> visiting)
    {
        if (!validHeadEvidence(provenance, check, comparison)) {
            return false;
        }
        CheckEvidence head = comparison.head();
        if (head.failureFingerprints().isEmpty()
                && head.aggregateEvidence() == null) {
            return false;
        }
        if (head.aggregateEvidence() != null) {
            if (candidateChecks == null || !visiting.add(head.externalId())) {
                return false;
            }
            boolean foundFailedDependency = false;
            try {
                for (AggregateDependency dependency :
                        head.aggregateEvidence().dependencies()) {
                    Check dependencyCheck = candidateChecks.get(
                            dependency.externalCheckId());
                    if (dependencyCheck == null
                            || dependencyCheck.state() != dependency.state()) {
                        return false;
                    }
                    if (!failureRequiringProvenance(dependencyCheck)) {
                        continue;
                    }
                    foundFailedDependency = true;
                    if (!validTaskBranchRepairEvidence(
                            provenance, dependencyCheck,
                            comparisons.get(dependency.externalCheckId()),
                            comparisons, candidateChecks, visiting)) {
                        return false;
                    }
                }
                return foundFailedDependency;
            }
            finally {
                visiting.remove(head.externalId());
            }
        }
        CheckEvidence base = comparison.base();
        return base != null && base.complete() && validLineage(base)
                && validActionsJobLogEvidence(base)
                && head.profile().equals(base.profile())
                && Objects.equals(
                        base.checkTestedSha(), provenance.observedBaseSha())
                && ((base.state() == CheckState.PASSED
                                && base.failureFingerprints().isEmpty())
                        || (base.state() == CheckState.FAILED
                                && !base.failureFingerprints().isEmpty()
                                && sameFailureEvidenceSource(head, base)));
    }

    private static Classification classifyEvidence(
            RemoteCiProvenance provenance,
            Check check,
            CheckComparison comparison,
            Map<String, CheckComparison> comparisons,
            Map<String, Check> candidateChecks,
            Set<String> visiting)
    {
        if (!validHeadEvidence(provenance, check, comparison)) {
            return Classification.UNKNOWN;
        }
        if (comparison.head().aggregateEvidence() != null) {
            if (provenance.schemaVersion() < 4 || candidateChecks == null) {
                return Classification.UNKNOWN;
            }
            return classifyAggregate(
                    provenance, comparison, comparisons, candidateChecks,
                    visiting);
        }
        return infrastructureFailure(check)
                ? Classification.INFRASTRUCTURE
                : classifyCheck(provenance, comparison);
    }

    private static Classification classifyAggregate(
            RemoteCiProvenance provenance,
            CheckComparison comparison,
            Map<String, CheckComparison> comparisons,
            Map<String, Check> candidateChecks,
            Set<String> visiting)
    {
        CheckEvidence head = comparison.head();
        if (!visiting.add(head.externalId())) {
            return Classification.UNKNOWN;
        }
        Set<Classification> classifications = new HashSet<>();
        try {
            for (AggregateDependency dependency :
                    head.aggregateEvidence().dependencies()) {
                Check check = candidateChecks.get(
                        dependency.externalCheckId());
                if (check == null || check.state() != dependency.state()) {
                    return Classification.UNKNOWN;
                }
                if (!failureRequiringProvenance(check)) {
                    continue;
                }
                Classification classification = classifyEvidence(
                        provenance, check,
                        comparisons.get(dependency.externalCheckId()),
                        comparisons, candidateChecks, visiting);
                if (classification == Classification.UNKNOWN) {
                    return Classification.UNKNOWN;
                }
                classifications.add(classification);
            }
        }
        finally {
            visiting.remove(head.externalId());
        }
        return classifications.size() == 1
                ? classifications.iterator().next()
                : Classification.UNKNOWN;
    }

    private static Classification classifyCheck(
            RemoteCiProvenance provenance,
            CheckComparison comparison)
    {
        if (comparison == null || comparison.base() == null) {
            return Classification.UNKNOWN;
        }
        CheckEvidence head = comparison.head();
        CheckEvidence base = comparison.base();
        if (head.failureFingerprints().isEmpty()
                || !base.complete() || !validLineage(base)
                || !validActionsJobLogEvidence(base)
                || !head.profile().equals(base.profile())
                || !Objects.equals(
                        base.checkTestedSha(), provenance.observedBaseSha())
                || !((base.state() == CheckState.PASSED
                                && base.failureFingerprints().isEmpty())
                        || (base.state() == CheckState.FAILED
                                && !base.failureFingerprints().isEmpty()))) {
            return Classification.UNKNOWN;
        }
        if (base.state() == CheckState.PASSED) {
            return Classification.TASK_DETERMINISTIC;
        }
        if (base.state() == CheckState.FAILED
                && sameFailureEvidenceSource(head, base)
                && base.failureFingerprints().containsAll(
                        head.failureFingerprints())) {
            return Classification.BASE_DETERMINISTIC;
        }
        return Classification.UNKNOWN;
    }

    private static boolean validHeadEvidence(
            RemoteCiProvenance provenance,
            Check check,
            CheckComparison comparison)
    {
        if (comparison == null) {
            return false;
        }
        CheckEvidence head = comparison.head();
        return "CHECK_RUN".equals(check.kind())
                && head.complete() && head.state() == check.state()
                && Objects.equals(head.externalId(), check.externalId())
                && head.profile() != null
                && Objects.equals(head.profile().checkName(), check.name())
                && validLineage(head)
                && validActionsJobLogEvidence(head)
                && validHeadSubject(provenance, head);
    }

    private static boolean validEnvelope(
            Candidate candidate, RemoteCiProvenance provenance)
    {
        return provenance != null
                && provenance.schemaVersion() >= 3
                && provenance.schemaVersion() <= 5
                && candidate.observation().schemaVersion()
                    == provenance.schemaVersion()
                && provenance.complete()
                && provenance.incompleteReasons().isEmpty()
                && Objects.equals(
                        provenance.repositoryId(),
                        candidate.context().repositoryId())
                && provenance.pullRequestNumber()
                        == candidate.context().pullRequestNumber()
                && Objects.equals(
                        provenance.observedHeadSha(),
                        candidate.observation().headSha())
                && Objects.equals(
                        provenance.observedBaseSha(),
                        candidate.observation().baseSha())
                && Objects.equals(
                        provenance.observedHeadSha(),
                        candidate.evidence().headSha())
                && Objects.equals(
                        provenance.observedBaseSha(),
                        candidate.evidence().baseSha());
    }

    private static Map<String, Check> indexCandidateChecks(List<Check> checks)
    {
        Map<String, Check> indexed = new HashMap<>();
        for (Check check : checks) {
            if (!hasText(check.externalId())
                    || indexed.put(check.externalId(), check) != null) {
                return null;
            }
        }
        return indexed;
    }

    private static boolean validAggregateGraph(
            Map<String, CheckComparison> comparisons,
            Map<String, Check> candidateChecks)
    {
        Map<String, JobIdentity> jobByExternal = new HashMap<>();
        Map<Long, JobIdentity> jobById = new HashMap<>();
        for (CheckComparison comparison : comparisons.values()) {
            CheckEvidence head = comparison.head();
            AggregateEvidence aggregate = head.aggregateEvidence();
            if (aggregate == null) {
                continue;
            }
            if (!validAggregateIdentity(head, aggregate)) {
                return false;
            }
            RunIdentity run = runIdentity(aggregate);
            JobIdentity aggregateJob = new JobIdentity(
                    run, aggregate.aggregateJobKey(), head.externalId());
            if (!register(
                            jobByExternal, head.externalId(), aggregateJob)
                    || !register(
                            jobById, aggregate.aggregateJobId(), aggregateJob)) {
                return false;
            }
            Set<DependencyIdentity> dependencies = new HashSet<>();
            Set<String> externalIds = new HashSet<>();
            for (AggregateDependency dependency : aggregate.dependencies()) {
                if (!validDependency(dependency)
                        || !dependencies.add(new DependencyIdentity(
                                dependency.jobKey(),
                                dependency.externalCheckId()))
                        || !externalIds.add(dependency.externalCheckId())
                        || head.externalId().equals(
                                dependency.externalCheckId())) {
                    return false;
                }
                Check candidate = candidateChecks.get(
                        dependency.externalCheckId());
                if (candidate == null
                        || candidate.state() != dependency.state()) {
                    return false;
                }
                CheckComparison dependencyComparison = comparisons.get(
                        dependency.externalCheckId());
                if (failureRequiringProvenance(candidate)
                        && !sameAggregateRun(
                                dependencyComparison, head, aggregate)) {
                    return false;
                }
                JobIdentity dependencyJob = new JobIdentity(
                        run, dependency.jobKey(),
                        dependency.externalCheckId());
                if (!register(
                            jobByExternal, dependency.externalCheckId(),
                            dependencyJob)) {
                    return false;
                }
            }
        }
        Set<String> visited = new HashSet<>();
        for (CheckComparison comparison : comparisons.values()) {
            if (comparison.head().aggregateEvidence() != null
                    && !acyclic(
                            comparison.head().externalId(), comparisons,
                            new HashSet<>(), visited)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameAggregateRun(
            CheckComparison comparison,
            CheckEvidence aggregateHead,
            AggregateEvidence aggregate)
    {
        if (comparison == null) {
            return false;
        }
        CheckEvidence dependency = comparison.head();
        return dependency.profile() != null
                && Objects.equals(
                        dependency.profile().workflowId(),
                        aggregateHead.profile().workflowId())
                && Objects.equals(
                        dependency.profile().workflowPath(),
                        aggregate.workflowPath())
                && Objects.equals(
                        dependency.workflowRunId(),
                        aggregate.workflowRunId())
                && Objects.equals(
                        dependency.workflowRunAttempt(),
                        aggregate.workflowRunAttempt())
                && Objects.equals(
                        dependency.checkSuiteId(),
                        aggregateHead.checkSuiteId())
                && Objects.equals(
                        dependency.workflowCheckSuiteId(),
                        aggregateHead.workflowCheckSuiteId())
                && Objects.equals(
                        dependency.workflowEvent(),
                        aggregateHead.workflowEvent())
                && Objects.equals(
                        dependency.checkTestedSha(),
                        aggregateHead.checkTestedSha())
                && Objects.equals(
                        dependency.workflowTestedSha(),
                        aggregateHead.workflowTestedSha());
    }

    private static boolean validAggregateIdentity(
            CheckEvidence head, AggregateEvidence aggregate)
    {
        return aggregate.workflowBlobSha() != null
                && aggregate.workflowBlobSha().matches(
                        "(?:[0-9a-f]{40}|[0-9a-f]{64})")
                && hasText(aggregate.workflowPath())
                && Objects.equals(
                        aggregate.workflowPath(), head.profile().workflowPath())
                && aggregate.workflowRunId() != null
                && aggregate.workflowRunId() > 0
                && Objects.equals(
                        aggregate.workflowRunId(), head.workflowRunId())
                && aggregate.workflowRunAttempt() != null
                && aggregate.workflowRunAttempt() > 0
                && Objects.equals(
                        aggregate.workflowRunAttempt(),
                        head.workflowRunAttempt())
                && aggregate.aggregateJobId() != null
                && aggregate.aggregateJobId() > 0
                && hasText(aggregate.aggregateJobKey());
    }

    private static boolean validDependency(AggregateDependency dependency)
    {
        return dependency != null
                && hasText(dependency.jobKey())
                && hasText(dependency.externalCheckId())
                && dependency.state() != null
                && dependency.state() != CheckState.NONE
                && dependency.state() != CheckState.MISSING
                && dependency.state() != CheckState.QUEUED
                && dependency.state() != CheckState.PENDING;
    }

    private static boolean acyclic(
            String externalId,
            Map<String, CheckComparison> comparisons,
            Set<String> visiting,
            Set<String> visited)
    {
        if (visited.contains(externalId)) {
            return true;
        }
        if (!visiting.add(externalId)) {
            return false;
        }
        CheckComparison comparison = comparisons.get(externalId);
        AggregateEvidence aggregate = comparison == null
                ? null : comparison.head().aggregateEvidence();
        if (aggregate != null) {
            for (AggregateDependency dependency : aggregate.dependencies()) {
                CheckComparison child = comparisons.get(
                        dependency.externalCheckId());
                if (child != null && child.head().aggregateEvidence() != null
                        && !acyclic(
                                dependency.externalCheckId(), comparisons,
                                visiting, visited)) {
                    return false;
                }
            }
        }
        visiting.remove(externalId);
        visited.add(externalId);
        return true;
    }

    private static RunIdentity runIdentity(AggregateEvidence aggregate)
    {
        return new RunIdentity(
                aggregate.workflowBlobSha(), aggregate.workflowPath(),
                aggregate.workflowRunId(), aggregate.workflowRunAttempt());
    }

    private static <K, V> boolean register(Map<K, V> values, K key, V value)
    {
        V previous = values.putIfAbsent(key, value);
        return previous == null || previous.equals(value);
    }

    private static boolean validLineage(CheckEvidence evidence)
    {
        CheckProfile profile = evidence.profile();
        return hasText(evidence.externalId())
                && profile != null && positive(profile.appId())
                && hasText(profile.appSlug()) && positive(profile.workflowId())
                && hasText(profile.workflowPath())
                && hasText(profile.checkName())
                && positive(evidence.checkSuiteId())
                && Objects.equals(
                        evidence.checkSuiteId(),
                        evidence.workflowCheckSuiteId())
                && positive(evidence.workflowRunId())
                && evidence.workflowRunAttempt() != null
                && evidence.workflowRunAttempt() > 0
                && hasText(evidence.workflowEvent())
                && hasText(evidence.checkTestedSha())
                && Objects.equals(
                        evidence.checkTestedSha(),
                        evidence.workflowTestedSha())
                && evidence.failureFingerprints().stream()
                        .allMatch(RemoteCiFailureClassifier::hasText);
    }

    private static boolean positive(Long value)
    {
        return value != null && value > 0;
    }

    private static boolean validActionsJobLogEvidence(CheckEvidence evidence)
    {
        ActionsJobLogEvidence proof = evidence.actionsJobLogEvidence();
        if (proof == null) {
            return true;
        }
        if (!RemoteCiProvenance.ACTIONS_JOB_LOG_SOURCE.equals(proof.source())
                || !RemoteCiProvenance.MAVEN_COMPILER_PARSER.equals(
                        proof.parserId())
                || proof.parserVersion()
                        != RemoteCiProvenance.MAVEN_COMPILER_PARSER_VERSION
                || !Objects.equals(
                        proof.workflowRunId(), evidence.workflowRunId())
                || !Objects.equals(
                        proof.workflowRunAttempt(),
                        evidence.workflowRunAttempt())
                || proof.jobId() == null || proof.jobId() < 1
                || proof.checkRunId() == null || proof.checkRunId() < 1
                || !Objects.equals(
                        evidence.externalId(),
                        "github-check:" + proof.checkRunId())
                || !Objects.equals(proof.testedSha(), evidence.checkTestedSha())
                || proof.rawByteCount() < 1
                || proof.rawByteCount() > MAX_ACTIONS_LOG_BYTES
                || proof.rawSha256() == null
                || !proof.rawSha256().matches("[0-9a-f]{64}")
                || !proof.captureComplete() || !proof.parseComplete()
                || proof.diagnostics().isEmpty()) {
            return false;
        }
        List<String> fingerprints = proof.diagnostics().stream()
                .map(RemoteCiProvenance::canonicalFingerprint)
                .sorted()
                .toList();
        if (fingerprints.size() != new HashSet<>(fingerprints).size()
                || !Set.copyOf(fingerprints).equals(
                        evidence.failureFingerprints())) {
            return false;
        }
        List<String> ordered = proof.diagnostics().stream()
                .map(RemoteCiProvenance::canonicalFingerprint)
                .sorted()
                .toList();
        List<String> actual = proof.diagnostics().stream()
                .map(RemoteCiProvenance::canonicalFingerprint)
                .toList();
        return ordered.equals(actual) && proof.diagnostics().stream()
                .allMatch(diagnostic -> hasText(diagnostic.file())
                        && !diagnostic.file().startsWith("/")
                        && !diagnostic.file().contains("..")
                        && !diagnostic.file().contains("\\")
                        && hasText(diagnostic.kind())
                        && hasText(diagnostic.code())
                        && hasText(diagnostic.message()));
    }

    private static boolean sameFailureEvidenceSource(
            CheckEvidence head, CheckEvidence base)
    {
        ActionsJobLogEvidence left = head.actionsJobLogEvidence();
        ActionsJobLogEvidence right = base.actionsJobLogEvidence();
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return Objects.equals(left.source(), right.source())
                && Objects.equals(left.parserId(), right.parserId())
                && left.parserVersion() == right.parserVersion();
    }

    private static boolean validHeadSubject(
            RemoteCiProvenance provenance, CheckEvidence head)
    {
        if (Objects.equals(
                head.checkTestedSha(), provenance.observedHeadSha())) {
            return true;
        }
        PullRequestAssociation association = head.pullRequestAssociation();
        return provenance.observedMergeSha() != null
                && Objects.equals(
                        head.checkTestedSha(), provenance.observedMergeSha())
                && association != null
                && association.pullRequestNumber()
                        == provenance.pullRequestNumber()
                && Objects.equals(
                        association.headSha(), provenance.observedHeadSha())
                && Objects.equals(
                        association.baseSha(), provenance.observedBaseSha());
    }

    private static boolean infrastructureFailure(Check check)
    {
        if (check.state() == CheckState.CANCELED) {
            return true;
        }
        return Set.of(
                "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED", "STALE")
                .contains(normalize(check.providerConclusion()));
    }

    private static boolean failureRequiringProvenance(Check check)
    {
        return check.state() == CheckState.FAILED
                || infrastructureFailure(check);
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ENGLISH);
    }

    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    private record RunIdentity(
            String workflowBlobSha,
            String workflowPath,
            long workflowRunId,
            int workflowRunAttempt) {}

    private record JobIdentity(
            RunIdentity run, String jobKey, String externalCheckId) {}

    private record DependencyIdentity(String jobKey, String externalCheckId) {}
}
