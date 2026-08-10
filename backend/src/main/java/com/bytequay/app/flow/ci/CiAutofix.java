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

import com.bytequay.app.flow.ci.CiAutofixRecords.AcceptedCiSnapshot;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCheckObservation;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeBlocked;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeBlocker;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeHeadResult;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Durable exact-head observation boundary for the greenfield CI Autofix flow.
 *
 * <p>This component owns no agent, task, gate, PR, or runtime lifecycle. The
 * supplied subject reader is the sole bridge to the new PR owner and prevents
 * this component from copying that state.
 *
 * <p>This first slice intentionally accepts only a PR-subject snapshot. It is
 * suitable for durable observation tests, but not for dispatch, gate, ready,
 * feedback, or merge authority. Integration must replace the snapshot read
 * with a PR-owner transaction/fence that freezes subject and CI evidence
 * together. Until then, no caller may treat a round or acceptance snapshot as
 * authorization evidence.
 */
public final class CiAutofix
{
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final Comparator<CiCheckObservation> SAME_EXECUTION_ORDER =
            Comparator.comparingLong((CiCheckObservation value) -> value.check().attempt())
                    .thenComparing(value -> isTerminal(value.check().status()))
                    .thenComparing(value -> normalizedInstant(value.check().completedAt()),
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(value -> normalizedInstant(value.check().observedAt()))
                    .thenComparing(CiCheckObservation::observationId);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final Function<String, PublishedPrSubject> subjectSnapshotReader;

    public CiAutofix(
            DataSource dataSource,
            ObjectMapper json,
            Clock clock,
            Function<String, PublishedPrSubject> subjectSnapshotReader)
    {
        requireNonNull(dataSource, "dataSource is null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.subjectSnapshotReader = requireNonNull(
                subjectSnapshotReader, "subjectSnapshotReader is null");
    }

    /**
     * Serializes policy sequence allocation in the one local sidecar process.
     * The baseline's unique repository/scope/sequence constraint remains the
     * final guard. A distributed deployment would need a database owner lock.
     */
    public synchronized RequiredCiPolicyRevision recordPolicy(
            String repositoryId,
            String scopeKey,
            String targetBaseRef,
            String sourceRef,
            String sourceDigest,
            PolicyResolution resolution,
            String unavailableReasonRef,
            List<String> requiredCheckSelectors,
            List<String> acceptedConclusions)
    {
        requireText(repositoryId, "repositoryId");
        requireText(scopeKey, "scopeKey");
        requireText(targetBaseRef, "targetBaseRef");
        requireNonNull(resolution, "resolution is null");
        List<String> selectors = normalize(requiredCheckSelectors, false);
        List<String> conclusions = normalize(acceptedConclusions, true);
        if (resolution == PolicyResolution.RESOLVED && conclusions.isEmpty()) {
            throw new IllegalArgumentException(
                    "A resolved CI policy must accept at least one conclusion");
        }
        if (resolution == PolicyResolution.UNAVAILABLE) {
            requireText(unavailableReasonRef, "unavailableReasonRef");
            selectors = List.of();
            conclusions = List.of();
        }

        List<String> finalSelectors = selectors;
        List<String> finalConclusions = conclusions;
        return requireNonNull(transactions.execute(ignored -> {
            Optional<RequiredCiPolicyRevision> current = currentPolicy(repositoryId, scopeKey);
            if (current.filter(policy -> samePolicy(
                    policy,
                    targetBaseRef,
                    sourceRef,
                    sourceDigest,
                    resolution,
                    unavailableReasonRef,
                    finalSelectors,
                    finalConclusions)).isPresent()) {
                return current.get();
            }
            long sequence = current.map(RequiredCiPolicyRevision::sequence).orElse(0L) + 1;
            Instant now = clock.instant();
            String id = stableId("ci-policy", repositoryId, scopeKey, Long.toString(sequence));
            jdbc.update(
                    """
                    INSERT INTO flow_ci_policy_revision (
                        policy_revision_id, repository_id, scope_key,
                        target_base_ref, sequence, resolution, source_ref,
                        source_digest, unavailable_reason_ref,
                        required_check_selectors_json,
                        accepted_conclusions_json, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    repositoryId,
                    scopeKey,
                    targetBaseRef,
                    sequence,
                    resolution.name(),
                    sourceRef,
                    sourceDigest,
                    unavailableReasonRef,
                    writeJson(finalSelectors),
                    writeJson(finalConclusions),
                    now.toEpochMilli());
            return new RequiredCiPolicyRevision(
                    id,
                    repositoryId,
                    scopeKey,
                    targetBaseRef,
                    sequence,
                    resolution,
                    sourceRef,
                    sourceDigest,
                    unavailableReasonRef,
                    finalSelectors,
                    finalConclusions,
                    now);
        }), "policy transaction returned null");
    }

    public Optional<RequiredCiPolicyRevision> currentPolicy(
            String repositoryId, String scopeKey)
    {
        requireText(repositoryId, "repositoryId");
        requireText(scopeKey, "scopeKey");
        return jdbc.query(
                """
                SELECT *
                FROM flow_ci_policy_revision
                WHERE repository_id = ? AND scope_key = ?
                ORDER BY sequence DESC
                LIMIT 1
                """,
                (result, row) -> readPolicy(result),
                repositoryId,
                scopeKey).stream().findFirst();
    }

    public CiCheckObservation observeCi(String prId, NormalizedCheck check)
    {
        requireText(prId, "prId");
        requireNonNull(check, "check is null");
        PublishedPrSubject subject = requireSubject(prId);
        String id = stableId(
                "ci-observation",
                prId,
                check.selectorKey(),
                check.providerCheckId(),
                check.providerRunId(),
                Long.toString(check.attempt()),
                check.providerStateRevision());
        jdbc.update(
                    """
                    INSERT OR IGNORE INTO flow_ci_check_observation (
                        observation_id, pr_id, head_sha, selector_key, provider_check_id,
                        provider_run_id, attempt, provider_state_revision,
                        name, status, conclusion, started_at, completed_at,
                        observed_at, raw_evidence_ref
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    subject.prId(),
                    check.headSha(),
                    check.selectorKey(),
                    check.providerCheckId(),
                    check.providerRunId(),
                    check.attempt(),
                    check.providerStateRevision(),
                    check.name(),
                    normalizeToken(check.status()),
                    normalizeNullableToken(check.conclusion()),
                    epochMillis(check.startedAt()),
                    epochMillis(check.completedAt()),
                    check.observedAt().toEpochMilli(),
                    check.rawEvidenceRef());
        CiCheckObservation stored = observation(id)
                .orElseThrow(() -> new IllegalStateException(
                        "CI observation identity conflict for " + id));
        assertSameObservation(stored.check(), check);
        return stored;
    }

    public FinalizeHeadResult finalizeHeadSnapshot(String prId, String headSha)
    {
        requireText(prId, "prId");
        requireText(headSha, "headSha");
        return requireNonNull(transactions.execute(ignored -> finalizeHeadInTransaction(prId, headSha)),
                "finalize transaction returned null");
    }

    /**
     * Lazily reconciles old rounds after the PR-owner snapshot reports a new head.
     * Future GitHub integration must invoke the equivalent operation inside the
     * PR-owner head-advance transaction; this snapshot method is not that fence.
     */
    public void reconcileRemoteHeadSnapshot(String prId)
    {
        requireText(prId, "prId");
        transactions.executeWithoutResult(
                ignored -> supersedeOldHeadRounds(requireSubject(prId)));
    }

    /** Advances an already frozen round without changing its evidence. */
    public CiRound advanceRoundState(
            String roundId, RoundState expectedState, RoundState nextState)
    {
        requireText(roundId, "roundId");
        requireNonNull(expectedState, "expectedState is null");
        requireNonNull(nextState, "nextState is null");
        if (!allowedTransition(expectedState, nextState)) {
            throw new IllegalArgumentException(
                    "Invalid CI round transition: " + expectedState + " -> " + nextState);
        }
        return requireNonNull(transactions.execute(ignored -> {
            int updated = jdbc.update(
                    """
                    UPDATE flow_ci_round
                    SET state = ?
                    WHERE round_id = ? AND state = ?
                    """,
                    nextState.name(),
                    roundId,
                    expectedState.name());
            if (updated != 1) {
                CiRound current = roundById(roundId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown CI round: " + roundId));
                throw new IllegalStateException(
                        "CI round state is " + current.state() + ", not " + expectedState);
            }
            return roundById(roundId).orElseThrow();
        }), "round-state transaction returned null");
    }

    /**
     * Computes a read-only snapshot, not gate-safe authority.
     *
     * <p>The current subject lookup is not locked with the future PR owner. Gate,
     * ready, feedback, and merge code must not consume this method. That integration
     * must introduce an owner-transaction API which freezes the PR subject and these
     * observation IDs atomically.
     */
    public AcceptedCiSnapshot acceptedRequiredCiSnapshot(
            String prId, String headSha, String policyRevisionId)
    {
        requireText(prId, "prId");
        requireText(headSha, "headSha");
        requireText(policyRevisionId, "policyRevisionId");
        FinalizeHeadResult result = finalizeHeadSnapshot(prId, headSha);
        if (result instanceof FinalizeBlocked blocked) {
            throw new CiEvidenceUnavailableException(blocked.blocker().name(), blocked.detail());
        }
        CiRound round = ((FinalizedRound) result).round();
        if (!round.policyRevisionId().equals(policyRevisionId)) {
            throw new CiEvidenceUnavailableException(
                    "STALE_CI_POLICY",
                    "Policy " + policyRevisionId + " is not current for " + prId);
        }
        if (round.state() != RoundState.GREEN) {
            throw new CiEvidenceUnavailableException(
                    "REQUIRED_CI_NOT_ACCEPTED",
                    "Required CI is " + round.state() + " for " + headSha);
        }
        PublishedPrSubject subject = requireSubject(prId);
        if (!subject.currentRemoteHead().equals(headSha)) {
            throw new CiEvidenceUnavailableException(
                    "STALE_REMOTE_HEAD",
                    "Current remote head is " + subject.currentRemoteHead());
        }
        RequiredCiPolicyRevision policy = currentPolicy(
                subject.repositoryId(), subject.scopeKey())
                .orElseThrow(() -> new CiEvidenceUnavailableException(
                        "CI_POLICY_MISSING", "No current CI policy"));
        RoundCalculation current = calculateRound(prId, headSha, policy);
        if (current.state() != RoundState.GREEN
                || !current.observationIds().equals(round.checkObservationIds())) {
            throw new CiEvidenceUnavailableException(
                    "CI_OBSERVATION_SUPERSEDED",
                    "New provider observations supersede the frozen green round");
        }
        return new AcceptedCiSnapshot(
                prId,
                headSha,
                policyRevisionId,
                round.roundId(),
                round.checkObservationIds());
    }

    public Optional<CiRound> round(String prId, String headSha, String policyRevisionId)
    {
        requireText(prId, "prId");
        requireText(headSha, "headSha");
        requireText(policyRevisionId, "policyRevisionId");
        return jdbc.query(
                """
                SELECT *
                FROM flow_ci_round
                WHERE pr_id = ? AND remote_head = ? AND policy_revision_id = ?
                """,
                (result, row) -> readRound(result),
                prId,
                headSha,
                policyRevisionId).stream().findFirst();
    }

    private FinalizeHeadResult finalizeHeadInTransaction(String prId, String headSha)
    {
        PublishedPrSubject subject = requireSubject(prId);
        supersedeOldHeadRounds(subject);
        if (!subject.currentRemoteHead().equals(headSha)) {
            return new FinalizeBlocked(
                    FinalizeBlocker.STALE_REMOTE_HEAD,
                    "Current remote head is " + subject.currentRemoteHead());
        }
        Optional<RequiredCiPolicyRevision> current = currentPolicy(
                subject.repositoryId(), subject.scopeKey());
        if (current.isEmpty()) {
            return new FinalizeBlocked(
                    FinalizeBlocker.CI_POLICY_MISSING,
                    "No CI policy exists for " + subject.scopeKey());
        }
        RequiredCiPolicyRevision policy = current.get();
        if (!policy.targetBaseRef().equals(subject.targetBaseRef())) {
            return new FinalizeBlocked(
                    FinalizeBlocker.CI_POLICY_MISSING,
                    "Current CI policy targets " + policy.targetBaseRef());
        }
        if (policy.resolution() == PolicyResolution.UNAVAILABLE) {
            return new FinalizeBlocked(
                    FinalizeBlocker.CI_POLICY_UNAVAILABLE,
                    requireNonNull(policy.unavailableReasonRef(), "unavailableReasonRef is null"));
        }

        RoundCalculation calculation = calculateRound(prId, headSha, policy);
        Optional<CiRound> before = round(prId, headSha, policy.policyRevisionId());
        CiRound stored = storeRound(subject, headSha, policy, calculation);
        jdbc.update(
                """
                UPDATE flow_ci_round
                SET state = 'SUPERSEDED', superseded_by = ?
                WHERE pr_id = ?
                  AND remote_head = ?
                  AND policy_revision_id <> ?
                  AND state NOT IN ('GREEN', 'SUPERSEDED')
                """,
                stored.roundId(),
                prId,
                headSha,
                policy.policyRevisionId());
        boolean newlyFinal = stored.state() == RoundState.FINAL_RED
                && before.map(CiRound::state).orElse(null) != RoundState.FINAL_RED;
        return new FinalizedRound(stored, newlyFinal);
    }

    private RoundCalculation calculateRound(
            String prId, String headSha, RequiredCiPolicyRevision policy)
    {
        Map<String, List<CiCheckObservation>> observationsBySelector = new HashMap<>();
        for (CiCheckObservation observation : observations(prId, headSha)) {
            observationsBySelector.computeIfAbsent(
                            observation.check().selectorKey(), ignored -> new ArrayList<>())
                    .add(observation);
        }

        List<CiCheckObservation> selected = new ArrayList<>();
        boolean collecting = false;
        boolean failed = false;
        boolean needsAttention = false;
        for (String selector : policy.requiredCheckSelectors()) {
            ObservationSelection selection = selectLatestObservation(
                    observationsBySelector.getOrDefault(selector, List.of()));
            if (selection.ambiguous()) {
                needsAttention = true;
                continue;
            }
            CiCheckObservation observation = selection.observation();
            if (observation == null || !isTerminal(observation.check().status())) {
                collecting = true;
                continue;
            }
            selected.add(observation);
            String conclusion = normalizeNullableToken(observation.check().conclusion());
            if (!policy.acceptedConclusions().contains(conclusion)) {
                if ("FAILURE".equals(conclusion)) {
                    failed = true;
                }
                else {
                    needsAttention = true;
                }
            }
        }
        RoundState state = needsAttention
                ? RoundState.NEEDS_ATTENTION
                : collecting
                        ? RoundState.COLLECTING
                        : failed ? RoundState.FINAL_RED : RoundState.GREEN;
        return new RoundCalculation(
                selected.stream().map(CiCheckObservation::observationId).toList(),
                state);
    }

    private CiRound storeRound(
            PublishedPrSubject subject,
            String headSha,
            RequiredCiPolicyRevision policy,
            RoundCalculation calculation)
    {
        String id = stableId("ci-round", subject.prId(), headSha, policy.policyRevisionId());
        Optional<CiRound> existing = round(subject.prId(), headSha, policy.policyRevisionId());
        if (existing.isEmpty()) {
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT OR IGNORE INTO flow_ci_round (
                        round_id, task_id, pr_id, remote_head,
                        policy_revision_id, check_observation_ids_json,
                        state, created_at, superseded_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """,
                    id,
                    subject.taskId(),
                    subject.prId(),
                    headSha,
                    policy.policyRevisionId(),
                    writeJson(calculation.observationIds()),
                    calculation.state().name(),
                    now.toEpochMilli());
        }
        else if (canRefreshEvidence(existing.get().state())) {
            jdbc.update(
                    """
                    UPDATE flow_ci_round
                    SET check_observation_ids_json = ?, state = ?, superseded_by = NULL
                    WHERE round_id = ?
                      AND state IN (
                          'COLLECTING', 'FINAL_RED', 'GREEN', 'NEEDS_ATTENTION'
                      )
                    """,
                    writeJson(calculation.observationIds()),
                    calculation.state().name(),
                    id);
        }
        return round(subject.prId(), headSha, policy.policyRevisionId())
                .orElseThrow(() -> new IllegalStateException("Round was not stored: " + id));
    }

    private void supersedeOldHeadRounds(PublishedPrSubject subject)
    {
        jdbc.update(
                """
                UPDATE flow_ci_round
                SET state = 'SUPERSEDED'
                WHERE pr_id = ?
                  AND remote_head <> ?
                  AND state IN (
                      'COLLECTING', 'FINAL_RED', 'QUEUED', 'ACTIVE',
                      'FIX_PREPARED', 'NEEDS_ATTENTION'
                  )
                """,
                subject.prId(),
                subject.currentRemoteHead());
    }

    private List<CiCheckObservation> observations(String prId, String headSha)
    {
        return jdbc.query(
                """
                SELECT *
                FROM flow_ci_check_observation
                WHERE pr_id = ? AND head_sha = ?
                """,
                (result, row) -> {
                    NormalizedCheck check = new NormalizedCheck(
                            result.getString("head_sha"),
                            result.getString("selector_key"),
                            result.getString("provider_check_id"),
                            result.getString("provider_run_id"),
                            result.getLong("attempt"),
                            result.getString("provider_state_revision"),
                            result.getString("name"),
                            result.getString("status"),
                            result.getString("conclusion"),
                            instant(nullableLong(result, "started_at")),
                            instant(nullableLong(result, "completed_at")),
                            instant(result.getLong("observed_at")),
                            result.getString("raw_evidence_ref"));
                    return new CiCheckObservation(
                            result.getString("observation_id"),
                            result.getString("pr_id"),
                            check);
                },
                prId,
                headSha);
    }

    private Optional<CiCheckObservation> observation(String observationId)
    {
        return jdbc.query(
                """
                SELECT *
                FROM flow_ci_check_observation
                WHERE observation_id = ?
                """,
                (result, row) -> {
                    NormalizedCheck check = new NormalizedCheck(
                            result.getString("head_sha"),
                            result.getString("selector_key"),
                            result.getString("provider_check_id"),
                            result.getString("provider_run_id"),
                            result.getLong("attempt"),
                            result.getString("provider_state_revision"),
                            result.getString("name"),
                            result.getString("status"),
                            result.getString("conclusion"),
                            instant(nullableLong(result, "started_at")),
                            instant(nullableLong(result, "completed_at")),
                            instant(result.getLong("observed_at")),
                            result.getString("raw_evidence_ref"));
                    return new CiCheckObservation(
                            result.getString("observation_id"),
                            result.getString("pr_id"),
                            check);
                },
                observationId).stream().findFirst();
    }

    private Optional<CiRound> roundById(String roundId)
    {
        return jdbc.query(
                """
                SELECT *
                FROM flow_ci_round
                WHERE round_id = ?
                """,
                (result, row) -> readRound(result),
                roundId).stream().findFirst();
    }

    private RequiredCiPolicyRevision readPolicy(ResultSet result)
            throws SQLException
    {
        return new RequiredCiPolicyRevision(
                result.getString("policy_revision_id"),
                result.getString("repository_id"),
                result.getString("scope_key"),
                result.getString("target_base_ref"),
                result.getLong("sequence"),
                PolicyResolution.valueOf(result.getString("resolution")),
                result.getString("source_ref"),
                result.getString("source_digest"),
                result.getString("unavailable_reason_ref"),
                readStringList(result.getString("required_check_selectors_json")),
                readStringList(result.getString("accepted_conclusions_json")),
                instant(result.getLong("recorded_at")));
    }

    private CiRound readRound(ResultSet result)
            throws SQLException
    {
        return new CiRound(
                result.getString("round_id"),
                result.getString("task_id"),
                result.getString("pr_id"),
                result.getString("remote_head"),
                result.getString("policy_revision_id"),
                readStringList(result.getString("check_observation_ids_json")),
                RoundState.valueOf(result.getString("state")),
                instant(result.getLong("created_at")),
                result.getString("superseded_by"));
    }

    private PublishedPrSubject requireSubject(String prId)
    {
        PublishedPrSubject subject = requireNonNull(
                subjectSnapshotReader.apply(prId), "No published PR subject for " + prId);
        if (!subject.prId().equals(prId)) {
            throw new IllegalArgumentException("PR subject identity mismatch");
        }
        return subject;
    }

    private static List<String> normalize(List<String> values, boolean tokens)
    {
        requireNonNull(values, "values is null");
        TreeSet<String> unique = new TreeSet<>();
        for (String value : values) {
            requireText(value, "list value");
            unique.add(tokens ? normalizeToken(value) : value.trim());
        }
        return List.copyOf(unique);
    }

    private static boolean isTerminal(String status)
    {
        return normalizeToken(status).equals("COMPLETED");
    }

    private static ObservationSelection selectLatestObservation(
            List<CiCheckObservation> observations)
    {
        if (observations.isEmpty()) {
            return new ObservationSelection(null, false);
        }
        Map<ExecutionKey, CiCheckObservation> latestByExecution = new HashMap<>();
        for (CiCheckObservation observation : observations) {
            ExecutionKey key = new ExecutionKey(
                    observation.check().providerRunId(),
                    observation.check().providerCheckId());
            latestByExecution.merge(key, observation, (first, second) ->
                    SAME_EXECUTION_ORDER.compare(first, second) >= 0 ? first : second);
        }
        if (latestByExecution.size() == 1) {
            return new ObservationSelection(
                    latestByExecution.values().iterator().next(), false);
        }
        if (latestByExecution.values().stream()
                .anyMatch(value -> value.check().startedAt() == null)) {
            return new ObservationSelection(null, true);
        }
        Instant latestStart = latestByExecution.values().stream()
                .map(value -> normalizedInstant(value.check().startedAt()))
                .max(Comparator.naturalOrder())
                .orElseThrow();
        List<CiCheckObservation> latest = latestByExecution.values().stream()
                .filter(value -> normalizedInstant(value.check().startedAt())
                        .equals(latestStart))
                .toList();
        return latest.size() == 1
                ? new ObservationSelection(latest.getFirst(), false)
                : new ObservationSelection(null, true);
    }

    private static boolean canRefreshEvidence(RoundState state)
    {
        return state == RoundState.COLLECTING
                || state == RoundState.FINAL_RED
                || state == RoundState.GREEN
                || state == RoundState.NEEDS_ATTENTION;
    }

    private static String normalizeToken(String value)
    {
        requireText(value, "token");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeNullableToken(String value)
    {
        return value == null ? null : normalizeToken(value);
    }

    private static Long epochMillis(Instant value)
    {
        return value == null ? null : value.toEpochMilli();
    }

    private static Instant instant(Long value)
    {
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    private static Instant instant(long value)
    {
        return Instant.ofEpochMilli(value);
    }

    private static Instant normalizedInstant(Instant value)
    {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }

    private static Long nullableLong(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static String stableId(String prefix, String... parts)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            for (String part : parts) {
                digest.update((byte) 0);
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return prefix + "-" + HexFormat.of().formatHex(digest.digest(), 0, 16);
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    private String writeJson(List<String> values)
    {
        try {
            return json.writeValueAsString(values);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode program-owned CI values", e);
        }
    }

    private List<String> readStringList(String value)
    {
        try {
            return json.readValue(value, STRING_LIST);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not decode program-owned CI values", e);
        }
    }

    private static void assertSameObservation(NormalizedCheck stored, NormalizedCheck supplied)
    {
        NormalizedCheck normalized = new NormalizedCheck(
                supplied.headSha(),
                supplied.selectorKey(),
                supplied.providerCheckId(),
                supplied.providerRunId(),
                supplied.attempt(),
                supplied.providerStateRevision(),
                supplied.name(),
                normalizeToken(supplied.status()),
                normalizeNullableToken(supplied.conclusion()),
                normalizedInstant(supplied.startedAt()),
                normalizedInstant(supplied.completedAt()),
                normalizedInstant(stored.observedAt()),
                stored.rawEvidenceRef());
        if (!stored.equals(normalized)) {
            throw new IllegalStateException(
                    "Provider CI revision was redelivered with different content");
        }
    }

    private static boolean samePolicy(
            RequiredCiPolicyRevision policy,
            String targetBaseRef,
            String sourceRef,
            String sourceDigest,
            PolicyResolution resolution,
            String unavailableReasonRef,
            List<String> selectors,
            List<String> conclusions)
    {
        return policy.targetBaseRef().equals(targetBaseRef)
                && Objects.equals(policy.sourceDigest(), sourceDigest)
                && (sourceDigest != null || Objects.equals(policy.sourceRef(), sourceRef))
                && policy.resolution() == resolution
                && Objects.equals(
                        policy.unavailableReasonRef(), unavailableReasonRef)
                && policy.requiredCheckSelectors().equals(selectors)
                && policy.acceptedConclusions().equals(conclusions);
    }

    private static boolean allowedTransition(RoundState from, RoundState to)
    {
        return switch (from) {
            case FINAL_RED -> to == RoundState.QUEUED
                    || to == RoundState.SUPERSEDED
                    || to == RoundState.NEEDS_ATTENTION;
            case QUEUED -> to == RoundState.ACTIVE
                    || to == RoundState.SUPERSEDED
                    || to == RoundState.NEEDS_ATTENTION;
            case ACTIVE -> to == RoundState.FIX_PREPARED
                    || to == RoundState.SUPERSEDED
                    || to == RoundState.NEEDS_ATTENTION;
            case FIX_PREPARED -> to == RoundState.SUPERSEDED
                    || to == RoundState.NEEDS_ATTENTION;
            case NEEDS_ATTENTION -> to == RoundState.QUEUED
                    || to == RoundState.SUPERSEDED;
            case COLLECTING, GREEN, SUPERSEDED -> false;
        };
    }

    private record RoundCalculation(
            List<String> observationIds,
            RoundState state) {}

    private record ExecutionKey(String providerRunId, String providerCheckId) {}

    private record ObservationSelection(
            CiCheckObservation observation, boolean ambiguous) {}

    public static final class CiEvidenceUnavailableException
            extends IllegalStateException
    {
        private final String reasonCode;

        public CiEvidenceUnavailableException(String reasonCode, String message)
        {
            super(message);
            this.reasonCode = requireNonNull(reasonCode, "reasonCode is null");
        }

        public String reasonCode()
        {
            return reasonCode;
        }
    }
}
