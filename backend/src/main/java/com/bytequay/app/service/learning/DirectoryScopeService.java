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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.NotFoundException;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Derives code-area proposals from analyzed PR evidence. Suggestions
 * are advisory: only an explicit approved decision can be assigned to a
 * thread, and this service never creates trunks.
 */
@Service
public class DirectoryScopeService
{
    private static final int MIN_EVIDENCE_PRS = 5;
    private static final int MAX_HISTORY_THRESHOLD = 25;
    private static final List<String> BUILD_MANIFESTS = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts", "package.json",
            "Cargo.toml", "go.mod", "pyproject.toml", "CMakeLists.txt",
            "Makefile", "build.xml", "WORKSPACE", "WORKSPACE.bazel", "MODULE.bazel");

    private final JdbcTemplate jdbc;
    private final WorkspaceRepositoryResolver repositories;
    private final WatchedRepoStore watchedRepos;
    private final ThreadStore threads;

    public DirectoryScopeService(
            JdbcTemplate jdbc,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            ThreadStore threads)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.threads = requireNonNull(threads, "threads is null");
    }

    public record Suggestion(
            String name,
            List<String> paths,
            int evidencePrCount,
            double confidence,
            String rationale,
            String decisionState) {}

    public record Assignment(
            String threadId,
            String name,
            List<String> paths,
            String decisionState,
            long assignedAtMs) {}

    public record Overview(
            int catalogedPrCount,
            int analyzedPrCount,
            int requiredAnalyzedPrCount,
            boolean historyReady,
            List<Suggestion> suggestions,
            List<Assignment> assignments) {}

    public record Decision(
            List<String> paths,
            String decisionState,
            long decidedAtMs) {}

    public Overview suggestions(String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity identity = repositories.resolve(workspaceId);
        String repo = identity.fullName();
        int cataloged = countSources(workspaceId, repo, null);
        int analyzed = countSources(workspaceId, repo, "analyzed");
        int required = Math.min(MAX_HISTORY_THRESHOLD, cataloged);
        List<Assignment> assignments = assignments(workspaceId, repo);
        if (cataloged == 0 || analyzed < required) {
            return new Overview(cataloged, analyzed, required, false, List.of(), assignments);
        }

        Optional<Path> root = cloneRoot(identity);
        if (root.isEmpty()) {
            return new Overview(cataloged, analyzed, required, true, List.of(), assignments);
        }

        Map<String, CandidateSupport> support = new TreeMap<>();
        jdbc.queryForList("""
                SELECT DISTINCT evidence.pr_number, evidence.file_path
                FROM repo_pr_evidence_ref evidence
                JOIN repo_pr_source source
                  ON source.workspace_id = evidence.workspace_id
                 AND source.repo = evidence.repo
                 AND source.pr_number = evidence.pr_number
                JOIN repo_pr_evidence_bundle bundle
                  ON bundle.workspace_id = evidence.workspace_id
                 AND bundle.repo = evidence.repo
                 AND bundle.pr_number = evidence.pr_number
                WHERE evidence.workspace_id = ? AND evidence.repo = ?
                  AND source.analysis_state = 'analyzed'
                  AND bundle.overall_completeness = 'complete'
                  AND evidence.ref_kind IN ('file', 'test')
                  AND evidence.file_path IS NOT NULL
                  AND trim(evidence.file_path) <> ''
                ORDER BY evidence.pr_number, evidence.file_path
                """, workspaceId, repo).forEach(row ->
                resolveScope(root.get(), (String) row.get("file_path"))
                        .ifPresent(scope -> support
                                .computeIfAbsent(scope.path(), ignored ->
                                        new CandidateSupport(scope.basis()))
                                .prNumbers().add(((Number) row.get("pr_number")).intValue())));

        Map<String, String> decisions = decisions(workspaceId, repo);
        List<Suggestion> candidates = new ArrayList<>();
        support.forEach((path, evidence) -> {
            int evidenceCount = evidence.prNumbers().size();
            if (evidenceCount < MIN_EVIDENCE_PRS) {
                return;
            }
            double confidence = Math.round(100.0 * evidenceCount / analyzed) / 100.0;
            String rationale = "%d distinct analyzed PRs changed files under %s; %s."
                    .formatted(evidenceCount, path, evidence.basis());
            candidates.add(new Suggestion(
                    nameOf(path), List.of(path), evidenceCount, confidence, rationale,
                    decisions.getOrDefault(path, "pending")));
        });
        return new Overview(cataloged, analyzed, required, true,
                List.copyOf(candidates), assignments);
    }

    @Transactional
    public Decision decide(String workspaceId, String requestedPath, String decisionState)
    {
        String state = normalizeDecision(decisionState);
        String path = canonicalPath(requestedPath);
        WorkspaceRepositoryResolver.RepositoryIdentity identity = repositories.resolve(workspaceId);
        boolean currentSuggestion = suggestions(workspaceId).suggestions().stream()
                .anyMatch(candidate -> candidate.paths().equals(List.of(path)));
        if (!currentSuggestion) {
            throw new IllegalArgumentException("code area is not a current suggestion: " + path);
        }

        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                INSERT INTO repo_directory_scope_decision (
                    workspace_id, repo, scope_path, decision_state, decided_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(workspace_id, repo, scope_path) DO UPDATE SET
                    decision_state = excluded.decision_state,
                    decided_at_ms = excluded.decided_at_ms
                """, workspaceId, identity.fullName(), path, state, now);
        if ("rejected".equals(state)) {
            jdbc.update("""
                    DELETE FROM thread_directory_scope_assignment
                    WHERE workspace_id = ? AND repo = ? AND scope_path = ?
                    """, workspaceId, identity.fullName(), path);
        }
        return new Decision(List.of(path), state, now);
    }

    @Transactional
    public Assignment assign(String workspaceId, String threadId, String requestedPath)
    {
        requireOwnedThread(workspaceId, threadId);
        String path = canonicalPath(requestedPath);
        WorkspaceRepositoryResolver.RepositoryIdentity identity = repositories.resolve(workspaceId);
        Integer approved = jdbc.queryForObject("""
                SELECT count(*) FROM repo_directory_scope_decision
                WHERE workspace_id = ? AND repo = ? AND scope_path = ?
                  AND decision_state = 'approved'
                """, Integer.class, workspaceId, identity.fullName(), path);
        if (approved == null || approved == 0) {
            throw new IllegalArgumentException("code area is not approved: " + path);
        }
        Path root = cloneRoot(identity)
                .orElseThrow(() -> new IllegalArgumentException(
                        "workspace repository has no verified clone"));
        requireExistingDirectory(root, path);

        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                INSERT INTO thread_directory_scope_assignment (
                    thread_id, workspace_id, repo, scope_path, assigned_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(thread_id) DO UPDATE SET
                    workspace_id = excluded.workspace_id,
                    repo = excluded.repo,
                    scope_path = excluded.scope_path,
                    assigned_at_ms = excluded.assigned_at_ms
                """, threadId, workspaceId, identity.fullName(), path, now);
        return new Assignment(threadId, nameOf(path), List.of(path), "approved", now);
    }

    @Transactional
    public void clearAssignment(String workspaceId, String threadId)
    {
        requireOwnedThread(workspaceId, threadId);
        jdbc.update("""
                DELETE FROM thread_directory_scope_assignment
                WHERE workspace_id = ? AND thread_id = ?
                """, workspaceId, threadId);
    }

    private int countSources(String workspaceId, String repo, String state)
    {
        String sql = "SELECT count(*) FROM repo_pr_source WHERE workspace_id = ? AND repo = ?";
        Integer count;
        if (state == null) {
            count = jdbc.queryForObject(sql, Integer.class, workspaceId, repo);
        }
        else if ("analyzed".equals(state)) {
            count = jdbc.queryForObject("""
                    SELECT count(*) FROM repo_pr_source source
                    JOIN repo_pr_evidence_bundle bundle
                      ON bundle.workspace_id = source.workspace_id
                     AND bundle.repo = source.repo
                     AND bundle.pr_number = source.pr_number
                    WHERE source.workspace_id = ? AND source.repo = ?
                      AND source.analysis_state = 'analyzed'
                      AND bundle.overall_completeness = 'complete'
                    """, Integer.class, workspaceId, repo);
        }
        else {
            count = jdbc.queryForObject(sql + " AND analysis_state = ?",
                    Integer.class, workspaceId, repo, state);
        }
        return count == null ? 0 : count;
    }

    private Map<String, String> decisions(String workspaceId, String repo)
    {
        Map<String, String> result = new TreeMap<>();
        jdbc.queryForList("""
                SELECT scope_path, decision_state
                FROM repo_directory_scope_decision
                WHERE workspace_id = ? AND repo = ?
                """, workspaceId, repo).forEach(row -> result.put(
                (String) row.get("scope_path"), (String) row.get("decision_state")));
        return result;
    }

    private List<Assignment> assignments(String workspaceId, String repo)
    {
        return jdbc.query("""
                SELECT thread_id, scope_path, assigned_at_ms
                FROM thread_directory_scope_assignment
                WHERE workspace_id = ? AND repo = ?
                ORDER BY thread_id
                """, (row, ignored) -> {
                    String path = row.getString("scope_path");
                    return new Assignment(row.getString("thread_id"), nameOf(path),
                            List.of(path), "approved", row.getLong("assigned_at_ms"));
                }, workspaceId, repo);
    }

    private Optional<Path> cloneRoot(WorkspaceRepositoryResolver.RepositoryIdentity identity)
    {
        String clone = watchedRepos.find(identity.owner(), identity.repo())
                .map(WatchedRepo::localClonePath)
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
        if (clone == null) {
            return Optional.empty();
        }
        try {
            Path root = Path.of(clone).toRealPath();
            return Files.isDirectory(root) ? Optional.of(root) : Optional.empty();
        }
        catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static Optional<ResolvedScope> resolveScope(Path root, String rawPath)
    {
        Optional<Path> relative = relativePath(rawPath);
        if (relative.isEmpty() || relative.get().getNameCount() < 2) {
            return Optional.empty();
        }
        Path evidencePath = root.resolve(relative.get()).normalize();
        if (!evidencePath.startsWith(root)) {
            return Optional.empty();
        }

        Path directory = Files.isDirectory(evidencePath) ? evidencePath : evidencePath.getParent();
        while (directory != null && directory.startsWith(root) && !directory.equals(root)) {
            String manifest = manifestIn(directory);
            if (manifest != null && isDirectoryInside(root, directory)) {
                return Optional.of(new ResolvedScope(
                        relativeTo(root, directory), "module root identified by " + manifest));
            }
            directory = directory.getParent();
        }

        Path topLevel = root.resolve(relative.get().getName(0)).normalize();
        if (isDirectoryInside(root, topLevel)) {
            return Optional.of(new ResolvedScope(
                    relativeTo(root, topLevel), "resolved by the top-level directory fallback"));
        }
        return Optional.empty();
    }

    private static String manifestIn(Path directory)
    {
        for (String manifest : BUILD_MANIFESTS) {
            if (Files.isRegularFile(directory.resolve(manifest))) {
                return manifest;
            }
        }
        return null;
    }

    private static boolean isDirectoryInside(Path root, Path directory)
    {
        try {
            return Files.isDirectory(directory) && directory.toRealPath().startsWith(root);
        }
        catch (IOException e) {
            return false;
        }
    }

    private static void requireExistingDirectory(Path root, String path)
    {
        Path directory = root.resolve(path).normalize();
        if (!directory.startsWith(root) || directory.equals(root)
                || !isDirectoryInside(root, directory)) {
            throw new IllegalArgumentException("code area does not exist: " + path);
        }
    }

    private void requireOwnedThread(String workspaceId, String threadId)
    {
        boolean owned = threads.findThreadById(threadId)
                .filter(thread -> workspaceId.equals(thread.workspaceId()))
                .isPresent();
        if (!owned) {
            throw new NotFoundException("no thread in workspace: " + threadId);
        }
    }

    private static Optional<Path> relativePath(String rawPath)
    {
        if (rawPath == null || rawPath.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(rawPath.replace('\\', '/')).normalize();
            if (path.isAbsolute() || path.getNameCount() == 0
                    || path.startsWith("..") || ".".equals(path.toString())) {
                return Optional.empty();
            }
            return Optional.of(path);
        }
        catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static String canonicalPath(String rawPath)
    {
        return relativePath(rawPath)
                .map(path -> path.toString().replace(File.separatorChar, '/'))
                .orElseThrow(() -> new IllegalArgumentException("invalid code-area path"));
    }

    private static String normalizeDecision(String state)
    {
        String normalized = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        if (!"approved".equals(normalized) && !"rejected".equals(normalized)) {
            throw new IllegalArgumentException("decision must be approved or rejected");
        }
        return normalized;
    }

    private static String relativeTo(Path root, Path directory)
    {
        return root.relativize(directory).toString().replace(File.separatorChar, '/');
    }

    private static String nameOf(String path)
    {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private record ResolvedScope(String path, String basis) {}

    private record CandidateSupport(String basis, Set<Integer> prNumbers)
    {
        private CandidateSupport(String basis)
        {
            this(basis, new LinkedHashSet<>());
        }
    }
}
