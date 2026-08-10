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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.CiAutofix.CiEvidenceUnavailableException;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeBlocked;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeBlocker;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCiAutofix
{
    private static final Instant NOW = Instant.parse("2026-08-10T10:15:30Z");

    @TempDir
    private Path temporaryDirectory;

    private final AtomicReference<PublishedPrSubject> subject = new AtomicReference<>();

    private CiAutofix autofix;

    @BeforeEach
    void setUp()
    {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("new-flow.db")
                        + "?foreign_keys=ON");
        CiAutofixSchema.install(dataSource);
        subject.set(new PublishedPrSubject(
                "pr-1", "task-1", "repo-1", "main", "main", "H1"));
        autofix = new CiAutofix(
                dataSource,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                ignored -> subject.get());
    }

    @Test
    void waitsForTheWholeRequiredMatrixBeforeFinalRed()
    {
        RequiredCiPolicyRevision policy = resolvedPolicy(List.of("build", "test"));
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW));
        autofix.observeCi("pr-1", check(
                "test-id", "test", "IN_PROGRESS", null, "1", NOW.plusSeconds(1)));

        FinalizedRound collecting = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");

        assertThat(collecting.round().state()).isEqualTo(RoundState.COLLECTING);
        assertThat(collecting.newlyFinal()).isFalse();

        autofix.observeCi("pr-1", check(
                "test-id", "test", "COMPLETED", "SUCCESS", "2", NOW.plusSeconds(2)));
        FinalizedRound red = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");

        assertThat(red.round().state()).isEqualTo(RoundState.FINAL_RED);
        assertThat(red.round().policyRevisionId()).isEqualTo(policy.policyRevisionId());
        assertThat(red.round().checkObservationIds()).hasSize(2);
        assertThat(red.newlyFinal()).isTrue();
    }

    @Test
    void duplicateProviderRevisionCreatesOneObservationAndOneRound()
    {
        RequiredCiPolicyRevision policy = resolvedPolicy(List.of("build"));
        RequiredCiPolicyRevision duplicatePolicy = resolvedPolicy(List.of("build"));
        NormalizedCheck failed = check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW);

        String firstObservation = autofix.observeCi("pr-1", failed).observationId();
        String duplicateObservation = autofix.observeCi("pr-1", failed).observationId();
        FinalizedRound first = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");
        FinalizedRound duplicate = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");

        assertThat(duplicateObservation).isEqualTo(firstObservation);
        assertThat(duplicatePolicy.policyRevisionId()).isEqualTo(policy.policyRevisionId());
        assertThat(duplicate.round().roundId()).isEqualTo(first.round().roundId());
        assertThat(first.round().checkObservationIds()).containsExactly(firstObservation);
        assertThat(duplicate.newlyFinal()).isFalse();
        assertThat(autofix.round("pr-1", "H1", policy.policyRevisionId())).isPresent();
    }

    @Test
    void duplicateDeliveryKeepsOriginalIngestionEvidenceButRejectsFactConflict()
    {
        Instant preciseStart = NOW.plusNanos(123_456);
        Instant preciseCompletion = NOW.plusSeconds(2).plusNanos(987_654);
        NormalizedCheck first = check(
                "H1", "build", "build-id", "run-1", 1, "Build",
                "COMPLETED", "FAILURE", "revision-1", preciseStart,
                preciseCompletion, NOW.plusNanos(111_222), "raw:first");
        NormalizedCheck redelivery = check(
                "H1", "build", "build-id", "run-1", 1, "Build",
                "COMPLETED", "FAILURE", "revision-1", preciseStart,
                preciseCompletion, NOW.plusSeconds(30), "raw:redelivery");

        var stored = autofix.observeCi("pr-1", first);
        var duplicate = autofix.observeCi("pr-1", redelivery);

        assertThat(duplicate).isEqualTo(stored);
        assertThat(stored.check().startedAt()).isEqualTo(NOW);
        assertThat(stored.check().completedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(stored.check().rawEvidenceRef()).isEqualTo("raw:first");

        NormalizedCheck conflicting = check(
                "H1", "build", "build-id", "run-1", 1, "Build",
                "COMPLETED", "SUCCESS", "revision-1", preciseStart,
                preciseCompletion, NOW.plusSeconds(60), "raw:conflict");
        assertThatThrownBy(() -> autofix.observeCi("pr-1", conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different content");
    }

    @Test
    void acceptsOnlyCurrentPolicyAndExactRemoteHead()
    {
        RequiredCiPolicyRevision firstPolicy = resolvedPolicy(List.of("build"));
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "SUCCESS", "1", NOW));

        assertThat(autofix.acceptedRequiredCiSnapshot(
                "pr-1", "H1", firstPolicy.policyRevisionId()).observationIds())
                .hasSize(1);

        RequiredCiPolicyRevision secondPolicy = resolvedPolicy(List.of("build", "lint"));
        FinalizedRound collecting = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");
        assertThat(collecting.round().state()).isEqualTo(RoundState.COLLECTING);

        assertThatThrownBy(() -> autofix.acceptedRequiredCiSnapshot(
                "pr-1", "H1", firstPolicy.policyRevisionId()))
                .isInstanceOf(CiEvidenceUnavailableException.class)
                .extracting("reasonCode")
                .isEqualTo("STALE_CI_POLICY");

        subject.set(new PublishedPrSubject(
                "pr-1", "task-1", "repo-1", "main", "main", "H2"));
        assertThat(autofix.finalizeHeadSnapshot("pr-1", "H1"))
                .isEqualTo(new FinalizeBlocked(
                        FinalizeBlocker.STALE_REMOTE_HEAD,
                        "Current remote head is H2"));
        assertThatThrownBy(() -> autofix.acceptedRequiredCiSnapshot(
                "pr-1", "H2", secondPolicy.policyRevisionId()))
                .isInstanceOf(CiEvidenceUnavailableException.class)
                .extracting("reasonCode")
                .isEqualTo("REQUIRED_CI_NOT_ACCEPTED");
    }

    @Test
    void missingAndUnavailablePoliciesBlockInsteadOfMeaningEmptyCi()
    {
        assertThat(autofix.finalizeHeadSnapshot("pr-1", "H1"))
                .isEqualTo(new FinalizeBlocked(
                        FinalizeBlocker.CI_POLICY_MISSING,
                        "No CI policy exists for main"));

        autofix.recordPolicy(
                "repo-1",
                "main",
                "main",
                "github-ruleset",
                "digest-1",
                PolicyResolution.UNAVAILABLE,
                "github-permission-denied",
                List.of("ignored"),
                List.of("SUCCESS"));

        assertThat(autofix.finalizeHeadSnapshot("pr-1", "H1"))
                .isEqualTo(new FinalizeBlocked(
                        FinalizeBlocker.CI_POLICY_UNAVAILABLE,
                        "github-permission-denied"));
    }

    @Test
    void explicitEmptyResolvedPolicyIsVacuouslyGreen()
    {
        RequiredCiPolicyRevision policy = resolvedPolicy(List.of());

        FinalizedRound green = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");

        assertThat(green.round().state()).isEqualTo(RoundState.GREEN);
        assertThat(autofix.acceptedRequiredCiSnapshot(
                "pr-1", "H1", policy.policyRevisionId()).observationIds())
                .isEmpty();
    }

    @Test
    void providerOrInfrastructureConclusionDoesNotWakeARepairAgent()
    {
        resolvedPolicy(List.of("build"));
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "STARTUP_FAILURE", "1", NOW));

        FinalizedRound result = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");

        assertThat(result.round().state()).isEqualTo(RoundState.NEEDS_ATTENTION);
        assertThat(result.newlyFinal()).isFalse();
    }

    @Test
    void lateNonterminalDeliveryCannotReplaceACompletedAttempt()
    {
        RequiredCiPolicyRevision policy = resolvedPolicy(List.of("build"));
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "SUCCESS", "2", NOW));
        autofix.observeCi("pr-1", check(
                "build-id", "build", "IN_PROGRESS", null, "1", NOW.plusSeconds(30)));

        FinalizedRound result = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");

        assertThat(result.round().state()).isEqualTo(RoundState.GREEN);
        assertThat(autofix.acceptedRequiredCiSnapshot(
                "pr-1", "H1", policy.policyRevisionId()).observationIds())
                .hasSize(1);
    }

    @Test
    void newerProviderRunSupersedesHigherAttemptWithoutUsingDisplayName()
    {
        resolvedPolicy(List.of("required-build"));
        autofix.observeCi("pr-1", check(
                "H1", "required-build", "old-check", "old-run", 2, "Build",
                "COMPLETED", "SUCCESS", "old-final", NOW.minusSeconds(120),
                NOW.minusSeconds(60), NOW.minusSeconds(60), "raw:old"));
        autofix.observeCi("pr-1", check(
                "H1", "unrelated-check", "new-check", "new-run", 1, "Build",
                "COMPLETED", "SUCCESS", "new-final", NOW.plusSeconds(20),
                NOW.plusSeconds(25), NOW.plusSeconds(25), "raw:other"));
        String failedObservation = autofix.observeCi("pr-1", check(
                "H1", "required-build", "new-check", "new-run", 1, "Build",
                "COMPLETED", "FAILURE", "new-final", NOW.minusSeconds(30),
                NOW, NOW, "raw:new")).observationId();

        FinalizedRound result = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");

        assertThat(result.round().state()).isEqualTo(RoundState.FINAL_RED);
        assertThat(result.round().checkObservationIds()).containsExactly(failedObservation);
    }

    @Test
    void finalizedRoundEvidenceCannotRegressWhileQueuedOrActive()
    {
        RequiredCiPolicyRevision policy = resolvedPolicy(List.of("build"));
        String failedObservation = autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW))
                .observationId();
        FinalizedRound red = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");
        autofix.advanceRoundState(red.round().roundId(), RoundState.FINAL_RED, RoundState.QUEUED);

        autofix.observeCi("pr-1", check(
                "H1", "build", "new-check", "new-run", 1, "Build",
                "COMPLETED", "SUCCESS", "new-final", NOW.plusSeconds(10),
                NOW.plusSeconds(20), NOW.plusSeconds(20), "raw:new"));
        FinalizedRound queued = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");

        assertThat(queued.round().state()).isEqualTo(RoundState.QUEUED);
        assertThat(queued.round().checkObservationIds()).containsExactly(failedObservation);

        autofix.advanceRoundState(red.round().roundId(), RoundState.QUEUED, RoundState.ACTIVE);
        FinalizedRound active = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");

        assertThat(active.round().state()).isEqualTo(RoundState.ACTIVE);
        assertThat(active.round().checkObservationIds()).containsExactly(failedObservation);
        assertThatThrownBy(() -> autofix.advanceRoundState(
                red.round().roundId(), RoundState.QUEUED, RoundState.ACTIVE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not QUEUED");

        autofix.advanceRoundState(red.round().roundId(),
                RoundState.ACTIVE, RoundState.FIX_PREPARED);
        FinalizedRound prepared = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");
        assertThat(prepared.round().state()).isEqualTo(RoundState.FIX_PREPARED);
        assertThat(prepared.round().checkObservationIds()).containsExactly(failedObservation);
        assertThat(autofix.round("pr-1", "H1", policy.policyRevisionId()))
                .contains(prepared.round());
    }

    @Test
    void sameHeadRerunCanTurnGreenRedBeforeWorkIsQueued()
    {
        RequiredCiPolicyRevision policy = resolvedPolicy(List.of("build"));
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "SUCCESS", "1", NOW));
        FinalizedRound green = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");
        String frozenObservation = green.round().checkObservationIds().getFirst();

        autofix.observeCi("pr-1", check(
                "H1", "build", "new-check", "new-run", 1, "Build",
                "COMPLETED", "FAILURE", "new-final", NOW.plusSeconds(10),
                NOW.plusSeconds(20), NOW.plusSeconds(20), "raw:new"));

        assertThatThrownBy(() -> autofix.acceptedRequiredCiSnapshot(
                "pr-1", "H1", policy.policyRevisionId()))
                .isInstanceOf(CiEvidenceUnavailableException.class)
                .extracting("reasonCode")
                .isEqualTo("REQUIRED_CI_NOT_ACCEPTED");
        assertThat(autofix.round("pr-1", "H1", policy.policyRevisionId()))
                .get()
                .satisfies(round -> {
                    assertThat(round.state()).isEqualTo(RoundState.FINAL_RED);
                    assertThat(round.checkObservationIds())
                            .doesNotContain(frozenObservation);
                });
    }

    @Test
    void sameHeadRerunCanTurnFinalRedGreenBeforeWorkIsQueued()
    {
        RequiredCiPolicyRevision policy = resolvedPolicy(List.of("build"));
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW));
        FinalizedRound red = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");
        assertThat(red.round().state()).isEqualTo(RoundState.FINAL_RED);

        autofix.observeCi("pr-1", check(
                "H1", "build", "new-check", "new-run", 1, "Build",
                "COMPLETED", "SUCCESS", "new-final", NOW.plusSeconds(10),
                NOW.plusSeconds(20), NOW.plusSeconds(20), "raw:new"));

        FinalizedRound green = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");
        assertThat(green.round().state()).isEqualTo(RoundState.GREEN);
        assertThat(autofix.acceptedRequiredCiSnapshot(
                "pr-1", "H1", policy.policyRevisionId()).roundId())
                .isEqualTo(green.round().roundId());
    }

    @Test
    void optionalStartedAtIsSafeForOneExecutionAndAmbiguousAcrossExecutions()
    {
        resolvedPolicy(List.of("build"));
        autofix.observeCi("pr-1", check(
                "H1", "build", "first-check", "first-run", 1, "Build",
                "COMPLETED", "SUCCESS", "first-final", null,
                NOW, NOW, "raw:first"));

        FinalizedRound oneExecution = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");
        assertThat(oneExecution.round().state()).isEqualTo(RoundState.GREEN);

        autofix.observeCi("pr-1", check(
                "H1", "build", "second-check", "second-run", 1, "Build",
                "COMPLETED", "FAILURE", "second-final", null,
                NOW.plusSeconds(10), NOW.plusSeconds(10), "raw:second"));

        FinalizedRound ambiguous = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");
        assertThat(ambiguous.round().state()).isEqualTo(RoundState.NEEDS_ATTENTION);
        assertThat(ambiguous.round().checkObservationIds()).isEmpty();
    }

    @Test
    void advancingRemoteHeadSupersedesOldHeadNonterminalRound()
    {
        RequiredCiPolicyRevision policy = resolvedPolicy(List.of("build"));
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW));
        autofix.finalizeHeadSnapshot("pr-1", "H1");
        subject.set(new PublishedPrSubject(
                "pr-1", "task-1", "repo-1", "main", "main", "H2"));

        autofix.reconcileRemoteHeadSnapshot("pr-1");
        assertThat(autofix.round("pr-1", "H1", policy.policyRevisionId()))
                .get()
                .extracting("state")
                .isEqualTo(RoundState.SUPERSEDED);
    }

    @Test
    void policyRecordingCanonicalizesAndSerializesConcurrentRedelivery()
    {
        List<CompletableFuture<RequiredCiPolicyRevision>> writes = IntStream.range(0, 8)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> autofix.recordPolicy(
                        "repo-1",
                        "main",
                        "main",
                        "github-ruleset:" + index,
                        "same-digest",
                        PolicyResolution.RESOLVED,
                        null,
                        index % 2 == 0 ? List.of("test", "build") : List.of("build", "test"),
                        index % 2 == 0
                                ? List.of("success", "neutral")
                                : List.of("NEUTRAL", "SUCCESS"))))
                .toList();

        List<RequiredCiPolicyRevision> revisions = writes.stream()
                .map(CompletableFuture::join)
                .toList();

        assertThat(revisions)
                .extracting(RequiredCiPolicyRevision::policyRevisionId)
                .containsOnly(revisions.getFirst().policyRevisionId());
        RequiredCiPolicyRevision current = autofix.currentPolicy("repo-1", "main")
                .orElseThrow();
        assertThat(current.sequence()).isEqualTo(1);
        assertThat(current.requiredCheckSelectors()).containsExactly("build", "test");
        assertThat(current.acceptedConclusions()).containsExactly("NEUTRAL", "SUCCESS");
    }

    private RequiredCiPolicyRevision resolvedPolicy(List<String> checks)
    {
        return autofix.recordPolicy(
                "repo-1",
                "main",
                "main",
                "github-ruleset",
                "digest-1",
                PolicyResolution.RESOLVED,
                null,
                checks,
                List.of("SUCCESS", "NEUTRAL", "SKIPPED"));
    }

    private static NormalizedCheck check(
            String checkId,
            String name,
            String status,
            String conclusion,
            String providerRevision,
            Instant observedAt)
    {
        return check(
                "H1",
                name,
                checkId,
                "run-" + checkId,
                1,
                name,
                status,
                conclusion,
                providerRevision,
                NOW.minusSeconds(30),
                conclusion == null ? null : observedAt,
                observedAt,
                "raw:" + checkId + ":" + providerRevision);
    }

    private static NormalizedCheck check(
            String headSha,
            String selectorKey,
            String checkId,
            String runId,
            long attempt,
            String name,
            String status,
            String conclusion,
            String providerRevision,
            Instant startedAt,
            Instant completedAt,
            Instant observedAt,
            String rawEvidenceRef)
    {
        return new NormalizedCheck(
                headSha,
                selectorKey,
                checkId,
                runId,
                attempt,
                providerRevision,
                name,
                status,
                conclusion,
                startedAt,
                completedAt,
                observedAt,
                rawEvidenceRef);
    }
}
