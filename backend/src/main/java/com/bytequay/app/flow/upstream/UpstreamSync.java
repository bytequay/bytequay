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
package com.bytequay.app.flow.upstream;

import com.bytequay.app.flow.upstream.UpstreamSyncRecords.FixupKind;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PickState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RepairPlacementPolicy;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RequestState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.SelectedCommit;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamFixup;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamPick;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRequest;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

/**
 * Durable store for the greenfield upstream cherry-pick records.
 *
 * <p>Deliberately shares no lifecycle state with the retired
 * {@code upstream_cherry_pick_job} model: there is no identifier column
 * pointing at it, no dual write, and no translation. A run is discovered from
 * its flow Task and nowhere else.
 */
public final class UpstreamSync
{
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;

    public UpstreamSync(
            DataSource dataSource, ObjectMapper mapper, Clock clock)
    {
        requireNonNull(dataSource, "dataSource is null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Records the request and its run against an already-created Task.
     *
     * <p>Idempotent on {@code requestKey} so a repeated enqueue of the exact
     * same command returns the stored run instead of starting a second range
     * over the same Task.
     */
    public UpstreamSyncRun startRun(
            String requestKey,
            String repositoryId,
            String goalText,
            String sourceRemote,
            String sourceFromRef,
            String sourceToRef,
            String targetRef,
            List<SelectedCommit> selectedCommits,
            String requestedByUserId,
            String taskId,
            int repairTurnBudget)
    {
        requireText(requestKey, "requestKey");
        requireText(repositoryId, "repositoryId");
        requireText(goalText, "goalText");
        requireText(taskId, "taskId");
        List<SelectedCommit> selected = List.copyOf(requireNonNull(
                selectedCommits, "selectedCommits is null"));
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "an upstream sync selects no commit");
        }
        if (repairTurnBudget < 0) {
            throw new IllegalArgumentException("repair budget is negative");
        }
        return transactions.execute(status -> {
            Optional<UpstreamSyncRequest> replay = requestForKey(requestKey);
            if (replay.isPresent()) {
                UpstreamSyncRequest existing = replay.orElseThrow();
                UpstreamSyncRun run = runForRequest(existing.requestId())
                        .orElseThrow(() -> new IllegalStateException(
                                "upstream request has no run"));
                if (!existing.repositoryId().equals(repositoryId)
                        || !existing.goalText().equals(goalText)
                        || !existing.selectedUpstreamShas().equals(
                                shas(selected))
                        || !run.taskId().equals(taskId)) {
                    throw new IllegalStateException(
                            "requestKey already owns a different upstream "
                                    + "sync command");
                }
                return run;
            }
            long now = clock.instant().toEpochMilli();
            String requestId = stableId("upstream-sync-request", requestKey);
            jdbc.update(
                    """
                    INSERT INTO flow_upstream_sync_request (
                        request_id, request_key, repository_id, goal_text,
                        source_remote, source_from_ref, source_to_ref,
                        target_ref, selected_upstream_shas_json,
                        selected_subjects_json, state,
                        requested_by_user_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', ?, ?)
                    """,
                    requestId, requestKey, repositoryId, goalText,
                    sourceRemote, sourceFromRef, sourceToRef, targetRef,
                    json(shas(selected)), json(subjects(selected)),
                    requestedByUserId, now);
            String runId = stableId("upstream-sync-run", requestId);
            jdbc.update(
                    """
                    INSERT INTO flow_upstream_sync_run (
                        run_id, request_id, task_id, repair_placement, state,
                        remaining_repair_turns, current_index, current_head,
                        park_reason, verification_ref, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'READY', ?, 0, NULL, NULL, NULL,
                              ?, ?)
                    """,
                    runId, requestId, taskId,
                    RepairPlacementPolicy.ATTRIBUTED_FIXUP.name(),
                    repairTurnBudget, now, now);
            return run(runId).orElseThrow();
        });
    }

    /**
     * The program-resolved repair placement for a Task.
     *
     * <p>Absence is the answer for every ordinary Task: it never appears here,
     * so it keeps {@code TIP} without this component knowing it exists.
     */
    public RepairPlacementPolicy repairPlacement(String taskId)
    {
        requireText(taskId, "taskId");
        return runForTask(taskId)
                .map(UpstreamSyncRun::repairPlacement)
                .orElse(RepairPlacementPolicy.TIP);
    }

    public Optional<UpstreamSyncRun> run(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                "SELECT * FROM flow_upstream_sync_run WHERE run_id = ?",
                (result, row) -> readRun(result), runId)
                .stream().findFirst();
    }

    public Optional<UpstreamSyncRun> runForTask(String taskId)
    {
        requireText(taskId, "taskId");
        return jdbc.query(
                "SELECT * FROM flow_upstream_sync_run WHERE task_id = ?",
                (result, row) -> readRun(result), taskId)
                .stream().findFirst();
    }

    public Optional<UpstreamSyncRun> runForRequest(String requestId)
    {
        requireText(requestId, "requestId");
        return jdbc.query(
                "SELECT * FROM flow_upstream_sync_run WHERE request_id = ?",
                (result, row) -> readRun(result), requestId)
                .stream().findFirst();
    }

    public Optional<UpstreamSyncRequest> request(String requestId)
    {
        requireText(requestId, "requestId");
        return jdbc.query(
                "SELECT * FROM flow_upstream_sync_request WHERE request_id = ?",
                (result, row) -> readRequest(result), requestId)
                .stream().findFirst();
    }

    public Optional<UpstreamSyncRequest> requestForKey(String requestKey)
    {
        requireText(requestKey, "requestKey");
        return jdbc.query(
                """
                SELECT * FROM flow_upstream_sync_request WHERE request_key = ?
                """,
                (result, row) -> readRequest(result), requestKey)
                .stream().findFirst();
    }

    public List<UpstreamPick> picks(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                """
                SELECT * FROM flow_upstream_pick
                WHERE run_id = ? ORDER BY ordinal
                """,
                (result, row) -> readPick(result), runId);
    }

    public Optional<UpstreamFixup> fixup(String pickId)
    {
        requireText(pickId, "pickId");
        return jdbc.query(
                "SELECT * FROM flow_upstream_fixup WHERE pick_id = ?",
                (result, row) -> readFixup(result), pickId)
                .stream().findFirst();
    }

    public List<UpstreamFixup> fixups(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                """
                SELECT f.* FROM flow_upstream_fixup f
                JOIN flow_upstream_pick p ON p.pick_id = f.pick_id
                WHERE f.run_id = ? ORDER BY p.ordinal
                """,
                (result, row) -> readFixup(result), runId);
    }

    /** Appends one pick outcome and advances the run's index in one write. */
    public UpstreamPick recordPick(
            String runId,
            int ordinal,
            String upstreamSha,
            String preHead,
            String resultHead,
            String resultCommitSha,
            PickState state,
            List<String> conflictedPaths,
            boolean provenanceVerified,
            String changeSetRevisionId)
    {
        requireText(runId, "runId");
        requireText(upstreamSha, "upstreamSha");
        requireText(preHead, "preHead");
        requireNonNull(state, "state is null");
        List<String> paths = List.copyOf(requireNonNull(
                conflictedPaths, "conflictedPaths is null"));
        return transactions.execute(status -> {
            long now = clock.instant().toEpochMilli();
            String pickId = stableId("upstream-pick", runId, upstreamSha);
            jdbc.update(
                    """
                    INSERT INTO flow_upstream_pick (
                        pick_id, run_id, ordinal, upstream_sha, pre_head,
                        result_head, result_commit_sha, state,
                        conflicted_paths_json, provenance_verified,
                        change_set_revision_id, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    pickId, runId, ordinal, upstreamSha, preHead,
                    resultHead, resultCommitSha, state.name(), json(paths),
                    provenanceVerified ? 1 : 0, changeSetRevisionId, now);
            jdbc.update(
                    """
                    UPDATE flow_upstream_sync_run
                    SET current_index = ?, current_head = COALESCE(?,
                            current_head), state = ?, updated_at = ?
                    WHERE run_id = ?
                    """,
                    ordinal + 1, resultHead,
                    (state == PickState.CONFLICTED
                            ? RunState.WAITING_CONFLICT_REPAIR
                            : RunState.PICKING).name(),
                    now, runId);
            return pick(pickId).orElseThrow();
        });
    }

    public Optional<UpstreamPick> pick(String pickId)
    {
        requireText(pickId, "pickId");
        return jdbc.query(
                "SELECT * FROM flow_upstream_pick WHERE pick_id = ?",
                (result, row) -> readPick(result), pickId)
                .stream().findFirst();
    }

    /**
     * Records the one fixup a pick may carry, or replaces its commit when a
     * later repair amended it. The {@code pick_id} unique constraint is what
     * makes a second row impossible rather than merely unwritten.
     */
    public UpstreamFixup recordFixup(
            String runId,
            UpstreamPick owner,
            FixupKind kind,
            String currentCommitSha,
            List<String> changedPaths,
            String createdByRunId,
            String changeSetRevisionId)
    {
        requireText(runId, "runId");
        requireNonNull(owner, "owner is null");
        requireNonNull(kind, "kind is null");
        requireText(currentCommitSha, "currentCommitSha");
        List<String> paths = List.copyOf(requireNonNull(
                changedPaths, "changedPaths is null"));
        return transactions.execute(status -> {
            long now = clock.instant().toEpochMilli();
            Optional<UpstreamFixup> existing = fixup(owner.pickId());
            if (existing.isPresent()) {
                jdbc.update(
                        """
                        UPDATE flow_upstream_fixup
                        SET current_commit_sha = ?, changed_paths_json = ?,
                            created_by_run_id = ?, amend_count = ?,
                            change_set_revision_id = ?, recorded_at = ?
                        WHERE pick_id = ?
                        """,
                        currentCommitSha, json(paths), createdByRunId,
                        existing.orElseThrow().amendCount() + 1,
                        changeSetRevisionId, now, owner.pickId());
            }
            else {
                jdbc.update(
                        """
                        INSERT INTO flow_upstream_fixup (
                            fixup_id, run_id, pick_id, owner_upstream_sha,
                            kind, current_commit_sha, changed_paths_json,
                            created_by_run_id, amend_count,
                            change_set_revision_id, recorded_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                        """,
                        stableId("upstream-fixup", owner.pickId()),
                        runId, owner.pickId(), owner.upstreamSha(),
                        kind.name(), currentCommitSha, json(paths),
                        createdByRunId, changeSetRevisionId, now);
            }
            jdbc.update(
                    """
                    UPDATE flow_upstream_pick
                    SET state = ?, result_head = ?, recorded_at = ?
                    WHERE pick_id = ?
                    """,
                    PickState.RESOLVED.name(), currentCommitSha, now,
                    owner.pickId());
            jdbc.update(
                    """
                    UPDATE flow_upstream_sync_run
                    SET current_head = ?, state = ?, remaining_repair_turns =
                            MAX(remaining_repair_turns - 1, 0), updated_at = ?
                    WHERE run_id = ?
                    """,
                    currentCommitSha, RunState.PICKING.name(), now, runId);
            return fixup(owner.pickId()).orElseThrow();
        });
    }

    public void spendRepairTurn(String runId)
    {
        requireText(runId, "runId");
        jdbc.update(
                """
                UPDATE flow_upstream_sync_run
                SET remaining_repair_turns = MAX(remaining_repair_turns - 1, 0),
                    updated_at = ?
                WHERE run_id = ?
                """,
                clock.instant().toEpochMilli(), runId);
    }

    public void park(String runId, String parkReason)
    {
        requireText(runId, "runId");
        requireText(parkReason, "parkReason");
        jdbc.update(
                """
                UPDATE flow_upstream_sync_run
                SET state = ?, park_reason = ?, updated_at = ?
                WHERE run_id = ?
                """,
                RunState.WAITING_USER.name(), parkReason,
                clock.instant().toEpochMilli(), runId);
    }

    public void recordVerification(
            String runId, RunState state, String verificationRef)
    {
        requireText(runId, "runId");
        requireNonNull(state, "state is null");
        requireText(verificationRef, "verificationRef");
        jdbc.update(
                """
                UPDATE flow_upstream_sync_run
                SET state = ?, verification_ref = ?, updated_at = ?
                WHERE run_id = ?
                """,
                state.name(), verificationRef,
                clock.instant().toEpochMilli(), runId);
    }

    public void advanceState(String runId, RunState state)
    {
        requireText(runId, "runId");
        requireNonNull(state, "state is null");
        jdbc.update(
                """
                UPDATE flow_upstream_sync_run
                SET state = ?, updated_at = ? WHERE run_id = ?
                """,
                state.name(), clock.instant().toEpochMilli(), runId);
    }

    private UpstreamSyncRequest readRequest(ResultSet result)
            throws SQLException
    {
        return new UpstreamSyncRequest(
                result.getString("request_id"),
                result.getString("request_key"),
                result.getString("repository_id"),
                result.getString("goal_text"),
                result.getString("source_remote"),
                result.getString("source_from_ref"),
                result.getString("source_to_ref"),
                result.getString("target_ref"),
                selectedCommits(
                        strings(result.getString("selected_upstream_shas_json")),
                        strings(result.getString("selected_subjects_json"))),
                RequestState.valueOf(result.getString("state")),
                result.getString("requested_by_user_id"),
                result.getLong("created_at"));
    }

    private static UpstreamSyncRun readRun(ResultSet result)
            throws SQLException
    {
        return new UpstreamSyncRun(
                result.getString("run_id"),
                result.getString("request_id"),
                result.getString("task_id"),
                RepairPlacementPolicy.valueOf(
                        result.getString("repair_placement")),
                RunState.valueOf(result.getString("state")),
                result.getInt("remaining_repair_turns"),
                result.getInt("current_index"),
                result.getString("current_head"),
                result.getString("park_reason"),
                result.getString("verification_ref"),
                result.getLong("created_at"),
                result.getLong("updated_at"));
    }

    private UpstreamPick readPick(ResultSet result)
            throws SQLException
    {
        return new UpstreamPick(
                result.getString("pick_id"),
                result.getString("run_id"),
                result.getInt("ordinal"),
                result.getString("upstream_sha"),
                result.getString("pre_head"),
                result.getString("result_head"),
                result.getString("result_commit_sha"),
                PickState.valueOf(result.getString("state")),
                strings(result.getString("conflicted_paths_json")),
                result.getInt("provenance_verified") == 1,
                result.getString("change_set_revision_id"),
                result.getLong("recorded_at"));
    }

    private UpstreamFixup readFixup(ResultSet result)
            throws SQLException
    {
        return new UpstreamFixup(
                result.getString("fixup_id"),
                result.getString("run_id"),
                result.getString("pick_id"),
                result.getString("owner_upstream_sha"),
                FixupKind.valueOf(result.getString("kind")),
                result.getString("current_commit_sha"),
                strings(result.getString("changed_paths_json")),
                result.getString("created_by_run_id"),
                result.getInt("amend_count"),
                result.getString("change_set_revision_id"),
                result.getLong("recorded_at"));
    }

    private static List<String> shas(List<SelectedCommit> selected)
    {
        return selected.stream().map(SelectedCommit::sha).toList();
    }

    private static List<String> subjects(List<SelectedCommit> selected)
    {
        return selected.stream().map(SelectedCommit::subject).toList();
    }

    /**
     * Rebuilds the selection from its two columns. A row written before
     * subjects were recorded has none, which reads as an unknown subject
     * rather than a wrong one.
     */
    private static List<SelectedCommit> selectedCommits(
            List<String> shas, List<String> subjects)
    {
        return IntStream.range(0, shas.size())
                .mapToObj(index -> new SelectedCommit(
                        shas.get(index),
                        index < subjects.size() ? subjects.get(index) : ""))
                .toList();
    }

    private String json(List<String> values)
    {
        try {
            return mapper.writeValueAsString(values);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "upstream sync value is not encodable", failure);
        }
    }

    private List<String> strings(String value)
    {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return List.of(mapper.readValue(value, String[].class));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "upstream sync value is not decodable", failure);
        }
    }

    static String stableId(String domain, String... values)
    {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        frame(digest, domain);
        for (String value : values) {
            frame(digest, value);
        }
        return domain + ":" + HexFormat.of()
                .formatHex(digest.digest()).substring(0, 32);
    }

    private static void frame(MessageDigest digest, String value)
    {
        byte[] bytes = value == null
                ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length)
                .getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
