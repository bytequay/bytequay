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
import com.bytequay.app.flow.ci.CiAutofixRecords.RepairPlacement;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.runtime.NewFlowDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
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

    private DataSource dataSource;
    private CiAutofix autofix;

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("new-flow.db")
                        + "?foreign_keys=ON");
        new NewFlowDatabase(dataSource, Clock.fixed(NOW, ZoneOffset.UTC))
                .bootstrap();
        subject.set(new PublishedPrSubject(
                "pr-1", "task-1", "repo-1", "main", "main", "H1"));
        autofix = new CiAutofix(
                dataSource,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                ignored -> subject.get());
    }

    @Test
    void logEvidenceIsBoundedSanitizedRestartSafeAndByteExact()
    {
        var observation = autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW));
        byte[] raw = ("first" + (char) 0 + "line\nsecond")
                .getBytes(StandardCharsets.UTF_8);

        var first = autofix.attachLog(observation.observationId(), raw, List.of());
        var duplicate = autofix.attachLog(
                observation.observationId(), raw, List.of());
        assertThat(duplicate).isEqualTo(first);
        assertThat(autofix.readLogWindow(first.logRef(), 0, 5).content())
                .isEqualTo("first");
        assertThat(autofix.readLogWindow(first.logRef(), 5, 64).content())
                .isEqualTo("line\nsecond");
        assertThatThrownBy(() -> autofix.readLogWindow(
                first.logRef(), 0, 64 * 1024 + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> autofix.attachLog(
                observation.observationId(),
                "different".getBytes(StandardCharsets.UTF_8),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different bytes");

        autofix = new CiAutofix(
                dataSource,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                ignored -> subject.get());
        assertThat(autofix.logEvidence(first.logRef())).contains(first);
        assertThat(autofix.readLogWindow(first.logRef(), 0, 64).content())
                .isEqualTo("firstline\nsecond");

        var largeObservation = autofix.observeCi("pr-1", check(
                "lint-id", "lint", "COMPLETED", "FAILURE", "1", NOW));
        byte[] oversized = new byte[1024 * 1024 + 17];
        Arrays.fill(oversized, (byte) 'x');
        var bounded = autofix.attachLog(
                largeObservation.observationId(), oversized, List.of());
        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.rawByteCount()).isEqualTo(oversized.length);
        assertThat(bounded.storedByteCount()).isEqualTo(1024 * 1024);
        assertThat(autofix.readLogWindow(
                bounded.logRef(), 512 * 1024 - 64, 256).content())
                .contains("[BYTEQUAY LOG TRUNCATED]");

        var secretObservation = autofix.observeCi("pr-1", check(
                "secret-id", "secret", "COMPLETED", "FAILURE", "1", NOW));
        String literalSecret = "runtime-known-secret";
        String credentials = """
                Authorization: Bearer bearer-value
                bare=ghp_123456789012345678901234
                key=AKIA1234567890123456
                PASSWORD=assignment-password
                api_key: assignment-api-key
                AWS_SECRET_ACCESS_KEY=aws-secret-value
                GH_TOKEN=github-secret-value
                MY_API_KEY=my-api-secret-value
                ::add-mask::masked-by-provider
                https://user:password@example.test/repository
                -----BEGIN PRIVATE KEY-----
                private-material
                -----END PRIVATE KEY-----
                runtime-known-secret
                """;
        var redacted = autofix.attachLog(
                secretObservation.observationId(),
                credentials.getBytes(StandardCharsets.UTF_8),
                List.of(literalSecret));
        String exposed = autofix.readLogWindow(
                redacted.logRef(), 0, 4096).content();
        assertThat(exposed)
                .contains("[REDACTED]")
                .contains("[REDACTED_GITHUB_TOKEN]")
                .contains("[REDACTED_AWS_ACCESS_KEY]")
                .contains("[REDACTED_PRIVATE KEY]")
                .contains("[MASKED]")
                .doesNotContain("bearer-value", "ghp_123456789012345678901234",
                        "AKIA1234567890123456", "password", "private-material",
                        "assignment-password", "assignment-api-key",
                        "aws-secret-value", "github-secret-value",
                        "my-api-secret-value",
                        "masked-by-provider", literalSecret);

        assertThatThrownBy(() -> autofix.attachLog(
                secretObservation.observationId(),
                "ignored".getBytes(StandardCharsets.UTF_8),
                List.of("short")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
        assertThatThrownBy(() -> autofix.attachLog(
                secretObservation.observationId(),
                "ignored".getBytes(StandardCharsets.UTF_8),
                IntStream.range(0, 65)
                        .mapToObj(index -> "secret-value-" + index)
                        .toList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many");
        assertThatThrownBy(() -> autofix.attachLog(
                secretObservation.observationId(),
                "ignored".getBytes(StandardCharsets.UTF_8),
                List.of("x".repeat(257))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
        assertThatThrownBy(() -> autofix.attachLog(
                secretObservation.observationId(),
                "ignored".getBytes(StandardCharsets.UTF_8),
                IntStream.range(0, 17)
                        .mapToObj(index -> ("secret-" + index + "-")
                                .repeat(32).substring(0, 256))
                        .toList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");

        var rejectedObservation = autofix.observeCi("pr-1", check(
                "huge-id", "huge", "COMPLETED", "FAILURE", "1", NOW));
        assertThatThrownBy(() -> autofix.attachLog(
                rejectedObservation.observationId(),
                new byte[4 * 1024 * 1024 + 1],
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
        assertThat(autofix.logEvidence(
                "ci-log-does-not-exist")).isEmpty();
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
    void utf8TruncationAndWindowsNeverSplitCodePoints()
    {
        var observation = autofix.observeCi("pr-1", check(
                "unicode-id", "unicode", "COMPLETED", "FAILURE", "1", NOW));
        byte[] raw = "🙂漢字".repeat(120_000)
                .getBytes(StandardCharsets.UTF_8);
        var evidence = autofix.attachLog(
                observation.observationId(), raw, List.of());

        assertThat(evidence.truncated()).isTrue();
        assertThatThrownBy(() -> autofix.readLogWindow(
                evidence.logRef(), 1, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boundary");
        assertThatThrownBy(() -> autofix.readLogWindow(
                evidence.logRef(), 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");

        StringBuilder reconstructed = new StringBuilder();
        long offset = 0;
        while (true) {
            var window = autofix.readLogWindow(
                    evidence.logRef(), offset, 4097);
            assertThat(window.content()).doesNotContain("�");
            assertThat(window.nextOffset()).isGreaterThan(offset);
            reconstructed.append(window.content());
            offset = window.nextOffset();
            if (window.endOfLog()) {
                break;
            }
        }
        byte[] exposed = reconstructed.toString()
                .getBytes(StandardCharsets.UTF_8);
        assertThat(exposed).hasSize((int) evidence.storedByteCount());
        assertThat(sha256(exposed)).isEqualTo(evidence.exposedContentDigest());
        assertThat(reconstructed).contains("[BYTEQUAY LOG TRUNCATED]");
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
        List<String> frozenObservations = result.round().checkObservationIds();

        autofix.observeCi("pr-1", check(
                "H1", "build", "new-check", "new-run", 1, "Build",
                "COMPLETED", "SUCCESS", "new-final", NOW.plusSeconds(10),
                NOW.plusSeconds(20), NOW.plusSeconds(20), "raw:new"));
        FinalizedRound successor = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");

        assertThat(successor.round().state()).isEqualTo(RoundState.GREEN);
        assertThat(successor.round().evidenceRevision()).isEqualTo(1);
        assertThat(autofix.roundById(result.round().roundId()))
                .get()
                .satisfies(frozen -> {
                    assertThat(frozen.state()).isEqualTo(RoundState.SUPERSEDED);
                    assertThat(frozen.checkObservationIds())
                            .isEqualTo(frozenObservations);
                });
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
    void newerEvidenceSupersedesQueuedRevisionWithoutMutatingIt()
    {
        RequiredCiPolicyRevision policy = resolvedPolicy(List.of("build"));
        String failedObservation = autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW))
                .observationId();
        FinalizedRound red = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");
        autofix.attachLog(
                failedObservation,
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        autofix.queueCurrentFinalRed(red.round().roundId());

        autofix.observeCi("pr-1", check(
                "H1", "build", "new-check", "new-run", 1, "Build",
                "COMPLETED", "SUCCESS", "new-final", NOW.plusSeconds(10),
                NOW.plusSeconds(20), NOW.plusSeconds(20), "raw:new"));
        assertThatThrownBy(() -> autofix.queueCurrentFinalRed(
                red.round().roundId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer match");
        FinalizedRound queued = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");

        assertThat(queued.round().roundId()).isNotEqualTo(red.round().roundId());
        assertThat(queued.round().evidenceRevision()).isEqualTo(1);
        assertThat(queued.round().state()).isEqualTo(RoundState.GREEN);
        assertThat(autofix.roundById(red.round().roundId()))
                .get()
                .satisfies(frozen -> {
                    assertThat(frozen.state()).isEqualTo(RoundState.SUPERSEDED);
                    assertThat(frozen.checkObservationIds())
                            .containsExactly(failedObservation);
                    assertThat(frozen.supersededBy())
                            .isEqualTo(queued.round().roundId());
                });
        assertThat(autofix.round("pr-1", "H1", policy.policyRevisionId()))
                .contains(queued.round());
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
        assertThat(autofix.roundById(green.round().roundId()))
                .get()
                .satisfies(frozen -> {
                    assertThat(frozen.state()).isEqualTo(RoundState.SUPERSEDED);
                    assertThat(frozen.checkObservationIds())
                            .containsExactly(frozenObservation);
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
        String frozenObservation = red.round().checkObservationIds().getFirst();

        autofix.observeCi("pr-1", check(
                "H1", "build", "new-check", "new-run", 1, "Build",
                "COMPLETED", "SUCCESS", "new-final", NOW.plusSeconds(10),
                NOW.plusSeconds(20), NOW.plusSeconds(20), "raw:new"));

        FinalizedRound green = (FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1");
        assertThat(green.round().state()).isEqualTo(RoundState.GREEN);
        assertThat(green.round().evidenceRevision()).isEqualTo(1);
        assertThat(autofix.roundById(red.round().roundId()))
                .get()
                .satisfies(frozen -> {
                    assertThat(frozen.state()).isEqualTo(RoundState.SUPERSEDED);
                    assertThat(frozen.checkObservationIds())
                            .containsExactly(frozenObservation);
                });
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

    @Test
    void aFailedPerCommitCompileCheckAdmitsRepairBeforeTheBoardFinishes()
    {
        resolvedPolicy(List.of("check-commits", "test"));
        autofix.recordPlacementPolicy(
                "task-1",
                RepairPlacement.ATTRIBUTED_FIXUP,
                List.of("check-commits"),
                ".github/workflows/ci.yml",
                "sha256:ci",
                true);
        var compile = autofix.observeCi("pr-1", check(
                "compile-id", "check-commits", "COMPLETED", "FAILURE",
                "1", NOW));
        autofix.observeCi("pr-1", check(
                "test-id", "test", "IN_PROGRESS", null, "1", NOW));
        autofix.attachLog(
                compile.observationId(),
                "cannot find symbol".getBytes(StandardCharsets.UTF_8),
                List.of());

        var finalized = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");

        assertThat(finalized.round().state())
                .isEqualTo(RoundState.PARTIAL_RED_COMPILE);
        assertThat(finalized.newlyFinal()).isTrue();
        // Only the compile selector's logs are frozen: nothing else on the
        // board has been judged yet.
        var queued = autofix.queueCurrentFinalRed(finalized.round().roundId());
        assertThat(queued.state()).isEqualTo(RoundState.QUEUED);
        assertThat(queued.failedLogRefs()).hasSize(1);
    }

    @Test
    void anUndeclaredCompileCheckWaitsForTheWholeBoard()
    {
        resolvedPolicy(List.of("check-commits", "test"));
        var compile = autofix.observeCi("pr-1", check(
                "compile-id", "check-commits", "COMPLETED", "FAILURE",
                "1", NOW));
        autofix.observeCi("pr-1", check(
                "test-id", "test", "IN_PROGRESS", null, "1", NOW));
        autofix.attachLog(
                compile.observationId(),
                "cannot find symbol".getBytes(StandardCharsets.UTF_8),
                List.of());

        var finalized = (FinalizedRound) autofix.finalizeHeadSnapshot(
                "pr-1", "H1");

        // The name says per-commit compile. Nothing proved it, so nothing is
        // judged early.
        assertThat(finalized.round().state())
                .isEqualTo(RoundState.COLLECTING);
        assertThat(finalized.newlyFinal()).isFalse();
    }

    @Test
    void aRewritingSeriesParksWhenTheIdenticalFailuresComeBack()
    {
        resolvedPolicy(List.of("build"));
        autofix.recordPlacementPolicy(
                "task-1", RepairPlacement.ATTRIBUTED_FIXUP, List.of(),
                null, null, true);
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW));
        assertThat(((FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H1"))
                .round().state())
                .isEqualTo(RoundState.FINAL_RED);

        // The fixer published something and the same check came back red.
        // There is no round ceiling; this is the stop.
        subject.set(new PublishedPrSubject(
                "pr-1", "task-1", "repo-1", "main", "main", "H2"));
        autofix.observeCi("pr-1", check(
                "H2", "build", "build-id-2", "run-2", 1, "build",
                "COMPLETED", "FAILURE", "1", NOW, NOW, NOW, "raw:2"));

        assertThat(((FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H2"))
                .round().state())
                .isEqualTo(RoundState.NEEDS_ATTENTION);
    }

    @Test
    void aMovedBoardIsStillRepairedAndAnOrdinaryTaskNeverParks()
    {
        resolvedPolicy(List.of("build", "test"));
        autofix.recordPlacementPolicy(
                "task-1", RepairPlacement.ATTRIBUTED_FIXUP, List.of(),
                null, null, true);
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW));
        autofix.observeCi("pr-1", check(
                "test-id", "test", "COMPLETED", "SUCCESS", "1", NOW));
        autofix.finalizeHeadSnapshot("pr-1", "H1");

        // Repairing the compile failure let the tests that never ran report:
        // a good round often raises the failing count, which is why a count is
        // the wrong signal and the failing set is the right one.
        subject.set(new PublishedPrSubject(
                "pr-1", "task-1", "repo-1", "main", "main", "H2"));
        autofix.observeCi("pr-1", check(
                "H2", "build", "build-2", "run-b2", 1, "build",
                "COMPLETED", "SUCCESS", "1", NOW, NOW, NOW, "raw:b2"));
        autofix.observeCi("pr-1", check(
                "H2", "test", "test-2", "run-t2", 1, "test",
                "COMPLETED", "FAILURE", "1", NOW, NOW, NOW, "raw:t2"));

        assertThat(((FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H2"))
                .round().state())
                .isEqualTo(RoundState.FINAL_RED);
    }

    @Test
    void anOrdinaryTaskRepairsTheSameFailureOnANewHead()
    {
        resolvedPolicy(List.of("build"));
        autofix.observeCi("pr-1", check(
                "build-id", "build", "COMPLETED", "FAILURE", "1", NOW));
        autofix.finalizeHeadSnapshot("pr-1", "H1");
        subject.set(new PublishedPrSubject(
                "pr-1", "task-1", "repo-1", "main", "main", "H2"));
        autofix.observeCi("pr-1", check(
                "H2", "build", "build-id-2", "run-2", 1, "build",
                "COMPLETED", "FAILURE", "1", NOW, NOW, NOW, "raw:2"));

        assertThat(((FinalizedRound) autofix.finalizeHeadSnapshot("pr-1", "H2"))
                .round().state())
                .isEqualTo(RoundState.FINAL_RED);
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

    private static String sha256(byte[] value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
