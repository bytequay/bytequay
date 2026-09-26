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
package com.bytequay.app.context;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import com.google.common.util.concurrent.TimeLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.app.context.RepositoryContextReader.repositoryId;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Explicit, bounded discovery. Preview reads sources; acceptance only appends revalidated configuration, never starts learning. */
@Service
public class ContextDiscoveryService
{
    private static final int MAX_DIRECTORIES = 24;
    private static final int MAX_FILES = 24;
    private static final TopKSelector SUGGESTIONS = new TopKSelector(10);
    private static final TopKSelector TERMS = new TopKSelector(8);
    private static final Set<String> MANIFESTS = ImmutableSet.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "package.json", "Cargo.toml", "go.mod", "pyproject.toml", "CMakeLists.txt");
    private static final Set<String> EXCLUDED_DIRECTORIES = ImmutableSet.of("node_modules", "target", "build", "dist", "vendor");
    private static final Pattern URL = Pattern.compile("https://[^\\s<>\"'\\[\\]{}()]+");

    private final RepositoryContextService notebooks;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;
    private final ContextBaselineReader reader;
    private final ContextHistoryDiscovery history;
    private final TimeLimiter timeLimiter;
    private final Duration scanTimeout;

    public ContextDiscoveryService(
            RepositoryContextService notebooks, WorkspaceRepositoryService workspaces, GitCommandRunner git,
            ContextBaselineReader reader, ContextHistoryDiscovery history,
            @Qualifier(IO_EXECUTOR) ExecutorService executor, @Value("${bytequay.repository-context.discovery-timeout}") Duration scanTimeout)
    {
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.reader = requireNonNull(reader, "reader is null");
        this.history = requireNonNull(history, "history is null");
        this.timeLimiter = SimpleTimeLimiter.create(requireNonNull(executor, "executor is null"));
        this.scanTimeout = requireNonNull(scanTimeout, "scanTimeout is null");
        checkArgument(scanTimeout.isPositive(), "scanTimeout must be positive");
    }

    /**
     * Suggests component boundaries, literal documentation links and history after a user request.
     *
     * @param workspaceId owning workspace with an already provisioned local repository
     * @param request displayed notebook revision, selected history scopes and optional plain query
     * @return a bounded preview with frozen repository evidence, matching reasons and coverage limits
     *
     * No notebook mutation, provider launch or Git synchronization occurs. Only includeRemote permits
     * the history reader's bounded GitHub searches; repository and documentation discovery stay local.
     */
    public Preview discover(String workspaceId, Request request)
    {
        checkInterrupted();
        RepositoryNotebook notebook = current(workspaceId, request.expectedRevision());
        RepositoryState state = repositoryState(workspaceId);
        WorkspaceRepositoryContext workspace = state.workspace();
        String sha = baseline(workspace);
        List<ContextScope> selected = request.scopeIds().stream().map(id -> notebook.scopes().stream()
                        .filter(scope -> scope.id().equals(id) && scope.enabled()).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown or disabled discovery scope: " + id)))
                .collect(toImmutableList());
        Scan scan = scan(workspace, sha, notebook, request.query());
        checkInterrupted();
        ContextHistoryDiscovery.Result related = selected.isEmpty()
                ? new ContextHistoryDiscovery.Result(ImmutableList.of(), ImmutableList.of("Choose an enabled scope to find related history."), 0, 0)
                : history.discover(workspace, sha, selected, request.query(), request.includeRemote());
        List<String> gaps = ImmutableList.<String>builder().addAll(scan.gaps).addAll(related.gaps()).build();
        return new Preview(workspaceId, notebook.revision(), sha, state.fingerprint(), request.query(), scan.scopes(), scan.sources(), related.candidates(), gaps,
                new Inspected(scan.directories, scan.files, related.inspectedPullRequests(), related.inspectedIssues()));
    }

    /**
     * Re-derives selected scope/source IDs at the preview baseline and appends them to the notebook.
     *
     * @param workspaceId owning workspace
     * @param request preview revision/baseline/query and selected stable proposal IDs, never caller-authored evidence or URLs
     * @return updated notebook containing exact repository evidence for the new configuration
     * @throws ContextConflictException when the notebook changed since preview
     * @throws IllegalArgumentException for a moved baseline, changed routing or an unavailable proposal
     *
     * Candidate reads run outside locks; the final bounded ref check holds only the workspace lock,
     * before notebook mutation. Acceptance performs no history search, document fetch,
     * task allocation or generation. Existing configuration, notes and user suppression are preserved.
     */
    public RepositoryNotebook apply(String workspaceId, ApplyRequest request)
    {
        checkInterrupted();
        RepositoryNotebook notebook = current(workspaceId, request.expectedRevision());
        RepositoryState state = repositoryState(workspaceId);
        WorkspaceRepositoryContext workspace = state.workspace();
        checkArgument(state.fingerprint().equals(request.repositoryFingerprint()), "The repository routing or sync state changed; find suggestions again");
        checkArgument(baseline(workspace).equals(request.baselineSha()), "The repository baseline changed; find suggestions again");
        Scan scan = scan(workspace, request.baselineSha(), notebook, request.query());
        List<ScopeSuggestion> scopes = request.scopeIds().stream().map(id -> scan.scopes().stream()
                .filter(item -> item.scope().id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Scope suggestion is unavailable; find suggestions again"))).collect(toImmutableList());
        List<SourceSuggestion> sources = request.sourceIds().stream().map(id -> scan.sources().stream()
                .filter(item -> item.source().id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Documentation suggestion is unavailable; find suggestions again"))).collect(toImmutableList());
        List<ContextSourceEvidence> evidence = ImmutableList.<ContextSourceEvidence>builder()
                .addAll(scopes.stream().map(ScopeSuggestion::evidence).toList())
                .addAll(sources.stream().map(SourceSuggestion::evidence).toList()).build();
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            checkInterrupted();
            checkArgument(repositoryState(workspaceId).equals(state), "The repository routing or sync state changed; find suggestions again");
            // A failed sync can advance refs without updating lastCodeSyncAt. Fence that before taking the notebook mutation lock.
            checkArgument(baseline(workspace).equals(request.baselineSha()), "The repository baseline changed; find suggestions again");
            checkInterrupted();
            return notebooks.registerDiscovered(workspaceId, request.expectedRevision(),
                    scopes.stream().map(ScopeSuggestion::scope).collect(toImmutableList()),
                    sources.stream().map(SourceSuggestion::source).collect(toImmutableList()), evidence);
        });
    }

    private RepositoryNotebook current(String workspaceId, long revision)
    {
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        if (notebook.revision() != revision) {
            throw new ContextConflictException(notebook.revision());
        }
        return notebook;
    }

    private RepositoryState repositoryState(String workspaceId)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            WorkspaceRepositoryContext workspace = workspaces.requireLocalRepository(workspaceId);
            String identity = workspace + "\n" + workspaces.getWorkspace(workspaceId).lastCodeSyncAt();
            return new RepositoryState(workspace, Hashing.sha256().hashString(identity, UTF_8).toString());
        });
    }

    private String baseline(WorkspaceRepositoryContext workspace)
    {
        String ref = "refs/remotes/" + workspace.sourceRemote() + "/" + workspace.defaultBranch() + "^{commit}";
        GitCommandRunner.Result result = git.runLocalBounded(workspace.repository(), Duration.ofSeconds(2), 1024,
                ImmutableList.of("rev-parse", "--verify", "--end-of-options", ref));
        checkArgument(result.successful() && !result.truncated() && result.output().strip().matches("[0-9a-f]{40}|[0-9a-f]{64}"),
                "The monitored baseline is unavailable locally; synchronize the workspace before finding suggestions");
        return result.output().strip();
    }

    private Scan scan(WorkspaceRepositoryContext workspace, String sha, RepositoryNotebook notebook, String query)
    {
        Scan scan = new Scan(timeLimiter, scanTimeout);
        try {
            while (!scan.pending.isEmpty() && scan.directories < MAX_DIRECTORIES && scan.files < MAX_FILES) {
                String path = scan.pending.removeFirst();
                Optional<ContextBaselineReader.Directory> directory = readDirectory(scan, workspace, sha, path);
                if (directory.isPresent()) {
                    inspectDirectory(scan, workspace, sha, notebook, query, directory.orElseThrow());
                }
            }
        }
        catch (TimeoutException failure) {
            scan.gaps.add("Repository discovery reached its time budget; completed suggestions are retained and coverage is incomplete.");
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Repository discovery was interrupted", failure);
        }
        reportLimits(scan);
        return scan;
    }

    private Optional<ContextBaselineReader.Directory> readDirectory(Scan scan, WorkspaceRepositoryContext workspace, String sha, String path)
            throws TimeoutException, InterruptedException
    {
        scan.directories++;
        try {
            ContextBaselineReader.Directory directory = scan.read(() -> reader.directory(workspace, repositoryId(workspace), sha, path, 0));
            if (directory.hasMore()) {
                scan.gaps.add("Only the first 100 entries of " + (path.isEmpty() ? "/" : path) + " were inspected.");
            }
            return Optional.of(directory);
        }
        catch (RuntimeException failure) {
            scan.gaps.add("Could not inspect directory " + (path.isEmpty() ? "/" : path) + "; it supplied no suggestions.");
            return Optional.empty();
        }
    }

    private void inspectDirectory(
            Scan scan, WorkspaceRepositoryContext workspace, String sha, RepositoryNotebook notebook, String query, ContextBaselineReader.Directory directory)
            throws TimeoutException, InterruptedException
    {
        List<ContextBaselineReader.TreeEntry> files = directory.entries().stream()
                .filter(entry -> entry.kind().equals("FILE") && (MANIFESTS.contains(name(entry.path())) || documentation(entry.path())))
                .sorted(Comparator.comparing((ContextBaselineReader.TreeEntry entry) -> !documentation(entry.path())).thenComparing(ContextBaselineReader.TreeEntry::path))
                .collect(toImmutableList());
        for (ContextBaselineReader.TreeEntry file : files) {
            if (scan.files >= MAX_FILES) {
                scan.gaps.add("Repository discovery reached its 24-file budget; additional files were not inspected.");
                break;
            }
            inspectFile(scan, workspace, sha, notebook, query, directory.path(), file.path());
        }
        enqueueChildren(scan, directory, query);
    }

    private void inspectFile(Scan scan, WorkspaceRepositoryContext workspace, String sha, RepositoryNotebook notebook, String query, String directory, String path)
            throws TimeoutException, InterruptedException
    {
        scan.files++;
        try {
            ContextBaselineReader.Read read = scan.read(() -> reader.read(workspace, repositoryId(workspace), sha, path, 1, Optional.empty()));
            if (read.truncated() || read.endLine() < read.totalLines()) {
                scan.gaps.add("Only a bounded opening excerpt of " + path + " was inspected.");
            }
            if (MANIFESTS.contains(name(path)) && !directory.isEmpty() && matches(directory, query)) {
                scope(scan, directory, read, notebook, workspace.monitoredRepository().repo());
            }
            if (documentation(path)) {
                documents(scan, read, notebook);
            }
        }
        catch (RuntimeException failure) {
            scan.gaps.add("Could not inspect " + path + "; no proposal was accepted from it.");
        }
    }

    private static void enqueueChildren(Scan scan, ContextBaselineReader.Directory directory, String query)
    {
        // A manifest or a nonmatching parent name does not rule out nested components in another language.
        directory.entries().stream().filter(entry -> entry.kind().equals("DIRECTORY") && safePath(entry.path()))
                .filter(entry -> !name(entry.path()).startsWith(".") && !EXCLUDED_DIRECTORIES.contains(name(entry.path())))
                .sorted(Comparator.comparing((ContextBaselineReader.TreeEntry entry) -> !matches(entry.path(), query)).thenComparing(ContextBaselineReader.TreeEntry::path))
                .forEach(entry -> scan.pending.addLast(entry.path()));
    }

    private static void reportLimits(Scan scan)
    {
        if (!scan.pending.isEmpty() && (scan.directories >= MAX_DIRECTORIES || scan.files >= MAX_FILES)) {
            scan.gaps.add("Directory discovery stopped at 24 directories or 24 files; narrow the query to inspect another area.");
        }
        if (scan.scopeCandidates.size() > SUGGESTIONS.limit() || scan.sourceCandidates.size() > SUGGESTIONS.limit()) {
            scan.gaps.add("At most ten component scopes and ten documentation locations are suggested.");
        }
        scan.gaps.add("Discovery is a bounded heuristic over frozen repository files; absence of a suggestion does not establish absence of a component or documentation.");
    }

    private static void checkInterrupted()
    {
        checkState(!Thread.currentThread().isInterrupted(), "Repository discovery was interrupted");
    }

    private static void scope(Scan scan, String path, ContextBaselineReader.Read read, RepositoryNotebook notebook, String repositoryName)
    {
        String prefix = path + "/";
        String id = "path:" + prefix;
        if (read.excerpt().isBlank() || notebook.scopes().stream().anyMatch(scope -> scope.id().equals(id)
                || scope.match() instanceof ContextMatch.Selected match && match.pathPrefixes().contains(prefix))) {
            return;
        }
        ContextSourceEvidence evidence = evidence(read, ContextText.prefix(read.excerpt(), 1200), "Component-manifest candidate at " + read.reference().path());
        String title = ContextText.prefix(path, 200);
        List<String> terms = TERMS.select(Arrays.stream(name(path).split("[-_.]"))
                .filter(term -> term.length() >= 3 && !term.equalsIgnoreCase(repositoryName)).distinct()).collect(toImmutableList());
        ContextScope scope = new ContextScope(id, title, new ContextMatch.Selected(ImmutableList.of(prefix), terms), true, Optional.empty(), Optional.of(evidence.id()));
        scan.scopeCandidates.putIfAbsent(id, new ScopeSuggestion(scope, evidence,
                ImmutableList.of("Directory contains " + name(read.reference().path()) + " at the monitored baseline; component boundary is a suggestion.")));
    }

    private static void documents(Scan scan, ContextBaselineReader.Read read, RepositoryNotebook notebook)
    {
        String captured = read.excerpt();
        if (read.truncated() && !captured.endsWith("\n")) {
            captured = captured.substring(0, captured.lastIndexOf('\n') + 1);
            scan.gaps.add("An unfinished excerpt line in " + read.reference().path() + " was excluded from documentation-link discovery.");
        }
        for (String line : captured.lines().toList()) {
            Matcher matcher = URL.matcher(line);
            while (matcher.find()) {
                if (matcher.end() < line.length() && "([{\"'".indexOf(line.charAt(matcher.end())) >= 0) {
                    scan.gaps.add("A documentation link with an ambiguous boundary in " + read.reference().path() + " was excluded.");
                    continue;
                }
                String url = matcher.group().replaceAll("[.,;:]+$", "");
                try {
                    ContextLimits.documentationUrl(url);
                    URI uri = URI.create(url);
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (!uri.getHost().startsWith("docs.") && !uri.getPath().contains("/docs/") && !uri.getPath().contains("/documentation/")
                            && !lower.matches(".*\\b(documentation|docs|manual|guide|reference)\\b.*")) {
                        continue;
                    }
                    if (notebook.sources().stream().anyMatch(source -> source.url().equals(url))) {
                        continue;
                    }
                    String id = "docs:" + Hashing.sha256().hashString(url, UTF_8).toString().substring(0, 24);
                    if (scan.sourceCandidates.containsKey(id) || scan.sourceCandidates.size() > SUGGESTIONS.limit()) {
                        continue;
                    }
                    ContextSourceEvidence evidence = evidence(read, ContextLimits.bytes(line) <= 3000 ? line.strip() : url,
                            "Literal documentation link in " + read.reference().path());
                    RegisteredContextSource source = new RegisteredContextSource(id,
                            url.endsWith("/") ? RegisteredContextSource.Kind.DOCUMENTATION_ROOT : RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                            url, ContextEntryOrigin.GENERATED, Optional.of(evidence.id()));
                    scan.sourceCandidates.putIfAbsent(id, new SourceSuggestion(source, evidence,
                            ImmutableList.of("Exact HTTPS link in " + read.reference().path() + "; its page/root boundary is unchanged.")));
                }
                catch (IllegalArgumentException failure) {
                    scan.gaps.add("A documentation link in " + read.reference().path() + " was excluded because it is not a supported public HTTPS location.");
                }
            }
        }
    }

    private static ContextSourceEvidence evidence(ContextBaselineReader.Read read, String excerpt, String reason)
    {
        return new ContextSourceEvidence("discovery:" + UUID.randomUUID(), read.reference(), reason + "; frozen blob " + read.blobId(),
                excerpt, ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(read.readAt()), Optional.of("git-blob:" + read.blobId()),
                Optional.of(read.reference().commit()), read.truncated() || read.endLine() < read.totalLines() || !excerpt.equals(read.excerpt()));
    }

    private static boolean matches(String path, String query)
    {
        return query.isBlank() || path.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static boolean safePath(String path)
    {
        try {
            ContextLimits.pathPrefix(path + "/");
            return true;
        }
        catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static String name(String path)
    {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static boolean documentation(String path)
    {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = name(lower);
        return name.startsWith("readme") || name.startsWith("contributing") || name.startsWith("architecture")
                || lower.equals("docs") || lower.equals("doc") || lower.equals("documentation")
                || (lower.startsWith("docs/") || lower.startsWith("doc/") || lower.startsWith("documentation/"))
                && (lower.endsWith(".md") || lower.endsWith(".rst") || lower.endsWith(".txt") || lower.endsWith(".html"));
    }

    private static String query(String query)
    {
        requireNonNull(query, "query is null");
        String value = query.strip();
        checkArgument(ContextLimits.bytes(value) <= 100 && value.matches("[\\p{L}\\p{N} _./-]*"), "Query must be at most 100 UTF-8 bytes of plain words or a repository path");
        return value;
    }

    public record Request(long expectedRevision, List<String> scopeIds, String query, boolean includeRemote)
    {
        public Request
        {
            checkArgument(expectedRevision >= 0, "expectedRevision must not be negative");
            scopeIds = ImmutableList.copyOf(scopeIds);
            ContextLimits.unique(scopeIds, "scopeIds", 100);
            scopeIds.forEach(id -> ContextLimits.text(id, "scopeId", 1100));
            query = ContextDiscoveryService.query(query);
        }
    }

    public record ApplyRequest(long expectedRevision, String baselineSha, String repositoryFingerprint, String query, List<String> scopeIds, List<String> sourceIds)
    {
        public ApplyRequest
        {
            checkArgument(expectedRevision >= 0, "expectedRevision must not be negative");
            checkArgument(baselineSha != null && baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid discovery baseline");
            checkArgument(repositoryFingerprint != null && repositoryFingerprint.matches("[0-9a-f]{64}"), "Invalid repository fingerprint");
            query = ContextDiscoveryService.query(query);
            scopeIds = ImmutableList.copyOf(scopeIds);
            sourceIds = ImmutableList.copyOf(sourceIds);
            ContextLimits.unique(scopeIds, "scopeIds", SUGGESTIONS.limit());
            ContextLimits.unique(sourceIds, "sourceIds", SUGGESTIONS.limit());
            scopeIds.forEach(id -> ContextLimits.text(id, "scopeId", 1100));
            sourceIds.forEach(id -> ContextLimits.text(id, "sourceId", 500));
            checkArgument(!scopeIds.isEmpty() || !sourceIds.isEmpty(), "Select at least one new scope or documentation location");
        }
    }

    public record ScopeSuggestion(ContextScope scope, ContextSourceEvidence evidence, List<String> reasons)
    {
        public ScopeSuggestion
        {
            reasons = ImmutableList.copyOf(reasons);
        }
    }

    public record SourceSuggestion(RegisteredContextSource source, ContextSourceEvidence evidence, List<String> reasons)
    {
        public SourceSuggestion
        {
            reasons = ImmutableList.copyOf(reasons);
        }
    }

    public record Inspected(int directories, int files, int pullRequests, int issues) {}

    public record Preview(
            String workspaceId, long notebookRevision, String baselineSha, String repositoryFingerprint, String query,
            List<ScopeSuggestion> scopes, List<SourceSuggestion> sources,
            List<ContextHistoryDiscovery.Candidate> history, List<String> gaps, Inspected inspected)
    {
        public Preview
        {
            scopes = ImmutableList.copyOf(scopes);
            sources = ImmutableList.copyOf(sources);
            history = ImmutableList.copyOf(history);
            gaps = ImmutableList.copyOf(gaps);
        }
    }

    private record RepositoryState(WorkspaceRepositoryContext workspace, String fingerprint) {}

    private static final class Scan
    {
        private final TimeLimiter timeLimiter;
        private final Duration timeout;
        private final Stopwatch stopwatch = Stopwatch.createStarted();
        private final Deque<String> pending = new ArrayDeque<>();
        private final Map<String, ScopeSuggestion> scopeCandidates = new LinkedHashMap<>();
        private final Map<String, SourceSuggestion> sourceCandidates = new LinkedHashMap<>();
        private final Set<String> gaps = new LinkedHashSet<>();
        private int directories;
        private int files;

        private Scan(TimeLimiter timeLimiter, Duration timeout)
        {
            this.timeLimiter = timeLimiter;
            this.timeout = timeout;
            pending.add("");
        }

        private <T> T read(Callable<T> source)
                throws TimeoutException, InterruptedException
        {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Repository discovery was interrupted");
            }
            Duration remaining = timeout.minus(stopwatch.elapsed());
            if (!remaining.isPositive()) {
                throw new TimeoutException("Repository discovery time budget exhausted");
            }
            try {
                // Only immutable read results cross threads; a late result cannot mutate the caller's scan.
                return timeLimiter.callWithTimeout(source, remaining);
            }
            catch (ExecutionException failure) {
                throw new IllegalStateException("Repository source read failed", failure.getCause());
            }
        }

        private List<ScopeSuggestion> scopes()
        {
            return SUGGESTIONS.select(scopeCandidates.values().stream()).collect(toImmutableList());
        }

        private List<SourceSuggestion> sources()
        {
            return SUGGESTIONS.select(sourceCandidates.values().stream()).collect(toImmutableList());
        }
    }
}
