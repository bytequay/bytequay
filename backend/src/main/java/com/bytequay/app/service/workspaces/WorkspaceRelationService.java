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

import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.local.GitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** Directional, read-only link from a fork workspace to an upstream workspace. */
@Service
public class WorkspaceRelationService
{
    private static final int HISTORY_LIMIT = 5_000;
    private static final Logger log = LoggerFactory.getLogger(WorkspaceRelationService.class);

    private final JdbcTemplate jdbc;
    private final WorkspaceService workspaces;
    private final WorkspaceRepositoryResolver resolver;
    private final WatchedRepoStore watchedRepos;
    private final RepoService repos;
    private final GitRunner git;
    private final Set<String> activeFetches = ConcurrentHashMap.newKeySet();

    public WorkspaceRelationService(
            JdbcTemplate jdbc,
            WorkspaceService workspaces,
            WorkspaceRepositoryResolver resolver,
            WatchedRepoStore watchedRepos,
            RepoService repos,
            GitRunner git)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.resolver = requireNonNull(resolver, "resolver is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.repos = requireNonNull(repos, "repos is null");
        this.git = requireNonNull(git, "git is null");
    }

    public Optional<WorkspaceRelationDto> find(String workspaceId)
    {
        workspaces.require(workspaceId);
        List<WorkspaceRelationDto> rows = jdbc.query("""
                SELECT relation.*, upstream.name AS upstream_workspace_name,
                       repo.repo_full_name AS upstream_repo_full_name
                FROM workspace_relation relation
                JOIN workspaces upstream ON upstream.id = relation.upstream_workspace_id
                JOIN workspace_repos repo ON repo.workspace_id = relation.upstream_workspace_id
                WHERE relation.workspace_id = ?
                """, WorkspaceRelationService::mapRelation, workspaceId);
        return rows.stream().findFirst();
    }

    public ResolvedRelation requireResolved(String workspaceId)
    {
        WorkspaceRelationDto relation = find(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "workspace has no linked upstream"));
        WorkspaceRepositoryResolver.RepositoryIdentity target = resolver.resolve(workspaceId);
        WorkspaceRepositoryResolver.RepositoryIdentity upstream =
                resolver.resolve(relation.upstreamWorkspaceId());
        return new ResolvedRelation(
                relation,
                target,
                upstream,
                clonePath(target),
                clonePath(upstream));
    }

    @Transactional
    public WorkspaceRelationDto link(String workspaceId, RelationUpdate request)
    {
        requireNonNull(request, "request is null");
        workspaces.require(workspaceId);
        if (request.upstreamWorkspaceId() == null
                || request.upstreamWorkspaceId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "upstreamWorkspaceId is required");
        }
        workspaces.require(request.upstreamWorkspaceId());
        if (workspaceId.equals(request.upstreamWorkspaceId())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "a workspace cannot be its own upstream");
        }
        if (!request.commitsEnabled() && !request.tagsEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "enable commits or tags for the upstream relation");
        }
        int interval = request.autoFetchIntervalMinutes();
        if (interval < 1 || interval > 1440) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "autoFetchIntervalMinutes must be between 1 and 1440");
        }
        requireNoCycle(workspaceId, request.upstreamWorkspaceId());
        // Resolve both sides now so an incomplete workspace cannot become a
        // relation which every later read would fail to use.
        clonePath(resolver.resolve(workspaceId));
        clonePath(resolver.resolve(request.upstreamWorkspaceId()));

        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                INSERT INTO workspace_relation (
                    workspace_id, upstream_workspace_id,
                    commits_enabled, tags_enabled,
                    last_fetched_at_ms, auto_fetch_interval_minutes,
                    indexed_commit_count, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, NULL, ?, 0, ?, ?)
                ON CONFLICT(workspace_id) DO UPDATE SET
                    upstream_workspace_id = excluded.upstream_workspace_id,
                    commits_enabled = excluded.commits_enabled,
                    tags_enabled = excluded.tags_enabled,
                    last_fetched_at_ms = CASE
                        WHEN workspace_relation.upstream_workspace_id = excluded.upstream_workspace_id
                        THEN workspace_relation.last_fetched_at_ms ELSE NULL END,
                    indexed_commit_count = CASE
                        WHEN workspace_relation.upstream_workspace_id = excluded.upstream_workspace_id
                        THEN workspace_relation.indexed_commit_count ELSE 0 END,
                    auto_fetch_interval_minutes = excluded.auto_fetch_interval_minutes,
                    updated_at_ms = excluded.updated_at_ms
                """,
                workspaceId,
                request.upstreamWorkspaceId(),
                request.commitsEnabled() ? 1 : 0,
                request.tagsEnabled() ? 1 : 0,
                interval,
                now,
                now);
        return find(workspaceId).orElseThrow();
    }

    @Transactional
    public void unlink(String workspaceId)
    {
        workspaces.require(workspaceId);
        jdbc.update("DELETE FROM workspace_relation WHERE workspace_id = ?", workspaceId);
    }

    public WorkspaceRelationDto fetch(String workspaceId)
            throws IOException, InterruptedException
    {
        if (!activeFetches.add(workspaceId)) {
            return find(workspaceId).orElseThrow();
        }
        try {
            ResolvedRelation resolved = requireResolved(workspaceId);
            git.fetch(resolved.upstreamClone());
            String branch = defaultBranch(resolved.upstream(), resolved.upstreamClone());
            String ref = resolveRef(resolved.upstreamClone(), branch);
            int count = git.countCommits(resolved.upstreamClone(), ref);
            long now = Instant.now().toEpochMilli();
            jdbc.update("""
                    UPDATE workspace_relation
                    SET last_fetched_at_ms = ?, indexed_commit_count = ?, updated_at_ms = ?
                    WHERE workspace_id = ?
                    """, now, count, now, workspaceId);
            return find(workspaceId).orElseThrow();
        }
        finally {
            activeFetches.remove(workspaceId);
        }
    }

    @Scheduled(fixedDelayString = "${bytequay.workspace-relations.scan-ms:60000}")
    public void fetchDueRelations()
    {
        long now = Instant.now().toEpochMilli();
        List<String> due = jdbc.queryForList("""
                SELECT workspace_id
                FROM workspace_relation
                WHERE last_fetched_at_ms IS NULL
                   OR last_fetched_at_ms + auto_fetch_interval_minutes * 60000 <= ?
                """, String.class, now);
        for (String workspaceId : due) {
            Thread.startVirtualThread(() -> {
                try {
                    fetch(workspaceId);
                }
                catch (Exception e) {
                    log.warn("fetching upstream for workspace {} failed: {}",
                            workspaceId, e.getMessage());
                }
            });
        }
    }

    public List<RelationCandidateDto> candidates(String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity target = resolver.resolve(workspaceId);
        String suggestedRepo = suggestedParent(target).orElse(null);
        List<RelationCandidateDto> result = new ArrayList<>();
        for (Workspace candidate : workspaces.list()) {
            if (workspaceId.equals(candidate.id())) {
                continue;
            }
            try {
                WorkspaceRepositoryResolver.RepositoryIdentity identity =
                        resolver.resolve(candidate.id());
                clonePath(identity);
                result.add(new RelationCandidateDto(
                        candidate.id(),
                        candidate.name(),
                        identity.fullName(),
                        suggestedRepo != null
                                && suggestedRepo.equalsIgnoreCase(identity.fullName()),
                        cycleReason(workspaceId, candidate.id()).orElse(null)));
            }
            catch (RuntimeException ignored) {
                // Incomplete recovery workspaces are not valid link targets.
            }
        }
        return List.copyOf(result);
    }

    /**
     * Every branch the linked upstream's clone knows about, as unqualified
     * names so they read the same as the revision {@link #commits} reports.
     * Read off {@code origin/*} rather than the upstream workspace's own
     * local branches: that clone rarely checks anything out, so its heads
     * would list one branch where the remote carries hundreds.
     */
    public List<String> upstreamBranches(String workspaceId)
            throws IOException, InterruptedException
    {
        ResolvedRelation resolved = requireResolved(workspaceId);
        return git.listRemoteBranches(resolved.upstreamClone(), "origin").stream()
                .map(name -> name.substring("origin/".length()))
                .sorted()
                .toList();
    }

    public UpstreamCommitsDto commits(String workspaceId, String revision, int requestedLimit)
            throws IOException, InterruptedException
    {
        ResolvedRelation resolved = requireResolved(workspaceId);
        if (!resolved.relation().commitsEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "upstream commit reading is disabled for this relation");
        }
        int limit = Math.min(Math.max(requestedLimit, 1), 500);
        String branch = revision == null || revision.isBlank()
                ? defaultBranch(resolved.upstream(), resolved.upstreamClone())
                : revision.strip();
        String ref = resolveRef(resolved.upstreamClone(), branch);
        List<GitRunner.DecoratedCommitEntry> history = git.listDecoratedCommits(
                resolved.upstreamClone(), ref, HISTORY_LIMIT);
        String targetBranch = defaultBranch(resolved.target(), resolved.targetClone());
        String targetRef = resolveRef(resolved.targetClone(), targetBranch);
        Set<String> pickedSubjects = pickedCommitSubjects(resolved, targetRef);

        List<UpstreamCommitDto> rows = history.stream()
                .limit(limit)
                .map(commit -> toCommit(
                        commit,
                        resolved.relation().tagsEnabled(),
                        pickedSubjects))
                .toList();
        int notInFork = (int) history.stream()
                .map(GitRunner.DecoratedCommitEntry::subject)
                .map(WorkspaceRelationService::normalizeSubject)
                .filter(subject -> !pickedSubjects.contains(subject))
                .count();
        int indexed = resolved.relation().indexedCommitCount() == 0
                ? history.size()
                : resolved.relation().indexedCommitCount();
        return new UpstreamCommitsDto(
                resolved.relation().upstreamWorkspaceId(),
                resolved.relation().upstreamWorkspaceName(),
                resolved.relation().upstreamRepoFullName(),
                branch,
                resolved.relation().lastFetchedAt(),
                indexed,
                notInFork,
                rows);
    }

    public String defaultBranch(
            WorkspaceRepositoryResolver.RepositoryIdentity repo,
            Path clone)
            throws IOException, InterruptedException
    {
        if (repo.defaultBaseBranch() != null
                && !repo.defaultBaseBranch().isBlank()) {
            return repo.defaultBaseBranch();
        }
        Optional<String> remoteDefault = git.defaultBranch(clone);
        if (remoteDefault.isPresent()) {
            return remoteDefault.orElseThrow();
        }
        if (git.refExists(clone, "main") || git.refExists(clone, "origin/main")) {
            return "main";
        }
        if (git.refExists(clone, "master") || git.refExists(clone, "origin/master")) {
            return "master";
        }
        throw new IllegalStateException(
                "repository default branch could not be resolved: " + repo.fullName());
    }

    public String resolveRef(Path clone, String branch)
            throws IOException, InterruptedException
    {
        String remote = branch.startsWith("origin/")
                ? branch
                : "origin/" + branch;
        if (git.refExists(clone, remote)) {
            return remote;
        }
        if (!remote.equals(branch) && git.refExists(clone, branch)) {
            return branch;
        }
        throw new IllegalArgumentException("branch is not available locally: " + branch);
    }

    /** Resolves only a branch proven to exist on the freshly fetched origin. */
    public String resolveFetchedRemoteRef(Path clone, String branch)
            throws IOException, InterruptedException
    {
        String branchName = branch.startsWith("origin/")
                ? branch.substring("origin/".length())
                : branch;
        if (!git.isValidBranchName(branchName)) {
            throw new IllegalArgumentException("invalid branch name: " + branch);
        }
        String remote = "origin/" + branchName;
        if (git.refExists(clone, remote)) {
            return remote;
        }
        throw new IllegalArgumentException(
                "branch is not available on fetched origin: " + branchName);
    }

    /**
     * Subjects of every commit already on the target branch, normalized for
     * comparison. A cherry-pick rewrites the sha, so the subject is what the
     * copy and the original still share.
     *
     * <p>Note the trade-off against the sha/trailer scheme this replaced:
     * subjects are not unique. Repeated messages ("Fix checkstyle issues",
     * dependabot bumps) collide, and a collision marks an upstream commit as
     * already present when it is not, so it is skipped. That failure is silent
     * and drops work, where the previous scheme failed the other way and merely
     * re-offered a commit.
     */
    Set<String> pickedCommitSubjects(ResolvedRelation relation, String targetRef)
            throws IOException, InterruptedException
    {
        return git.listCommits(relation.targetClone(), targetRef, HISTORY_LIMIT).stream()
                .map(GitRunner.CommitEntry::subject)
                .map(WorkspaceRelationService::normalizeSubject)
                .filter(subject -> !subject.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whitespace and case are incidental to whether two subjects are the same. */
    public static String normalizeSubject(String subject)
    {
        return subject == null
                ? ""
                : subject.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private UpstreamCommitDto toCommit(
            GitRunner.DecoratedCommitEntry commit,
            boolean tagsEnabled,
            Set<String> pickedSubjects)
    {
        return new UpstreamCommitDto(
                commit.sha(),
                commit.shortSha(),
                commit.subject(),
                commit.authorName(),
                commit.authorEmail(),
                Instant.parse(commit.authoredAt()),
                tagsEnabled ? commit.tags() : List.of(),
                pickedSubjects.contains(normalizeSubject(commit.subject())));
    }

    private Optional<String> suggestedParent(
            WorkspaceRepositoryResolver.RepositoryIdentity target)
    {
        try {
            RepoMeta meta = repos.getRepoMeta(target.owner(), target.repo());
            if (meta.parentOwner() == null || meta.parentName() == null) {
                return Optional.empty();
            }
            return Optional.of(meta.parentOwner() + "/" + meta.parentName());
        }
        catch (RuntimeException e) {
            log.debug("fork parent suggestion unavailable for {}: {}",
                    target.fullName(), e.getMessage());
            return Optional.empty();
        }
    }

    private Path clonePath(WorkspaceRepositoryResolver.RepositoryIdentity repo)
    {
        WatchedRepo watched = watchedRepos.find(repo.owner(), repo.repo())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "workspace repository is not watched: " + repo.fullName()));
        if (watched.localClonePath() == null
                || watched.localClonePath().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "workspace repository has no local clone: " + repo.fullName());
        }
        Path path = Path.of(watched.localClonePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path) || !git.isGitWorkingTree(path)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "workspace repository clone is unavailable: " + repo.fullName());
        }
        return path;
    }

    private void requireNoCycle(String workspaceId, String upstreamWorkspaceId)
    {
        cycleReason(workspaceId, upstreamWorkspaceId).ifPresent(reason -> {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        });
    }

    /**
     * Why making {@code upstreamWorkspaceId} the upstream of
     * {@code workspaceId} would close a loop — walking the candidate's own
     * upstream chain until it either ends or comes back around. Empty when
     * the link is fine.
     *
     * <p>Shared by the picker and the write path so a candidate is never
     * offered and then refused.
     */
    private Optional<String> cycleReason(String workspaceId, String upstreamWorkspaceId)
    {
        Set<String> seen = new HashSet<>();
        String cursor = upstreamWorkspaceId;
        // Hop 0 is the candidate itself (a self-link, rejected earlier);
        // hop 1 is the candidate's own upstream, so coming back around
        // there is the plain reverse link.
        int hops = 0;
        while (cursor != null && seen.add(cursor)) {
            if (workspaceId.equals(cursor)) {
                return Optional.of(hops <= 1
                        ? "already reads from this workspace, so linking back would make a cycle"
                        : "reads from this workspace through another link, "
                                + "so this would make a cycle");
            }
            List<String> next = jdbc.queryForList("""
                    SELECT upstream_workspace_id
                    FROM workspace_relation
                    WHERE workspace_id = ?
                    """, String.class, cursor);
            cursor = next.isEmpty() ? null : next.getFirst();
            hops++;
        }
        return Optional.empty();
    }

    private static WorkspaceRelationDto mapRelation(ResultSet rs, int ignored)
            throws SQLException
    {
        long fetched = rs.getLong("last_fetched_at_ms");
        boolean fetchedWasNull = rs.wasNull();
        return new WorkspaceRelationDto(
                rs.getString("workspace_id"),
                rs.getString("upstream_workspace_id"),
                rs.getString("upstream_workspace_name"),
                rs.getString("upstream_repo_full_name"),
                rs.getInt("commits_enabled") != 0,
                rs.getInt("tags_enabled") != 0,
                false,
                false,
                fetchedWasNull ? null : Instant.ofEpochMilli(fetched),
                rs.getInt("auto_fetch_interval_minutes"),
                rs.getInt("indexed_commit_count"));
    }

    public record RelationUpdate(
            String upstreamWorkspaceId,
            boolean commitsEnabled,
            boolean tagsEnabled,
            int autoFetchIntervalMinutes) {}

    public record WorkspaceRelationDto(
            String workspaceId,
            String upstreamWorkspaceId,
            String upstreamWorkspaceName,
            String upstreamRepoFullName,
            boolean commitsEnabled,
            boolean tagsEnabled,
            boolean branchesEnabled,
            boolean issuesPullRequestsEnabled,
            Instant lastFetchedAt,
            int autoFetchIntervalMinutes,
            int indexedCommitCount) {}

    /**
     * @param ineligibleReason why this workspace cannot be the upstream,
     *        or null when it can. Returned rather than filtered out so
     *        the picker can say why a workspace the user expects to see
     *        is unavailable, instead of silently omitting it.
     */
    public record RelationCandidateDto(
            String workspaceId,
            String name,
            String repoFullName,
            boolean suggested,
            String ineligibleReason) {}

    public record UpstreamCommitsDto(
            String upstreamWorkspaceId,
            String upstreamWorkspaceName,
            String upstreamRepoFullName,
            String revision,
            Instant lastFetchedAt,
            int indexedCommitCount,
            int notInForkCount,
            List<UpstreamCommitDto> commits) {}

    public record UpstreamCommitDto(
            String sha,
            String shortSha,
            String subject,
            String authorName,
            String authorEmail,
            Instant authoredAt,
            List<String> tags,
            boolean picked) {}

    public record ResolvedRelation(
            WorkspaceRelationDto relation,
            WorkspaceRepositoryResolver.RepositoryIdentity target,
            WorkspaceRepositoryResolver.RepositoryIdentity upstream,
            Path targetClone,
            Path upstreamClone) {}
}
