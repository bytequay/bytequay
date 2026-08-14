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

import com.bytequay.app.flow.gate.InitialPublishVerificationProvider;
import com.bytequay.app.flow.gate.InitialPublishVerificationProvider.Verification;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.FixupKind;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PickState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PrResult;
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
import java.util.Objects;
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
        implements InitialPublishVerificationProvider
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
            String prTitle,
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
                        pr_title, source_remote, source_from_ref,
                        source_to_ref, target_ref,
                        selected_upstream_shas_json,
                        selected_subjects_json, state,
                        requested_by_user_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', ?, ?)
                    """,
                    requestId, requestKey, repositoryId, goalText, prTitle,
                    sourceRemote, sourceFromRef, sourceToRef, targetRef,
                    json(shas(selected)), json(subjects(selected)),
                    requestedByUserId, now);
            String runId = stableId("upstream-sync-run", requestId);
            jdbc.update(
                    """
                    INSERT INTO flow_upstream_sync_run (
                        run_id, request_id, task_id, repair_placement, state,
                        repair_turn_budget, remaining_repair_turns,
                        current_index, current_head,
                        park_reason, verification_ref, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'READY', ?, ?, 0, NULL, NULL, NULL,
                              ?, ?)
                    """,
                    runId, requestId, taskId,
                    RepairPlacementPolicy.ATTRIBUTED_FIXUP.name(),
                    repairTurnBudget, repairTurnBudget, now, now);
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

    @Override
    public boolean owns(String taskId)
    {
        return runForTask(taskId).isPresent();
    }

    @Override
    public Optional<Verification> current(String taskId)
    {
        return runForTask(taskId).flatMap(run -> {
            if ((run.state() != RunState.FINAL_REVIEW
                    && run.state() != RunState.WAITING_INITIAL_PUBLISH)
                    || run.verificationRef() == null
                    || run.currentHead() == null) {
                return Optional.empty();
            }
            UpstreamSyncRequest request = request(run.requestId()).orElseThrow();
            return Optional.of(new Verification(
                    taskId, run.runId(), request.targetRef(),
                    run.currentHead(), run.verificationRef()));
        });
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
            Optional<UpstreamPick> replay = pick(pickId);
            if (replay.isPresent()) {
                UpstreamPick existing = replay.orElseThrow();
                if (existing.ordinal() != ordinal
                        || !existing.runId().equals(runId)
                        || !existing.upstreamSha().equals(upstreamSha)
                        || !existing.preHead().equals(preHead)
                        || !Objects.equals(existing.resultHead(), resultHead)
                        || !Objects.equals(
                                existing.resultCommitSha(), resultCommitSha)
                        || existing.state() != state
                        || !existing.conflictedPaths().equals(paths)
                        || existing.provenanceVerified() != provenanceVerified
                        || !Objects.equals(existing.changeSetRevisionId(),
                                changeSetRevisionId)) {
                    throw new IllegalStateException(
                            "pick redelivery changed its durable outcome");
                }
                return existing;
            }
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
            int advanced = jdbc.update(
                    "UPDATE flow_upstream_sync_run "
                            + "SET current_index = ?, "
                            + "current_head = COALESCE(?, current_head), "
                            + "state = ?, updated_at = ? "
                            + "WHERE run_id = ? AND state = ? "
                            + "AND current_index = ? "
                            + "AND (current_head = ? OR current_head IS NULL)",
                    ordinal + 1, resultHead,
                    (state == PickState.CONFLICTED
                            ? RunState.WAITING_CONFLICT_REPAIR
                            : RunState.PICKING).name(),
                    now, runId, RunState.PICKING.name(), ordinal, preHead);
            if (advanced != 1) {
                status.setRollbackOnly();
                throw new IllegalStateException(
                        "pick cannot advance the current upstream state");
            }
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

    public Optional<UpstreamPick> pick(String runId, int ordinal)
    {
        requireText(runId, "runId");
        return jdbc.query(
                "SELECT * FROM flow_upstream_pick "
                        + "WHERE run_id = ? AND ordinal = ?",
                (result, row) -> readPick(result), runId, ordinal)
                .stream().findFirst();
    }

    /** Restores the durable conflict boundary before a parked repair resumes. */
    public void reenterConflictRepair(String pickId)
    {
        requireText(pickId, "pickId");
        transactions.execute(status -> {
            UpstreamPick conflict = pick(pickId).orElseThrow(() ->
                    new IllegalArgumentException(
                            "unknown upstream pick: " + pickId));
            if (conflict.state() != PickState.CONFLICTED) {
                throw new IllegalStateException(
                        "only a conflicted pick can re-enter repair");
            }
            UpstreamSyncRun current = run(conflict.runId()).orElseThrow();
            if (current.state() == RunState.WAITING_CONFLICT_REPAIR) {
                if (!Objects.equals(
                        current.currentHead(), conflict.preHead())) {
                    throw new IllegalStateException(
                            "conflicted pick lost its recorded repair head");
                }
                return null;
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_upstream_sync_run
                    SET state = ?, updated_at = ?
                    WHERE run_id = ? AND state = ? AND current_head = ?
                    """,
                    RunState.WAITING_CONFLICT_REPAIR.name(),
                    clock.instant().toEpochMilli(), conflict.runId(),
                    RunState.PICKING.name(), conflict.preHead());
            if (updated != 1) {
                throw new IllegalStateException(
                        "conflicted pick cannot restore its repair boundary");
            }
            return null;
        });
    }

    /** Records the commit created only after a conflicted index was resolved. */
    public UpstreamPick resolvePick(
            String pickId,
            String resultHead,
            String resultCommitSha,
            boolean provenanceVerified,
            String changeSetRevisionId)
    {
        requireText(pickId, "pickId");
        requireText(resultHead, "resultHead");
        requireText(resultCommitSha, "resultCommitSha");
        requireText(changeSetRevisionId, "changeSetRevisionId");
        if (!provenanceVerified) {
            throw new IllegalArgumentException(
                    "a resolved pick must carry -x provenance");
        }
        return transactions.execute(status -> {
            UpstreamPick existing = pick(pickId).orElseThrow(() ->
                    new IllegalStateException("conflicted pick is missing"));
            if (existing.state() == PickState.RESOLVED) {
                if (!Objects.equals(existing.resultHead(), resultHead)
                        || !Objects.equals(
                                existing.resultCommitSha(), resultCommitSha)
                        || !existing.provenanceVerified()
                        || !Objects.equals(existing.changeSetRevisionId(),
                                changeSetRevisionId)) {
                    throw new IllegalStateException(
                            "resolved pick redelivery changed its outcome");
                }
                return existing;
            }
            if (existing.state() != PickState.CONFLICTED
                    || existing.resultCommitSha() != null) {
                throw new IllegalStateException(
                        "only an uncommitted conflict can be resolved");
            }
            long now = clock.instant().toEpochMilli();
            int resolved = jdbc.update(
                    """
                    UPDATE flow_upstream_pick
                    SET result_head = ?, result_commit_sha = ?, state = ?,
                        provenance_verified = 1,
                        change_set_revision_id = ?, recorded_at = ?
                    WHERE pick_id = ? AND state = ?
                    """,
                    resultHead, resultCommitSha, PickState.RESOLVED.name(),
                    changeSetRevisionId, now, pickId,
                    PickState.CONFLICTED.name());
            if (resolved != 1) {
                status.setRollbackOnly();
                throw new IllegalStateException(
                        "conflicted pick changed while it was being resolved");
            }
            int advanced = jdbc.update(
                    """
                    UPDATE flow_upstream_sync_run
                    SET current_head = ?, state = ?, updated_at = ?
                    WHERE run_id = ? AND state = ? AND current_head = ?
                    """,
                    resultHead, RunState.PICKING.name(), now, existing.runId(),
                    RunState.WAITING_CONFLICT_REPAIR.name(), existing.preHead());
            if (advanced != 1) {
                status.setRollbackOnly();
                throw new IllegalStateException(
                        "resolved pick cannot advance the current upstream state");
            }
            return pick(pickId).orElseThrow();
        });
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
            int advanced = jdbc.update(
                    """
                    UPDATE flow_upstream_sync_run
                    SET current_head = ?, state = ?, updated_at = ?
                    WHERE run_id = ? AND state = ?
                    """,
                    currentCommitSha, RunState.PICKING.name(), now, runId,
                    RunState.PICKING.name());
            if (advanced != 1) {
                status.setRollbackOnly();
                throw new IllegalStateException(
                        "fixup cannot advance the current upstream state");
            }
            return fixup(owner.pickId()).orElseThrow();
        });
    }

    /**
     * Records how the run's pull request ended, once.
     *
     * <p>Written only from an observation of the provider. The first one wins:
     * a pull request that was merged does not become closed afterwards, and a
     * later probe seeing a different state is a probe of a different thing.
     */
    public void recordPullRequestEnd(String runId, PrResult result)
    {
        requireText(runId, "runId");
        requireNonNull(result, "result is null");
        long now = clock.instant().toEpochMilli();
        jdbc.update(
                """
                UPDATE flow_upstream_sync_run
                SET pr_result = ?, pr_result_at = ?, updated_at = ?
                WHERE run_id = ? AND pr_result IS NULL
                """,
                result.name(), now, now, runId);
    }

    /**
     * The runs worth asking the provider about: published, and not yet known
     * to have ended.
     */
    public List<UpstreamSyncRun> runsAwaitingPullRequestEnd()
    {
        return jdbc.query(
                """
                SELECT * FROM flow_upstream_sync_run
                WHERE pr_result IS NULL
                ORDER BY created_at
                """,
                (result, row) -> readRun(result));
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

    /**
     * The user asked the run to stop, recorded until the run reaches a pick
     * boundary and honours it.
     *
     * <p>It is written to {@code park_reason} rather than to a column of its
     * own because the new-flow schema is one digest-sealed baseline: adding a
     * column there costs every existing install the runs it already has. The
     * column is null for exactly as long as the run has no reason to stop, so
     * a pending reason and a settled one never contend for it — {@link #park}
     * overwrites the request with the reason it actually parked for, and
     * {@link #resume} clears it.
     */
    public static final String PAUSE_REQUESTED = "USER_PAUSE_REQUESTED";

    /** The user's terminal stop, honoured at the same boundary as a park. */
    public static final String CLOSE_REQUESTED = "USER_CLOSE_REQUESTED";

    /**
     * Asks a running sync to park at its next pick boundary.
     *
     * <p>Between commits is the only place a run can wait safely: the worktree
     * is at a recorded head there, so the request is stored and honoured
     * rather than applied where it lands.
     */
    public void requestPause(String runId)
    {
        requireText(runId, "runId");
        int updated = jdbc.update(
                """
                UPDATE flow_upstream_sync_run
                SET park_reason = ?, updated_at = ?
                WHERE run_id = ? AND park_reason IS NULL
                  AND state IN ('READY', 'PICKING', 'WAITING_CONFLICT_REPAIR')
                """,
                PAUSE_REQUESTED, clock.instant().toEpochMilli(), runId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "only a running upstream sync run can be asked to park");
        }
    }

    /**
     * Asks a running sync to stop for good at its next pick boundary.
     *
     * <p>Unlike a park request this overwrites one already pending: a user who
     * asked to pause and then asked to close wants the close, and the run has
     * only one boundary to honour either at.
     */
    public void requestClose(String runId)
    {
        requireText(runId, "runId");
        int updated = jdbc.update(
                """
                UPDATE flow_upstream_sync_run
                SET park_reason = ?, updated_at = ?
                WHERE run_id = ?
                  AND state IN ('READY', 'PICKING',
                                'WAITING_CONFLICT_REPAIR', 'FINAL_REVIEW')
                """,
                CLOSE_REQUESTED, clock.instant().toEpochMilli(), runId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "only a running upstream sync run stops at a boundary");
        }
    }

    /** Whether the user's park request is still waiting for a boundary. */
    public boolean pauseRequested(String runId)
    {
        requireText(runId, "runId");
        return run(runId)
                .map(run -> PAUSE_REQUESTED.equals(run.parkReason()))
                .orElse(false);
    }

    /** Whether the user's terminal stop is still waiting for a boundary. */
    public boolean closeRequested(String runId)
    {
        requireText(runId, "runId");
        return run(runId)
                .map(run -> CLOSE_REQUESTED.equals(run.parkReason()))
                .orElse(false);
    }

    /**
     * Drops a closed run and everything that only described it: its picks, its
     * fixups, and the request it was started from.
     *
     * <p>Deliberately does not touch the Task. The branch, the worktree record
     * and anything already pushed belong to the Task, and the user asked for
     * the run to leave the list — not for its work to be undone.
     *
     * <p>Only a closed run can be deleted. Deleting a live one would leave a
     * turn writing into a worktree whose run no longer exists.
     */
    public void delete(String runId)
    {
        requireText(runId, "runId");
        transactions.execute(status -> {
            UpstreamSyncRun run = run(runId).orElseThrow(
                    () -> new IllegalArgumentException(
                            "unknown upstream sync run: " + runId));
            if (run.state() != RunState.CANCELED) {
                throw new IllegalStateException(
                        "only a closed upstream sync run can be deleted");
            }
            // Innermost reference first: a fixup names its pick, a pick names
            // its run, and the run names its request.
            jdbc.update(
                    "DELETE FROM flow_upstream_fixup WHERE run_id = ?", runId);
            jdbc.update(
                    "DELETE FROM flow_upstream_pick WHERE run_id = ?", runId);
            jdbc.update(
                    "DELETE FROM flow_upstream_sync_run WHERE run_id = ?",
                    runId);
            jdbc.update(
                    "DELETE FROM flow_upstream_sync_request WHERE request_id = ?",
                    run.requestId());
            return null;
        });
    }

    public void park(String runId, String parkReason)
    {
        requireText(runId, "runId");
        requireText(parkReason, "parkReason");
        int updated = jdbc.update(
                """
                UPDATE flow_upstream_sync_run
                SET state = ?, park_reason = ?, updated_at = ?
                WHERE run_id = ? AND state IN (
                    'READY', 'PICKING', 'WAITING_CONFLICT_REPAIR', 'FINAL_REVIEW'
                )
                """,
                RunState.WAITING_USER.name(), parkReason,
                clock.instant().toEpochMilli(), runId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "only an active upstream sync run can park");
        }
    }

    /**
     * Reopens a parked run, optionally granting more repair turns.
     *
     * <p>Only a parked run can resume — a run that failed some other way has
     * no recorded safe boundary to re-enter at. The grant is additive so a
     * budget park can be resumed with room to continue, and zero is valid for
     * a park the user resolved by other means. It is clamped to the run's cap,
     * so it is a no-op for a run started without one.
     */
    public UpstreamSyncRun resume(String runId, int additionalRepairTurns)
    {
        requireText(runId, "runId");
        if (additionalRepairTurns < 0) {
            throw new IllegalArgumentException(
                    "additionalRepairTurns is negative");
        }
        int updated = jdbc.update(
                """
                UPDATE flow_upstream_sync_run
                SET state = ?, park_reason = NULL,
                    remaining_repair_turns = MIN(
                        remaining_repair_turns + ?, repair_turn_budget),
                    updated_at = ?
                WHERE run_id = ? AND state = ?
                """,
                RunState.PICKING.name(), additionalRepairTurns,
                clock.instant().toEpochMilli(), runId,
                RunState.WAITING_USER.name());
        if (updated != 1) {
            throw new IllegalStateException(
                    "only a parked upstream sync run can be resumed");
        }
        return run(runId).orElseThrow();
    }

    public void recordVerification(
            String runId,
            String currentHead,
            String verificationRef)
    {
        requireText(runId, "runId");
        requireText(currentHead, "currentHead");
        requireText(verificationRef, "verificationRef");
        transactions.execute(status -> {
            UpstreamSyncRun current = run(runId).orElseThrow(() ->
                    new IllegalArgumentException(
                            "unknown upstream sync run: " + runId));
            if (current.state() != RunState.PICKING
                    && current.state() != RunState.FINAL_REVIEW) {
                throw illegalTransition(
                        current.state(), RunState.FINAL_REVIEW);
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_upstream_sync_run
                    SET state = ?, current_head = ?, verification_ref = ?,
                        updated_at = ?
                    WHERE run_id = ? AND state = ?
                    """,
                    RunState.FINAL_REVIEW.name(), currentHead, verificationRef,
                    clock.instant().toEpochMilli(), runId,
                    current.state().name());
            if (updated != 1) {
                throw new IllegalStateException(
                        "upstream sync state changed during verification");
            }
            return null;
        });
    }

    public void advanceState(String runId, RunState state)
    {
        requireText(runId, "runId");
        requireNonNull(state, "state is null");
        transactions.execute(status -> {
            UpstreamSyncRun current = run(runId).orElseThrow(() ->
                    new IllegalArgumentException(
                            "unknown upstream sync run: " + runId));
            if (!transitionAllowed(current.state(), state)) {
                throw illegalTransition(current.state(), state);
            }
            if (state == RunState.WAITING_INITIAL_PUBLISH
                    && (current.currentHead() == null
                            || current.verificationRef() == null)) {
                throw new IllegalStateException(
                        "an unverified upstream sync cannot await publication");
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_upstream_sync_run
                    SET state = ?,
                        park_reason = CASE WHEN ? = 'CANCELED' THEN NULL
                                           ELSE park_reason END,
                        updated_at = ? WHERE run_id = ? AND state = ?
                    """,
                    state.name(), state.name(), clock.instant().toEpochMilli(),
                    runId, current.state().name());
            if (updated != 1) {
                throw new IllegalStateException(
                        "upstream sync state changed during transition");
            }
            return null;
        });
    }

    private static boolean transitionAllowed(RunState from, RunState to)
    {
        if (to == RunState.CANCELED) {
            return true;
        }
        return switch (to) {
            case PICKING -> from == RunState.READY || from == RunState.PICKING;
            case WAITING_INITIAL_PUBLISH -> from == RunState.FINAL_REVIEW;
            default -> false;
        };
    }

    private static IllegalStateException illegalTransition(
            RunState from, RunState to)
    {
        return new IllegalStateException(
                "illegal upstream sync transition: " + from + " -> " + to);
    }

    private UpstreamSyncRequest readRequest(ResultSet result)
            throws SQLException
    {
        return new UpstreamSyncRequest(
                result.getString("request_id"),
                result.getString("request_key"),
                result.getString("repository_id"),
                result.getString("goal_text"),
                result.getString("pr_title"),
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
                result.getInt("repair_turn_budget"),
                result.getInt("remaining_repair_turns"),
                result.getInt("current_index"),
                result.getString("current_head"),
                result.getString("park_reason"),
                result.getString("verification_ref"),
                result.getString("pr_result") == null
                        ? null : PrResult.valueOf(result.getString("pr_result")),
                result.getLong("pr_result_at"),
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
