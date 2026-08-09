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
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.Evaluation;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.Policy;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.PolicyOutcome;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.ActionsJobLogEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.AggregateDependency;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.AggregateEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CanonicalDiagnostic;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckComparison;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckEvidence;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.CheckProfile;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance.PullRequestAssociation;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.Classification;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationEvidence;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRemoteCiFailureClassifier
{
    private final RemoteCiFailureClassifier classifier =
            new RemoteCiFailureClassifier();

    @Test
    void rawMarkersAndOldObservationsNeverAuthorizeCodeRepair()
    {
        assertThat(classifier.classify(candidate(
                List.of(check("check-1", "build", CheckState.FAILED,
                        "failure")), null, 2, "failure-origin: task")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(check("check-1", "build", CheckState.FAILED,
                        "failure")), null, 2,
                "failure-class: base-deterministic known-flake")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void classifiesOnlyCompleteExactHeadVersusBaseProof()
    {
        Check check = check("check-1", "build", CheckState.FAILED,
                "failure");
        assertThat(classifier.classify(candidate(
                List.of(check), provenance(List.of(comparison(
                        "check-1", "build", CheckState.PASSED,
                        ImmutableSet.of()))), 3, "forged failure-origin: base")))
                .isEqualTo(Classification.TASK_DETERMINISTIC);
        assertThat(classifier.classify(candidate(
                List.of(check), provenance(List.of(comparison(
                        "check-1", "build", CheckState.FAILED,
                        ImmutableSet.of("failure-a", "failure-b")))), 3, "")))
                .isEqualTo(Classification.BASE_DETERMINISTIC);
        assertThat(classifier.classify(candidate(
                List.of(check), provenance(List.of(comparison(
                        "check-1", "build", CheckState.FAILED,
                        ImmutableSet.of("different")))), 3, "")))
                .isEqualTo(Classification.TASK_BRANCH_REPAIRABLE);
    }

    @Test
    void schemaFiveClassifiesOnlyExactMatchingActionsJobLogProof()
    {
        CanonicalDiagnostic diagnostic = new CanonicalDiagnostic(
                "backend/src/test/java/acme/TestThing.java",
                "COMPILATION_ERROR", "CANNOT_FIND_SYMBOL",
                "cannot find symbol", "class MissingType",
                "class acme.TestThing");
        String fingerprint = RemoteCiProvenance.canonicalFingerprint(diagnostic);
        CheckProfile profile = new CheckProfile(
                15368L, "github-actions", 7L,
                ".github/workflows/ci.yml", "build");
        CheckEvidence head = new CheckEvidence(
                "github-check:11", profile, 31L, 31L, 101L, 1,
                "merge-1", "merge-1", "pull_request", CheckState.FAILED,
                true, ImmutableSet.of(fingerprint), new PullRequestAssociation(
                        41, "head-1", "base-1"), null,
                jobLog(101L, 1, 1_001L, 11L, "merge-1", diagnostic,
                        RemoteCiProvenance.MAVEN_COMPILER_PARSER, true));
        CheckEvidence base = new CheckEvidence(
                "github-check:12", profile, 32L, 32L, 102L, 1,
                "base-1", "base-1", "push", CheckState.FAILED,
                true, ImmutableSet.of(fingerprint), null, null,
                jobLog(102L, 1, 2_001L, 12L, "base-1", diagnostic,
                        RemoteCiProvenance.MAVEN_COMPILER_PARSER, true));
        Check failed = check(
                "github-check:11", "build", CheckState.FAILED, "failure");

        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(
                        5, List.of(new CheckComparison(head, base))), 5, "")))
                .isEqualTo(Classification.BASE_DETERMINISTIC);

        CheckEvidence mixedParserBase = new CheckEvidence(
                base.externalId(), base.profile(), base.checkSuiteId(),
                base.workflowCheckSuiteId(), base.workflowRunId(),
                base.workflowRunAttempt(), base.checkTestedSha(),
                base.workflowTestedSha(), base.workflowEvent(), base.state(),
                base.complete(), base.failureFingerprints(), null, null,
                jobLog(102L, 1, 2_001L, 12L, "base-1", diagnostic,
                        "OTHER_PARSER", true));
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(5, List.of(
                        new CheckComparison(head, mixedParserBase))), 5, "")))
                .isEqualTo(Classification.UNKNOWN);

        CheckEvidence annotationBase = new CheckEvidence(
                base.externalId(), base.profile(), base.checkSuiteId(),
                base.workflowCheckSuiteId(), base.workflowRunId(),
                base.workflowRunAttempt(), base.checkTestedSha(),
                base.workflowTestedSha(), base.workflowEvent(), base.state(),
                base.complete(), base.failureFingerprints(), null);
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(5, List.of(
                        new CheckComparison(head, annotationBase))), 5, "")))
                .isEqualTo(Classification.UNKNOWN);

        CheckEvidence malformedPassedBase = new CheckEvidence(
                base.externalId(), base.profile(), base.checkSuiteId(),
                base.workflowCheckSuiteId(), base.workflowRunId(),
                base.workflowRunAttempt(), base.checkTestedSha(),
                base.workflowTestedSha(), base.workflowEvent(),
                CheckState.PASSED, true, ImmutableSet.of(), null, null,
                jobLog(102L, 1, 2_001L, 12L, "base-1", diagnostic,
                        RemoteCiProvenance.MAVEN_COMPILER_PARSER, false));
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(5, List.of(
                        new CheckComparison(head, malformedPassedBase))),
                5, "")))
                .isEqualTo(Classification.UNKNOWN);

        CheckEvidence partialHead = new CheckEvidence(
                head.externalId(), head.profile(), head.checkSuiteId(),
                head.workflowCheckSuiteId(), head.workflowRunId(),
                head.workflowRunAttempt(), head.checkTestedSha(),
                head.workflowTestedSha(), head.workflowEvent(), head.state(),
                head.complete(), head.failureFingerprints(),
                head.pullRequestAssociation(), null,
                jobLog(101L, 1, 1_001L, 11L, "merge-1", diagnostic,
                        RemoteCiProvenance.MAVEN_COMPILER_PARSER, false));
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(5, List.of(
                        new CheckComparison(partialHead, base))), 5, "")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void mixedExactOwnershipIsTaskBranchRepairableButMalformedProofStaysUnknown()
    {
        Check build = check("check-1", "build", CheckState.FAILED,
                "failure");
        Check test = check("check-2", "test", CheckState.FAILED,
                "failure");
        assertThat(classifier.classify(candidate(
                List.of(build, test), provenance(List.of(
                        comparison("check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()),
                        comparison("check-2", "test", CheckState.FAILED,
                                ImmutableSet.of("failure-a")))), 3, "")))
                .isEqualTo(Classification.TASK_BRANCH_REPAIRABLE);

        CheckComparison incomplete = comparison(
                "check-1", "build", CheckState.FAILED, ImmutableSet.of("failure-a"));
        incomplete = new CheckComparison(incomplete.head(), new CheckEvidence(
                incomplete.base().externalId(), incomplete.base().profile(),
                incomplete.base().checkSuiteId(),
                incomplete.base().workflowCheckSuiteId(),
                incomplete.base().workflowRunId(),
                incomplete.base().workflowRunAttempt(),
                incomplete.base().checkTestedSha(),
                incomplete.base().workflowTestedSha(), "push",
                CheckState.FAILED, false, ImmutableSet.of("failure-a"), null));
        assertThat(classifier.classify(candidate(
                List.of(build), provenance(List.of(incomplete)), 3, "")))
                .isEqualTo(Classification.UNKNOWN);

        CheckComparison missingHeadFingerprint = withHead(
                comparison(
                        "check-1", "build", CheckState.PASSED, ImmutableSet.of()),
                CheckState.FAILED, true, ImmutableSet.of());
        assertThat(classifier.classify(candidate(
                List.of(build), provenance(List.of(missingHeadFingerprint)),
                3, "")))
                .isEqualTo(Classification.UNKNOWN);

        CheckComparison failedBaseWithoutFingerprint = comparison(
                "check-1", "build", CheckState.FAILED, ImmutableSet.of());
        assertThat(classifier.classify(candidate(
                List.of(build),
                provenance(List.of(failedBaseWithoutFingerprint)), 3, "")))
                .isEqualTo(Classification.UNKNOWN);
        CheckComparison nonterminalBase = comparison(
                "check-1", "build", CheckState.PENDING, ImmutableSet.of());
        assertThat(classifier.classify(candidate(
                List.of(build), provenance(List.of(nonterminalBase)), 3, "")))
                .isEqualTo(Classification.UNKNOWN);
        CheckComparison passedBaseWithFingerprint = comparison(
                "check-1", "build", CheckState.PASSED, ImmutableSet.of("stale"));
        assertThat(classifier.classify(candidate(
                List.of(build), provenance(List.of(passedBaseWithFingerprint)),
                3, "")))
                .isEqualTo(Classification.UNKNOWN);

        CheckComparison first = comparison(
                "check-1", "build", CheckState.PASSED, ImmutableSet.of());
        CheckComparison duplicate = comparison(
                "check-2", "build", CheckState.PASSED, ImmutableSet.of());
        assertThat(classifier.classify(candidate(
                List.of(build, check("check-2", "build", CheckState.FAILED,
                        "failure")),
                provenance(List.of(first, duplicate)), 3, "")))
                .isEqualTo(Classification.UNKNOWN);

        assertThat(classifier.classify(candidate(
                List.of(build, build), provenance(List.of(
                        comparison("check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()),
                        comparison("check-2", "test", CheckState.PASSED,
                                ImmutableSet.of()))), 3, "")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void malformedOrNonPositiveProviderIdentityNeverAuthorizesRepair()
    {
        Check failed = check(
                "check-1", "build", CheckState.FAILED, "failure");
        CheckComparison exact = comparison(
                "check-1", "build", CheckState.FAILED,
                ImmutableSet.of("failure-a"));
        CheckEvidence head = exact.head();
        CheckEvidence blankExternalId = new CheckEvidence(
                "", head.profile(), head.checkSuiteId(),
                head.workflowCheckSuiteId(), head.workflowRunId(),
                head.workflowRunAttempt(), head.checkTestedSha(),
                head.workflowTestedSha(), head.workflowEvent(), head.state(),
                head.complete(), head.failureFingerprints(),
                head.pullRequestAssociation());
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(List.of(new CheckComparison(
                        blankExternalId, exact.base()))), 3, "")))
                .isEqualTo(Classification.UNKNOWN);

        Check statusContext = new Check(
                "STATUS_CONTEXT", "check-1", "build", CheckState.FAILED,
                "completed", "failure", null, 10L, "{}");
        assertThat(classifier.classify(candidate(
                List.of(statusContext), provenance(List.of(comparison(
                        "check-1", "build", CheckState.PASSED, ImmutableSet.of()))),
                3, "")))
                .isEqualTo(Classification.UNKNOWN);

        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(List.of(withIdentity(
                        exact, 0L, 0L, 0L, 0L, 1))), 3, "")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(List.of(withIdentity(
                        exact, -1L, -1L, -1L, -1L, -1))), 3, "")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(List.of(withIdentity(
                        exact, 15_368L, 7L, 11L, 101L, 1))), 3, "")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(List.of(withHead(
                        exact, CheckState.FAILED, true, ImmutableSet.of("")))),
                3, "")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(List.of(withEvents(
                        exact, " ", "push"))), 3, "")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(failed), provenance(List.of(withEvents(
                        exact, "pull_request", ""))), 3, "")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void syntheticSubjectMustMatchTheExactPullRequestAssociation()
    {
        Check check = check("check-1", "build", CheckState.FAILED,
                "failure");
        CheckComparison comparison = comparison(
                "check-1", "build", CheckState.PASSED, ImmutableSet.of());
        CheckEvidence wrong = new CheckEvidence(
                comparison.head().externalId(), comparison.head().profile(),
                comparison.head().checkSuiteId(),
                comparison.head().workflowCheckSuiteId(),
                comparison.head().workflowRunId(),
                comparison.head().workflowRunAttempt(), "merge-1", "merge-1",
                "pull_request", CheckState.FAILED, true,
                ImmutableSet.of("failure-a"), new PullRequestAssociation(
                        41, "another-head", "base-1"));
        assertThat(classifier.classify(candidate(
                List.of(check), provenance(List.of(
                        new CheckComparison(wrong, comparison.base()))),
                3, "")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void infrastructureConclusionStillRequiresTheExactTypedEnvelope()
    {
        RemoteCiProvenance exact = provenance(List.of(comparison(
                "check-1", "build", CheckState.PASSED, ImmutableSet.of())));
        assertThat(classifier.classify(candidate(
                List.of(check("check-1", "build", CheckState.FAILED,
                        "timed_out")), exact, 3, "")))
                .isEqualTo(Classification.INFRASTRUCTURE);
        CheckComparison canceled = withHead(
                exact.checks().getFirst(), CheckState.CANCELED, true, ImmutableSet.of());
        assertThat(classifier.classify(candidate(
                List.of(check("check-1", "build", CheckState.CANCELED,
                        "cancelled")), provenance(List.of(canceled)), 3, "")))
                .isEqualTo(Classification.INFRASTRUCTURE);
        assertThat(classifier.classify(candidate(
                List.of(check("check-1", "build", CheckState.FAILED,
                        "timed_out")), null, 2, "service outage")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void infrastructureNeedsExactProofAndMixedExactFailuresUseTaskBranchRepair()
    {
        Check timedOut = check(
                "check-1", "build", CheckState.FAILED, "timed_out");
        assertThat(classifier.classify(candidate(
                List.of(timedOut), provenance(List.of()), 3, "")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(timedOut), provenance(List.of(comparison(
                        "another-check", "build", CheckState.PASSED,
                        ImmutableSet.of()))), 3, "")))
                .isEqualTo(Classification.UNKNOWN);

        CheckComparison incomplete = withHead(
                comparison("check-1", "build", CheckState.PASSED, ImmutableSet.of()),
                CheckState.FAILED, false, ImmutableSet.of());
        assertThat(classifier.classify(candidate(
                List.of(timedOut), provenance(List.of(incomplete)), 3, "")))
                .isEqualTo(Classification.UNKNOWN);

        Check deterministic = check(
                "check-2", "test", CheckState.FAILED, "failure");
        assertThat(classifier.classify(candidate(
                List.of(timedOut, deterministic), provenance(List.of(
                        comparison("check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()),
                        comparison("check-2", "test", CheckState.PASSED,
                                ImmutableSet.of()))), 3, "")))
                .isEqualTo(Classification.TASK_BRANCH_REPAIRABLE);
    }

    @Test
    void schemaFourKeepsConcreteProofAndClassifiesUnanimousDependencies()
    {
        Check build = check("check-1", "build", CheckState.FAILED, "failure");
        assertThat(classifier.classify(candidate(
                List.of(build), provenance(
                        4, List.of(comparison(
                                "check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()))), 4, "")))
                .isEqualTo(Classification.TASK_DETERMINISTIC);

        Check aggregate = check(
                "aggregate-1", "CI success", CheckState.FAILED, "failure");
        Check lint = check("lint-1", "lint", CheckState.PASSED, "success");
        CheckComparison aggregateProof = aggregateComparison(
                "aggregate-1", "CI success", 9_001L, "ci-success",
                List.of(
                        dependency(
                                "check-commit", "check-1", CheckState.FAILED),
                        dependency(
                                "check-commit", "lint-1", CheckState.PASSED)));
        assertThat(classifier.classify(candidate(
                List.of(aggregate, build, lint), provenance(4, List.of(
                        aggregateProof,
                        comparison("check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()))), 4, "")))
                .isEqualTo(Classification.TASK_DETERMINISTIC);
        assertThat(classifier.classify(candidate(
                List.of(aggregate, build, lint), provenance(5, List.of(
                        aggregateProof,
                        comparison("check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()))), 5, "")))
                .isEqualTo(Classification.TASK_DETERMINISTIC);
    }

    @Test
    void nestedAggregateRecursesToOneStrictClassification()
    {
        Check top = check(
                "aggregate-top", "CI success", CheckState.FAILED, "failure");
        Check nested = check(
                "aggregate-test", "Test success", CheckState.FAILED,
                "failure");
        Check test = check("test-1", "test", CheckState.FAILED, "failure");
        assertThat(classifier.classify(candidate(
                List.of(top, nested, test), provenance(4, List.of(
                        aggregateComparison(
                                "aggregate-top", "CI success", 9_001L,
                                "ci-success", List.of(dependency(
                                        "test-success", "aggregate-test",
                                        CheckState.FAILED))),
                        aggregateComparison(
                                "aggregate-test", "Test success", 9_002L,
                                "test-success", List.of(dependency(
                                        "test", "test-1", CheckState.FAILED))),
                        comparison(
                                "test-1", "test", CheckState.FAILED,
                                ImmutableSet.of("failure-a")))), 4, "")))
                .isEqualTo(Classification.BASE_DETERMINISTIC);
    }

    @Test
    void aggregateFallbackRequiresExactConcreteBaseProofForEveryFailedLeaf()
    {
        Check aggregate = check(
                "aggregate-1", "CI success", CheckState.FAILED, "failure");
        Check build = check("check-1", "build", CheckState.FAILED, "failure");
        Check test = check("check-2", "test", CheckState.FAILED, "failure");
        Check lint = check("lint-1", "lint", CheckState.PASSED, "success");

        assertThat(classifier.classify(candidate(
                List.of(aggregate, lint), provenance(4, List.of(
                        aggregateComparison(
                                "aggregate-1", "CI success", 9_001L,
                                "ci-success", List.of(dependency(
                                        "lint", "lint-1",
                                        CheckState.PASSED))))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);

        assertThat(classifier.classify(candidate(
                List.of(aggregate, build), provenance(4, List.of(
                        aggregateComparison(
                                "aggregate-1", "CI success", 9_001L,
                                "ci-success", List.of(dependency(
                                        "build", "check-1",
                                        CheckState.FAILED))),
                        new CheckComparison(comparison(
                                "check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()).head(), null))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);

        assertThat(classifier.classify(candidate(
                List.of(aggregate, build), provenance(4, List.of(
                        aggregateComparison(
                                "aggregate-1", "CI success", 9_001L,
                                "ci-success", List.of(dependency(
                                        "build", "check-1",
                                        CheckState.FAILED))),
                        comparison(
                                "unrelated", "unrelated", CheckState.PASSED,
                                ImmutableSet.of()))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);

        assertThat(classifier.classify(candidate(
                List.of(aggregate, build, test), provenance(4, List.of(
                        aggregateComparison(
                                "aggregate-1", "CI success", 9_001L,
                                "ci-success", List.of(
                                        dependency(
                                                "build", "check-1",
                                                CheckState.FAILED),
                                        dependency(
                                                "test", "check-2",
                                                CheckState.FAILED))),
                        comparison(
                                "check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()),
                        comparison(
                                "check-2", "test", CheckState.FAILED,
                                ImmutableSet.of("failure-a")))), 4, "")))
                .isEqualTo(Classification.TASK_BRANCH_REPAIRABLE);

        assertThat(classifier.classify(candidate(
                List.of(aggregate, build, test), provenance(4, List.of(
                        aggregateComparison(
                                "aggregate-1", "CI success", 9_001L,
                                "ci-success", List.of(
                                        dependency(
                                                "build", "check-1",
                                                CheckState.FAILED),
                                        dependency(
                                                "build", "check-1",
                                                CheckState.FAILED))),
                        comparison(
                                "check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()),
                        comparison(
                                "check-2", "test", CheckState.PASSED,
                                ImmutableSet.of()))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void aggregateCyclesAndInexactWorkflowIdentityStayUnknown()
    {
        Check first = check(
                "aggregate-1", "CI success", CheckState.FAILED, "failure");
        Check second = check(
                "aggregate-2", "Build success", CheckState.FAILED, "failure");
        assertThat(classifier.classify(candidate(
                List.of(first, second), provenance(4, List.of(
                        aggregateComparison(
                                "aggregate-1", "CI success", 9_001L,
                                "ci-success", List.of(dependency(
                                        "build-success", "aggregate-2",
                                        CheckState.FAILED))),
                        aggregateComparison(
                                "aggregate-2", "Build success", 9_002L,
                                "build-success", List.of(dependency(
                                        "ci-success", "aggregate-1",
                                        CheckState.FAILED))))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);

        Check build = check("check-1", "build", CheckState.FAILED, "failure");
        CheckComparison exact = aggregateComparison(
                "aggregate-1", "CI success", 9_001L, "ci-success",
                List.of(dependency(
                        "build", "check-1", CheckState.FAILED)));
        AggregateEvidence wrongPath = new AggregateEvidence(
                exact.head().aggregateEvidence().workflowBlobSha(),
                ".github/workflows/another.yml", 101L, 1, 9_001L,
                "ci-success", exact.head().aggregateEvidence().dependencies());
        assertThat(classifier.classify(candidate(
                List.of(first, build), provenance(4, List.of(
                        withAggregate(exact, wrongPath),
                        comparison(
                                "check-1", "build", CheckState.PASSED,
                                ImmutableSet.of()))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void aggregateDependenciesMustComeFromTheDeclaredRunPathAndAttempt()
    {
        Check aggregate = check(
                "aggregate-1", "CI success", CheckState.FAILED, "failure");
        Check build = check(
                "check-1", "build", CheckState.FAILED, "failure");
        CheckComparison aggregateProof = aggregateComparison(
                "aggregate-1", "CI success", 9_001L, "ci-success",
                List.of(dependency(
                        "build", "check-1", CheckState.FAILED)));
        CheckComparison concrete = comparison(
                "check-1", "build", CheckState.PASSED, ImmutableSet.of());

        assertThat(classifier.classify(candidate(
                List.of(aggregate, build), provenance(4, List.of(
                        aggregateProof,
                        withHeadWorkflow(
                                concrete, 202L, 1,
                                ".github/workflows/ci.yml"))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(aggregate, build), provenance(4, List.of(
                        aggregateProof,
                        withHeadWorkflow(
                                concrete, 101L, 2,
                                ".github/workflows/ci.yml"))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(aggregate, build), provenance(4, List.of(
                        aggregateProof,
                        withHeadWorkflow(
                                concrete, 101L, 1,
                                ".github/workflows/other.yml"))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);
        assertThat(classifier.classify(candidate(
                List.of(aggregate, build), provenance(4, List.of(
                        aggregateProof,
                        withHeadSuite(concrete, 88L))), 4, "")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void schemaThreeRejectsAggregateEvidence()
    {
        assertThatThrownBy(() -> provenance(3, List.of(aggregateComparison(
                "aggregate-1", "CI success", 9_001L, "ci-success",
                List.of(dependency(
                        "build", "check-1", CheckState.FAILED))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema v4");
    }

    @Test
    void observationAndProvenanceSchemasMustMatch()
    {
        assertThatThrownBy(() -> candidate(
                List.of(check(
                        "check-1", "build", CheckState.FAILED, "failure")),
                provenance(3, List.of(comparison(
                        "check-1", "build", CheckState.PASSED, ImmutableSet.of()))),
                4, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must match");
    }

    private static Check check(
            String id, String name, CheckState state, String conclusion)
    {
        return new Check(
                "CHECK_RUN", id, name, state, "completed", conclusion,
                null, 10L, "untrusted raw evidence");
    }

    private static Candidate candidate(
            List<Check> checks,
            RemoteCiProvenance provenance,
            int schemaVersion,
            String rawEvidence)
    {
        ObservationDelivery context = new ObservationDelivery(
                "row-1", "operation-1", "task-1", 1,
                "stage-1", 1, "binding-1", "policy-1",
                "acme/widget", 41, "head-1", "base-1",
                "head-1", "base-1", 0, 1, true,
                policy(), ImmutableSet.of());
        RemoteObservationOperationHandler.Observation observation =
                schemaVersion >= 3
                        ? new RemoteObservationOperationHandler.Observation(
                                schemaVersion, "observation-1", "head-1", "base-1",
                                RemoteObservationOperationHandler.PrState.OPEN,
                                RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                                RemoteObservationOperationHandler.MergeQueueState.NONE,
                                0, 0, 0, 0, 0, 0, checks, List.of(), "me", true,
                                provenance, rawEvidence, 10)
                        : new RemoteObservationOperationHandler.Observation(
                                schemaVersion, "observation-1", "head-1", "base-1",
                                RemoteObservationOperationHandler.PrState.OPEN,
                                RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                                RemoteObservationOperationHandler.MergeQueueState.NONE,
                                0, 0, 0, 0, 0, 0, checks, List.of(), "me", true,
                                rawEvidence, 10);
        Evaluation evaluation = new Evaluation(
                CheckState.FAILED, PolicyOutcome.FAILED, checks,
                checks.size(), 0);
        ObservationEvidence evidence = new ObservationEvidence(
                "snapshot-1", "evaluation-1", 1,
                "head-1", "base-1", PolicyOutcome.FAILED, 10);
        return new Candidate(context, observation, evaluation, evidence);
    }

    private static RemoteCiProvenance provenance(
            List<CheckComparison> comparisons)
    {
        return provenance(3, comparisons);
    }

    private static RemoteCiProvenance provenance(
            int schemaVersion, List<CheckComparison> comparisons)
    {
        return new RemoteCiProvenance(
                schemaVersion, "acme/widget", 41, "head-1", "base-1", "merge-1",
                true, List.of(), comparisons);
    }

    private static CheckComparison aggregateComparison(
            String externalId,
            String name,
            long jobId,
            String jobKey,
            List<AggregateDependency> dependencies)
    {
        CheckProfile profile = new CheckProfile(
                15368L, "github-actions", 7L,
                ".github/workflows/ci.yml", name);
        AggregateEvidence aggregate = new AggregateEvidence(
                "a".repeat(40), ".github/workflows/ci.yml",
                101L, 1, jobId, jobKey, dependencies);
        CheckEvidence head = new CheckEvidence(
                externalId, profile, 11L, 11L, 101L, 1,
                "merge-1", "merge-1", "pull_request", CheckState.FAILED,
                true, ImmutableSet.of(), new PullRequestAssociation(
                        41, "head-1", "base-1"), aggregate);
        return new CheckComparison(head, null);
    }

    private static AggregateDependency dependency(
            String jobKey, String externalCheckId, CheckState state)
    {
        return new AggregateDependency(jobKey, externalCheckId, state);
    }

    private static ActionsJobLogEvidence jobLog(
            long runId,
            int runAttempt,
            long jobId,
            long checkRunId,
            String testedSha,
            CanonicalDiagnostic diagnostic,
            String parser,
            boolean complete)
    {
        return new ActionsJobLogEvidence(
                RemoteCiProvenance.ACTIONS_JOB_LOG_SOURCE,
                parser, 1, runId, runAttempt, jobId, checkRunId, testedSha,
                100, "a".repeat(64), complete, complete,
                List.of(diagnostic));
    }

    private static CheckComparison withAggregate(
            CheckComparison comparison, AggregateEvidence aggregate)
    {
        CheckEvidence head = comparison.head();
        return new CheckComparison(new CheckEvidence(
                head.externalId(), head.profile(), head.checkSuiteId(),
                head.workflowCheckSuiteId(), head.workflowRunId(),
                head.workflowRunAttempt(), head.checkTestedSha(),
                head.workflowTestedSha(), head.workflowEvent(), head.state(),
                head.complete(), head.failureFingerprints(),
                head.pullRequestAssociation(), aggregate), comparison.base());
    }

    private static CheckComparison comparison(
            String externalId,
            String name,
            CheckState baseState,
            Set<String> baseFingerprints)
    {
        CheckProfile profile = new CheckProfile(
                15368L, "github-actions", 7L,
                ".github/workflows/ci.yml", name);
        CheckEvidence head = new CheckEvidence(
                externalId, profile, 11L, 11L, 101L, 1,
                "merge-1", "merge-1", "pull_request", CheckState.FAILED,
                true, ImmutableSet.of("failure-a"), new PullRequestAssociation(
                        41, "head-1", "base-1"));
        CheckEvidence base = new CheckEvidence(
                "base-" + externalId, profile, 12L, 12L, 102L, 1,
                "base-1", "base-1", "push", baseState, true,
                baseFingerprints, null);
        return new CheckComparison(head, base);
    }

    private static CheckComparison withHead(
            CheckComparison comparison,
            CheckState state,
            boolean complete,
            Set<String> fingerprints)
    {
        CheckEvidence head = comparison.head();
        return new CheckComparison(new CheckEvidence(
                head.externalId(), head.profile(), head.checkSuiteId(),
                head.workflowCheckSuiteId(), head.workflowRunId(),
                head.workflowRunAttempt(), head.checkTestedSha(),
                head.workflowTestedSha(), head.workflowEvent(), state, complete,
                fingerprints, head.pullRequestAssociation()), comparison.base());
    }

    private static CheckComparison withHeadWorkflow(
            CheckComparison comparison,
            long runId,
            int runAttempt,
            String workflowPath)
    {
        CheckEvidence head = comparison.head();
        CheckProfile profile = new CheckProfile(
                head.profile().appId(), head.profile().appSlug(),
                head.profile().workflowId(), workflowPath,
                head.profile().checkName());
        CheckEvidence oldBase = comparison.base();
        CheckProfile baseProfile = new CheckProfile(
                oldBase.profile().appId(), oldBase.profile().appSlug(),
                oldBase.profile().workflowId(), workflowPath,
                oldBase.profile().checkName());
        CheckEvidence base = new CheckEvidence(
                oldBase.externalId(), baseProfile, oldBase.checkSuiteId(),
                oldBase.workflowCheckSuiteId(), oldBase.workflowRunId(),
                oldBase.workflowRunAttempt(), oldBase.checkTestedSha(),
                oldBase.workflowTestedSha(), oldBase.workflowEvent(),
                oldBase.state(), oldBase.complete(),
                oldBase.failureFingerprints(),
                oldBase.pullRequestAssociation());
        return new CheckComparison(new CheckEvidence(
                head.externalId(), profile, head.checkSuiteId(),
                head.workflowCheckSuiteId(), runId, runAttempt,
                head.checkTestedSha(), head.workflowTestedSha(),
                head.workflowEvent(), head.state(), head.complete(),
                head.failureFingerprints(), head.pullRequestAssociation()),
                base);
    }

    private static CheckComparison withHeadSuite(
            CheckComparison comparison, long suiteId)
    {
        CheckEvidence head = comparison.head();
        return new CheckComparison(new CheckEvidence(
                head.externalId(), head.profile(), suiteId, suiteId,
                head.workflowRunId(), head.workflowRunAttempt(),
                head.checkTestedSha(), head.workflowTestedSha(),
                head.workflowEvent(), head.state(), head.complete(),
                head.failureFingerprints(), head.pullRequestAssociation()),
                comparison.base());
    }

    private static CheckComparison withIdentity(
            CheckComparison comparison,
            long appId,
            long workflowId,
            long suiteId,
            long runId,
            int runAttempt)
    {
        CheckEvidence head = comparison.head();
        CheckEvidence oldBase = comparison.base();
        CheckProfile profile = new CheckProfile(
                appId, head.profile().appSlug(), workflowId,
                head.profile().workflowPath(), head.profile().checkName());
        CheckEvidence changedHead = new CheckEvidence(
                head.externalId(), profile, suiteId, suiteId, runId,
                runAttempt, head.checkTestedSha(), head.workflowTestedSha(),
                head.workflowEvent(), head.state(), head.complete(),
                head.failureFingerprints(), head.pullRequestAssociation());
        CheckEvidence changedBase = new CheckEvidence(
                oldBase.externalId(), profile, suiteId, suiteId, runId,
                runAttempt, oldBase.checkTestedSha(),
                oldBase.workflowTestedSha(), oldBase.workflowEvent(),
                oldBase.state(), oldBase.complete(),
                oldBase.failureFingerprints(),
                oldBase.pullRequestAssociation());
        return new CheckComparison(changedHead, changedBase);
    }

    private static CheckComparison withEvents(
            CheckComparison comparison, String headEvent, String baseEvent)
    {
        CheckEvidence head = comparison.head();
        CheckEvidence oldBase = comparison.base();
        CheckEvidence changedHead = new CheckEvidence(
                head.externalId(), head.profile(), head.checkSuiteId(),
                head.workflowCheckSuiteId(), head.workflowRunId(),
                head.workflowRunAttempt(), head.checkTestedSha(),
                head.workflowTestedSha(), headEvent, head.state(),
                head.complete(), head.failureFingerprints(),
                head.pullRequestAssociation());
        CheckEvidence changedBase = new CheckEvidence(
                oldBase.externalId(), oldBase.profile(),
                oldBase.checkSuiteId(), oldBase.workflowCheckSuiteId(),
                oldBase.workflowRunId(), oldBase.workflowRunAttempt(),
                oldBase.checkTestedSha(), oldBase.workflowTestedSha(),
                baseEvent, oldBase.state(), oldBase.complete(),
                oldBase.failureFingerprints(),
                oldBase.pullRequestAssociation());
        return new CheckComparison(changedHead, changedBase);
    }

    private static Policy policy()
    {
        return new Policy(Map.of(
                CheckState.NONE, PolicyOutcome.WAITING,
                CheckState.MISSING, PolicyOutcome.FAILED,
                CheckState.QUEUED, PolicyOutcome.WAITING,
                CheckState.PENDING, PolicyOutcome.WAITING,
                CheckState.NEUTRAL, PolicyOutcome.ACCEPTED,
                CheckState.SKIPPED, PolicyOutcome.ACCEPTED,
                CheckState.CANCELED, PolicyOutcome.FAILED));
    }
}
