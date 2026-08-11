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
import com.bytequay.app.flow.ci.CiAutofixRecords.AttemptState;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCheckObservation;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupCompletion;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupSeal;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiLogEvidence;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiLogWindow;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiUpdateGateEvidence;
import com.bytequay.app.flow.ci.CiAutofixRecords.CleanupAttentionReason;
import com.bytequay.app.flow.ci.CiAutofixRecords.CleanupOutcome;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeBlocked;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeBlocker;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeHeadResult;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupFinalizationReceipt;
import com.bytequay.app.flow.runtime.FlowRuntime.CleanupHandoff;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.AttachmentState;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.GitOperation;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanInspection;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanKind;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Durable exact-head observation boundary for the greenfield CI Autofix flow.
 *
 * <p>This component owns no agent, task, gate, PR, or runtime lifecycle. The
 * supplied subject reader is the sole bridge to the new PR owner and prevents
 * this component from copying that state.
 *
 * <p>The observation/finalization methods intentionally accept only a
 * PR-subject snapshot. They are not dispatch, gate, ready, feedback, or merge
 * authority. {@link CiAutofixCoordinator} revalidates the runtime's actual
 * Task/PR owner rows in the transaction which queues a repair; acceptance
 * snapshots remain unsuitable as authorization evidence.
 */
public final class CiAutofix
{
    private static final int MAX_STORED_LOG_BYTES = 1024 * 1024;
    private static final int MAX_RAW_LOG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LOG_WINDOW_BYTES = 64 * 1024;
    private static final int MAX_LITERAL_SECRET_COUNT = 64;
    private static final int MAX_LITERAL_SECRET_LENGTH = 256;
    private static final int MAX_LITERAL_SECRET_TOTAL_LENGTH = 4096;
    private static final int MIN_LITERAL_SECRET_LENGTH = 8;
    private static final byte[] TRUNCATION_MARKER =
            "\n...[BYTEQUAY LOG TRUNCATED]...\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final Pattern AUTHORIZATION_SECRET = Pattern.compile(
            "(?i)(authorization\\s*:\\s*(?:bearer|token|basic)\\s+)(\\S+)");
    private static final Pattern GITHUB_TOKEN = Pattern.compile(
            "\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}"
                    + "|github_pat_[A-Za-z0-9_]{20,})\\b");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile(
            "\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");
    private static final Pattern URL_CREDENTIAL = Pattern.compile(
            "(?i)(https?://[^\\s/:@]+:)([^\\s@]+)(@)");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN ([A-Z ]*PRIVATE KEY)-----.*?"
                    + "-----END \\1-----");
    private static final Pattern COMMON_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?=[A-Z_])[A-Z0-9_]{0,64}"
                    + "(?:TOKEN|SECRET|PASSWORD|PASSWD|API[_-]?KEY)"
                    + "[A-Z0-9_]{0,64}\\b\\s*[:=]\\s*)"
                    + "(?:\"[^\\r\\n\"]*\"|'[^\\r\\n']*'|[^\\s,;]+)");
    private static final Pattern ADD_MASK_COMMAND = Pattern.compile(
            "(?m)(::add-mask::)[^\\r\\n]*");
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
            int advanced;
            if (current.isEmpty()) {
                advanced = jdbc.update(
                        """
                        INSERT INTO flow_ci_policy_current (
                            repository_id, scope_key, policy_revision_id
                        ) VALUES (?, ?, ?)
                        """,
                        repositoryId,
                        scopeKey,
                        id);
            }
            else {
                advanced = jdbc.update(
                        """
                        UPDATE flow_ci_policy_current
                        SET policy_revision_id = ?
                        WHERE repository_id = ? AND scope_key = ?
                          AND policy_revision_id = ?
                        """,
                        id,
                        repositoryId,
                        scopeKey,
                        current.orElseThrow().policyRevisionId());
            }
            if (advanced != 1) {
                throw new IllegalStateException(
                        "CI policy pointer changed during append");
            }
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
                SELECT p.*
                FROM flow_ci_policy_current c
                JOIN flow_ci_policy_revision p
                  ON p.policy_revision_id = c.policy_revision_id
                WHERE c.repository_id = ? AND c.scope_key = ?
                """,
                (result, row) -> readPolicy(result),
                repositoryId,
                scopeKey).stream().findFirst();
    }

    boolean lockCurrentPolicy(RequiredCiPolicyRevision policy)
    {
        requireNonNull(policy, "policy is null");
        return jdbc.update(
                """
                UPDATE flow_ci_policy_current
                SET policy_revision_id = policy_revision_id
                WHERE repository_id = ? AND scope_key = ?
                  AND policy_revision_id = ?
                """,
                policy.repositoryId(), policy.scopeKey(),
                policy.policyRevisionId()) == 1;
    }

    /** Current actionable CI-fix facts for one local CI_UPDATE gate. */
    public CiUpdateGateEvidence ciUpdateGateEvidence(
            String sourceKind, String sourceId)
    {
        requireText(sourceKind, "sourceKind");
        requireText(sourceId, "sourceId");
        return requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt attempt;
            String outputRevision;
            String outputHead;
            String cleanupId = null;
            String cleanupResultId = null;
            if (sourceKind.equals("REPAIR_ATTEMPT")) {
                attempt = repairAttempt(sourceId).orElseThrow(() ->
                        new IllegalStateException(
                                "unknown CI repair gate source"));
                outputRevision = attempt.outputChangeSetRevisionId();
                outputHead = attempt.outputLocalHead();
            }
            else if (sourceKind.equals("CLEANUP")) {
                CiCleanupCompletion completion = cleanupCompletion(sourceId)
                        .orElseThrow(() -> new IllegalStateException(
                                "unknown CI cleanup gate source"));
                CiCleanupSeal seal = cleanupSeal(sourceId).orElseThrow();
                attempt = repairAttempt(seal.repairAttemptId()).orElseThrow();
                outputRevision = completion.outputChangeSetRevisionId();
                outputHead = completion.outputHead();
                cleanupId = completion.cleanupId();
                cleanupResultId = completion.resultRef();
                if (completion.outcome() != CleanupOutcome.FIX_PREPARED
                        && completion.outcome()
                                != CleanupOutcome.NO_HEAD_CHANGE) {
                    throw new IllegalStateException(
                            "CI cleanup is not gate actionable");
                }
            }
            else {
                throw new IllegalArgumentException(
                        "unsupported CI gate source kind");
            }
            if (attempt.state() != AttemptState.FIX_PREPARED
                    && attempt.state() != AttemptState.NO_HEAD_CHANGE
                    && !sourceKind.equals("CLEANUP")) {
                throw new IllegalStateException(
                        "CI repair is not gate actionable");
            }
            CiRound round = roundById(attempt.roundId()).orElseThrow();
            PublishedPrSubject subject = requireSubject(round.prId());
            RequiredCiPolicyRevision policy = currentPolicy(
                    subject.repositoryId(), subject.scopeKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "CI_UPDATE requires a current CI policy"));
            CiRound currentRound = round(
                    round.prId(), round.remoteHead(),
                    policy.policyRevisionId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "CI_UPDATE has no current CI round"));
            int policyLocked = jdbc.update(
                    """
                    UPDATE flow_ci_policy_current
                    SET policy_revision_id = policy_revision_id
                    WHERE repository_id = ? AND scope_key = ?
                      AND policy_revision_id = ?
                    """,
                    subject.repositoryId(),
                    subject.scopeKey(),
                    policy.policyRevisionId());
            int roundLocked = jdbc.update(
                    """
                    UPDATE flow_ci_round SET state = state
                    WHERE round_id = ? AND state = 'FIX_PREPARED'
                      AND superseded_by IS NULL
                    """,
                    round.roundId());
            if (policy.resolution() != PolicyResolution.RESOLVED
                    || !policy.targetBaseRef().equals(
                            subject.targetBaseRef())
                    || round.state() != RoundState.FIX_PREPARED
                    || !currentRound.roundId().equals(round.roundId())
                    || !round.policyRevisionId().equals(
                            policy.policyRevisionId())
                    || !subject.currentRemoteHead().equals(round.remoteHead())
                    || outputRevision == null
                    || outputHead == null
                    || attempt.resultRef() == null
                    || policyLocked != 1
                    || roundLocked != 1) {
                throw new IllegalStateException(
                        "CI_UPDATE source is no longer current/actionable");
            }
            return new CiUpdateGateEvidence(
                    sourceKind,
                    sourceId,
                    round.roundId(),
                    round.taskId(),
                    round.prId(),
                    round.remoteHead(),
                    round.policyRevisionId(),
                    round.evidenceRevision(),
                    round.checkObservationIds(),
                    round.failedLogRefs(),
                    outputRevision,
                    outputHead,
                    attempt.attemptId(),
                    attempt.resultRef(),
                    cleanupId,
                    cleanupResultId);
        }), "CI_UPDATE evidence transaction returned null");
    }

    CiCheckObservation observeCi(String prId, NormalizedCheck check)
    {
        return observeCi(null, null, prId, check);
    }

    CiCheckObservation observeProviderCi(
            String sourceOperationId,
            String sourceReceiptId,
            String prId,
            NormalizedCheck check)
    {
        requireText(sourceOperationId, "sourceOperationId");
        requireText(sourceReceiptId, "sourceReceiptId");
        return observeCi(sourceOperationId, sourceReceiptId, prId, check);
    }

    private CiCheckObservation observeCi(
            String sourceOperationId,
            String sourceReceiptId,
            String prId,
            NormalizedCheck check)
    {
        requireText(prId, "prId");
        requireNonNull(check, "check is null");
        PublishedPrSubject subject = requireSubject(prId);
        String id = stableId(
                "ci-observation",
                prId,
                Objects.toString(sourceOperationId, "INTERNAL"),
                Objects.toString(sourceReceiptId, "INTERNAL"),
                check.selectorKey(),
                check.providerCheckId(),
                check.providerRunId(),
                Long.toString(check.attempt()),
                check.providerStateRevision());
        jdbc.update(
                    """
                    INSERT OR IGNORE INTO flow_ci_check_observation (
                        observation_id, pr_id, source_operation_id,
                        source_receipt_id, head_sha, selector_key, provider_check_id,
                        provider_run_id, attempt, provider_state_revision,
                        name, status, conclusion, started_at, completed_at,
                        observed_at, raw_evidence_ref
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    subject.prId(),
                    sourceOperationId,
                    sourceReceiptId,
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
        if (!Objects.equals(stored.sourceOperationId(), sourceOperationId)
                || !Objects.equals(
                        stored.sourceReceiptId(), sourceReceiptId)) {
            throw new IllegalStateException(
                    "CI observation source identity conflict for " + id);
        }
        assertSameObservation(stored.check(), check);
        return stored;
    }

    /**
     * Stores one immutable bounded log after best-effort known-form redaction.
     * Ingestion must explicitly supply the provider/context secret literals;
     * this cannot recognize arbitrary credentials.
     */
    public CiLogEvidence attachLog(
            String observationId, byte[] rawLog, List<String> literalSecrets)
    {
        requireText(observationId, "observationId");
        requireNonNull(rawLog, "rawLog is null");
        requireNonNull(literalSecrets, "literalSecrets is null");
        if (rawLog.length > MAX_RAW_LOG_BYTES) {
            throw new IllegalArgumentException(
                    "raw CI log exceeds " + MAX_RAW_LOG_BYTES + " bytes");
        }
        List<String> secrets = validateLiteralSecrets(literalSecrets);
        if (observation(observationId).isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown CI observation: " + observationId);
        }
        byte[] sanitized = sanitizeLog(rawLog, secrets);
        boolean truncated = sanitized.length > MAX_STORED_LOG_BYTES;
        byte[] stored = boundLog(sanitized);
        String digest = sha256(rawLog);
        String exposedDigest = sha256(stored);
        String logRef = stableId("ci-log", observationId);
        jdbc.update(
                """
                INSERT OR IGNORE INTO flow_ci_log_evidence (
                    log_ref, observation_id, content_digest,
                    exposed_content_digest,
                    raw_byte_count, stored_byte_count, truncated,
                    sanitized_content, stored_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                logRef,
                observationId,
                digest,
                exposedDigest,
                rawLog.length,
                stored.length,
                truncated ? 1 : 0,
                stored,
                clock.instant().toEpochMilli());
        CiLogEvidence evidence = logEvidence(logRef)
                .orElseThrow(() -> new IllegalStateException(
                        "CI log identity conflict for " + observationId));
        if (!evidence.observationId().equals(observationId)
                || !evidence.contentDigest().equals(digest)
                || !evidence.exposedContentDigest().equals(exposedDigest)
                || evidence.rawByteCount() != rawLog.length
                || evidence.storedByteCount() != stored.length
                || evidence.truncated() != truncated) {
            throw new IllegalStateException(
                    "CI observation log was redelivered with different bytes");
        }
        return evidence;
    }

    /** Reads one bounded byte window; callers cannot request an unbounded log. */
    public CiLogWindow readLogWindow(String logRef, long offset, int maxBytes)
    {
        requireText(logRef, "logRef");
        if (offset < 0) {
            throw new IllegalArgumentException("offset is negative");
        }
        if (maxBytes <= 0 || maxBytes > MAX_LOG_WINDOW_BYTES) {
            throw new IllegalArgumentException(
                    "maxBytes must be between 1 and " + MAX_LOG_WINDOW_BYTES);
        }
        CiLogEvidence evidence = logEvidence(logRef)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown CI log: " + logRef));
        if (offset > evidence.storedByteCount()) {
            throw new IllegalArgumentException("offset is beyond stored log");
        }
        if (offset < evidence.storedByteCount()) {
            byte[] first = jdbc.queryForObject(
                    """
                    SELECT substr(sanitized_content, ?, 1)
                    FROM flow_ci_log_evidence
                    WHERE log_ref = ?
                    """,
                    byte[].class,
                    offset + 1,
                    logRef);
            if (first == null || first.length != 1) {
                throw new IllegalStateException("stored CI log is unreadable");
            }
            if (isUtf8Continuation(first[0])) {
                throw new IllegalArgumentException(
                        "offset is not a UTF-8 code-point boundary");
            }
        }
        byte[] bytes = jdbc.queryForObject(
                """
                SELECT substr(sanitized_content, ?, ?)
                FROM flow_ci_log_evidence
                WHERE log_ref = ?
                """,
                byte[].class,
                offset + 1,
                maxBytes,
                logRef);
        byte[] window = bytes == null ? new byte[0] : bytes;
        boolean reachesEnd = offset + window.length
                >= evidence.storedByteCount();
        int safeLength = reachesEnd
                ? window.length
                : completeUtf8PrefixLength(window);
        if (safeLength == 0 && offset < evidence.storedByteCount()) {
            throw new IllegalArgumentException(
                    "maxBytes is too small for the next UTF-8 code point");
        }
        byte[] safeWindow = safeLength == window.length
                ? window
                : Arrays.copyOf(window, safeLength);
        long next = offset + safeLength;
        return new CiLogWindow(
                logRef,
                offset,
                new String(safeWindow, StandardCharsets.UTF_8),
                next,
                next >= evidence.storedByteCount());
    }

    public Optional<CiLogEvidence> logEvidence(String logRef)
    {
        requireText(logRef, "logRef");
        return jdbc.query(
                """
                SELECT log_ref, observation_id, content_digest,
                       exposed_content_digest,
                       raw_byte_count, stored_byte_count, truncated, stored_at
                FROM flow_ci_log_evidence
                WHERE log_ref = ?
                """,
                (result, row) -> new CiLogEvidence(
                        result.getString("log_ref"),
                        result.getString("observation_id"),
                        result.getString("content_digest"),
                        result.getString("exposed_content_digest"),
                        result.getLong("raw_byte_count"),
                        result.getLong("stored_byte_count"),
                        result.getBoolean("truncated"),
                        instant(result.getLong("stored_at"))),
                logRef).stream().findFirst();
    }

    public FinalizeHeadResult finalizeHeadSnapshot(String prId, String headSha)
    {
        requireText(prId, "prId");
        requireText(headSha, "headSha");
        return requireNonNull(transactions.execute(ignored -> finalizeHeadInTransaction(prId, headSha)),
                "finalize transaction returned null");
    }

    FinalizeHeadResult finalizeProviderBatch(
            String sourceOperationId,
            String sourceReceiptId,
            String prId,
            String headSha,
            String policyRevisionId,
            List<String> exactObservationIds)
    {
        requireText(sourceOperationId, "sourceOperationId");
        requireText(sourceReceiptId, "sourceReceiptId");
        requireText(prId, "prId");
        requireText(headSha, "headSha");
        requireText(policyRevisionId, "policyRevisionId");
        requireNonNull(exactObservationIds, "exactObservationIds is null");
        if (exactObservationIds.size()
                        != new HashSet<>(exactObservationIds).size()) {
            throw new IllegalArgumentException(
                    "provider batch observation IDs are invalid");
        }
        ObservationSource source = new ObservationSource(
                sourceOperationId, sourceReceiptId);
        return requireNonNull(transactions.execute(ignored -> {
            PublishedPrSubject subject = requireSubject(prId);
            supersedeOldHeadRounds(subject);
            if (!subject.currentRemoteHead().equals(headSha)) {
                return new FinalizeBlocked(
                        FinalizeBlocker.STALE_REMOTE_HEAD,
                        "Current remote head is " + subject.currentRemoteHead());
            }
            RequiredCiPolicyRevision policy = currentPolicy(
                    subject.repositoryId(), subject.scopeKey()).orElseThrow(() ->
                            new IllegalStateException(
                                    "Current CI policy is unavailable"));
            if (!policy.policyRevisionId().equals(policyRevisionId)
                    || policy.resolution() != PolicyResolution.RESOLVED
                    || !policy.targetBaseRef().equals(
                            subject.targetBaseRef())) {
                throw new IllegalStateException(
                        "provider batch CI policy is no longer current");
            }
            List<CiCheckObservation> exact = exactObservationIds.stream()
                    .map(id -> observation(id).orElseThrow(() ->
                            new IllegalStateException(
                                    "provider batch observation is missing: " + id)))
                    .toList();
            for (CiCheckObservation observation : exact) {
                if (!observation.prId().equals(prId)
                        || !observation.check().headSha().equals(headSha)
                        || !Objects.equals(
                                observation.sourceOperationId(),
                                source.operationId())
                        || !Objects.equals(
                                observation.sourceReceiptId(),
                                source.receiptId())) {
                    throw new IllegalStateException(
                            "provider batch mixes observation authority");
                }
            }
            return storeFinalizedRound(
                    subject, headSha, policy,
                    calculateRound(exact, policy), source);
        }), "provider batch finalize transaction returned null");
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

    /**
     * Freezes the exact failing log set and queues one current red evidence
     * revision. The runtime inbox write is deliberately owned by the
     * coordinator so both owner changes can share one database transaction.
     */
    CiRound queueCurrentFinalRed(String roundId)
    {
        requireText(roundId, "roundId");
        return requireNonNull(transactions.execute(ignored -> {
            CiRound requested = roundById(roundId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown CI round: " + roundId));
            if (requested.state() != RoundState.FINAL_RED
                    && requested.state() != RoundState.QUEUED) {
                throw new IllegalStateException(
                        "CI round is not queueable: " + requested.state());
            }
            PublishedPrSubject subject = requireSubject(requested.prId());
            if (!subject.taskId().equals(requested.taskId())
                    || !subject.currentRemoteHead().equals(requested.remoteHead())) {
                throw new IllegalStateException(
                        "CI round is not the current PR head");
            }
            RequiredCiPolicyRevision policy = currentPolicy(
                    subject.repositoryId(), subject.scopeKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Current CI policy is unavailable"));
            if (!policy.policyRevisionId().equals(requested.policyRevisionId())
                    || policy.resolution() != PolicyResolution.RESOLVED) {
                throw new IllegalStateException(
                        "CI round policy is no longer current");
            }
            CiRound latest = round(
                    requested.prId(),
                    requested.remoteHead(),
                    requested.policyRevisionId()).orElseThrow();
            RoundCalculation calculation = calculateStoredRound(
                    requested, policy);
            if (!latest.roundId().equals(roundId)
                    || calculation.state() != RoundState.FINAL_RED
                    || !calculation.observationIds().equals(
                            requested.checkObservationIds())) {
                throw new IllegalStateException(
                        "CI observations no longer match this red round");
            }
            List<String> failedLogs = requiredFailedLogs(requested, policy);
            if (failedLogs.isEmpty()) {
                throw new IllegalStateException(
                        "FINAL_RED has no failed required log evidence");
            }
            if (requested.state() == RoundState.QUEUED) {
                if (!requested.failedLogRefs().equals(failedLogs)) {
                    throw new IllegalStateException(
                            "Queued CI round has stale failed log evidence");
                }
                return requested;
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_ci_round
                    SET failed_log_refs_json = ?, state = 'QUEUED'
                    WHERE round_id = ? AND state = 'FINAL_RED'
                    """,
                    writeJson(failedLogs),
                    roundId);
            if (updated != 1) {
                throw new IllegalStateException(
                        "CI round changed while it was queued");
            }
            return roundById(roundId).orElseThrow();
        }), "queue transaction returned null");
    }

    /** Disposes only an unselected stale queued round. */
    CiRound supersedeQueuedRound(String roundId)
    {
        requireText(roundId, "roundId");
        return requireNonNull(transactions.execute(ignored -> {
            int updated = jdbc.update(
                    """
                    UPDATE flow_ci_round
                    SET state = 'SUPERSEDED'
                    WHERE round_id = ? AND state = 'QUEUED'
                    """,
                    roundId);
            if (updated != 1) {
                CiRound current = roundById(roundId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown CI round: " + roundId));
                if (current.state() == RoundState.SUPERSEDED) {
                    return current;
                }
                throw new IllegalStateException(
                        "CI round is not stale QUEUED work: "
                                + current.state());
            }
            return roundById(roundId).orElseThrow();
        }), "supersede transaction returned null");
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
        RoundCalculation current = calculateStoredRound(round, policy);
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
                ORDER BY evidence_revision DESC
                LIMIT 1
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

        Optional<CiRound> latest = round(
                prId, headSha, policy.policyRevisionId());
        if (latest.isPresent()
                && latest.orElseThrow().sourceObservationOperationId()
                        != null) {
            CiRound sourced = latest.orElseThrow();
            RoundCalculation exact = calculateStoredRound(sourced, policy);
            if (!sameFrozenEvidence(sourced, exact)) {
                throw new IllegalStateException(
                        "provider-sourced CI round evidence is inconsistent");
            }
            return new FinalizedRound(sourced, false);
        }
        Integer sourcedFacts = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_ci_check_observation
                WHERE pr_id = ? AND head_sha = ?
                  AND source_operation_id IS NOT NULL
                """,
                Integer.class, prId, headSha);
        if (latest.isEmpty() && requireNonNull(
                sourcedFacts, "sourced observation count is null") > 0) {
            return new FinalizeBlocked(
                    FinalizeBlocker.CI_OBSERVATION_PENDING,
                    "Current CI policy awaits its next exhaustive provider poll");
        }
        RoundCalculation calculation = calculateRound(prId, headSha, policy);
        return storeFinalizedRound(
                subject, headSha, policy, calculation, null);
    }

    private FinalizeHeadResult storeFinalizedRound(
            PublishedPrSubject subject,
            String headSha,
            RequiredCiPolicyRevision policy,
            RoundCalculation calculation,
            ObservationSource source)
    {
        Optional<CiRound> before = round(
                subject.prId(), headSha, policy.policyRevisionId());
        CiRound stored = storeRound(
                subject, headSha, policy, calculation, source);
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
                subject.prId(),
                headSha,
                policy.policyRevisionId());
        boolean newlyFinal = stored.state() == RoundState.FINAL_RED
                && before.filter(previous -> previous.roundId().equals(stored.roundId()))
                        .map(CiRound::state)
                        .orElse(null) != RoundState.FINAL_RED;
        return new FinalizedRound(stored, newlyFinal);
    }

    private RoundCalculation calculateRound(
            String prId, String headSha, RequiredCiPolicyRevision policy)
    {
        return calculateRound(observations(prId, headSha), policy);
    }

    private RoundCalculation calculateRound(
            List<CiCheckObservation> exactObservations,
            RequiredCiPolicyRevision policy)
    {
        Map<String, List<CiCheckObservation>> observationsBySelector = new HashMap<>();
        for (CiCheckObservation observation : exactObservations) {
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

    private RoundCalculation calculateStoredRound(
            CiRound round, RequiredCiPolicyRevision policy)
    {
        if (round.sourceObservationOperationId() == null) {
            return calculateRound(round.prId(), round.remoteHead(), policy);
        }
        List<CiCheckObservation> exact = round.checkObservationIds().stream()
                .map(id -> observation(id).orElseThrow(() ->
                        new IllegalStateException(
                                "sourced CI round observation is missing")))
                .toList();
        for (CiCheckObservation observation : exact) {
            if (!observation.prId().equals(round.prId())
                    || !observation.check().headSha().equals(
                            round.remoteHead())
                    || !Objects.equals(
                            observation.sourceOperationId(),
                            round.sourceObservationOperationId())
                    || !Objects.equals(
                            observation.sourceReceiptId(),
                            round.sourceReceiptId())) {
                throw new IllegalStateException(
                        "sourced CI round mixes observation authority");
            }
        }
        return calculateRound(exact, policy);
    }

    private CiRound storeRound(
            PublishedPrSubject subject,
            String headSha,
            RequiredCiPolicyRevision policy,
            RoundCalculation calculation)
    {
        return storeRound(subject, headSha, policy, calculation, null);
    }

    private CiRound storeRound(
            PublishedPrSubject subject,
            String headSha,
            RequiredCiPolicyRevision policy,
            RoundCalculation calculation,
            ObservationSource source)
    {
        Optional<CiRound> existing = round(subject.prId(), headSha, policy.policyRevisionId());
        if (existing.isEmpty()) {
            return insertRound(
                    subject, headSha, policy, calculation, source, 0);
        }
        CiRound current = existing.get();
        if (sameSource(current, source)
                && canRefreshEvidence(current.state())) {
            jdbc.update(
                    """
                    UPDATE flow_ci_round
                    SET check_observation_ids_json = ?, state = ?, superseded_by = NULL
                    WHERE round_id = ?
                      AND state = 'COLLECTING'
                    """,
                    writeJson(calculation.observationIds()),
                    calculation.state().name(),
                    current.roundId());
            return roundById(current.roundId()).orElseThrow();
        }
        if (sameFrozenEvidence(current, calculation)
                && sameSource(current, source)) {
            return current;
        }

        CiRound successor = insertRound(
                subject,
                headSha,
                policy,
                calculation,
                source,
                current.evidenceRevision() + 1);
        if (current.state() != RoundState.SUPERSEDED) {
            int superseded = jdbc.update(
                    """
                    UPDATE flow_ci_round
                    SET state = 'SUPERSEDED', superseded_by = ?
                    WHERE round_id = ?
                      AND state IN (
                          'COLLECTING', 'FINAL_RED', 'QUEUED', 'ACTIVE', 'FIX_PREPARED',
                          'GREEN', 'NEEDS_ATTENTION'
                      )
                    """,
                    successor.roundId(),
                    current.roundId());
            if (superseded != 1) {
                throw new IllegalStateException(
                        "CI evidence changed while superseding "
                                + current.roundId());
            }
        }
        return successor;
    }

    private CiRound insertRound(
            PublishedPrSubject subject,
            String headSha,
            RequiredCiPolicyRevision policy,
            RoundCalculation calculation,
            ObservationSource source,
            long evidenceRevision)
    {
        String id = stableId(
                "ci-round",
                subject.prId(),
                headSha,
                policy.policyRevisionId(),
                Long.toString(evidenceRevision));
        int inserted = jdbc.update(
                """
                INSERT OR IGNORE INTO flow_ci_round (
                    round_id, task_id, pr_id, remote_head,
                    policy_revision_id, evidence_revision,
                    source_observation_operation_id, source_receipt_id,
                    check_observation_ids_json, failed_log_refs_json,
                    state, created_at, superseded_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '[]', ?, ?, NULL)
                """,
                id,
                subject.taskId(),
                subject.prId(),
                headSha,
                policy.policyRevisionId(),
                evidenceRevision,
                source == null ? null : source.operationId(),
                source == null ? null : source.receiptId(),
                writeJson(calculation.observationIds()),
                calculation.state().name(),
                clock.instant().toEpochMilli());
        CiRound stored = roundById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Round was not stored: " + id));
        if (inserted == 0
                && (!stored.checkObservationIds().equals(calculation.observationIds())
                || !sameSource(stored, source)
                || stored.state() != calculation.state())) {
            throw new IllegalStateException(
                    "CI evidence revision identity has conflicting content");
        }
        return stored;
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
                            result.getString("source_operation_id"),
                            result.getString("source_receipt_id"),
                            check);
                },
                prId,
                headSha);
    }

    Optional<CiCheckObservation> observation(String observationId)
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
                            result.getString("source_operation_id"),
                            result.getString("source_receipt_id"),
                            check);
                },
                observationId).stream().findFirst();
    }

    public Optional<CiRound> roundById(String roundId)
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

    Optional<CiRepairAttempt> repairAttempt(String attemptId)
    {
        requireText(attemptId, "attemptId");
        return jdbc.query(
                "SELECT * FROM flow_ci_repair_attempt WHERE attempt_id = ?",
                (result, row) -> readRepairAttempt(result),
                attemptId).stream().findFirst();
    }

    Optional<CiRepairAttempt> repairAttemptForRound(
            String roundId, long retryOrdinal)
    {
        requireText(roundId, "roundId");
        return jdbc.query(
                """
                SELECT * FROM flow_ci_repair_attempt
                WHERE round_id = ? AND retry_ordinal = ?
                """,
                (result, row) -> readRepairAttempt(result),
                roundId,
                retryOrdinal).stream().findFirst();
    }

    CiRepairAttempt bindRepairAttempt(
            CiRound round,
            String operationId,
            String runId,
            String inputLocalHead,
            String inputChangeSetRevisionId)
    {
        requireNonNull(round, "round is null");
        requireText(operationId, "operationId");
        requireText(runId, "runId");
        requireText(inputLocalHead, "inputLocalHead");
        requireText(inputChangeSetRevisionId,
                "inputChangeSetRevisionId");
        return requireNonNull(transactions.execute(ignored -> {
            String attemptId = stableId(
                    "ci-repair-attempt", round.roundId(), "0");
            Optional<CiRepairAttempt> existing = repairAttempt(attemptId);
            if (existing.isPresent()) {
                CiRepairAttempt attempt = existing.get();
                if (!attempt.roundId().equals(round.roundId())
                        || !attempt.operationId().equals(operationId)
                        || !attempt.agentRunId().equals(runId)
                        || !attempt.inputLocalHead().equals(inputLocalHead)
                        || !attempt.inputRemoteHead().equals(
                                round.remoteHead())
                        || !attempt.inputChangeSetRevisionId().equals(
                                inputChangeSetRevisionId)
                        || attempt.retryOrdinal() != 0) {
                    throw new IllegalStateException(
                            "CI repair attempt redelivery changed identity");
                }
                if (attempt.state() != AttemptState.ACTIVE
                        || roundById(round.roundId()).orElseThrow().state()
                                != RoundState.ACTIVE) {
                    throw new IllegalStateException(
                            "CI repair attempt is no longer active");
                }
                return attempt;
            }
            if (round.state() != RoundState.QUEUED) {
                throw new IllegalStateException(
                        "CI repair round is not queued");
            }
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_ci_repair_attempt (
                        attempt_id, round_id, operation_id, agent_run_id,
                        input_local_head, input_remote_head,
                        input_change_set_revision_id, local_check_run_ids_json,
                        state, retry_ordinal, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, '[]', 'ACTIVE', 0, ?)
                    """,
                    attemptId,
                    round.roundId(),
                    operationId,
                    runId,
                    inputLocalHead,
                    round.remoteHead(),
                    inputChangeSetRevisionId,
                    now.toEpochMilli());
            int activated = jdbc.update(
                    """
                    UPDATE flow_ci_round SET state = 'ACTIVE'
                    WHERE round_id = ? AND state = 'QUEUED'
                    """,
                    round.roundId());
            if (activated != 1) {
                throw new IllegalStateException(
                        "CI round changed during repair binding");
            }
            return repairAttempt(attemptId).orElseThrow();
        }), "repair binding transaction returned null");
    }

    CiRepairAttempt completeRepairAttempt(
            String attemptId,
            String outputLocalHead,
            String outputChangeSetRevisionId,
            String resultRef,
            AttemptState outcome)
    {
        requireText(attemptId, "attemptId");
        requireText(outputLocalHead, "outputLocalHead");
        requireText(outputChangeSetRevisionId,
                "outputChangeSetRevisionId");
        requireText(resultRef, "resultRef");
        requireNonNull(outcome, "outcome is null");
        if (outcome != AttemptState.FIX_PREPARED
                && outcome != AttemptState.NO_HEAD_CHANGE) {
            throw new IllegalArgumentException(
                    "CI repair completion requires a clean outcome");
        }
        return requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt attempt = repairAttempt(attemptId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown CI repair attempt: " + attemptId));
            if (attempt.state() == AttemptState.FIX_PREPARED
                    || attempt.state() == AttemptState.NO_HEAD_CHANGE) {
                if (!Objects.equals(
                                attempt.outputLocalHead(), outputLocalHead)
                        || !Objects.equals(
                                attempt.outputChangeSetRevisionId(),
                                outputChangeSetRevisionId)
                        || !Objects.equals(attempt.resultRef(), resultRef)
                        || attempt.state() != outcome) {
                    throw new IllegalStateException(
                            "CI repair finalization changed identity");
                }
                return attempt;
            }
            if (attempt.state() != AttemptState.ACTIVE) {
                throw new IllegalStateException(
                        "CI repair attempt is not finalizable");
            }
            int attemptUpdated = jdbc.update(
                    """
                    UPDATE flow_ci_repair_attempt
                    SET output_local_head = ?,
                        output_change_set_revision_id = ?, result_ref = ?,
                        state = ?
                    WHERE attempt_id = ? AND state = 'ACTIVE'
                    """,
                    outputLocalHead,
                    outputChangeSetRevisionId,
                    resultRef,
                    outcome.name(),
                    attemptId);
            int roundUpdated = jdbc.update(
                    """
                    UPDATE flow_ci_round SET state = 'FIX_PREPARED'
                    WHERE round_id = ? AND state = 'ACTIVE'
                    """,
                    attempt.roundId());
            RoundState roundState = roundById(
                    attempt.roundId()).orElseThrow().state();
            if (attemptUpdated != 1
                    || (roundUpdated != 1
                    && roundState != RoundState.SUPERSEDED)) {
                throw new IllegalStateException(
                        "CI repair owner changed during finalization");
            }
            return repairAttempt(attemptId).orElseThrow();
        }), "repair completion transaction returned null");
    }

    Optional<CiCleanupSeal> cleanupSealForRepair(String repairAttemptId)
    {
        requireText(repairAttemptId, "repairAttemptId");
        return jdbc.query(
                "SELECT * FROM flow_ci_cleanup_seal WHERE repair_attempt_id = ?",
                (result, row) -> readCleanupSeal(result),
                repairAttemptId).stream().findFirst();
    }

    Optional<CiCleanupSeal> cleanupSeal(String cleanupId)
    {
        requireText(cleanupId, "cleanupId");
        return jdbc.query(
                "SELECT * FROM flow_ci_cleanup_seal WHERE cleanup_id = ?",
                (result, row) -> readCleanupSeal(result),
                cleanupId).stream().findFirst();
    }

    Optional<CiCleanupSeal> cleanupSealForSuccessor(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query(
                "SELECT * FROM flow_ci_cleanup_seal WHERE successor_operation_id = ?",
                (result, row) -> readCleanupSeal(result),
                operationId).stream().findFirst();
    }

    Optional<CiCleanupCompletion> cleanupCompletion(String cleanupId)
    {
        requireText(cleanupId, "cleanupId");
        return jdbc.query(
                "SELECT * FROM flow_ci_cleanup_completion WHERE cleanup_id = ?",
                (result, row) -> readCleanupCompletion(result),
                cleanupId).stream().findFirst();
    }

    CiCleanupCompletion storeCleanupCompletion(
            CiCleanupFinalizationReceipt receipt)
    {
        requireNonNull(receipt, "receipt is null");
        return requireNonNull(transactions.execute(ignored -> {
            CiCleanupSeal authoritativeSeal = cleanupSeal(receipt.cleanupId())
                    .orElseThrow(() -> new IllegalStateException(
                            "CI cleanup completion has no immutable seal"));
            if (!authoritativeSeal.successorOperationId().equals(
                    receipt.operationId())) {
                throw new IllegalStateException(
                        "CI cleanup receipt belongs to another operation");
            }
            CiCleanupCompletion completion = completionFromReceipt(receipt);
            Optional<CiCleanupCompletion> existing = cleanupCompletion(
                    completion.cleanupId());
            if (existing.isPresent()) {
                if (!existing.get().equals(completion)) {
                    throw new IllegalStateException(
                            "CI cleanup completion redelivery changed identity");
                }
                return existing.get();
            }
            CiCleanupSeal seal = authoritativeSeal;
            CiRepairAttempt predecessor = repairAttempt(
                    seal.repairAttemptId()).orElseThrow();
            if (predecessor.state() != AttemptState.NON_CLEAN_HANDOFF) {
                throw new IllegalStateException(
                        "CI cleanup predecessor is not terminally handed off");
            }
            assertCleanupCompletionRuntime(
                    seal, predecessor, completion);
            jdbc.update(
                    """
                    INSERT INTO flow_ci_cleanup_completion (
                        cleanup_id, run_id, result_ref, outcome,
                        output_head, output_change_set_revision_id,
                        final_actual_head, final_branch_head,
                        final_attachment_state, final_kind,
                        final_operations_json, final_state_digest,
                        attention_reason, inspection_failure_code,
                        completed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    completion.cleanupId(),
                    completion.runId(),
                    completion.resultRef(),
                    completion.outcome().name(),
                    completion.outputHead(),
                    completion.outputChangeSetRevisionId(),
                    completion.finalActualHead(),
                    completion.finalBranchHead(),
                    completion.finalAttachmentState() == null
                            ? null
                            : completion.finalAttachmentState().name(),
                    completion.finalKind() == null
                            ? null
                            : completion.finalKind().name(),
                    completion.finalStateDigest() == null
                            ? null
                            : writeJson(completion.finalOperations().stream()
                                    .map(GitOperation::name)
                                    .toList()),
                    completion.finalStateDigest(),
                    completion.attentionReason() == null
                            ? null
                            : completion.attentionReason().name(),
                    completion.inspectionFailureCode() == null
                            ? null
                            : completion.inspectionFailureCode().name(),
                    completion.completedAt().toEpochMilli());
            String roundState = switch (completion.outcome()) {
                case FIX_PREPARED, NO_HEAD_CHANGE -> "FIX_PREPARED";
                case NEEDS_ATTENTION, ADMISSION_BLOCKED -> "NEEDS_ATTENTION";
            };
            int roundUpdated = jdbc.update(
                    """
                    UPDATE flow_ci_round SET state = ?
                    WHERE round_id = ? AND state = 'ACTIVE'
                    """,
                    roundState,
                    predecessor.roundId());
            RoundState storedRound = roundById(
                    predecessor.roundId()).orElseThrow().state();
            if (roundUpdated != 1
                    && storedRound != RoundState.SUPERSEDED) {
                throw new IllegalStateException(
                        "CI cleanup round changed before completion");
            }
            return cleanupCompletion(completion.cleanupId()).orElseThrow();
        }), "cleanup completion transaction returned null");
    }

    private void assertCleanupCompletionRuntime(
            CiCleanupSeal seal,
            CiRepairAttempt predecessor,
            CiCleanupCompletion completion)
    {
        boolean admission = completion.outcome()
                == CleanupOutcome.ADMISSION_BLOCKED;
        String ownerSql;
        Object[] ownerArguments;
        String blockedRef = "CI_CLEANUP_ADMISSION_BLOCKED:"
                + seal.cleanupId();
        if (admission && completion.runId() != null) {
            ownerSql = """
                    SELECT COUNT(*)
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    JOIN flow_runtime_task t ON t.task_id = o.task_id
                    JOIN flow_runtime_agent_run r
                      ON r.operation_id = o.operation_id
                    JOIN flow_runtime_agent_result a ON a.run_id = r.run_id
                    JOIN flow_runtime_agent_session s
                      ON s.session_id = r.session_id
                    WHERE o.operation_id = ?
                      AND o.owner_kind = 'CI_CLEANUP'
                      AND o.owner_id = ? AND o.state = 'FAILED'
                      AND o.result_ref = ? AND d.delivery_state = 'DONE'
                      AND t.status = 'NEEDS_ATTENTION'
                      AND t.selected_writer_operation_id IS NULL
                      AND t.waiting_mutation_state_ref = ?
                      AND r.run_id = ? AND r.state = 'CANCELED'
                      AND a.result_id = ? AND a.terminal_outcome = 'CANCELED'
                      AND a.final_content IS NULL AND a.error_ref = ?
                      AND a.stop_proof_ref = ?
                      AND s.state = 'IDLE' AND s.last_run_id = r.run_id
                      AND NOT EXISTS (
                        SELECT 1 FROM flow_runtime_agent_process_attempt p
                        WHERE p.run_id = r.run_id)
                    """;
            ownerArguments = new Object[] {
                seal.successorOperationId(),
                seal.cleanupId(),
                completion.resultRef(),
                cleanupAttentionRef(seal.cleanupId()),
                completion.runId(),
                completion.resultRef(),
                blockedRef,
                stableId(
                        "never-launched-stop",
                        seal.cleanupId(),
                        completion.runId())};
        }
        else if (admission) {
            ownerSql = """
                    SELECT COUNT(*)
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    JOIN flow_runtime_task t ON t.task_id = o.task_id
                    WHERE o.operation_id = ?
                      AND o.owner_kind = 'CI_CLEANUP'
                      AND o.owner_id = ? AND o.state = 'FAILED'
                      AND o.result_ref = ? AND d.delivery_state = 'DONE'
                      AND t.status = 'NEEDS_ATTENTION'
                      AND t.selected_writer_operation_id IS NULL
                      AND t.waiting_mutation_state_ref = ?
                    """;
            ownerArguments = new Object[] {
                seal.successorOperationId(),
                seal.cleanupId(),
                blockedRef,
                cleanupAttentionRef(seal.cleanupId())};
        }
        else {
            ownerSql = """
                    SELECT COUNT(*)
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    JOIN flow_runtime_agent_run r
                      ON r.operation_id = o.operation_id
                    JOIN flow_runtime_agent_result a ON a.run_id = r.run_id
                    WHERE o.operation_id = ?
                      AND o.owner_kind = 'CI_CLEANUP'
                      AND o.owner_id = ? AND o.result_ref = ?
                      AND d.delivery_state = 'DONE'
                      AND r.run_id = ? AND a.result_id = ?
                    """;
            ownerArguments = new Object[] {
                seal.successorOperationId(),
                seal.cleanupId(),
                completion.resultRef(),
                completion.runId(),
                completion.resultRef()};
        }
        Integer ownerMatches = jdbc.queryForObject(
                ownerSql, Integer.class, ownerArguments);
        if (requireNonNull(ownerMatches, "cleanup runtime owner count is null")
                != 1) {
            throw new IllegalStateException(
                    "CI cleanup completion is not runtime-owned");
        }
        boolean clean = completion.outcome() == CleanupOutcome.FIX_PREPARED
                || completion.outcome() == CleanupOutcome.NO_HEAD_CHANGE;
        if (clean) {
            boolean noChange = completion.outputHead().equals(
                    predecessor.inputLocalHead());
            if (noChange != (completion.outcome()
                    == CleanupOutcome.NO_HEAD_CHANGE)) {
                throw new IllegalStateException(
                        "CI cleanup outcome contradicts its objective head");
            }
            Integer outputMatches = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flow_runtime_change_set_revision c
                    JOIN flow_runtime_task t ON t.task_id = c.task_id
                    JOIN flow_runtime_inbox i
                      ON i.agent_result_id = ?
                    WHERE c.change_set_revision_id = ? AND c.head_sha = ?
                      AND c.previous_change_set_revision_id = ?
                      AND c.previous_head_sha = ?
                      AND c.source = 'CI_FIXER'
                      AND c.source_operation_id = ? AND c.source_run_id = ?
                      AND t.current_change_set_revision_id = c.change_set_revision_id
                      AND t.current_head_sha = c.head_sha
                      AND t.selected_writer_operation_id IS NULL
                      AND t.waiting_mutation_state_ref IS NULL
                      AND i.kind = 'CI_FIX_READY'
                      AND i.external_key = ? AND i.subject_head = ?
                      AND i.payload_ref = ?
                    """,
                    Integer.class,
                    completion.resultRef(),
                    completion.outputChangeSetRevisionId(),
                    completion.outputHead(),
                    predecessor.inputChangeSetRevisionId(),
                    predecessor.inputLocalHead(),
                    seal.successorOperationId(),
                    completion.runId(),
                    seal.cleanupId(),
                    completion.outputHead(),
                    cleanupReadyPayload(completion));
            if (requireNonNull(
                    outputMatches, "cleanup output owner count is null") != 1) {
                throw new IllegalStateException(
                        "CI cleanup output is not mechanically adopted");
            }
        }
        else if (!admission) {
            Integer attentionMatches = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_runtime_task t
                    JOIN flow_runtime_operation o ON o.task_id = t.task_id
                    WHERE o.operation_id = ?
                      AND t.status = 'NEEDS_ATTENTION'
                      AND t.selected_writer_operation_id IS NULL
                      AND t.waiting_mutation_state_ref = ?
                    """,
                    Integer.class,
                    seal.successorOperationId(),
                    cleanupAttentionRef(seal.cleanupId()));
            if (requireNonNull(
                    attentionMatches, "cleanup attention count is null") != 1) {
                throw new IllegalStateException(
                        "CI cleanup attention is not durably fenced");
            }
        }
    }

    private static String cleanupReadyPayload(CiCleanupCompletion completion)
    {
        String outcome = completion.outcome() == CleanupOutcome.NO_HEAD_CHANGE
                ? CiFixOutcome.NO_HEAD_CHANGE.name()
                : CiFixOutcome.FIX_PREPARED.name();
        return "ci-cleanup:" + completion.cleanupId() + ":outcome:" + outcome
                + ":change-set:" + completion.outputChangeSetRevisionId();
    }

    private static CiCleanupCompletion completionFromReceipt(
            CiCleanupFinalizationReceipt receipt)
    {
        AgentResult result = receipt.result().orElse(null);
        CiFixOutcome clean = receipt.cleanOutcome().orElse(null);
        NonCleanInspection state = receipt.finalState().orElse(null);
        FailureCode failure = receipt.failureCode().orElse(null);
        CleanupOutcome outcome;
        CleanupAttentionReason reason = null;
        if (clean != null) {
            outcome = clean == CiFixOutcome.NO_HEAD_CHANGE
                    ? CleanupOutcome.NO_HEAD_CHANGE
                    : CleanupOutcome.FIX_PREPARED;
        }
        else if (receipt.admissionBlocked()) {
            outcome = CleanupOutcome.ADMISSION_BLOCKED;
            reason = state == null
                    ? CleanupAttentionReason.ADMISSION_INSPECTION_BLOCKED
                    : CleanupAttentionReason.ADMISSION_SEAL_MISMATCH;
        }
        else {
            if (result == null) {
                throw new IllegalStateException(
                        "cleanup attention receipt has no AgentResult");
            }
            outcome = CleanupOutcome.NEEDS_ATTENTION;
            if (state == null) {
                reason = CleanupAttentionReason.FINAL_INSPECTION_BLOCKED;
            }
            else {
                reason = state.kind() == NonCleanKind.DIRTY
                        ? CleanupAttentionReason.SECOND_DIRTY
                        : CleanupAttentionReason
                                .SECOND_GIT_OPERATION_IN_PROGRESS;
            }
        }
        return new CiCleanupCompletion(
                receipt.cleanupId(),
                result == null ? null : result.runId(),
                result == null ? null : result.resultId(),
                outcome,
                receipt.outputHead().orElse(null),
                receipt.outputChangeSetRevisionId().orElse(null),
                state == null ? null : state.actualHeadSha(),
                state == null ? null : state.branchHeadSha(),
                state == null ? null : state.attachmentState(),
                state == null ? null : state.kind(),
                state == null ? null : state.operations(),
                state == null ? null : state.stateDigest(),
                reason,
                failure,
                receipt.completedAt());
    }

    private static String cleanupAttentionRef(String cleanupId)
    {
        return "ci-cleanup-attention:" + cleanupId;
    }

    CiCleanupSeal storeCleanupSeal(
            String repairAttemptId, CleanupHandoff handoff)
    {
        requireText(repairAttemptId, "repairAttemptId");
        requireNonNull(handoff, "handoff is null");
        return requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt attempt = repairAttempt(repairAttemptId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown CI repair attempt: " + repairAttemptId));
            Optional<CiCleanupSeal> existing = cleanupSealForRepair(
                    repairAttemptId);
            if (existing.isPresent()) {
                CiCleanupSeal seal = existing.get();
                if (attempt.state() != AttemptState.NON_CLEAN_HANDOFF
                        || !Objects.equals(attempt.resultRef(),
                                handoff.predecessorResult().resultId())
                        || !seal.cleanupId().equals(handoff.cleanupId())
                        || !seal.successorOperationId().equals(
                                handoff.successorOperation().operationId())
                        || !sameSeal(seal, handoff.sealedState())) {
                    throw new IllegalStateException(
                            "CI cleanup handoff redelivery changed identity");
                }
                return seal;
            }
            if (attempt.state() != AttemptState.ACTIVE
                    || !attempt.agentRunId().equals(
                            handoff.predecessorResult().runId())) {
                throw new IllegalStateException(
                        "CI repair attempt is not cleanup-reservable");
            }
            RoundState roundState = roundById(
                    attempt.roundId()).orElseThrow().state();
            if (roundState != RoundState.ACTIVE
                    && roundState != RoundState.SUPERSEDED) {
                throw new IllegalStateException(
                        "CI cleanup round is not active or superseded");
            }
            NonCleanInspection state = handoff.sealedState();
            String cleanupId = stableId("ci-cleanup-seal", repairAttemptId);
            if (!cleanupId.equals(handoff.cleanupId())) {
                throw new IllegalStateException(
                        "runtime cleanup identity is not canonical");
            }
            jdbc.update(
                    """
                    INSERT INTO flow_ci_cleanup_seal (
                        cleanup_id, repair_attempt_id,
                        successor_operation_id, actual_head, branch_head,
                        attachment_state, kind, operations_json,
                        state_digest, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    cleanupId,
                    repairAttemptId,
                    handoff.successorOperation().operationId(),
                    state.actualHeadSha(),
                    state.branchHeadSha(),
                    state.attachmentState().name(),
                    state.kind().name(),
                    writeJson(state.operations().stream()
                            .map(GitOperation::name)
                            .toList()),
                    state.stateDigest(),
                    clock.instant().toEpochMilli());
            int attemptUpdated = jdbc.update(
                    """
                    UPDATE flow_ci_repair_attempt
                    SET result_ref = ?, state = 'NON_CLEAN_HANDOFF'
                    WHERE attempt_id = ? AND state = 'ACTIVE'
                      AND result_ref IS NULL
                    """,
                    handoff.predecessorResult().resultId(),
                    repairAttemptId);
            if (attemptUpdated != 1) {
                throw new IllegalStateException(
                        "CI repair attempt changed during cleanup reservation");
            }
            return cleanupSealForRepair(repairAttemptId).orElseThrow();
        }), "cleanup seal transaction returned null");
    }

    CiRepairAttempt blockDirtyRepairAttempt(String attemptId)
    {
        requireText(attemptId, "attemptId");
        return requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt attempt = repairAttempt(attemptId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown CI repair attempt: " + attemptId));
            if (attempt.state() == AttemptState.NEEDS_ATTENTION) {
                return attempt;
            }
            if (attempt.state() != AttemptState.ACTIVE) {
                throw new IllegalStateException(
                        "CI repair attempt is not dirty-blockable");
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_ci_repair_attempt SET state = 'NEEDS_ATTENTION'
                    WHERE attempt_id = ? AND state = 'ACTIVE'
                    """,
                    attemptId);
            jdbc.update(
                    """
                    UPDATE flow_ci_round SET state = 'NEEDS_ATTENTION'
                    WHERE round_id = ? AND state = 'ACTIVE'
                    """,
                    attempt.roundId());
            if (updated != 1) {
                throw new IllegalStateException(
                        "CI dirty repair owner changed");
            }
            return repairAttempt(attemptId).orElseThrow();
        }), "dirty repair transaction returned null");
    }

    private Optional<CiLogEvidence> logForObservation(String observationId)
    {
        return jdbc.query(
                """
                SELECT log_ref, observation_id, content_digest,
                       exposed_content_digest, raw_byte_count,
                       stored_byte_count, truncated, stored_at
                FROM flow_ci_log_evidence
                WHERE observation_id = ?
                """,
                (result, row) -> new CiLogEvidence(
                        result.getString("log_ref"),
                        result.getString("observation_id"),
                        result.getString("content_digest"),
                        result.getString("exposed_content_digest"),
                        result.getLong("raw_byte_count"),
                        result.getLong("stored_byte_count"),
                        result.getBoolean("truncated"),
                        instant(result.getLong("stored_at"))),
                observationId).stream().findFirst();
    }

    private List<String> requiredFailedLogs(
            CiRound round, RequiredCiPolicyRevision policy)
    {
        return round.checkObservationIds().stream()
                .map(id -> observation(id).orElseThrow())
                .filter(value -> !policy.acceptedConclusions().contains(
                        normalizeNullableToken(value.check().conclusion())))
                .map(value -> logForObservation(value.observationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing bounded log for failed observation "
                                        + value.observationId()))
                        .logRef())
                .toList();
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
                result.getLong("evidence_revision"),
                result.getString("source_observation_operation_id"),
                result.getString("source_receipt_id"),
                readStringList(result.getString("check_observation_ids_json")),
                readStringList(result.getString("failed_log_refs_json")),
                RoundState.valueOf(result.getString("state")),
                instant(result.getLong("created_at")),
                result.getString("superseded_by"));
    }

    private CiRepairAttempt readRepairAttempt(ResultSet result)
            throws SQLException
    {
        return new CiRepairAttempt(
                result.getString("attempt_id"),
                result.getString("round_id"),
                result.getString("operation_id"),
                result.getString("agent_run_id"),
                result.getString("input_local_head"),
                result.getString("input_remote_head"),
                result.getString("input_change_set_revision_id"),
                result.getString("output_local_head"),
                result.getString("output_change_set_revision_id"),
                readStringList(result.getString("local_check_run_ids_json")),
                result.getString("result_ref"),
                AttemptState.valueOf(result.getString("state")),
                result.getString("retry_of_attempt_id"),
                result.getLong("retry_ordinal"),
                instant(result.getLong("created_at")));
    }

    private CiCleanupSeal readCleanupSeal(ResultSet result)
            throws SQLException
    {
        return new CiCleanupSeal(
                result.getString("cleanup_id"),
                result.getString("repair_attempt_id"),
                result.getString("successor_operation_id"),
                result.getString("actual_head"),
                result.getString("branch_head"),
                AttachmentState.valueOf(result.getString("attachment_state")),
                NonCleanKind.valueOf(result.getString("kind")),
                readStringList(result.getString("operations_json")).stream()
                        .map(GitOperation::valueOf)
                        .toList(),
                result.getString("state_digest"),
                instant(result.getLong("created_at")));
    }

    private CiCleanupCompletion readCleanupCompletion(ResultSet result)
            throws SQLException
    {
        String operations = result.getString("final_operations_json");
        String attachment = result.getString("final_attachment_state");
        String kind = result.getString("final_kind");
        String reason = result.getString("attention_reason");
        String failure = result.getString("inspection_failure_code");
        return new CiCleanupCompletion(
                result.getString("cleanup_id"),
                result.getString("run_id"),
                result.getString("result_ref"),
                CleanupOutcome.valueOf(result.getString("outcome")),
                result.getString("output_head"),
                result.getString("output_change_set_revision_id"),
                result.getString("final_actual_head"),
                result.getString("final_branch_head"),
                attachment == null ? null : AttachmentState.valueOf(attachment),
                kind == null ? null : NonCleanKind.valueOf(kind),
                operations == null
                        ? null
                        : readStringList(operations).stream()
                                .map(GitOperation::valueOf)
                                .toList(),
                result.getString("final_state_digest"),
                reason == null
                        ? null
                        : CleanupAttentionReason.valueOf(reason),
                failure == null ? null : FailureCode.valueOf(failure),
                instant(result.getLong("completed_at")));
    }

    private static boolean sameSeal(
            CiCleanupSeal seal, NonCleanInspection state)
    {
        return seal.actualHead().equals(state.actualHeadSha())
                && seal.branchHead().equals(state.branchHeadSha())
                && seal.attachmentState() == state.attachmentState()
                && seal.kind() == state.kind()
                && seal.operations().equals(state.operations())
                && seal.stateDigest().equals(state.stateDigest());
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
        return state == RoundState.COLLECTING;
    }

    private static boolean sameFrozenEvidence(
            CiRound round, RoundCalculation calculation)
    {
        RoundState calculated = calculation.state();
        boolean sameState = round.state() == calculated
                || ((round.state() == RoundState.QUEUED
                        || round.state() == RoundState.ACTIVE
                        || round.state() == RoundState.FIX_PREPARED)
                        && calculated == RoundState.FINAL_RED);
        return sameState
                && round.checkObservationIds().equals(calculation.observationIds());
    }

    private static boolean sameSource(
            CiRound round, ObservationSource source)
    {
        return Objects.equals(
                        round.sourceObservationOperationId(),
                        source == null ? null : source.operationId())
                && Objects.equals(
                        round.sourceReceiptId(),
                        source == null ? null : source.receiptId());
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

    private static String sha256(byte[] value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    private static byte[] sanitizeLog(byte[] raw, List<String> literalSecrets)
    {
        String decoded = new String(raw, StandardCharsets.UTF_8);
        StringBuilder sanitized = new StringBuilder(decoded.length());
        decoded.codePoints().forEach(value -> {
            if (value == '\n' || value == '\r' || value == '\t'
                    || (!Character.isISOControl(value) && value != 0x7F)) {
                sanitized.appendCodePoint(value);
            }
        });
        String redacted = AUTHORIZATION_SECRET.matcher(sanitized)
                .replaceAll("$1[REDACTED]");
        redacted = GITHUB_TOKEN.matcher(redacted)
                .replaceAll("[REDACTED_GITHUB_TOKEN]");
        redacted = AWS_ACCESS_KEY.matcher(redacted)
                .replaceAll("[REDACTED_AWS_ACCESS_KEY]");
        redacted = URL_CREDENTIAL.matcher(redacted)
                .replaceAll("$1[REDACTED]$3");
        redacted = PRIVATE_KEY.matcher(redacted)
                .replaceAll("[REDACTED_$1]");
        redacted = COMMON_SECRET_ASSIGNMENT.matcher(redacted)
                .replaceAll("$1[REDACTED]");
        redacted = ADD_MASK_COMMAND.matcher(redacted)
                .replaceAll("$1[REDACTED]");
        if (!literalSecrets.isEmpty()) {
            Pattern literals = Pattern.compile(String.join(
                    "|",
                    literalSecrets.stream()
                            .map(Pattern::quote)
                            .toList()));
            redacted = literals.matcher(redacted)
                    .replaceAll("[MASKED]");
        }
        return redacted.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> validateLiteralSecrets(List<String> values)
    {
        if (values.size() > MAX_LITERAL_SECRET_COUNT) {
            throw new IllegalArgumentException(
                    "too many program-known CI log secrets");
        }
        int totalLength = 0;
        TreeSet<String> unique = new TreeSet<>(
                Comparator.comparingInt(String::length).reversed()
                        .thenComparing(Comparator.naturalOrder()));
        for (String value : values) {
            requireText(value, "literal secret");
            if (value.length() < MIN_LITERAL_SECRET_LENGTH
                    || value.length() > MAX_LITERAL_SECRET_LENGTH) {
                throw new IllegalArgumentException(
                        "program-known CI log secret length is out of bounds");
            }
            totalLength += value.length();
            if (totalLength > MAX_LITERAL_SECRET_TOTAL_LENGTH) {
                throw new IllegalArgumentException(
                        "program-known CI log secrets are too large");
            }
            unique.add(value);
        }
        return List.copyOf(unique);
    }

    private static byte[] boundLog(byte[] sanitized)
    {
        if (sanitized.length <= MAX_STORED_LOG_BYTES) {
            return sanitized;
        }
        int remaining = MAX_STORED_LOG_BYTES - TRUNCATION_MARKER.length;
        int prefix = floorUtf8Boundary(sanitized, remaining / 2);
        int suffixStart = ceilUtf8Boundary(
                sanitized, sanitized.length - (remaining - prefix));
        int suffix = sanitized.length - suffixStart;
        byte[] bounded = new byte[prefix + TRUNCATION_MARKER.length + suffix];
        System.arraycopy(sanitized, 0, bounded, 0, prefix);
        System.arraycopy(
                TRUNCATION_MARKER, 0, bounded, prefix,
                TRUNCATION_MARKER.length);
        System.arraycopy(
                sanitized, suffixStart,
                bounded, prefix + TRUNCATION_MARKER.length, suffix);
        return bounded;
    }

    private static int floorUtf8Boundary(byte[] value, int offset)
    {
        int boundary = Math.min(offset, value.length);
        while (boundary > 0
                && boundary < value.length
                && isUtf8Continuation(value[boundary])) {
            boundary--;
        }
        return boundary;
    }

    private static int ceilUtf8Boundary(byte[] value, int offset)
    {
        int boundary = Math.max(0, offset);
        while (boundary < value.length
                && isUtf8Continuation(value[boundary])) {
            boundary++;
        }
        return boundary;
    }

    private static int completeUtf8PrefixLength(byte[] value)
    {
        if (value.length == 0) {
            return 0;
        }
        int codePointStart = value.length - 1;
        while (codePointStart > 0
                && isUtf8Continuation(value[codePointStart])) {
            codePointStart--;
        }
        int expectedLength = utf8SequenceLength(value[codePointStart]);
        if (expectedLength < 0) {
            throw new IllegalStateException("stored CI log is not valid UTF-8");
        }
        return value.length - codePointStart < expectedLength
                ? codePointStart
                : value.length;
    }

    private static int utf8SequenceLength(byte value)
    {
        int unsigned = value & 0xFF;
        if ((unsigned & 0x80) == 0) {
            return 1;
        }
        if ((unsigned & 0xE0) == 0xC0) {
            return 2;
        }
        if ((unsigned & 0xF0) == 0xE0) {
            return 3;
        }
        if ((unsigned & 0xF8) == 0xF0) {
            return 4;
        }
        return -1;
    }

    private static boolean isUtf8Continuation(byte value)
    {
        return (value & 0xC0) == 0x80;
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

    private record RoundCalculation(
            List<String> observationIds,
            RoundState state) {}

    private record ObservationSource(
            String operationId,
            String receiptId) {}

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
