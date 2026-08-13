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

import com.bytequay.app.flow.timeline.TaskViews;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PickState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.SelectedCommit;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamFixup;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamPick;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Read-only projection of a greenfield upstream synchronization for the run
 * surfaces.
 *
 * <p>It writes nothing, owns no storage, and reads only the authoritative
 * owner tables — the sync records, the Task the run picks into, and the CI
 * rounds and boundary proof its pull request accumulated afterwards.
 *
 * <p>Nothing here reads or writes the retired {@code upstream_cherry_pick_job}
 * model. The two lists coexist on the home page while legacy runs drain, and
 * they coexist by both being read, never by one being translated into the
 * other.
 */
public final class UpstreamSyncViews
{
    public static final int MAX_LIST_SIZE = 100;

    /**
     * A run's display number, over this repository's sync Tasks only. Tasks
     * are retained as the audit owner when a closed run is deleted, so using
     * them keeps RUN #4 as #4 instead of renumbering it to #3. It is a label
     * and never an identity — while legacy runs are still listed, a number can
     * legitimately appear twice.
     */
    private static final String RUN_COLUMNS = """
            WITH sync_task_time AS (
                SELECT t.task_id, t.repository_id,
                       (SELECT MIN(recorded_at)
                          FROM flow_runtime_task_lifecycle_revision l
                         WHERE l.task_id = t.task_id) AS created_at
                FROM flow_runtime_task t
                WHERE t.request_key LIKE 'upstream-sync-command:%'
            ),
            numbered AS (
                SELECT task_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY repository_id
                           ORDER BY created_at, task_id
                       ) AS run_number
                FROM sync_task_time
            )
            SELECT r.run_id, r.task_id, r.state, r.park_reason,
                   r.verification_ref, r.repair_turn_budget,
                   r.remaining_repair_turns,
                   r.pr_result, r.pr_result_at,
                   r.created_at, r.updated_at,
                   (SELECT k.conflicted_paths_json FROM flow_upstream_pick k
                     WHERE k.run_id = r.run_id AND k.state = 'CONFLICTED'
                     ORDER BY k.ordinal LIMIT 1) AS conflicted_paths_json,
                   q.goal_text, q.source_remote, q.source_from_ref,
                   q.source_to_ref, q.target_ref,
                   q.selected_upstream_shas_json, q.selected_subjects_json,
                   json_array_length(q.selected_upstream_shas_json)
                       AS requested_count,
                   json_extract(q.selected_upstream_shas_json, '$[0]')
                       AS range_from_sha,
                   json_extract(q.selected_upstream_shas_json, '$[#-1]')
                       AS range_to_sha,
                   -- What the pull request is called: the draft's own title
                   -- once one exists, and until then the title the user typed
                   -- when they confirmed the range.
                   COALESCE(d.title, q.pr_title) AS pr_title,
                   t.repository_id, t.status AS task_status,
                   t.branch_name, t.worktree_path,
                   t.base_ref, t.launch_base_sha, t.current_base_sha,
                   n.run_number,
                   p.pr_id,
                   ri.pr_number, ri.html_url,
                   s.provider_session_id,
                   (SELECT COUNT(*) FROM flow_upstream_pick k
                     WHERE k.run_id = r.run_id
                       AND k.state IN ('CLEAN', 'RESOLVED', 'CONFLICTED'))
                       AS applied_count,
                   (SELECT COUNT(*) FROM flow_upstream_pick k
                     WHERE k.run_id = r.run_id
                       AND k.state IN ('RESOLVED', 'CONFLICTED'))
                       AS conflicted_count,
                   (SELECT COUNT(*) FROM flow_upstream_pick k
                     WHERE k.run_id = r.run_id
                       AND k.state = 'SKIPPED_EMPTY') AS skipped_count,
                   (SELECT COALESCE(SUM(total_cost_milli_usd), 0)
                      FROM flow_runtime_agent_session g
                     WHERE g.task_id = t.task_id) AS spent_milli_usd,
                   (SELECT COUNT(*) FROM flow_ci_round c
                     WHERE c.task_id = t.task_id
                       AND c.state <> 'SUPERSEDED') AS round_count
            FROM flow_upstream_sync_run r
            JOIN flow_upstream_sync_request q ON q.request_id = r.request_id
            JOIN flow_runtime_task t ON t.task_id = r.task_id
            JOIN numbered n ON n.task_id = r.task_id
            LEFT JOIN flow_runtime_pr p ON p.task_id = t.task_id
            LEFT JOIN flow_runtime_pr_draft_revision d
                ON d.draft_revision_id = p.current_draft_revision_id
            LEFT JOIN flow_runtime_remote_identity ri
                ON ri.remote_identity_id = p.remote_identity_id
            LEFT JOIN flow_runtime_agent_session s
                ON s.session_id = t.task_session_id
            """;

    private final JdbcTemplate jdbc;
    private final UpstreamSync sync;
    private final TaskViews tasks;
    private final ObjectMapper mapper;

    public UpstreamSyncViews(
            DataSource dataSource,
            UpstreamSync sync,
            TaskViews tasks,
            ObjectMapper mapper)
    {
        this.jdbc = new JdbcTemplate(
                requireNonNull(dataSource, "dataSource is null"));
        this.sync = requireNonNull(sync, "sync is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Newest first, matching the order the run list renders. */
    public List<SyncJob> list(String repositoryId, int limit)
    {
        requireText(repositoryId, "repositoryId");
        if (limit < 1 || limit > MAX_LIST_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIST_SIZE);
        }
        return List.copyOf(jdbc.query(
                RUN_COLUMNS + """
                        WHERE t.repository_id = ?
                        ORDER BY r.created_at DESC, r.run_id
                        LIMIT ?
                        """,
                (rows, row) -> readJob(rows),
                repositoryId,
                limit));
    }

    public Optional<SyncJob> job(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                        RUN_COLUMNS + "WHERE r.run_id = ?",
                        (rows, row) -> readJob(rows),
                        runId)
                .stream()
                .findFirst();
    }

    /** Everything one run's cockpit renders, in one read. */
    public Optional<SyncRunDetail> detail(String runId)
    {
        return job(runId).map(job -> {
            UpstreamSyncRequest request = sync.run(runId)
                    .flatMap(run -> sync.request(run.requestId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "upstream sync run has no request"));
            List<UpstreamPick> picks = sync.picks(runId);
            List<SyncFixup> fixups = fixups(runId, request, picks);
            return new SyncRunDetail(
                    job,
                    job.baseRef(),
                    commits(request, picks, job),
                    events(job, request, picks, fixups),
                    fixups,
                    rounds(job.taskId()),
                    compileProof(job.taskId(), fixups),
                    job.prId() == null ? null : publishGate(job.prId()));
        });
    }

    /**
     * One row per CI round, oldest first.
     *
     * <p>The rounds themselves are {@link TaskViews}' — a sync run's pull
     * request is an ordinary Task's pull request once it is published, and two
     * copies of "which checks failed in this round" would be two answers.
     */
    public List<SyncRound> rounds(String taskId)
    {
        return tasks.rounds(taskId).stream()
                .map(round -> new SyncRound(
                        round.ordinal(),
                        round.roundId(),
                        round.remoteHead(),
                        round.state(),
                        round.observedCount(),
                        round.failingCount(),
                        round.createdAt().toString()))
                .toList();
    }

    /** Which post-pick adaptation belongs to which semantic owner. */
    private List<SyncFixup> fixups(
            String runId,
            UpstreamSyncRequest request,
            List<UpstreamPick> picks)
    {
        Map<String, Integer> ordinalBySha = new HashMap<>();
        for (UpstreamPick pick : picks) {
            ordinalBySha.put(pick.upstreamSha(), pick.ordinal());
        }
        List<SelectedCommit> selected = request.selectedCommits();
        List<SyncFixup> fixups = new ArrayList<>();
        for (UpstreamFixup fixup : sync.fixups(runId)) {
            Integer index = ordinalBySha.get(fixup.ownerUpstreamSha());
            if (index == null) {
                continue;
            }
            fixups.add(new SyncFixup(
                    index,
                    fixup.ownerUpstreamSha(),
                    index < selected.size()
                            ? selected.get(index).subject() : "",
                    fixup.kind().name(),
                    fixup.currentCommitSha(),
                    fixup.changedPaths(),
                    fixup.amendCount(),
                    fixup.createdByRunId() == null
                            ? "CONFLICT_REPAIR" : "CI_REPAIR",
                    iso(fixup.recordedAt())));
        }
        return List.copyOf(fixups);
    }

    /**
     * The proof that excuses a red per-commit compile check, or null when
     * there is none.
     *
     * <p>Deliberately carries the boundary builds themselves rather than a
     * verdict: an excused red with no visible evidence is indistinguishable
     * from a bug.
     */
    private SyncCompileProof compileProof(String taskId, List<SyncFixup> fixups)
    {
        Optional<SyncCompileProof> latest = jdbc.query(
                        """
                        SELECT proof_id, head_sha, proved_at
                        FROM flow_ci_boundary_compile_proof
                        WHERE task_id = ?
                        ORDER BY proved_at DESC, proof_id
                        LIMIT 1
                        """,
                        (rows, row) -> new SyncCompileProof(
                                rows.getString("proof_id"),
                                rows.getString("head_sha"),
                                iso(rows.getLong("proved_at")),
                                List.of(), List.of(), null, List.of()),
                        taskId)
                .stream()
                .findFirst();
        if (latest.isEmpty()) {
            return null;
        }
        SyncCompileProof proof = latest.orElseThrow();
        List<SyncBoundary> boundaries = jdbc.query(
                """
                SELECT ordinal, commit_sha, kind, exit_state, evidence_ref
                FROM flow_ci_boundary_compile_result
                WHERE proof_id = ?
                ORDER BY ordinal
                """,
                (rows, row) -> new SyncBoundary(
                        rows.getInt("ordinal"),
                        rows.getString("commit_sha"),
                        rows.getString("kind"),
                        rows.getString("exit_state"),
                        rows.getString("evidence_ref")),
                proof.proofId());
        // The compile selectors and the exact repository CI configuration
        // they were read from. A selector without that citation cannot be
        // stored, which is what keeps this out of reach of a name heuristic.
        Optional<CompilePolicy> policy = jdbc.query(
                        """
                        SELECT per_commit_compile_selectors_json AS selectors,
                               compile_source_ref
                        FROM flow_ci_repair_placement
                        WHERE task_id = ?
                        """,
                        (rows, row) -> new CompilePolicy(
                                strings(rows.getString("selectors")),
                                rows.getString("compile_source_ref")),
                        taskId)
                .stream()
                .findFirst();
        // The excused reds: a target commit whose repair lives in the fixup
        // after it is red in isolation by construction, and this proof is the
        // only thing that may excuse it.
        List<String> excused = fixups.stream()
                .map(SyncFixup::targetSubject)
                .toList();
        return new SyncCompileProof(
                proof.proofId(), proof.headSha(), proof.provedAt(),
                List.copyOf(boundaries),
                policy.map(CompilePolicy::selectors).orElse(List.of()),
                policy.map(CompilePolicy::sourceRef).orElse(null),
                excused);
    }

    private record CompilePolicy(List<String> selectors, String sourceRef) {}

    /** The INITIAL_PUBLISH gate, as displayed, plus what authorizing it does. */
    private SyncGate publishGate(String prId)
    {
        return jdbc.query(
                        """
                        SELECT g.gate_id, v.revision, v.subject_digest,
                               v.action_digest,
                               (SELECT to_state
                                  FROM flow_user_gate_transition x
                                 WHERE x.gate_id = g.gate_id
                                   AND x.gate_revision = v.revision
                                 ORDER BY x.sequence DESC LIMIT 1) AS state,
                               s.proposed_head, s.branch_ref, s.target_base_ref
                        FROM flow_user_gate g
                        JOIN flow_user_gate_revision v
                            ON v.gate_id = g.gate_id
                           AND v.revision = g.current_revision
                        LEFT JOIN flow_user_gate_initial_publish_subject s
                            ON s.subject_id = v.subject_manifest_ref
                        WHERE g.pr_id = ? AND g.kind = 'INITIAL_PUBLISH'
                        """,
                        (rows, row) -> new SyncGate(
                                rows.getString("gate_id"),
                                rows.getLong("revision"),
                                rows.getString("subject_digest"),
                                rows.getString("action_digest"),
                                rows.getString("state"),
                                rows.getString("proposed_head"),
                                rows.getString("branch_ref"),
                                rows.getString("target_base_ref")),
                        prId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * The commit queue: every confirmed commit in range order, carrying what
     * the run has since made of it.
     */
    private static List<SyncCommit> commits(
            UpstreamSyncRequest request,
            List<UpstreamPick> picks,
            SyncJob job)
    {
        Map<String, PickState> stateBySha = new HashMap<>();
        for (UpstreamPick pick : picks) {
            stateBySha.put(pick.upstreamSha(), pick.state());
        }
        List<SelectedCommit> selected = request.selectedCommits();
        List<SyncCommit> commits = new ArrayList<>(selected.size());
        boolean live = "RUNNING".equals(job.status())
                || "QUEUED".equals(job.status());
        for (int index = 0; index < selected.size(); index++) {
            SelectedCommit commit = selected.get(index);
            PickState state = stateBySha.get(commit.sha());
            commits.add(new SyncCommit(
                    index,
                    commit.sha(),
                    shortSha(commit.sha()),
                    commit.subject(),
                    commitState(state, live && index == picks.size())));
        }
        return List.copyOf(commits);
    }

    private static String commitState(PickState state, boolean inFlight)
    {
        if (state == null) {
            return inFlight ? "current" : "waiting";
        }
        return switch (state) {
            case CLEAN -> "applied";
            case RESOLVED -> "conflicted";
            case SKIPPED_EMPTY -> "skipped";
            // Still open: git's own resolution is committed but the repair is
            // not, which is the pick the run is working on.
            case CONFLICTED, NEEDS_ATTENTION -> "current";
        };
    }

    /**
     * The run's log, derived from what the run recorded rather than from a
     * second write-side event stream. Every line here is a record that exists;
     * nothing is invented to fill the column.
     */
    private static List<SyncEvent> events(
            SyncJob job,
            UpstreamSyncRequest request,
            List<UpstreamPick> picks,
            List<SyncFixup> fixups)
    {
        List<SyncEvent> events = new ArrayList<>();
        events.add(new SyncEvent(
                "start", events.size(), null, "start",
                "Started " + request.selectedCommits().size()
                        + " picks from " + request.sourceRemote() + "/"
                        + request.sourceToRef(),
                request.goalText(), null, null, job.createdAt()));
        Map<Integer, SyncFixup> fixupByPick = new HashMap<>();
        for (SyncFixup fixup : fixups) {
            fixupByPick.put(fixup.pickIndex(), fixup);
        }
        for (UpstreamPick pick : picks) {
            events.add(new SyncEvent(
                    pick.pickId(), events.size(), pick.ordinal(), "command",
                    "git cherry-pick -x " + shortSha(pick.upstreamSha()),
                    pickDetail(pick), null, null, iso(pick.recordedAt())));
            SyncFixup fixup = fixupByPick.get(pick.ordinal());
            if (fixup != null) {
                events.add(new SyncEvent(
                        "fixup:" + pick.pickId(), events.size(),
                        pick.ordinal(), "fixup",
                        "fixup! " + fixup.targetSubject(),
                        String.join(", ", fixup.changedPaths()),
                        null, null, fixup.at()));
            }
        }
        if (job.verified()) {
            events.add(new SyncEvent(
                    "verified", events.size(), null, "done",
                    "Range constructed and verified",
                    job.verificationRef(), null, null, job.updatedAt()));
        }
        if (job.prNumber() != null) {
            events.add(new SyncEvent(
                    "pr", events.size(), null, "pr",
                    "Draft pull request #" + job.prNumber() + " opened",
                    job.prUrl(), null, null, job.updatedAt()));
        }
        if (job.errorMessage() != null) {
            events.add(new SyncEvent(
                    "park", events.size(), null, "park",
                    "Parked — " + job.errorMessage(), null, null, null,
                    job.updatedAt()));
        }
        return List.copyOf(events);
    }

    private static String pickDetail(UpstreamPick pick)
    {
        return switch (pick.state()) {
            case CLEAN -> "applied clean at " + shortSha(
                    pick.resultCommitSha());
            case SKIPPED_EMPTY -> "the fork already carries this change";
            case RESOLVED -> "conflict resolved before the pick continued";
            case CONFLICTED -> "conflict in " + String.join(
                    ", ", pick.conflictedPaths());
            case NEEDS_ATTENTION -> "needs attention";
        };
    }

    private SyncJob readJob(ResultSet rows)
            throws SQLException
    {
        String state = rows.getString("state");
        long prNumber = rows.getLong("pr_number");
        boolean noPrNumber = rows.wasNull();
        // A pull request that ended is the end of the run: there is nothing
        // left for it to do, whatever it still holds on disk.
        String prResult = rows.getString("pr_result");
        boolean closed = prResult != null
                || "CANCELED".equals(state);
        // A stop the user asked for and the run has not reached yet — a park or
        // a close, which wait at the same boundary. Both share the parked run's
        // column, so both are reported as the pending request they are rather
        // than as the reason a parked run gives for having stopped.
        String parkReason = rows.getString("park_reason");
        boolean pauseRequested =
                UpstreamSync.PAUSE_REQUESTED.equals(parkReason);
        boolean closeRequested =
                UpstreamSync.CLOSE_REQUESTED.equals(parkReason);
        return new SyncJob(
                rows.getString("run_id"),
                rows.getString("task_id"),
                rows.getString("pr_id"),
                rows.getString("repository_id"),
                "flow",
                rows.getInt("run_number"),
                status(state),
                state,
                // What the run is picking from, as the surface names it. The
                // range's own endpoints are shas and are reported as such.
                rows.getString("source_remote"),
                rows.getString("branch_name"),
                headBase(rows),
                rows.getInt("requested_count"),
                rows.getString("range_from_sha"),
                rows.getString("range_to_sha"),
                rows.getInt("applied_count"),
                rows.getInt("skipped_count"),
                rows.getInt("conflicted_count"),
                pauseRequested,
                rows.getInt("repair_turn_budget") == 0
                        ? null : rows.getInt("remaining_repair_turns"),
                rows.getLong("spent_milli_usd"),
                rows.getString("provider_session_id"),
                strings(rows.getString("conflicted_paths_json")),
                rows.getString("worktree_path"),
                noPrNumber ? null : prNumber,
                rows.getString("html_url"),
                rows.getString("pr_title"),
                closeRequested,
                rows.getInt("round_count"),
                pauseRequested || closeRequested ? null : parkReason,
                "FINAL_REVIEW".equals(state)
                        || "WAITING_INITIAL_PUBLISH".equals(state)
                        || "HANDED_OFF".equals(state),
                rows.getString("verification_ref"),
                prResult == null ? null : prResult.toLowerCase(Locale.ROOT),
                closed
                        ? iso(prResult == null
                                ? rows.getLong("updated_at")
                                : rows.getLong("pr_result_at"))
                        : null,
                iso(rows.getLong("created_at")),
                iso(rows.getLong("updated_at")));
    }

    /** What the run is standing on: its current base, or its launch base. */
    private static String headBase(ResultSet rows)
            throws SQLException
    {
        String current = rows.getString("current_base_sha");
        if (current != null) {
            return current;
        }
        String launch = rows.getString("launch_base_sha");
        return launch != null ? launch : rows.getString("base_ref");
    }

    /**
     * The run state as the shared surface reads it. The surface's vocabulary
     * is older than this model, so the mapping lives here rather than being
     * pushed into every component.
     */
    private static String status(String state)
    {
        if ("CANCELED".equals(state)) {
            return "CLOSED";
        }
        return switch (state) {
            case "READY" -> "QUEUED";
            case "PICKING", "WAITING_CONFLICT_REPAIR", "FINAL_REVIEW" ->
                    "RUNNING";
            case "WAITING_USER" -> "PAUSED_CONFLICT";
            case "NEEDS_ATTENTION" -> "FAILED";
            // The range is built and nothing is pushed until the user
            // authorizes it. That is the publish gate, not a failure.
            default -> "COMPLETED";
        };
    }

    private static String shortSha(String sha)
    {
        if (sha == null) {
            return "";
        }
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    private static String iso(long epochMillis)
    {
        return Instant.ofEpochMilli(epochMillis).toString();
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
                    "stored selector list is not decodable", failure);
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    /**
     * One run as the sync surfaces read it.
     *
     * @param budgetMilliUsd deliberately absent: phase 1 is bounded by repair
     *         turns, and reporting a dollar ceiling it does not have would be
     *         a number the program cannot keep.
     */
    public record SyncJob(
            String jobId,
            String taskId,
            String prId,
            String repositoryId,
            String source,
            int runNumber,
            String status,
            String runState,
            String sourceBranch,
            String resultBranch,
            String baseRef,
            int requestedCount,
            String rangeFromSha,
            String rangeToSha,
            int appliedCount,
            int skippedCount,
            int conflictedCount,
            boolean pauseRequested,
            /** Null when the run has no conflict-repair cap. */
            Integer remainingRepairTurns,
            long spentMilliUsd,
            String agentSessionId,
            List<String> conflictPaths,
            String worktreePath,
            Long prNumber,
            String prUrl,
            /** What the pull request is called; null until anything named it. */
            String prTitle,
            /**
             * A terminal stop the user asked for and the run has not reached.
             * Distinct from {@code pauseRequested} because the two end
             * somewhere different, and a surface that showed one as the other
             * would tell the user their close was only a pause.
             */
            boolean closeRequested,
            int roundCount,
            String errorMessage,
            boolean verified,
            String verificationRef,
            /** {@code merged | closed} once the pull request ended. */
            String prResult,
            String closedAt,
            String createdAt,
            String updatedAt)
    {
        public SyncJob
        {
            requireNonNull(jobId, "jobId is null");
            requireNonNull(status, "status is null");
            conflictPaths = List.copyOf(
                    requireNonNull(conflictPaths, "conflictPaths is null"));
        }
    }

    public record SyncCommit(
            int index, String sha, String shortSha, String subject,
            String state) {}

    public record SyncEvent(
            String id,
            int ordinal,
            Integer pickIndex,
            String kind,
            String title,
            String detail,
            Integer exitCode,
            Long durationMs,
            String at) {}

    /**
     * @param origin {@code CONFLICT_REPAIR} for a repair made while picking,
     *         {@code CI_REPAIR} for one a CI round produced.
     */
    public record SyncFixup(
            int pickIndex,
            String upstreamSha,
            String targetSubject,
            String kind,
            String commitSha,
            List<String> changedPaths,
            int amendCount,
            String origin,
            String at) {}

    public record SyncRound(
            int ordinal,
            String roundId,
            String remoteHead,
            String state,
            int observedCount,
            int failingCount,
            String createdAt) {}

    public record SyncBoundary(
            int ordinal,
            String commitSha,
            String kind,
            String exitState,
            String evidenceRef) {}

    /**
     * @param excusedTargets the target commits whose red is excused by this
     *         proof — each is a commit whose repair lives in the fixup after
     *         it, so it is red in isolation by construction.
     */
    public record SyncCompileProof(
            String proofId,
            String headSha,
            String provedAt,
            List<SyncBoundary> boundaries,
            List<String> compileSelectors,
            String compileSourceRef,
            List<String> excusedTargets) {}

    public record SyncGate(
            String gateId,
            long revision,
            String subjectDigest,
            String actionDigest,
            String state,
            String proposedHead,
            String branchRef,
            String targetBaseRef) {}

    public record SyncRunDetail(
            SyncJob job,
            String baseBranch,
            List<SyncCommit> commits,
            List<SyncEvent> events,
            List<SyncFixup> fixups,
            List<SyncRound> rounds,
            SyncCompileProof compileProof,
            SyncGate publishGate) {}
}
