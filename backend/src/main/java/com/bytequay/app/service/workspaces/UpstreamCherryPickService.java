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
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRSyncService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 * once to open the requested draft PR; the harness handoff it creates has no
 * push capability. Conflicts are retained for a human and never enqueue an
 * agent turn.
 */
@Service
public class UpstreamCherryPickService
{
    private static final int MAX_COMMITS = 500;
    private static final int HISTORY_LIMIT = 50_000;
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
    private final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    public UpstreamCherryPickService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            WorkspaceRelationService relations,
            GitRunner git,
            PatResolver pats,
            PullRequestRepository pullRequests,
            PRSyncService prSync,
            ObjectProvider<HarnessWatchHandoff> harnessHandoff)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.relations = requireNonNull(relations, "relations is null");
        this.git = requireNonNull(git, "git is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.prSync = requireNonNull(prSync, "prSync is null");
        this.harnessHandoff = requireNonNull(harnessHandoff, "harnessHandoff is null");
    }

    public synchronized UpstreamCherryPickJobDto enqueue(
            String workspaceId,
            StartRequest request)
            throws IOException, InterruptedException
    {
        requireNonNull(request, "request is null");
        if (request.shas() == null
                || request.shas().isEmpty()
                || request.shas().size() > MAX_COMMITS) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "choose between 1 and " + MAX_COMMITS + " commits");
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
                relation.upstreamClone(),
                sourceRef,
                HISTORY_LIMIT,
                WorkspaceRelationService.UPSTREAM_PR_TRAILER);
        List<GitRunner.DecoratedCommitEntry> ordered = contiguousOldestFirst(
                relation.upstreamClone(), history, request.shas());
        List<CommitSpec> specs = resolveCommitSpecs(relation, ordered);
        Set<String> picked = relations.pickedCommitShas(
                relation, baseRef, history);
        boolean allPicked = specs.stream()
                .allMatch(spec -> picked.contains(spec.sha().toLowerCase(Locale.ROOT)));
        if (allPicked && request.openDraftPr()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "the selected upstream range is already present in the fork");
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
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?,
                    '[]', '[]', 0, '[]', ?, ?, ?, ?, ?, ?)
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
                worktree.toString(),
                request.openDraftPr() ? 1 : 0,
                request.createHarnessWatch() ? 1 : 0,
                budget,
                now,
                now);
        launchAfterCommit(id);
        return require(workspaceId, id);
    }

    public UpstreamCherryPickJobDto require(String workspaceId, String id)
    {
        JobRow row = requireRow(id);
        if (!workspaceId.equals(row.workspaceId())) {
            throw new NoSuchElementException("no upstream cherry-pick job: " + id);
        }
        return row.dto();
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
        JobRow row = requireRow(id);
        if (!workspaceId.equals(row.workspaceId())) {
            throw new NoSuchElementException("no upstream cherry-pick job: " + id);
        }
        if (!"PAUSED_CONFLICT".equals(row.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "upstream cherry-pick is not paused for a conflict");
        }
        if (row.nextCommitIndex() >= row.specs().size()) {
            throw new IllegalStateException("paused job has no current commit");
        }
        Path worktree = Path.of(row.worktreePath());
        CommitSpec current = row.specs().get(row.nextCommitIndex());
        if (git.cherryPickInProgress(worktree)) {
            GitRunner.CherryPickOutcome continued = git.continueCherryPick(worktree);
            if (!continued.complete()) {
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
        git.amendHeadTrailer(
                worktree,
                WorkspaceRelationService.UPSTREAM_PR_TRAILER,
                current.upstreamPr());
        git.amendHeadTrailer(
                worktree,
                WorkspaceRelationService.UPSTREAM_COMMIT_TRAILER,
                current.sha());
        List<String> applied = append(row.appliedShas(), current.sha());
        progress(row.id(), applied, row.skippedShas(), row.nextCommitIndex() + 1);
        queue(row.id());
        launch(row.id());
        return require(workspaceId, id);
    }

    /** Explicitly retries a failed job without discarding durable progress. */
    public UpstreamCherryPickJobDto retry(String workspaceId, String id)
    {
        JobRow row = requireRow(id);
        if (!workspaceId.equals(row.workspaceId())) {
            throw new NoSuchElementException("no upstream cherry-pick job: " + id);
        }
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
                WHERE status IN ('QUEUED', 'RUNNING')
                """, String.class).forEach(this::launch);
    }

    private void launch(String id)
    {
        if (!activeJobs.add(id)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                execute(id);
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
        if (!LIVE_STATUSES.contains(row.status())) {
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
            List<GitRunner.DecoratedCommitEntry> history = git.listDecoratedCommits(
                    relation.upstreamClone(),
                    row.sourceRef(),
                    HISTORY_LIMIT,
                    WorkspaceRelationService.UPSTREAM_PR_TRAILER);
            Set<String> picked = new HashSet<>(relations.pickedCommitShas(
                    relation, row.baseRef(), history));
            List<String> applied = new ArrayList<>(row.appliedShas());
            List<String> skipped = new ArrayList<>(row.skippedShas());
            int index = row.nextCommitIndex();
            while (index < row.specs().size()) {
                CommitSpec commit = row.specs().get(index);
                if (picked.contains(commit.sha().toLowerCase(Locale.ROOT))) {
                    skipped.add(commit.sha());
                    index++;
                    progress(id, applied, skipped, index);
                    continue;
                }
                GitRunner.CherryPickOutcome outcome =
                        git.cherryPick(worktree, List.of(commit.sha()));
                if (!outcome.complete()) {
                    if (!git.cherryPickInProgress(worktree)) {
                        throw new IllegalStateException(outcome.message() == null
                                ? "upstream cherry-pick failed"
                                : outcome.message());
                    }
                    pause(id, outcome.conflictPaths(), outcome.message());
                    return;
                }
                git.amendHeadTrailer(
                        worktree,
                        WorkspaceRelationService.UPSTREAM_PR_TRAILER,
                        commit.upstreamPr());
                git.amendHeadTrailer(
                        worktree,
                        WorkspaceRelationService.UPSTREAM_COMMIT_TRAILER,
                        commit.sha());
                applied.add(commit.sha());
                picked.add(commit.sha().toLowerCase(Locale.ROOT));
                index++;
                progress(id, applied, skipped, index);
            }

            row = requireRow(id);
            if (row.openDraftPr() && row.prNumber() == null) {
                PullRequest pr = openOrAdoptDraft(row, relation, worktree);
                storePullRequest(id, pr);
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
                        row.budgetMilliUsd());
                jdbc.update("""
                        UPDATE upstream_cherry_pick_job
                        SET harness_watch_id = ?, updated_at_ms = ?
                        WHERE id = ?
                        """, watchId, now(), id);
            }
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
        List<GitRunner.CommitEntry> branchCommits = git.listCommits(
                worktree,
                row.baseRef() + "..HEAD",
                row.specs().size() + 1);
        if (branchCommits.size() == row.appliedShas().size()) {
            return row;
        }
        if (branchCommits.size() != row.appliedShas().size() + 1
                || row.nextCommitIndex() >= row.specs().size()) {
            throw new IllegalStateException(
                    "cherry-pick worktree history no longer matches durable progress");
        }
        CommitSpec current = row.specs().get(row.nextCommitIndex());
        GitRunner.CommitEntry head = branchCommits.getFirst();
        if (!current.subject().equals(head.subject())) {
            throw new IllegalStateException(
                    "unexpected commit at cherry-pick HEAD: " + head.subject());
        }
        git.amendHeadTrailer(
                worktree,
                WorkspaceRelationService.UPSTREAM_PR_TRAILER,
                current.upstreamPr());
        git.amendHeadTrailer(
                worktree,
                WorkspaceRelationService.UPSTREAM_COMMIT_TRAILER,
                current.sha());
        progress(
                row.id(),
                append(row.appliedShas(), current.sha()),
                row.skippedShas(),
                row.nextCommitIndex() + 1);
        return requireRow(row.id());
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
        String title = row.specs().size() == 1
                ? row.specs().getFirst().subject()
                : "Cherry-pick " + row.specs().size() + " commits from "
                        + relation.upstream().fullName();
        String body = "Cherry-picked a contiguous range from `"
                + relation.upstream().fullName() + "/" + row.sourceBranch()
                + "`. Each applied commit records its source in `"
                + WorkspaceRelationService.UPSTREAM_PR_TRAILER + "` and `"
                + WorkspaceRelationService.UPSTREAM_COMMIT_TRAILER + "` trailers.";
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

    private List<CommitSpec> resolveCommitSpecs(
            WorkspaceRelationService.ResolvedRelation relation,
            List<GitRunner.DecoratedCommitEntry> commits)
    {
        boolean needsGitHub = commits.stream()
                .anyMatch(commit -> WorkspaceRelationService.upstreamPr(
                        commit, relation.upstream().fullName()).isEmpty());
        String pat = needsGitHub
                ? pats.resolve(relation.upstream().fullName())
                : null;
        RepoRef upstream = RepoRef.of(
                relation.upstream().owner(), relation.upstream().repo());
        List<CommitSpec> result = new ArrayList<>();
        for (GitRunner.DecoratedCommitEntry commit : commits) {
            Optional<String> inferred = WorkspaceRelationService.upstreamPr(
                    commit, relation.upstream().fullName());
            String upstreamPr = inferred.orElseGet(() ->
                    uniqueAssociatedPullRequest(pat, upstream, commit.sha()));
            result.add(new CommitSpec(commit.sha(), upstreamPr, commit.subject()));
        }
        return List.copyOf(result);
    }

    private String uniqueAssociatedPullRequest(String pat, RepoRef upstream, String sha)
    {
        Set<Integer> numbers = new HashSet<>();
        pullRequests.listPullRequestsForCommit(pat, upstream, sha).stream()
                .map(PullRequest::number)
                .filter(number -> number > 0)
                .forEach(numbers::add);
        if (numbers.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    numbers.isEmpty()
                            ? "upstream commit is not associated with a pull request: " + sha
                            : "upstream commit has an ambiguous pull request association: " + sha);
        }
        return upstream.fullName() + "#" + numbers.iterator().next();
    }

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
                rs.getInt("next_commit_index"),
                read(rs.getString("conflict_paths_json"), new TypeReference<>() {}),
                rs.getString("worktree_path"),
                rs.getInt("open_draft_pr") != 0,
                rs.getInt("create_harness_watch") != 0,
                rs.getLong("budget_milli_usd"),
                prNumber,
                rs.getString("pr_url"),
                rs.getString("harness_watch_id"),
                rs.getString("error_message"),
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                Instant.ofEpochMilli(rs.getLong("updated_at_ms")));
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
        jdbc.update("""
                UPDATE upstream_cherry_pick_job
                SET status = 'COMPLETED', conflict_paths_json = '[]',
                    error_message = NULL, updated_at_ms = ?
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
            boolean openDraftPr,
            boolean createHarnessWatch,
            Long budgetMilliUsd) {}

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
            List<String> conflictPaths,
            String worktreePath,
            Integer prNumber,
            String prUrl,
            String harnessWatchId,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {}

    private record CommitSpec(String sha, String upstreamPr, String subject) {}

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
            int nextCommitIndex,
            List<String> conflictPaths,
            String worktreePath,
            boolean openDraftPr,
            boolean createHarnessWatch,
            long budgetMilliUsd,
            Integer prNumber,
            String prUrl,
            String harnessWatchId,
            String errorMessage,
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
                    conflictPaths,
                    worktreePath,
                    prNumber,
                    prUrl,
                    harnessWatchId,
                    errorMessage,
                    createdAt,
                    updatedAt);
        }
    }
}
