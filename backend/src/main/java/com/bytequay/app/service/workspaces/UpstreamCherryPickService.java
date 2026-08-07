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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRSyncService;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * Durable, user-started upstream cherry-pick setup. This operation may push
 * once to open the requested draft PR.
 *
 * <p>Conflicts are expected, not exceptional: a range off an upstream a fork has
 * drifted from produces them by the dozen. A conflicted pick is therefore staged,
 * committed and carried into the pull request rather than blocked on locally, and
 * the CI harness watching that pull request is what judges and repairs it. The
 * job only pauses when git itself cannot finish the pick.
 */
@Service
public class UpstreamCherryPickService
{
    private static final int MAX_COMMITS = 500;
    private static final int HISTORY_LIMIT = 5_000;
    private static final Set<String> LIVE_STATUSES = Set.of("QUEUED", "RUNNING");
    private static final Logger log = LoggerFactory.getLogger(UpstreamCherryPickService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkspaceRelationService relations;
    private final GitRunner git;
    private final PatResolver pats;
    private final PullRequestRepository pullRequests;
    private final PRSyncService prSync;
    private final ObjectProvider<HarnessWatchHandoff> harnessHandoff;
    /** Optional by design: with no writer registered a merge simply tears down. */
    private final ObjectProvider<SyncRetrospectiveWriter> retrospective;
    private final SyncRunStream stream;
    /** Optional by design: with no agent registered a conflict simply parks. */
    private final ObjectProvider<ConflictRepairAdvisor> repairAdvisor;
    private final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    public UpstreamCherryPickService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            WorkspaceRelationService relations,
            GitRunner git,
            PatResolver pats,
            PullRequestRepository pullRequests,
            PRSyncService prSync,
            ObjectProvider<HarnessWatchHandoff> harnessHandoff,
            ObjectProvider<SyncRetrospectiveWriter> retrospective,
            ObjectProvider<ConflictRepairAdvisor> repairAdvisor,
            SyncRunStream stream)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.relations = requireNonNull(relations, "relations is null");
        this.git = requireNonNull(git, "git is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.prSync = requireNonNull(prSync, "prSync is null");
        this.harnessHandoff = requireNonNull(harnessHandoff, "harnessHandoff is null");
        this.retrospective = requireNonNull(retrospective, "retrospective is null");
        this.stream = requireNonNull(stream, "stream is null");
        this.repairAdvisor = requireNonNull(repairAdvisor, "repairAdvisor is null");
    }

    /**
     * Read-only dry run. Deliberately does not fetch: a range is pinned to two
     * commit SHAs, so fetching cannot change which commits it covers, and the
     * page would otherwise pay a network round trip on every edit. {@link #enqueue}
     * fetches and re-plans before it applies anything.
     */
    public CherryPickPlan preview(String workspaceId, PreviewRequest request)
            throws IOException, InterruptedException
    {
        requireNonNull(request, "request is null");
        WorkspaceRelationService.ResolvedRelation relation =
                relations.requireResolved(workspaceId);
        if (!relation.relation().commitsEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "upstream commit reading is disabled for this relation");
        }
        String sourceBranch = request.sourceBranch() == null || request.sourceBranch().isBlank()
                ? relations.defaultBranch(relation.upstream(), relation.upstreamClone())
                : request.sourceBranch().strip();
        String sourceRef = relations.resolveFetchedRemoteRef(
                relation.upstreamClone(), sourceBranch);
        String baseBranch = relations.defaultBranch(relation.target(), relation.targetClone());
        String baseRef = relations.resolveFetchedRemoteRef(
                relation.targetClone(), baseBranch);
        List<GitRunner.DecoratedCommitEntry> history = git.listDecoratedCommits(
                relation.upstreamClone(), sourceRef, HISTORY_LIMIT);
        List<GitRunner.DecoratedCommitEntry> ordered = resolveSelection(
                relation.upstreamClone(), history, request.fromSha(), request.toSha(), request.shas());
        List<PlannedCommit> planned = plan(
                ordered,
                relations.pickedCommitSubjects(relation, baseRef),
                SkipFilters.normalize(request.skipStartsWith(), request.skipContains()));
        int picks = (int) planned.stream().filter(PlannedCommit::pick).count();
        return new CherryPickPlan(planned, picks, planned.size() - picks);
    }

    /** A selection arrives either as an explicit sha list or as a from/to range. */
    private List<GitRunner.DecoratedCommitEntry> resolveSelection(
            Path upstreamClone,
            List<GitRunner.DecoratedCommitEntry> history,
            String fromSha,
            String toSha,
            List<String> shas)
            throws IOException, InterruptedException
    {
        boolean hasRange = fromSha != null && !fromSha.isBlank()
                && toSha != null && !toSha.isBlank();
        if (hasRange) {
            return rangeOldestFirst(upstreamClone, history, fromSha, toSha);
        }
        if (shas == null || shas.isEmpty() || shas.size() > MAX_COMMITS) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "choose between 1 and " + MAX_COMMITS + " commits, or a from/to range");
        }
        return contiguousOldestFirst(upstreamClone, history, shas);
    }

    public synchronized UpstreamCherryPickJobDto enqueue(
            String workspaceId,
            StartRequest request)
            throws IOException, InterruptedException
    {
        requireNonNull(request, "request is null");
        boolean hasRange = request.fromSha() != null && !request.fromSha().isBlank()
                && request.toSha() != null && !request.toSha().isBlank();
        if (!hasRange && (request.shas() == null
                || request.shas().isEmpty()
                || request.shas().size() > MAX_COMMITS)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "choose between 1 and " + MAX_COMMITS + " commits, or a from/to range");
        }
        requireText(request.targetBranch(), "targetBranch");
        if (!git.isValidBranchName(request.targetBranch())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "targetBranch is not a valid git branch name");
        }
        if (request.createHarnessWatch() && !request.openDraftPr()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "a harness watch requires a draft pull request");
        }
        long budget = request.budgetMilliUsd() == null
                ? 5_000L
                : request.budgetMilliUsd();
        if (budget < 100 || budget > 100_000L) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "budgetMilliUsd must be between 100 and 100000");
        }

        WorkspaceRelationService.ResolvedRelation relation =
                relations.requireResolved(workspaceId);
        if (!relation.relation().commitsEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "upstream commit reading is disabled for this relation");
        }
        git.fetch(relation.upstreamClone());
        git.fetch(relation.targetClone());
        String sourceBranch = request.sourceBranch() == null
                || request.sourceBranch().isBlank()
                ? relations.defaultBranch(relation.upstream(), relation.upstreamClone())
                : request.sourceBranch().strip();
        String sourceRef = relations.resolveFetchedRemoteRef(
                relation.upstreamClone(), sourceBranch);
        String baseBranch = relations.defaultBranch(relation.target(), relation.targetClone());
        String movingBaseRef = relations.resolveFetchedRemoteRef(
                relation.targetClone(), baseBranch);
        String baseRef = git.resolveCommitSha(relation.targetClone(), movingBaseRef)
                .orElseThrow(() -> new IllegalStateException(
                        "target base is unavailable: " + movingBaseRef));
        if (git.refExists(relation.targetClone(), request.targetBranch())
                || git.refExists(
                        relation.targetClone(), "origin/" + request.targetBranch())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "target branch already exists: " + request.targetBranch());
        }

        List<GitRunner.DecoratedCommitEntry> history = git.listDecoratedCommits(
                relation.upstreamClone(), sourceRef, HISTORY_LIMIT);
        List<GitRunner.DecoratedCommitEntry> ordered = resolveSelection(
                relation.upstreamClone(), history,
                request.fromSha(), request.toSha(), request.shas());
        Set<String> picked = relations.pickedCommitSubjects(relation, baseRef);
        SkipFilters filters = SkipFilters.normalize(
                request.skipStartsWith(), request.skipContains());
        // Same planner the preview ran, against freshly fetched refs. Its
        // verdict is carried into the run rather than rediscovered pick by
        // pick, so the queue opens on the list the dry run promised.
        List<PlannedCommit> planned = plan(ordered, picked, filters);
        List<CommitSpec> specs = planned.stream()
                .map(commit -> new CommitSpec(commit.sha(), commit.subject()))
                .toList();
        List<String> preSkipped = planned.stream()
                .filter(commit -> !commit.pick())
                .map(PlannedCommit::sha)
                .toList();
        if (preSkipped.size() == specs.size()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    filters.isEmpty()
                            ? "the selected upstream range is already present in the fork"
                            : "every commit in that range is already in the fork or excluded by a filter");
        }

        String id = UUID.randomUUID().toString();
        Path worktree = relation.targetClone()
                .resolveSibling(relation.targetClone().getFileName()
                        + ".bytequay-worktrees")
                .resolve("upstream-cherry-pick")
                .resolve(id)
                .toAbsolutePath().normalize();
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json,
                    applied_shas_json, skipped_shas_json,
                    next_commit_index, conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    pr_description, skip_filters_json, compile_script,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?,
                    '[]', ?, 0, '[]', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                workspaceId,
                relation.relation().upstreamWorkspaceId(),
                sourceBranch,
                sourceRef,
                baseBranch,
                baseRef,
                request.targetBranch(),
                json(specs),
                json(preSkipped),
                worktree.toString(),
                request.openDraftPr() ? 1 : 0,
                request.createHarnessWatch() ? 1 : 0,
                budget,
                normalizedDescription(request.prDescription()),
                json(filters),
                // How this project compiles, read out of its own CI config. A
                // parser rather than a question for the model: the answer is
                // executed, so it must come from the repository.
                CiJobScriptReader.anyBuildScript(relation.targetClone()).orElse(null),
                now,
                now);
        record(id, null, "start",
                "Sync run started — " + (specs.size() - preSkipped.size())
                        + " commits from " + sourceBranch + " onto "
                        + request.targetBranch(),
                preSkipped.isEmpty()
                        ? "worktree " + worktree
                        : preSkipped.size() + " of " + specs.size()
                                + " already in the fork or filtered out\nworktree " + worktree,
                null, null);
        for (int index = 0; index < planned.size(); index++) {
            PlannedCommit dropped = planned.get(index);
            if (!dropped.pick()) {
                record(id, index, "skip", "Skipped " + dropped.subject(),
                        dropped.skipReason(), null, null);
            }
        }
        launchAfterCommit(id);
        return require(workspaceId, id);
    }

    public UpstreamCherryPickJobDto require(String workspaceId, String id)
    {
        return requireOwned(workspaceId, id).dto();
    }

    /**
     * Everything the run view renders: the commit queue with each pick's state,
     * and the command log behind it. The log is windowed to the most recent
     * {@code eventLimit} entries — a range can be hundreds of commits long and
     * the view only scrolls back so far.
     */
    public UpstreamCherryPickRunDto run(String workspaceId, String id, int eventLimit)
    {
        JobRow row = requireOwned(workspaceId, id);
        return new UpstreamCherryPickRunDto(
                row.dto(),
                row.baseBranch(),
                commitQueue(row),
                events(id, eventLimit));
    }

    /**
     * Stops the run before the next pick. A pick is a single git command, so
     * there is no safe point inside one: both "pause after this pick" and
     * "park now" land here and take effect at the next commit boundary.
     */
    public UpstreamCherryPickJobDto pause(String workspaceId, String id)
    {
        JobRow row = requireOpen(workspaceId, id);
        if (!LIVE_STATUSES.contains(row.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "only a running upstream cherry-pick can be paused");
        }
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET pause_requested = 1, updated_at_ms = ?
                WHERE id = ?
                """, now(), id);
        record(id, null, "note", "Pause requested — stopping after this pick",
                null, null, null);
        return require(workspaceId, id);
    }

    /**
     * Drops the commit the run is stopped on and carries on with the next one.
     * Only offered while parked: skipping a commit the worker is mid-way
     * through would race it.
     */
    public UpstreamCherryPickJobDto skipCurrent(String workspaceId, String id)
            throws IOException, InterruptedException
    {
        JobRow row = requireOpen(workspaceId, id);
        if (!"PAUSED_CONFLICT".equals(row.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "only a parked upstream cherry-pick can skip its commit");
        }
        if (row.nextCommitIndex() >= row.specs().size()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "the parked run has no current commit");
        }
        CommitSpec current = row.specs().get(row.nextCommitIndex());
        Path worktree = Path.of(row.worktreePath());
        if (git.cherryPickInProgress(worktree)) {
            git.abortInProgressOperationForRepair(worktree);
        }
        record(id, row.nextCommitIndex(), "skip",
                "Skipped " + current.subject(), "dropped at your request", null, null);
        progress(
                id,
                row.appliedShas(),
                append(row.skippedShas(), current.sha()),
                row.nextCommitIndex() + 1);
        clearPauseRequest(id);
        queue(id);
        launch(id);
        return require(workspaceId, id);
    }

    /**
     * Ends the run for good: the picker stops at the next commit boundary, the
     * harness watch it created is stopped, and its isolated worktree is removed.
     * Nothing that was committed is touched — the result branch and this run's
     * log both survive, so a closed run stays readable.
     *
     * <p>The worktree is only removed here when no worker owns it; a run closed
     * mid-pick has its worktree removed by that worker as it exits, because
     * pulling the directory out from under a running {@code git cherry-pick} is
     * exactly the kind of damage the isolated worktree exists to prevent.
     */
    public UpstreamCherryPickJobDto close(String workspaceId, String id)
    {
        closeRun(requireOwned(workspaceId, id), "at your request");
        return require(workspaceId, id);
    }

    /**
     * Closes the run and then forgets it: same teardown as {@link #close}, plus
     * the run's own record and log. For a run whose result is already on the
     * remote there is nothing left worth keeping locally, and the sync list is
     * where a finished range otherwise piles up.
     *
     * <p>A run with a worker still on it is closed but not deleted — the worker
     * writes to these rows as it winds down. Closing sets it on the path to
     * removal; deleting again once it has stopped finishes the job.
     */
    public void delete(String workspaceId, String id)
    {
        JobRow row = requireOwned(workspaceId, id);
        closeRun(row, "at your request");
        if (activeJobs.contains(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "the run is still stopping; it can be deleted in a moment");
        }
        // The log is declared ON DELETE CASCADE, but that only fires when the
        // connection has foreign keys switched on. Deleting it outright keeps
        // the run from leaving its events behind if that pragma ever changes.
        jdbc.update("DELETE FROM upstream_cherry_pick_event WHERE job_id = ?", id);
        jdbc.update("DELETE FROM upstream_cherry_pick_job WHERE id = ?", id);
    }

    /**
     * A run closes for exactly two reasons: the user closed it, or the pull
     * request it produced was merged or closed on the remote. Either way the
     * picker stops, the watch stops, and the worktree goes.
     */
    private void closeRun(JobRow row, String reason)
    {
        if (row.closedAt() != null) {
            return;
        }
        // closed_at_ms is the terminal marker, and the only one — the status column
        // has a CHECK constraint that admits no closed value, and inventing one
        // here failed against the real schema while the test fixture's looser DDL
        // let it pass. The status stays as it was, which is honest history: this
        // run was parked when you closed it. Readers must test closedAt.
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET closed_at_ms = ?, pause_requested = 0, repair_pending = 0,
                    updated_at_ms = ?
                WHERE id = ? AND closed_at_ms IS NULL
                """, now(), now(), row.id());
        record(row.id(), null, "closed",
                "Run closed " + reason + (row.harnessWatchId() == null
                        ? " — worktree removed"
                        : " — watch stopped and worktree removed"),
                "the result branch and this log are kept", null, null);
        stopWatch(row);
        if (!activeJobs.contains(row.id())) {
            removeWorktree(requireRow(row.id()));
        }
        releaseResources(row);
    }

    /**
     * Everything a run accumulated outside its own log: the agent's on-disk
     * session, its transcripts, and the local branch. A closed run is over, and
     * these are the parts that keep costing disk after it is.
     *
     * <p>Each step is best-effort and independently guarded — the run is already
     * closed by the time this runs, so a resource that cannot be released is
     * worth a log line, never a failed close the user cannot retry.
     */
    private void releaseResources(JobRow row)
    {
        removeAgentSession(row);
        // The transcripts are by far the biggest thing in the log, and the run
        // they explain is finished. The agent's one-line summaries stay.
        jdbc.update("""
                DELETE FROM upstream_cherry_pick_event
                WHERE job_id = ? AND kind = 'agent_log'
                """, row.id());
        deleteResultBranch(row);
    }

    /**
     * Deletes the CLI agent's on-disk session for this run's worktree. Claude
     * keys a session directory by the working directory it ran in, with every
     * non-alphanumeric character replaced by a dash — so the name is derived,
     * never stored, and the substitution itself is what keeps the result a
     * single path segment that cannot escape {@code ~/.claude/projects}.
     *
     * <p>Only a worktree path this service built is ever encoded, which is what
     * the job-id check establishes. Codex keeps its sessions elsewhere and is
     * left alone.
     */
    private void removeAgentSession(JobRow row)
    {
        if (row.worktreePath() == null || !row.worktreePath().endsWith(row.id())) {
            return;
        }
        Path projects = Path.of(System.getProperty("user.home"), ".claude", "projects");
        Path session = projects.resolve(
                row.worktreePath().replaceAll("[^a-zA-Z0-9]", "-"));
        try {
            if (Files.isDirectory(session)) {
                FileSystemUtils.deleteRecursively(session);
            }
        }
        catch (IOException | RuntimeException e) {
            log.warn("removing agent session {} failed: {}", session, e.getMessage());
        }
    }

    /**
     * Drops the local result branch. Gated on the run having opened a pull
     * request: that is the proof the picks reached the remote. A run closed
     * before it pushed keeps its branch, because the branch is then the only
     * copy of the work and deleting it would be unrecoverable.
     */
    private void deleteResultBranch(JobRow row)
    {
        if (row.prNumber() == null || row.resultBranch() == null) {
            return;
        }
        try {
            WorkspaceRelationService.ResolvedRelation relation =
                    relations.requireResolved(row.workspaceId());
            if (git.refExists(relation.targetClone(), row.resultBranch())) {
                git.deleteBranches(relation.targetClone(), List.of(row.resultBranch()));
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("deleting sync branch {} was interrupted", row.resultBranch());
        }
        catch (IOException | RuntimeException e) {
            log.warn("deleting sync branch {} failed: {}",
                    row.resultBranch(), e.getMessage());
        }
    }

    /**
     * The second way a run closes: its pull request reached a terminal state on
     * the remote, so neither the run nor the worktree it holds has anything left
     * to do. Polled on a background cadence — never on a user's turn.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void closeRunsWhosePullRequestEnded()
    {
        for (JobRow row : jdbc.query("""
                SELECT * FROM upstream_cherry_pick_job
                WHERE closed_at_ms IS NULL AND pr_number IS NOT NULL
                """, this::mapRow)) {
            try {
                WorkspaceRelationService.ResolvedRelation relation =
                        relations.requireResolved(row.workspaceId());
                prSync.syncExternalPR(relation.target().fullName(), row.prNumber())
                        .map(PR::status)
                        .filter(status -> PR.STATUS_MERGED.equals(status)
                                || PR.STATUS_CLOSED.equals(status))
                        .ifPresent(status -> {
                            // A merge is the run's last chance to remember anything:
                            // the worktree still holds the merged history, the session
                            // still remembers what it tried, and whatever a reviewer
                            // corrected before merging is visible nowhere else. So the
                            // retrospective runs before teardown, never after.
                            if (PR.STATUS_MERGED.equals(status)) {
                                writeRetrospective(requireRow(row.id()));
                            }
                            closeRun(
                                    requireRow(row.id()),
                                    "— pull request #" + row.prNumber() + " was "
                                            + (PR.STATUS_MERGED.equals(status)
                                                    ? "merged" : "closed"));
                        });
            }
            catch (RuntimeException e) {
                log.warn("checking pull request state for sync run {} failed: {}",
                        row.id(), e.getMessage());
            }
        }
    }

    /**
     * The run's last act: what this range taught the fork, written by the session
     * that lived through it. Distinct from the per-failure memories phase 2 writes
     * — this one is about the range as a whole, and it is the only moment anything
     * a reviewer changed before merging is still there to read.
     *
     * <p>Best-effort by design. A retrospective that fails loses a memory; it must
     * never leave a worktree behind, so nothing here can stop the teardown.
     */
    private void writeRetrospective(JobRow row)
    {
        SyncRetrospectiveWriter writer = retrospective.getIfAvailable();
        String session = agentSessionId(row.id());
        if (writer == null || row.worktreePath() == null || session == null) {
            // No session means no picks were repaired and nothing was chased —
            // there is no run to look back over.
            return;
        }
        Path worktree = Path.of(row.worktreePath());
        if (!Files.isDirectory(worktree)) {
            return;
        }
        try {
            writer.write(
                    worktree, row.workspaceId(), row.prNumber(),
                    Math.max(0, row.budgetMilliUsd() - spentMilliUsd(row.id())),
                    session);
            record(row.id(), null, "note",
                    "Wrote what this range taught the repository", null, null, null);
        }
        catch (RuntimeException e) {
            log.warn("retrospective for sync run {} failed: {}", row.id(), e.getMessage());
        }
    }

    private void stopWatch(JobRow row)
    {
        if (row.harnessWatchId() == null) {
            return;
        }
        HarnessWatchHandoff handoff = harnessHandoff.getIfAvailable();
        if (handoff == null) {
            return;
        }
        try {
            handoff.stopWatch(row.workspaceId(), row.harnessWatchId());
        }
        catch (RuntimeException e) {
            // The run is closed either way; a watch that cannot be stopped is
            // worth a log line, not a failed close the user cannot retry.
            log.warn("stopping harness watch {} failed: {}",
                    row.harnessWatchId(), e.getMessage());
        }
    }

    private void removeWorktree(JobRow row)
    {
        Path worktree = Path.of(row.worktreePath());
        try {
            WorkspaceRelationService.ResolvedRelation relation =
                    relations.requireResolved(row.workspaceId());
            if (Files.isDirectory(worktree)) {
                git.worktreeRemove(relation.targetClone(), worktree);
            }
            git.worktreePrune(relation.targetClone());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("removing sync worktree {} was interrupted", worktree);
        }
        catch (IOException | RuntimeException e) {
            log.warn("removing sync worktree {} failed: {}", worktree, e.getMessage());
        }
    }

    /**
     * Records the user's steering note on the run. It lands in the log the run
     * view renders, next to the picks it was written about.
     */
    public UpstreamCherryPickJobDto guide(String workspaceId, String id, String text)
    {
        JobRow row = requireOpen(workspaceId, id);
        String note = text == null ? "" : text.strip();
        if (note.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "guidance text is required");
        }
        if (note.length() > 4_000) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "guidance must be at most 4000 characters");
        }
        // ponytail: stored, not consumed — the conflict-repair agent that will
        // read it does not exist yet, so guidance is a durable note for now.
        record(id, currentPickIndex(row), "guidance", note, null, null, null);
        return row.dto();
    }

    private JobRow requireOwned(String workspaceId, String id)
    {
        JobRow row = requireRow(id);
        if (!workspaceId.equals(row.workspaceId())) {
            throw new NoSuchElementException("no upstream cherry-pick job: " + id);
        }
        return row;
    }

    /** Every action but {@link #close} and reading is refused once closed. */
    private JobRow requireOpen(String workspaceId, String id)
    {
        JobRow row = requireOwned(workspaceId, id);
        if (row.closedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "this upstream sync run is closed");
        }
        return row;
    }

    private static Integer currentPickIndex(JobRow row)
    {
        return row.nextCommitIndex() < row.specs().size()
                ? row.nextCommitIndex() : null;
    }

    /** Queue rows, oldest pick first — the order the run applies them in. */
    private static List<UpstreamCherryPickCommitDto> commitQueue(JobRow row)
    {
        Set<String> applied = Set.copyOf(row.appliedShas());
        Set<String> skipped = Set.copyOf(row.skippedShas());
        Set<String> conflicted = Set.copyOf(row.conflictedShas());
        boolean stopped = "COMPLETED".equals(row.status()) || "FAILED".equals(row.status());
        List<UpstreamCherryPickCommitDto> rows = new ArrayList<>();
        for (int index = 0; index < row.specs().size(); index++) {
            CommitSpec spec = row.specs().get(index);
            String state;
            if (skipped.contains(spec.sha())) {
                state = "skipped";
            }
            else if (applied.contains(spec.sha())) {
                state = conflicted.contains(spec.sha()) ? "conflicted" : "applied";
            }
            else if (index == row.nextCommitIndex() && !stopped) {
                state = "current";
            }
            else {
                state = "waiting";
            }
            rows.add(new UpstreamCherryPickCommitDto(
                    index, spec.sha(), shortSha(spec.sha()), spec.subject(), state));
        }
        return List.copyOf(rows);
    }

    private List<UpstreamCherryPickEventDto> events(String jobId, int requestedLimit)
    {
        int limit = Math.min(Math.max(requestedLimit, 1), 2_000);
        List<UpstreamCherryPickEventDto> newestFirst = jdbc.query("""
                SELECT * FROM upstream_cherry_pick_event
                WHERE job_id = ?
                ORDER BY ordinal DESC
                LIMIT ?
                """, (rs, ignored) -> {
            int pick = rs.getInt("pick_index");
            Integer pickIndex = rs.wasNull() ? null : pick;
            int exit = rs.getInt("exit_code");
            Integer exitCode = rs.wasNull() ? null : exit;
            long duration = rs.getLong("duration_ms");
            Long durationMs = rs.wasNull() ? null : duration;
            return new UpstreamCherryPickEventDto(
                    rs.getString("id"),
                    rs.getInt("ordinal"),
                    pickIndex,
                    rs.getString("kind"),
                    rs.getString("title"),
                    rs.getString("detail"),
                    exitCode,
                    durationMs,
                    Instant.ofEpochMilli(rs.getLong("created_at_ms")));
        }, jobId, limit);
        return newestFirst.reversed();
    }

    private static final int MAX_EVENT_DETAIL = 8_000;
    /** An agent transcript is the evidence behind every other line in the run,
     *  so it gets room the ordinary log lines do not need. */
    private static final int MAX_TRANSCRIPT_DETAIL = 64 * 1024;

    /**
     * Appends one line to the run log. The log is what the view renders, never
     * something the run depends on, so a failed write is logged and swallowed
     * rather than failing the pick that produced it.
     */
    private void record(
            String jobId,
            Integer pickIndex,
            String kind,
            String title,
            String detail,
            Integer exitCode,
            Long durationMs)
    {
        record(jobId, pickIndex, kind, title, detail, exitCode, durationMs, MAX_EVENT_DETAIL);
    }

    private void record(
            String jobId,
            Integer pickIndex,
            String kind,
            String title,
            String detail,
            Integer exitCode,
            Long durationMs,
            int detailCap)
    {
        try {
            jdbc.update("""
                    INSERT INTO upstream_cherry_pick_event (
                        id, job_id, ordinal, pick_index, kind, title, detail,
                        exit_code, duration_ms, created_at_ms)
                    VALUES (?, ?, (SELECT COALESCE(MAX(ordinal), 0) + 1
                        FROM upstream_cherry_pick_event WHERE job_id = ?),
                        ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    jobId,
                    jobId,
                    pickIndex,
                    kind,
                    title,
                    clampDetail(detail, detailCap),
                    exitCode,
                    durationMs,
                    now());
        }
        catch (DataAccessException e) {
            log.warn("recording upstream cherry-pick event failed: {}", e.getMessage());
        }
    }

    private static String clampDetail(String detail)
    {
        return clampDetail(detail, MAX_EVENT_DETAIL);
    }

    private static String clampDetail(String detail, int max)
    {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String trimmed = detail.strip();
        return trimmed.length() <= max
                ? trimmed
                : trimmed.substring(0, max) + "\n…";
    }

    /**
     * The turn's own transcript — its conversation and tool calls — as its own
     * log line, so the summary above it stays one sentence and the evidence is
     * one click away. Written whether the turn succeeded or not: a turn that did
     * not run is exactly the one worth reading.
     */
    private void recordTranscript(String jobId, Integer index, String transcript)
    {
        if (transcript == null || transcript.isBlank()) {
            return;
        }
        record(jobId, index, "agent_log", "Agent transcript", transcript,
                null, null, MAX_TRANSCRIPT_DETAIL);
    }

    private static String shortSha(String sha)
    {
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    /** Durable newest-first discovery for restoring the dialog after reload. */
    public List<UpstreamCherryPickJobDto> list(String workspaceId, int requestedLimit)
    {
        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        return jdbc.query("""
                SELECT * FROM upstream_cherry_pick_job
                WHERE workspace_id = ?
                ORDER BY created_at_ms DESC
                LIMIT ?
                """, this::mapRow, workspaceId, limit).stream()
                .map(JobRow::dto)
                .toList();
    }

    /**
     * Continues only after the user resolved the stopped index. It never asks
     * an agent to modify the worktree. If the user already ran cherry-pick
     * --continue themselves, the exact expected subject is checked before the
     * durable trailer and progress row are repaired.
     */
    public UpstreamCherryPickJobDto resume(String workspaceId, String id)
            throws IOException, InterruptedException
    {
        JobRow row = requireOpen(workspaceId, id);
        if (!"PAUSED_CONFLICT".equals(row.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "upstream cherry-pick is not paused for a conflict");
        }
        if (row.pauseRequested() || row.repairPending()) {
            // Both parks happen at a commit boundary, so the current commit was
            // never started and there is nothing to continue or reconcile. A
            // repair park leaves repair_pending set through this: the pick before
            // the boundary is committed but unrepaired, and the worker owes it a
            // retry before it picks anything else.
            clearPauseRequest(id);
            record(id, currentPickIndex(row), "note", "Resumed", null, null, null);
            queue(id);
            launch(id);
            return require(workspaceId, id);
        }
        if (row.nextCommitIndex() >= row.specs().size()) {
            throw new IllegalStateException("paused job has no current commit");
        }
        Path worktree = Path.of(row.worktreePath());
        CommitSpec current = row.specs().get(row.nextCommitIndex());
        if (git.cherryPickInProgress(worktree)) {
            GitRunner.CherryPickOutcome continued = git.continueCherryPick(worktree);
            if (!continued.complete()) {
                // An empty pick can never be continued, so resuming one used to
                // park it again and leave the run with no way forward at all.
                // Drop it and carry on, exactly as the picker now does.
                if (isEmptyPick(worktree, continued)) {
                    git.skipCherryPick(worktree);
                    record(row.id(), row.nextCommitIndex(), "skip",
                            "Skipped " + current.subject(),
                            "already applied — the pick came out empty", null, null);
                    progress(
                            row.id(),
                            row.appliedShas(),
                            append(row.skippedShas(), current.sha()),
                            row.nextCommitIndex() + 1);
                    clearPauseRequest(row.id());
                    queue(row.id());
                    launch(row.id());
                    return require(workspaceId, id);
                }
                pause(row.id(), continued.conflictPaths(), continued.message());
                return require(workspaceId, id);
            }
        }
        else {
            GitRunner.CommitDetailEntry head = git.commitDetail(worktree, "HEAD")
                    .orElseThrow(() -> new IllegalStateException(
                            "resolved cherry-pick HEAD is unavailable"));
            if (!current.subject().equals(head.subject())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "the paused cherry-pick was aborted or HEAD changed; expected "
                                + current.subject());
            }
        }
        List<String> applied = append(row.appliedShas(), current.sha());
        record(row.id(), row.nextCommitIndex(), "note",
                "Resumed after your resolution", current.subject(), null, null);
        progress(
                row.id(),
                applied,
                row.skippedShas(),
                append(row.conflictedShas(), current.sha()),
                row.nextCommitIndex() + 1);
        queue(row.id());
        launch(row.id());
        return require(workspaceId, id);
    }

    /**
     * Raises a parked run's ceiling and carries on. The agent sets its own bounds
     * now — no attempt counter, no retry limit — so the budget is the only hard
     * stop, and it is deliberately one the user can lift rather than one that ends
     * the run. Resuming reuses the run's existing agent session.
     */
    public UpstreamCherryPickJobDto raiseBudget(
            String workspaceId, String id, long additionalMilliUsd)
            throws IOException, InterruptedException
    {
        JobRow row = requireOpen(workspaceId, id);
        if (additionalMilliUsd < 100 || additionalMilliUsd > 1_000_000) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "additionalMilliUsd must be between 100 and 1000000");
        }
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET budget_milli_usd = budget_milli_usd + ?, updated_at_ms = ?
                WHERE id = ?
                """, additionalMilliUsd, now(), id);
        record(id, currentPickIndex(row), "note",
                "Budget raised by " + additionalMilliUsd + " milli-USD", null, null, null);
        if ("PAUSED_CONFLICT".equals(row.status())) {
            return resume(workspaceId, id);
        }
        return require(workspaceId, id);
    }

    /** Explicitly retries a failed job without discarding durable progress. */
    public UpstreamCherryPickJobDto retry(String workspaceId, String id)
    {
        requireOpen(workspaceId, id);
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE upstream_cherry_pick_job
                    SET status = 'QUEUED', conflict_paths_json = '[]',
                        error_message = NULL, updated_at_ms = ?
                    WHERE id = ? AND workspace_id = ? AND status = 'FAILED'
                    """, now(), id, workspaceId);
        }
        catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "another upstream cherry-pick is active for this workspace",
                    e);
        }
        if (updated == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "only a failed upstream cherry-pick can be retried");
        }
        launch(id);
        return require(workspaceId, id);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover()
    {
        jdbc.queryForList("""
                SELECT id FROM upstream_cherry_pick_job
                WHERE status IN ('QUEUED', 'RUNNING') AND closed_at_ms IS NULL
                """, String.class).forEach(this::launch);
    }

    /**
     * Whether a worker thread is still running for this job. Visible for tests:
     * a status lands in the database inside {@code execute}, but the worker keeps
     * running for a few instructions after that — long enough for a test that only
     * waits on the status to tear its temp directory down underneath it.
     */
    boolean isWorking(String id)
    {
        return activeJobs.contains(id);
    }

    private void launch(String id)
    {
        if (!activeJobs.add(id)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                execute(id);
                JobRow finished = requireRow(id);
                if (finished.closedAt() != null) {
                    removeWorktree(finished);
                }
            }
            finally {
                activeJobs.remove(id);
                // A resume or retry can race the final few instructions of a
                // worker. If it queued work while this id was still active,
                // pick that durable state up now.
                try {
                    if ("QUEUED".equals(requireRow(id).status())) {
                        launch(id);
                    }
                }
                catch (RuntimeException ignored) {
                    // Job/workspace may have been deleted while it ran.
                }
            }
        });
    }

    private void launchAfterCommit(String id)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            launch(id);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization()
                {
                    @Override
                    public void afterCommit()
                    {
                        launch(id);
                    }
                });
    }

    private void execute(String id)
    {
        JobRow row = requireRow(id);
        if (!LIVE_STATUSES.contains(row.status()) || row.closedAt() != null) {
            return;
        }
        running(id);
        try {
            WorkspaceRelationService.ResolvedRelation relation =
                    relations.requireResolved(row.workspaceId());
            if (!row.upstreamWorkspaceId().equals(
                    relation.relation().upstreamWorkspaceId())) {
                throw new IllegalStateException(
                        "workspace upstream changed while cherry-pick was queued");
            }
            Path worktree = ensureWorktree(row, relation);
            if (git.cherryPickInProgress(worktree)) {
                pause(id, git.unresolvedPaths(worktree),
                        "cherry-pick conflict requires human resolution");
                return;
            }

            row = reconcileUnpersistedPick(row, worktree);

            git.fetchObjects(
                    relation.targetClone(),
                    relation.upstreamClone(),
                    row.specs().stream().map(CommitSpec::sha).toList());
            // The already-applied check reads the target branch only, so the
            // resume path no longer scans upstream history at all.
            Set<String> picked = new HashSet<>(
                    relations.pickedCommitSubjects(relation, row.baseRef()));
            List<String> applied = new ArrayList<>(row.appliedShas());
            List<String> skipped = new ArrayList<>(row.skippedShas());
            List<String> conflicted = new ArrayList<>(row.conflictedShas());
            int index = row.nextCommitIndex();
            if (row.repairPending() && index > 0
                    && !retryPendingRepair(
                            id, worktree, index - 1, row.specs().get(index - 1))) {
                return;
            }
            while (index < row.specs().size()) {
                String stop = stopReason(id);
                if ("closed".equals(stop)) {
                    // close() already wrote the log line; the worktree is
                    // removed by this worker's exit path.
                    return;
                }
                if ("paused".equals(stop)) {
                    parkOnRequest(id, index);
                    return;
                }
                CommitSpec commit = row.specs().get(index);
                // Same two skip reasons the preview reported, re-evaluated here
                // because the worker resumes across restarts and the fork may
                // have gained commits since the range was planned.
                String skipReason = picked.contains(
                        WorkspaceRelationService.normalizeSubject(commit.subject()))
                        ? "already in the fork"
                        : row.skipFilters().skipReason(commit.subject());
                if (skipReason != null) {
                    // Already on the list the plan drew at enqueue: walking past
                    // it must not log or count it a second time.
                    if (!skipped.contains(commit.sha())) {
                        skipped.add(commit.sha());
                        record(id, index, "skip",
                                "Skipped " + commit.subject(), skipReason, null, null);
                    }
                    index++;
                    progress(id, applied, skipped, conflicted, index);
                    continue;
                }
                long startedAt = System.currentTimeMillis();
                GitRunner.CherryPickOutcome outcome =
                        git.cherryPick(worktree, List.of(commit.sha()), true);
                long tookMs = System.currentTimeMillis() - startedAt;
                boolean conflictedPick = !outcome.complete();
                if (conflictedPick) {
                    record(id, index, "command",
                            "git cherry-pick -x " + shortSha(commit.sha()),
                            outcome.message(), 1, tookMs);
                    if (!git.cherryPickInProgress(worktree)) {
                        throw new IllegalStateException(outcome.message() == null
                                ? "upstream cherry-pick failed"
                                : outcome.message());
                    }
                    // Commit git's own three-way resolution first — markers and all —
                    // so the repair that follows is an ordinary fixup on top of a real
                    // commit rather than an edit to a half-finished pick.
                    git.stageAll(worktree);
                    GitRunner.CherryPickOutcome continued = git.continueCherryPick(worktree);
                    if (!continued.complete()) {
                        // A pick the branch already carries. Git will not record an
                        // empty commit: it holds the sequencer open and rejects
                        // --continue until the pick is skipped or forced through.
                        // Nothing is unresolved and there is nothing to commit, so
                        // this is not a conflict and must not park a human.
                        if (isEmptyPick(worktree, continued)) {
                            if (git.cherryPickInProgress(worktree)) {
                                git.skipCherryPick(worktree);
                            }
                            skipped.add(commit.sha());
                            record(id, index, "skip",
                                    "Skipped " + commit.subject(),
                                    "already applied — the pick came out empty",
                                    null, null);
                            index++;
                            progress(id, applied, skipped, conflicted, index);
                            continue;
                        }
                        pause(id, continued.conflictPaths(), continued.message());
                        record(id, index, "park",
                                "Parked — git cannot finish this pick",
                                continued.message(), null, null);
                        return;
                    }
                    conflicted.add(commit.sha());
                    record(id, index, "note",
                            "Committed git's three-way resolution",
                            outcome.conflictPaths().isEmpty()
                                    ? "conflicts carried forward for repair"
                                    : String.join("\n", outcome.conflictPaths()),
                            null, null);
                }
                else {
                    record(id, index, "command",
                            "git cherry-pick -x " + shortSha(commit.sha()),
                            outcome.message(), 0, tookMs);
                }
                applied.add(commit.sha());
                picked.add(WorkspaceRelationService.normalizeSubject(commit.subject()));
                // The pick is committed, so the index advances even if the repair
                // below parks — otherwise a resume re-picks a commit that is
                // already applied. It does mean the queue shows this pick done
                // while its repair is still running, which is what the live agent
                // panel and the "repairing" line are for.
                index++;
                progress(id, applied, skipped, conflicted, index);

                // A clean pick applied what upstream already compiled; a conflicted one
                // holds a resolution nobody has judged, so that is where the gate goes.
                if (conflictedPick
                        && !repairConflictedPick(
                                id, worktree, index - 1, commit, outcome.conflictPaths())) {
                    return;
                }
                row = requireRow(id);
            }

            row = requireRow(id);
            if (row.openDraftPr() && row.prNumber() == null) {
                PullRequest pr = openOrAdoptDraft(row, relation, worktree);
                storePullRequest(id, pr);
                record(id, null, "push",
                        "git push origin " + row.resultBranch(),
                        "the picked range is now on the remote", 0, null);
                record(id, null, "pr",
                        "Opened draft pull request #" + pr.number(), pr.htmlUrl(), null, null);
                row = requireRow(id);
            }
            if (row.createHarnessWatch() && row.harnessWatchId() == null) {
                HarnessWatchHandoff handoff = harnessHandoff.getIfAvailable();
                if (handoff == null) {
                    throw new IllegalStateException(
                            "CI harness watch service is unavailable");
                }
                if (row.prNumber() == null) {
                    throw new IllegalStateException(
                            "cannot create a harness watch without a pull request");
                }
                String watchId = handoff.create(
                        row.workspaceId(),
                        relation.target().fullName(),
                        row.prNumber(),
                        requireLocalPrId(row, relation),
                        row.resultBranch(),
                        row.worktreePath(),
                        row.budgetMilliUsd(),
                        agentSessionId(id));
                jdbc.update("""
                        UPDATE upstream_cherry_pick_job
                        SET harness_watch_id = ?, updated_at_ms = ?
                        WHERE id = ?
                        """, watchId, now(), id);
                record(id, null, "watch",
                        "CI Harness watch created — phase 2 drives the pull request green",
                        null, null, null);
            }
            record(id, null, "done",
                    "Range complete — " + row.appliedShas().size() + " picked, "
                            + row.skippedShas().size() + " skipped",
                    null, null, null);
            complete(id);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(id, "upstream cherry-pick was interrupted");
        }
        catch (Exception e) {
            log.warn("upstream cherry-pick {} failed: {}", id, e.getMessage());
            fail(id, e.getMessage() == null
                    ? "upstream cherry-pick failed"
                    : e.getMessage());
        }
    }

    /**
     * Finishes the repair the run parked on before it picks anything else. The
     * pick itself is already committed — the index advanced past it so a resume
     * would not apply it twice — which is exactly why a resume used to sail past
     * the unrepaired conflict and push git's markers to the pull request.
     *
     * <p>The park did not keep the conflicted paths, so they are read back off
     * the tree. Nothing left carrying a marker means the conflict was settled in
     * the worktree by hand, and the run simply carries on.
     *
     * @return false when the retry parked again and the worker must stop
     */
    private boolean retryPendingRepair(
            String id, Path worktree, int index, CommitSpec commit)
            throws IOException, InterruptedException
    {
        List<String> unrepaired = git.pathsWithConflictMarkers(worktree, List.of());
        if (unrepaired.isEmpty()) {
            clearRepairPending(id);
            return true;
        }
        record(id, index, "note", "Retrying the repair of " + commit.subject(),
                "the run parked here before its conflict was resolved", null, null);
        if (!repairConflictedPick(id, worktree, index, commit, unrepaired)) {
            return false;
        }
        clearRepairPending(id);
        return true;
    }

    /**
     * The per-commit handoff. A conflicted pick is committed with git's own
     * resolution, markers and all; the agent then repairs it, commits the
     * fixup and validates it. The program's part is starting that turn,
     * recording what came back, and refusing to carry on over a worktree the
     * next pick could not be applied to.
     *
     * <p>No attempt counter and no compile loop live here any more — both were
     * the program deciding something the agent is better placed to decide. It
     * retries as it sees fit within the budget and says when it is stuck.
     *
     * @return false when the run parked and the worker must stop
     */
    private boolean repairConflictedPick(
            String id,
            Path worktree,
            int index,
            CommitSpec commit,
            List<String> conflictPaths)
            throws IOException, InterruptedException
    {
        JobRow row = requireRow(id);
        ConflictRepairAdvisor advisor = repairAdvisor.getIfAvailable();
        if (advisor == null) {
            return parked(id, index, commit, "no repair agent is configured");
        }
        long remaining = row.budgetMilliUsd() - spentMilliUsd(id);
        if (remaining <= 0) {
            return parked(id, index, commit, "the repair budget is spent");
        }
        // Says who the agent is working on before it starts. The queue has
        // already moved past this pick by now, and a turn can run for minutes.
        record(id, index, "note", "Repairing " + commit.subject(),
                "the agent is resolving this pick's conflict", null, null);
        long startedAt = System.currentTimeMillis();
        ConflictRepairAdvisor.Outcome outcome;
        try {
            outcome = advisor.repair(
                    worktree, row.workspaceId(), commit.subject(), conflictPaths,
                    row.compileScript(), remaining, agentSessionId(id),
                    line -> stream.publish(id, line));
        }
        catch (RuntimeException e) {
            return parked(id, index, commit, e.getMessage() == null
                    ? "the repair turn failed" : e.getMessage());
        }
        addSpend(id, outcome.costMilliUsd());
        // One session for the whole run: later conflicts inherit what this one
        // established about the fork.
        rememberAgentSession(id, outcome.sessionId());
        recordTranscript(id, index, outcome.transcript());
        record(id, index, "agent", outcome.detail(),
                outcome.validated()
                        ? "agent resolved, committed and validated"
                        : "agent resolved and committed; validation could not run here",
                outcome.resolved() ? 0 : 1, System.currentTimeMillis() - startedAt);
        if (!outcome.resolved()) {
            return parked(id, index, commit, outcome.detail());
        }
        if (!outcome.validated()) {
            // Sticky for the run log only. From here the range's verdict comes
            // from CI, and the user needs to know that before trusting a green.
            markLocalGateUnavailable(id);
        }
        // A pick can only be applied to a clean tree; anything left behind
        // would surface as an unrelated failure on the next commit.
        if (git.hasUncommittedChanges(worktree)) {
            return parked(id, index, commit,
                    "the repair left uncommitted changes in the worktree");
        }
        // The one thing the verdict cannot be taken on trust for. Git's own
        // three-way output is already committed by the time the agent starts, so
        // a file it reported resolved but never edited keeps its markers, and
        // from here nothing else reads the diff before the branch is pushed.
        List<String> unresolved = git.pathsWithConflictMarkers(worktree, conflictPaths);
        if (!unresolved.isEmpty()) {
            return parked(id, index, commit,
                    "the repair left conflict markers in " + String.join(", ", unresolved));
        }
        recordFixup(id, index, worktree);
        return true;
    }

    /**
     * Names the commit the repair produced, so the queue can show the pick's
     * fixup rather than the fact it once conflicted. A repair that turned the
     * pick into a no-op leaves HEAD on the pick itself and has nothing to name.
     */
    private void recordFixup(String id, int index, Path worktree)
            throws IOException, InterruptedException
    {
        Optional<GitRunner.CommitDetailEntry> head = git.commitDetail(worktree, "HEAD");
        if (head.isEmpty() || !head.get().subject().startsWith("fixup!")) {
            return;
        }
        record(id, index, "fixup", shortSha(head.get().sha()), head.get().subject(),
                null, null);
    }

    /** Parks and returns false, so a caller can {@code return} the call itself. */
    private boolean parked(String id, int index, CommitSpec commit, String reason)
    {
        parkForRepair(id, index, commit, reason);
        return false;
    }

    private void parkForRepair(String id, int index, CommitSpec commit, String reason)
    {
        String message = reason == null || reason.isBlank()
                ? "the conflict repair could not be completed"
                : reason.strip();
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET status = 'PAUSED_CONFLICT', repair_pending = 1,
                    conflict_paths_json = '[]',
                    error_message = ?,
                    updated_at_ms = ?
                WHERE id = ?
                """, clampDetail(message), now(), id);
        record(id, index, "park",
                "Parked — " + commit.subject(),
                message + "\n\nnothing is pushed; take over in the worktree and resume",
                null, null);
    }

    private void markLocalGateUnavailable(String id)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET local_gate_unavailable = 1, updated_at_ms = ?
                WHERE id = ?
                """, now(), id);
    }

    private String agentSessionId(String id)
    {
        return jdbc.queryForObject("""
                SELECT agent_session_id FROM upstream_cherry_pick_job WHERE id = ?
                """, String.class, id);
    }

    private void rememberAgentSession(String id, String sessionId)
    {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET agent_session_id = ?, updated_at_ms = ?
                WHERE id = ?
                """, sessionId, now(), id);
    }

    private long spentMilliUsd(String id)
    {
        Long spent = jdbc.queryForObject("""
                SELECT spent_milli_usd FROM upstream_cherry_pick_job WHERE id = ?
                """, Long.class, id);
        return spent == null ? 0L : spent;
    }

    private void addSpend(String id, long costMilliUsd)
    {
        if (costMilliUsd <= 0) {
            return;
        }
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET spent_milli_usd = spent_milli_usd + ?, updated_at_ms = ?
                WHERE id = ?
                """, costMilliUsd, now(), id);
    }

    private Path ensureWorktree(
            JobRow row,
            WorkspaceRelationService.ResolvedRelation relation)
            throws IOException, InterruptedException
    {
        Path worktree = Path.of(row.worktreePath());
        if (Files.isDirectory(worktree)) {
            return worktree;
        }
        Files.createDirectories(worktree.getParent());
        if (git.refExists(relation.targetClone(), row.resultBranch())) {
            git.worktreePrune(relation.targetClone());
            git.worktreeAddExisting(
                    relation.targetClone(), worktree, row.resultBranch());
        }
        else {
            git.worktreeAdd(
                    relation.targetClone(),
                    worktree,
                    row.resultBranch(),
                    row.baseRef());
        }
        return worktree;
    }

    /**
     * Repairs the one-command crash window between a successful cherry-pick
     * and its trailer/progress writes. The exact base SHA persisted at enqueue
     * makes this comparison stable even if the target branch moves meanwhile.
     */
    private JobRow reconcileUnpersistedPick(JobRow row, Path worktree)
            throws IOException, InterruptedException
    {
        // Only the picks are durable progress. A repaired pick also leaves the
        // agent's `fixup!` commit on the branch, and counting those as picks
        // made every resume after two repairs look like history it could not
        // explain — which is what this check exists to refuse. Counting picks
        // rather than deriving an expected total also survives a repair that
        // was a no-op and committed nothing.
        List<GitRunner.CommitEntry> picks = git.listCommits(
                        worktree, row.baseRef() + "..HEAD", HISTORY_LIMIT).stream()
                .filter(entry -> !isRepairCommit(entry.subject()))
                .toList();
        if (picks.size() == row.appliedShas().size()) {
            return row;
        }
        if (picks.size() != row.appliedShas().size() + 1
                || row.nextCommitIndex() >= row.specs().size()) {
            throw new IllegalStateException(
                    "cherry-pick worktree history no longer matches durable progress");
        }
        CommitSpec current = row.specs().get(row.nextCommitIndex());
        GitRunner.CommitEntry head = picks.getFirst();
        if (!current.subject().equals(head.subject())) {
            throw new IllegalStateException(
                    "unexpected commit at cherry-pick HEAD: " + head.subject());
        }
        progress(
                row.id(),
                append(row.appliedShas(), current.sha()),
                row.skippedShas(),
                row.nextCommitIndex() + 1);
        return requireRow(row.id());
    }

    /**
     * Whether a pick that would not finish came out empty rather than
     * conflicted. Read off the worktree, not off git's message: after the
     * three-way stage there is nothing unresolved and nothing to commit only
     * when the branch already carries this change.
     */
    private boolean isEmptyPick(Path worktree, GitRunner.CherryPickOutcome continued)
            throws IOException, InterruptedException
    {
        return continued.conflictPaths().isEmpty()
                && git.unresolvedPaths(worktree).isEmpty()
                && !git.hasUncommittedChanges(worktree);
    }

    /** Anything git itself will fold away on an autosquash — never a pick. */
    private static boolean isRepairCommit(String subject)
    {
        return subject.startsWith("fixup!")
                || subject.startsWith("squash!")
                || subject.startsWith("amend!");
    }

    private PullRequest openOrAdoptDraft(
            JobRow row,
            WorkspaceRelationService.ResolvedRelation relation,
            Path worktree)
            throws IOException, InterruptedException
    {
        git.push(worktree);
        RepoRef target = RepoRef.of(
                relation.target().owner(), relation.target().repo());
        String pat = pats.resolve(target.fullName());
        String head = target.owner() + ":" + row.resultBranch();
        ListPullRequestsQuery query = new ListPullRequestsQuery(
                "open",
                Optional.of(head),
                Optional.of(row.baseBranch()),
                "created", "desc", 10, 1);
        Optional<PullRequest> existing = pullRequests
                .listPullRequests(pat, target, query).stream()
                .filter(pr -> target.fullName().equalsIgnoreCase(pr.repo()))
                .filter(pr -> row.resultBranch().equals(pr.headRef()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        // What landed on the branch, not what was selected — a range whose
        // skips outnumber its picks used to title the PR after the range.
        List<CommitSpec> picks = row.specs().stream()
                .filter(spec -> row.appliedShas().contains(spec.sha()))
                .toList();
        String title = picks.size() == 1
                ? picks.getFirst().subject()
                : "Cherry-pick " + picks.size() + " commits from "
                        + relation.upstream().fullName();
        String provenance = "Cherry-picked a contiguous range from `"
                + relation.upstream().fullName() + "/" + row.sourceBranch() + "`."
                + (row.skipFilters().isEmpty() ? ""
                        : " Commits excluded by a subject filter were skipped.");
        String body = row.prDescription() == null || row.prDescription().isBlank()
                ? provenance
                : row.prDescription() + "\n\n---\n\n" + provenance;
        try {
            return pullRequests.createPullRequest(
                    pat,
                    target,
                    CreatePullRequestCommand.draft(
                            head, row.baseBranch(), title, body));
        }
        catch (RuntimeException createFailure) {
            return pullRequests.listPullRequests(pat, target, query).stream()
                    .filter(pr -> row.resultBranch().equals(pr.headRef()))
                    .findFirst()
                    .orElseThrow(() -> createFailure);
        }
    }

    private static final int MAX_PR_DESCRIPTION = 60_000;

    private static String blankToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalizedDescription(String value)
    {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_PR_DESCRIPTION) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "pull-request description must be at most "
                            + MAX_PR_DESCRIPTION + " characters");
        }
        return normalized;
    }

    private SkipFilters readFilters(String value)
    {
        if (value == null || value.isBlank()) {
            return SkipFilters.none();
        }
        try {
            return mapper.readValue(value, SkipFilters.class);
        }
        catch (JsonProcessingException e) {
            // A row written before filters existed, or hand-edited. Applying no
            // filter is the safe reading: it picks more, never silently fewer.
            log.warn("unreadable cherry-pick skip filters; treating as none", e);
            return SkipFilters.none();
        }
    }

    private String requireLocalPrId(
            JobRow row,
            WorkspaceRelationService.ResolvedRelation relation)
    {
        return prSync.syncExternalPR(
                        relation.target().fullName(), row.prNumber())
                .map(pr -> pr.id())
                .orElseThrow(() -> new IllegalStateException(
                        "draft pull request could not be synced locally: "
                                + relation.target().fullName() + "#" + row.prNumber()));
    }

    /**
     * Expands a from/to pair into the inclusive range between them, oldest first.
     * Order of the two endpoints does not matter. Resolution happens against the
     * most recent {@code HISTORY_LIMIT} commits rather than the page the UI
     * renders, so a range may reach further back than what is displayed — but
     * not past that window: an older endpoint is rejected as not on the branch.
     */
    private List<GitRunner.DecoratedCommitEntry> rangeOldestFirst(
            Path upstreamClone,
            List<GitRunner.DecoratedCommitEntry> history,
            String fromSha,
            String toSha)
            throws IOException, InterruptedException
    {
        int first = requirePosition(upstreamClone, history, fromSha);
        int last = requirePosition(upstreamClone, history, toSha);
        int newest = Math.min(first, last);
        int oldest = Math.max(first, last);
        int size = oldest - newest + 1;
        if (size > MAX_COMMITS) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "that range covers " + size + " commits; the maximum is " + MAX_COMMITS);
        }
        List<GitRunner.DecoratedCommitEntry> ordered = new ArrayList<>();
        for (int i = oldest; i >= newest; i--) {
            ordered.add(history.get(i));
        }
        return List.copyOf(ordered);
    }

    private int requirePosition(
            Path upstreamClone,
            List<GitRunner.DecoratedCommitEntry> history,
            String requestedSha)
            throws IOException, InterruptedException
    {
        requireText(requestedSha, "sha");
        String resolved = git.resolveCommitSha(upstreamClone, requestedSha)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "unknown upstream commit: " + requestedSha));
        int position = indexOf(history, resolved);
        if (position < 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "commit is not on the selected upstream branch: " + requestedSha);
        }
        return position;
    }

    /**
     * The single place that decides what a cherry-pick will and will not apply.
     * The preview endpoint and {@link #enqueue} both route through it, so a dry
     * run cannot describe a different outcome than the run it previews.
     */
    static List<PlannedCommit> plan(
            List<GitRunner.DecoratedCommitEntry> orderedOldestFirst,
            Set<String> alreadyPickedSubjects,
            SkipFilters filters)
    {
        List<PlannedCommit> planned = new ArrayList<>();
        for (GitRunner.DecoratedCommitEntry commit : orderedOldestFirst) {
            String skipReason = null;
            if (alreadyPickedSubjects.contains(
                    WorkspaceRelationService.normalizeSubject(commit.subject()))) {
                skipReason = "already in the fork";
            }
            else {
                skipReason = filters.skipReason(commit.subject());
            }
            planned.add(new PlannedCommit(
                    commit.sha(),
                    commit.shortSha(),
                    commit.subject(),
                    commit.authorName(),
                    skipReason == null,
                    skipReason));
        }
        return List.copyOf(planned);
    }

    /**
     * Subject filters. Matching is case-insensitive and evaluated against the
     * commit subject only, never the body, so a term appearing in a long message
     * cannot silently drop a commit the user meant to keep.
     */
    public record SkipFilters(List<String> startsWith, List<String> contains)
    {
        private static final int MAX_TERMS = 20;
        private static final int MAX_TERM_LENGTH = 200;

        public static SkipFilters none()
        {
            return new SkipFilters(List.of(), List.of());
        }

        public static SkipFilters normalize(List<String> startsWith, List<String> contains)
        {
            return new SkipFilters(normalizeTerms(startsWith), normalizeTerms(contains));
        }

        private static List<String> normalizeTerms(List<String> terms)
        {
            if (terms == null) {
                return List.of();
            }
            List<String> normalized = terms.stream()
                    .filter(term -> term != null && !term.isBlank())
                    .map(term -> term.strip().toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
            if (normalized.size() > MAX_TERMS) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "at most " + MAX_TERMS + " filter terms are allowed");
            }
            if (normalized.stream().anyMatch(term -> term.length() > MAX_TERM_LENGTH)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "a filter term may be at most " + MAX_TERM_LENGTH + " characters");
            }
            return normalized;
        }

        /** Null when the subject survives every filter. */
        public String skipReason(String subject)
        {
            String candidate = subject == null ? "" : subject.strip().toLowerCase(Locale.ROOT);
            return startsWith.stream()
                    .filter(candidate::startsWith)
                    .findFirst()
                    .map(term -> "subject starts with \"" + term + "\"")
                    .or(() -> contains.stream()
                            .filter(candidate::contains)
                            .findFirst()
                            .map(term -> "subject contains \"" + term + "\""))
                    .orElse(null);
        }

        /**
         * Derived, and it must stay out of the JSON this record is persisted
         * as: Jackson reads {@code isEmpty} as a bean property on the way out
         * and then rejects it as unknown on the way back in, which quietly
         * turned a stored filter into no filter at all mid-run.
         */
        @JsonIgnore
        public boolean isEmpty()
        {
            return startsWith.isEmpty() && contains.isEmpty();
        }
    }

    public record PlannedCommit(
            String sha,
            String shortSha,
            String subject,
            String authorName,
            boolean pick,
            String skipReason) {}

    public record CherryPickPlan(
            List<PlannedCommit> commits,
            int pickCount,
            int skipCount) {}

    private List<GitRunner.DecoratedCommitEntry> contiguousOldestFirst(
            Path upstreamClone,
            List<GitRunner.DecoratedCommitEntry> history,
            List<String> requested)
            throws IOException, InterruptedException
    {
        Map<String, GitRunner.DecoratedCommitEntry> bySha = new HashMap<>();
        for (GitRunner.DecoratedCommitEntry entry : history) {
            bySha.put(entry.sha(), entry);
        }
        List<String> full = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        for (String requestedSha : requested) {
            requireText(requestedSha, "sha");
            String resolved = git.resolveCommitSha(upstreamClone, requestedSha)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "unknown upstream commit: " + requestedSha));
            if (!dedup.add(resolved)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "commit selection contains duplicates");
            }
            full.add(resolved);
        }
        List<Integer> positions = full.stream()
                .map(sha -> indexOf(history, sha))
                .sorted()
                .toList();
        if (positions.contains(-1)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "every selected commit must belong to the upstream branch");
        }
        if (positions.getLast() - positions.getFirst() + 1 != positions.size()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "selected commits must be a contiguous displayed range");
        }
        List<GitRunner.DecoratedCommitEntry> ordered = new ArrayList<>();
        for (int i = positions.getLast(); i >= positions.getFirst(); i--) {
            ordered.add(bySha.get(history.get(i).sha()));
        }
        return List.copyOf(ordered);
    }

    /** Sha and subject are all a pick needs; provenance is git's own `-x` line. */
    private static int indexOf(
            List<GitRunner.DecoratedCommitEntry> history,
            String sha)
    {
        for (int i = 0; i < history.size(); i++) {
            if (sha.equals(history.get(i).sha())) {
                return i;
            }
        }
        return -1;
    }

    private JobRow requireRow(String id)
    {
        List<JobRow> rows = jdbc.query("""
                SELECT * FROM upstream_cherry_pick_job WHERE id = ?
                """, this::mapRow, id);
        if (rows.isEmpty()) {
            throw new NoSuchElementException("no upstream cherry-pick job: " + id);
        }
        return rows.getFirst();
    }

    private JobRow mapRow(ResultSet rs, int ignored)
            throws SQLException
    {
        int pr = rs.getInt("pr_number");
        Integer prNumber = rs.wasNull() ? null : pr;
        return new JobRow(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("upstream_workspace_id"),
                rs.getString("status"),
                rs.getString("source_branch"),
                rs.getString("source_ref"),
                rs.getString("base_branch"),
                rs.getString("base_ref"),
                rs.getString("result_branch"),
                read(rs.getString("commit_specs_json"), new TypeReference<>() {}),
                read(rs.getString("applied_shas_json"), new TypeReference<>() {}),
                read(rs.getString("skipped_shas_json"), new TypeReference<>() {}),
                read(rs.getString("conflicted_shas_json"), new TypeReference<>() {}),
                rs.getInt("next_commit_index"),
                read(rs.getString("conflict_paths_json"), new TypeReference<>() {}),
                rs.getString("worktree_path"),
                rs.getInt("open_draft_pr") != 0,
                rs.getInt("create_harness_watch") != 0,
                rs.getLong("budget_milli_usd"),
                rs.getString("pr_description"),
                readFilters(rs.getString("skip_filters_json")),
                rs.getString("compile_script"),
                rs.getInt("pause_requested") != 0,
                rs.getInt("repair_pending") != 0,
                rs.getInt("local_gate_unavailable") != 0,
                rs.getLong("spent_milli_usd"),
                rs.getString("agent_session_id"),
                prNumber,
                rs.getString("pr_url"),
                rs.getString("harness_watch_id"),
                rs.getString("error_message"),
                closedAt(rs),
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                Instant.ofEpochMilli(rs.getLong("updated_at_ms")));
    }

    private static Instant closedAt(ResultSet rs)
            throws SQLException
    {
        long closed = rs.getLong("closed_at_ms");
        return rs.wasNull() ? null : Instant.ofEpochMilli(closed);
    }

    private void running(String id)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET status = 'RUNNING', error_message = NULL, updated_at_ms = ?
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """, now(), id);
    }

    private void progress(
            String id,
            List<String> applied,
            List<String> skipped,
            int nextIndex)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET applied_shas_json = ?, skipped_shas_json = ?,
                    next_commit_index = ?, conflict_paths_json = '[]',
                    error_message = NULL, updated_at_ms = ?
                WHERE id = ?
                """, json(applied), json(skipped), nextIndex, now(), id);
    }

    private void progress(
            String id,
            List<String> applied,
            List<String> skipped,
            List<String> conflicted,
            int nextIndex)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET applied_shas_json = ?, skipped_shas_json = ?,
                    conflicted_shas_json = ?,
                    next_commit_index = ?, conflict_paths_json = '[]',
                    error_message = NULL, updated_at_ms = ?
                WHERE id = ?
                """, json(applied), json(skipped), json(conflicted), nextIndex, now(), id);
    }

    /** "closed" | "paused" | null when the run should carry on. */
    private String stopReason(String id)
    {
        List<String> reasons = jdbc.query("""
                SELECT pause_requested, closed_at_ms
                FROM upstream_cherry_pick_job WHERE id = ?
                """, (rs, ignored) -> {
            rs.getLong("closed_at_ms");
            if (!rs.wasNull()) {
                return "closed";
            }
            return rs.getInt("pause_requested") != 0 ? "paused" : null;
        }, id);
        // A row that vanished under the worker is as good as closed.
        return reasons.isEmpty() ? "closed" : reasons.getFirst();
    }

    /** The requested stop, taken at a commit boundary with nothing half-applied. */
    private void parkOnRequest(String id, int index)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET status = 'PAUSED_CONFLICT', conflict_paths_json = '[]',
                    error_message = 'paused at your request', updated_at_ms = ?
                WHERE id = ?
                """, now(), id);
        record(id, index, "park", "Parked at your request — nothing is pushed",
                "resume when you are ready", null, null);
    }

    private void clearPauseRequest(String id)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET pause_requested = 0, updated_at_ms = ?
                WHERE id = ?
                """, now(), id);
    }

    /**
     * Only once the repair that parked the run has actually been made. This used
     * to be cleared alongside the pause request, which let a resume walk straight
     * past a pick whose conflict was never resolved.
     */
    private void clearRepairPending(String id)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET repair_pending = 0, updated_at_ms = ?
                WHERE id = ?
                """, now(), id);
    }

    private void pause(String id, List<String> conflictPaths, String message)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET status = 'PAUSED_CONFLICT', conflict_paths_json = ?,
                    error_message = ?, updated_at_ms = ?
                WHERE id = ?
                """, json(conflictPaths), message, now(), id);
    }

    private void queue(String id)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET status = 'QUEUED', conflict_paths_json = '[]',
                    error_message = NULL, updated_at_ms = ?
                WHERE id = ?
                """, now(), id);
    }

    private void storePullRequest(String id, PullRequest pr)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET pr_number = ?, pr_url = ?, updated_at_ms = ?
                WHERE id = ?
                """, pr.number(), pr.htmlUrl(), now(), id);
    }

    private void complete(String id)
    {
        // A pause requested while the last pick ran has nothing left to stop, so
        // it is cleared here rather than left to read as "pausing" forever.
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET status = 'COMPLETED', conflict_paths_json = '[]',
                    pause_requested = 0, error_message = NULL, updated_at_ms = ?
                WHERE id = ?
                """, now(), id);
    }

    private void fail(String id, String message)
    {
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET status = 'FAILED', error_message = ?, updated_at_ms = ?
                WHERE id = ?
                """, message, now(), id);
        record(id, null, "error", "Run failed", message, null, null);
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("serialising upstream cherry-pick job failed", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type)
    {
        try {
            return mapper.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("reading upstream cherry-pick job failed", e);
        }
    }

    private static List<String> append(List<String> values, String value)
    {
        List<String> result = new ArrayList<>(values);
        if (!result.contains(value)) {
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static long now()
    {
        return Instant.now().toEpochMilli();
    }

    private static void requireText(String value, String field)
    {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    field + " is required");
        }
    }

    public record StartRequest(
            String sourceBranch,
            String targetBranch,
            List<String> shas,
            String fromSha,
            String toSha,
            String prDescription,
            List<String> skipStartsWith,
            List<String> skipContains,
            boolean openDraftPr,
            boolean createHarnessWatch,
            Long budgetMilliUsd) {}

    public record PreviewRequest(
            String sourceBranch,
            List<String> shas,
            String fromSha,
            String toSha,
            List<String> skipStartsWith,
            List<String> skipContains) {}

    public record UpstreamCherryPickJobDto(
            String jobId,
            String workspaceId,
            String upstreamWorkspaceId,
            String status,
            String sourceBranch,
            String resultBranch,
            String baseRef,
            int requestedCount,
            int appliedCount,
            int skippedCount,
            int conflictedCount,
            boolean pauseRequested,
            long budgetMilliUsd,
            long spentMilliUsd,
            /** The local compile could not run, so CI carries the verdict. */
            boolean localGateUnavailable,
            /** The CLI session the whole run shares — the handle for reading its
             *  own transcript on disk, or resuming it by hand. */
            String agentSessionId,
            List<String> conflictPaths,
            String worktreePath,
            Integer prNumber,
            String prUrl,
            String harnessWatchId,
            String errorMessage,
            /** Set once the user closed the run; every action but reading is refused after. */
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt) {}

    /** One row of the run view's commit queue. */
    public record UpstreamCherryPickCommitDto(
            int index,
            String sha,
            String shortSha,
            String subject,
            /** applied | conflicted | skipped | current | waiting */
            String state) {}

    /** One line of the run log — a command it ran, or a note about the run. */
    public record UpstreamCherryPickEventDto(
            String id,
            int ordinal,
            Integer pickIndex,
            /** start | command | note | skip | park | guidance | push | pr | watch | done | error */
            String kind,
            String title,
            String detail,
            Integer exitCode,
            Long durationMs,
            Instant at) {}

    public record UpstreamCherryPickRunDto(
            UpstreamCherryPickJobDto job,
            String baseBranch,
            List<UpstreamCherryPickCommitDto> commits,
            List<UpstreamCherryPickEventDto> events) {}

    private record CommitSpec(String sha, String subject) {}

    private record JobRow(
            String id,
            String workspaceId,
            String upstreamWorkspaceId,
            String status,
            String sourceBranch,
            String sourceRef,
            String baseBranch,
            String baseRef,
            String resultBranch,
            List<CommitSpec> specs,
            List<String> appliedShas,
            List<String> skippedShas,
            List<String> conflictedShas,
            int nextCommitIndex,
            List<String> conflictPaths,
            String worktreePath,
            boolean openDraftPr,
            boolean createHarnessWatch,
            long budgetMilliUsd,
            String prDescription,
            SkipFilters skipFilters,
            String compileScript,
            boolean pauseRequested,
            boolean repairPending,
            boolean localGateUnavailable,
            long spentMilliUsd,
            String agentSessionId,
            Integer prNumber,
            String prUrl,
            String harnessWatchId,
            String errorMessage,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt)
    {
        UpstreamCherryPickJobDto dto()
        {
            return new UpstreamCherryPickJobDto(
                    id,
                    workspaceId,
                    upstreamWorkspaceId,
                    status,
                    sourceBranch,
                    resultBranch,
                    baseRef,
                    specs.size(),
                    appliedShas.size(),
                    skippedShas.size(),
                    conflictedShas.size(),
                    pauseRequested,
                    budgetMilliUsd,
                    spentMilliUsd,
                    localGateUnavailable,
                    agentSessionId,
                    conflictPaths,
                    worktreePath,
                    prNumber,
                    prUrl,
                    harnessWatchId,
                    errorMessage,
                    closedAt,
                    createdAt,
                    updatedAt);
        }
    }
}
