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
package com.bytequay.app.cherrypick;

import com.bytequay.app.cherrypick.CherryPickCommitPreview.TargetMatch;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.repository.RepositoryEnrichmentService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.RepoRef;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Builds one ordered, read-only preview of a source range.
 *
 * <p>Synchronizing the local target branch is the only step in the whole
 * workflow that contacts a Git remote. Everything after it reads local objects.
 */
@Service
public class CherryPickPreviewService
{
    static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(30);
    static final int MAX_CACHED_PREVIEWS = 8;
    private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_RANGE_COMMITS = 3000;
    private static final int MAX_ORIGIN_SCAN_COMMITS = 5000;
    /** ASCII unit separator: cannot appear in a ref, path, or commit subject. */
    private static final String FIELD = "\u001f";
    /** ASCII record separator, for the same reason. */
    private static final String RECORD = "\u001e";
    private static final Pattern ORIGIN_LINE =
            Pattern.compile("^\\(cherry picked from commit ([0-9a-f]{40}|[0-9a-f]{64})\\)$");

    private final GitCommandRunner git;
    private final CherryPickConflictScout conflictScout;
    private final RepositoryEnrichmentService enrichment;
    private final CredentialService credentials;
    private final WorkspaceRemoteSourceService remoteSources;
    private final Clock clock;
    private final Cache<String, CherryPickPreview> cache;

    public CherryPickPreviewService(
            GitCommandRunner git,
            CherryPickConflictScout conflictScout,
            RepositoryEnrichmentService enrichment,
            CredentialService credentials,
            WorkspaceRemoteSourceService remoteSources,
            Clock clock)
    {
        this.git = requireNonNull(git, "git is null");
        this.conflictScout = requireNonNull(conflictScout, "conflictScout is null");
        this.enrichment = requireNonNull(enrichment, "enrichment is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.remoteSources = requireNonNull(remoteSources, "remoteSources is null");
        this.clock = requireNonNull(clock, "clock is null");
        // One time source drives both the stored expiry and the cache's own, so
        // a preview cannot read as live in one and expired in the other.
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(MAX_CACHED_PREVIEWS)
                .expireAfterWrite(PREVIEW_LIFETIME)
                .ticker(new Ticker()
                {
                    @Override
                    public long read()
                    {
                        return MILLISECONDS.toNanos(clock.millis());
                    }
                })
                .build();
    }

    /**
     * Synchronizes the target branch and returns one frozen preview.
     *
     * @param command repository, source range, and target branch
     * @return preview with resolved object IDs and per-commit evidence
     */
    public CherryPickPreview preview(PreviewCherryPickRangeCommand command)
    {
        requireNonNull(command, "command is null");
        Path repository = command.repository();
        requireRepository(repository);
        requireFullClone(repository);
        Map<String, String> environment = authenticationEnvironment(
                repository,
                command.workspaceId(),
                ImmutableList.of(command.sourceRemote(), command.targetRemote()));
        synchronizeTarget(repository, command, environment);

        String targetSha = resolve(repository, command.localTargetBranch());
        String baseSha = resolve(repository, command.sourceBaseRef());
        String tipSha = resolve(repository, command.sourceTipRef());
        if (baseSha.equals(tipSha)) {
            throw new CherryPickInvalidRequestException("Source range is empty: base and tip are the same commit");
        }
        if (!isAncestor(repository, baseSha, tipSha)) {
            throw new CherryPickInvalidRequestException(
                    "Source base " + shortSha(baseSha) + " is not an ancestor of tip " + shortSha(tipSha));
        }

        List<RangeCommit> range = readRange(repository, baseSha, tipSha);
        Set<String> targetAncestors = readTargetAncestors(repository, targetSha, range);
        Map<String, String> originLines = readOriginLines(repository, targetSha, baseSha);
        List<String> warnings = new ArrayList<>();
        Set<String> patchEquivalent = readPatchEquivalent(repository, targetSha, tipSha, baseSha, warnings);

        // Only commits execution would actually attempt are simulated; a commit
        // already on the target is never picked, so it cannot conflict.
        List<String> planned = range.stream()
                .map(RangeCommit::sha)
                .filter(sha -> evidence(sha, targetAncestors, originLines).isEmpty())
                .collect(toImmutableList());
        Map<String, ConflictPreview> conflicts = conflictScout.scout(repository, targetSha, planned);
        Map<String, PullRequestAssociation> associations = persistedAssociations(command, range, warnings);

        ImmutableList.Builder<CherryPickCommitPreview> commits = ImmutableList.builderWithExpectedSize(range.size());
        int position = 1;
        for (RangeCommit commit : range) {
            Optional<TargetMatch> match = evidence(commit.sha(), targetAncestors, originLines);
            commits.add(new CherryPickCommitPreview(
                    position++,
                    commit.sha(),
                    commit.parentSha(),
                    commit.title(),
                    commit.author(),
                    commit.committedAt(),
                    commit.changedPaths(),
                    commit.changedPaths().isEmpty(),
                    match,
                    patchEquivalent.contains(commit.sha()),
                    match.isPresent()
                            ? ConflictPreview.clean()
                            : conflicts.getOrDefault(commit.sha(), ConflictPreview.notAnalyzed()),
                    Optional.ofNullable(associations.get(commit.sha()))));
        }
        if (conflicts.values().stream().anyMatch(preview -> preview.status() == ConflictPreview.Status.CONFLICT)) {
            warnings.add("Conflict scope is exact up to the first conflict. Commits after it are not analyzed, "
                    + "because the resolution you choose decides what they meet.");
        }

        Instant now = clock.instant();
        CherryPickPreview preview = new CherryPickPreview(
                UUID.randomUUID().toString(),
                command.workspaceId(),
                readRepository(repository, command.targetRemote()),
                command.sourceRemote(),
                command.targetRemote(),
                command.targetBranch(),
                command.localTargetBranch(),
                baseSha,
                tipSha,
                targetSha,
                commits.build(),
                ImmutableList.copyOf(warnings),
                now,
                now.plus(PREVIEW_LIFETIME));
        cache.put(preview.previewId(), preview);
        return preview;
    }

    private Map<String, PullRequestAssociation> persistedAssociations(
            PreviewCherryPickRangeCommand command,
            List<RangeCommit> range,
            List<String> warnings)
    {
        OptionalLong repositoryId = command.sourceRepositoryId();
        if (repositoryId.isEmpty()) {
            warnings.add("Pull-request grouping is unavailable until commit history has been synchronized. "
                    + "Every commit is listed individually and stays selectable.");
            return ImmutableMap.of();
        }
        Map<String, PullRequestAssociation> associations = new LinkedHashMap<>();
        enrichment.findCommits(
                        repositoryId.orElseThrow(),
                        range.stream().map(RangeCommit::sha).collect(toImmutableList()))
                .commits().stream()
                .filter(value -> value.pullRequestNumber().isPresent())
                .forEach(value -> associations.put(value.sha(), association(value)));
        int missing = range.size() - associations.size();
        if (missing > 0) {
            warnings.add(missing + " commit(s) have no stored pull-request association. "
                    + "They are listed individually and stay selectable.");
        }
        return ImmutableMap.copyOf(associations);
    }

    private static PullRequestAssociation association(CommitEnrichment enrichment)
    {
        int number = enrichment.pullRequestNumber().orElseThrow();
        return new PullRequestAssociation(
                number,
                enrichment.pullRequestTitle().orElse("PR #" + number),
                enrichment.pullRequestUrl().orElse(""),
                "",
                "");
    }

    /**
     * Returns a cached preview that has not expired.
     *
     * @param previewId identifier returned by {@link #preview}
     * @return live preview, or empty when unknown or expired
     */
    public Optional<CherryPickPreview> find(String previewId)
    {
        requireNonNull(previewId, "previewId is null");
        return Optional.ofNullable(cache.getIfPresent(previewId));
    }

    /**
     * Synchronizes the target again immediately before execution and rejects
     * a preview whose frozen target is no longer current.
     */
    void requireCurrentTarget(Path repository, CherryPickPreview preview)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(preview, "preview is null");
        Map<String, String> environment = authenticationEnvironment(
                repository,
                preview.workspaceId(),
                ImmutableList.of(preview.targetRemote()));
        synchronizeTarget(
                repository,
                preview.targetRemote(),
                preview.targetBranch(),
                preview.localTargetBranch(),
                "refs/remotes/" + preview.targetRemote() + "/" + preview.targetBranch(),
                environment);
        if (!resolve(repository, preview.localTargetBranch()).equals(preview.targetSha())) {
            throw new CherryPickInvalidRequestException(
                    "Preview is stale; the synchronized target moved, so review a refreshed preview");
        }
    }

    private static Optional<TargetMatch> evidence(
            String sourceSha,
            Set<String> targetAncestors,
            Map<String, String> originLines)
    {
        if (targetAncestors.contains(sourceSha)) {
            return Optional.of(new TargetMatch(TargetMatch.Kind.DIRECT_ANCESTOR, sourceSha));
        }
        return Optional.ofNullable(originLines.get(sourceSha))
                .map(targetCommit -> new TargetMatch(TargetMatch.Kind.CHERRY_PICK_ORIGIN, targetCommit));
    }

    private void requireRepository(Path repository)
    {
        if (!git.run(repository, "rev-parse", "--git-dir").successful()) {
            throw new CherryPickInvalidRequestException("Not a Git repository: " + repository);
        }
    }

    private void requireFullClone(Path repository)
    {
        // Range enumeration, ancestry, and merge-base all read wrong on a shallow
        // clone, and they fail quietly rather than loudly.
        if (required(repository, "rev-parse", "--is-shallow-repository").equals("true")) {
            throw new CherryPickInvalidRequestException(
                    "Repository is a shallow clone; run 'git fetch --unshallow' before previewing a range");
        }
    }

    private void synchronizeTarget(
            Path repository,
            PreviewCherryPickRangeCommand command,
            Map<String, String> environment)
    {
        Result source = git.run(
                repository,
                FETCH_TIMEOUT,
                environment,
                ImmutableList.of("fetch", "--quiet", command.sourceRemote()));
        if (!source.successful()) {
            throw new CherryPickConflictException(
                    "Cannot fetch source remote " + command.sourceRemote() + ": " + source.output().strip());
        }
        synchronizeTarget(
                repository,
                command.targetRemote(),
                command.targetBranch(),
                command.localTargetBranch(),
                command.remoteTrackingRef(),
                environment);
    }

    private void synchronizeTarget(
            Path repository,
            String targetRemote,
            String targetBranch,
            String localBranch,
            String remoteTrackingRef,
            Map<String, String> environment)
    {
        Optional<String> holder = checkedOutAt(repository, localBranch);
        if (holder.isPresent()) {
            throw new CherryPickConflictException(
                    "Local target branch " + localBranch + " is checked out at " + holder.orElseThrow()
                            + "; Git cannot synchronize a checked-out branch");
        }

        Result target = git.run(
                repository,
                FETCH_TIMEOUT,
                environment,
                ImmutableList.of("fetch", "--quiet", targetRemote, targetBranch + ":" + localBranch));

        // Neither the exit code nor the message decides this. A rejected
        // non-fast-forward still advances the remote-tracking ref, and --quiet
        // suppresses the rejection line, so only the refs themselves say whether
        // the local branch actually moved.
        Optional<String> remote = tryResolve(repository, remoteTrackingRef);
        if (remote.isEmpty()) {
            throw new CherryPickConflictException(
                    "Cannot fetch target branch " + remoteTrackingRef + ": " + target.output().strip());
        }
        Optional<String> local = tryResolve(repository, localBranch);
        if (local.isEmpty()) {
            throw new CherryPickConflictException(
                    "Local target branch " + localBranch + " was not created by the fetch: "
                            + target.output().strip());
        }
        if (!local.equals(remote)) {
            throw new CherryPickConflictException(
                    "Local target branch " + localBranch + " has diverged from " + remoteTrackingRef
                            + "; reconcile it before previewing a range");
        }
    }

    private Optional<String> checkedOutAt(Path repository, String branch)
    {
        String current = null;
        for (String line : required(repository, "worktree", "list", "--porcelain").split("\n")) {
            if (line.startsWith("worktree ")) {
                current = line.substring("worktree ".length()).strip();
            }
            else if (line.strip().equals("branch refs/heads/" + branch)) {
                return Optional.ofNullable(current);
            }
        }
        return Optional.empty();
    }

    private List<RangeCommit> readRange(Path repository, String baseSha, String tipSha)
    {
        String output = requiredRaw(
                repository,
                "log",
                "--reverse",
                "--topo-order",
                "--name-only",
                "--no-renames",
                "--format=" + RECORD + "%H" + FIELD + "%P" + FIELD + "%s" + FIELD + "%an" + FIELD + "%cI" + FIELD,
                baseSha + ".." + tipSha);
        ImmutableList.Builder<RangeCommit> commits = ImmutableList.builder();
        int count = 0;
        for (String record : output.split(RECORD)) {
            if (record.isBlank()) {
                continue;
            }
            String[] fields = record.split(FIELD, -1);
            if (fields.length < 6) {
                throw new CherryPickInvalidRequestException("Cannot read source range metadata");
            }
            String[] parents = fields[1].isBlank() ? new String[0] : fields[1].strip().split(" ");
            if (parents.length > 1) {
                throw new CherryPickInvalidRequestException(
                        "Source range contains merge commit " + shortSha(fields[0])
                                + "; merge commits are not supported");
            }
            if (parents.length == 0) {
                throw new CherryPickInvalidRequestException(
                        "Source range contains root commit " + shortSha(fields[0]));
            }
            if (++count > MAX_RANGE_COMMITS) {
                throw new CherryPickInvalidRequestException("Source range exceeds " + MAX_RANGE_COMMITS + " commits");
            }
            commits.add(new RangeCommit(
                    fields[0],
                    parents[0],
                    fields[2],
                    fields[3],
                    Instant.parse(fields[4]),
                    paths(fields[5])));
        }
        if (count == 0) {
            throw new CherryPickInvalidRequestException("Source range contains no commits");
        }
        return commits.build();
    }

    private static List<String> paths(String tail)
    {
        ImmutableList.Builder<String> paths = ImmutableList.builder();
        for (String line : tail.split("\n")) {
            if (!line.isBlank()) {
                paths.add(line.strip());
            }
        }
        return paths.build();
    }

    /** Returns the selected range prefix that is already an ancestor of the target. */
    private Set<String> readTargetAncestors(Path repository, String targetSha, List<RangeCommit> range)
    {
        Result result = git.run(repository, "merge-base", targetSha, range.getLast().sha());
        if (result.exitCode() == 1) {
            return ImmutableSet.of();
        }
        if (!result.successful()) {
            throw new CherryPickInvalidRequestException("git merge-base failed: " + result.output().strip());
        }
        String mergeBase = result.output().strip();
        ImmutableSet.Builder<String> ancestors = ImmutableSet.builder();
        for (RangeCommit commit : range) {
            ancestors.add(commit.sha());
            if (commit.sha().equals(mergeBase)) {
                return ancestors.build();
            }
        }
        return ImmutableSet.of();
    }

    /** Maps origin lines found in the newest bounded portion of target history. */
    private Map<String, String> readOriginLines(Path repository, String targetSha, String baseSha)
    {
        String output = requiredRaw(
                repository,
                "log",
                "--max-count=" + MAX_ORIGIN_SCAN_COMMITS,
                "--format=" + RECORD + "%H" + FIELD + "%B",
                targetSha,
                "^" + baseSha);
        Map<String, String> origins = new LinkedHashMap<>();
        for (String record : output.split(RECORD)) {
            if (record.isBlank()) {
                continue;
            }
            String[] fields = record.split(FIELD, 2);
            if (fields.length < 2) {
                continue;
            }
            String targetCommit = fields[0].strip();
            for (String line : fields[1].split("\n")) {
                Matcher matcher = ORIGIN_LINE.matcher(line.strip());
                if (matcher.matches()) {
                    // The first target commit claiming a source commit wins; a
                    // later duplicate is not better evidence.
                    origins.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), targetCommit);
                }
            }
        }
        return ImmutableMap.copyOf(origins);
    }

    /** Advisory only: patch equivalence never causes an automatic skip. */
    private Set<String> readPatchEquivalent(
            Path repository,
            String targetSha,
            String tipSha,
            String baseSha,
            List<String> warnings)
    {
        Result result = git.run(repository, "cherry", targetSha, tipSha, baseSha);
        if (!result.successful()) {
            warnings.add("Patch-equivalence check was unavailable; every commit stays selectable.");
            return ImmutableSet.of();
        }
        ImmutableSet.Builder<String> equivalent = ImmutableSet.builder();
        int count = 0;
        for (String line : result.output().split("\n")) {
            String value = line.strip();
            if (value.startsWith("- ")) {
                equivalent.add(value.substring(2).strip().toLowerCase(Locale.ROOT));
                count++;
            }
        }
        if (count > 0) {
            warnings.add(count + " commit(s) look patch-equivalent to work already on the target. "
                    + "This is advisory: they remain selectable and are not skipped automatically.");
        }
        return equivalent.build();
    }

    private RepoRef readRepository(Path repository, String remote)
    {
        String path = remoteUrl(repository, remote);
        int scheme = path.indexOf("//");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 2);
            path = slash < 0 ? path : path.substring(slash + 1);
        }
        else if (path.contains(":")) {
            path = path.substring(path.lastIndexOf(':') + 1);
        }
        path = path.replaceFirst("^/+", "").replaceFirst("\\.git$", "");
        int separator = path.lastIndexOf('/');
        if (separator <= 0 || separator == path.length() - 1) {
            throw new CherryPickInvalidRequestException("Remote " + remote + " does not name an owner and repository");
        }
        return RepoRef.of(path.substring(0, separator), path.substring(separator + 1));
    }

    private Map<String, String> authenticationEnvironment(
            Path repository,
            Optional<String> workspaceId,
            List<String> remotes)
    {
        if (workspaceId.isEmpty()) {
            return ImmutableMap.of();
        }
        String id = workspaceId.orElseThrow();
        Map<String, String> configured = remoteSources.list(id).stream()
                .collect(ImmutableMap.toImmutableMap(WorkspaceRemote::name, WorkspaceRemote::url));
        List<String> urls = remotes.stream()
                .map(remote -> {
                    String expected = Optional.ofNullable(configured.get(remote))
                            .orElseThrow(() -> new CherryPickInvalidRequestException(
                                    "Remote " + remote + " is not persisted for this workspace"));
                    if (!remoteUrl(repository, remote).equals(expected)) {
                        throw new CherryPickInvalidRequestException(
                                "Remote " + remote + " does not match persisted workspace routing");
                    }
                    return expected;
                })
                .collect(toImmutableList());
        return credentials.gitAuthenticationEnvironment(
                id,
                urls);
    }

    private String remoteUrl(Path repository, String remote)
    {
        Result result = git.run(repository, "config", "--get", "remote." + remote + ".url");
        if (!result.successful()) {
            throw new CherryPickInvalidRequestException("Remote " + remote + " has no configured URL");
        }
        return result.output().strip();
    }

    private boolean isAncestor(Path repository, String ancestor, String descendant)
    {
        return git.run(repository, "merge-base", "--is-ancestor", ancestor, descendant).successful();
    }

    private String resolve(Path repository, String ref)
    {
        return tryResolve(repository, ref)
                .orElseThrow(() -> new CherryPickInvalidRequestException("Cannot resolve ref: " + ref));
    }

    private Optional<String> tryResolve(Path repository, String ref)
    {
        Result result = git.run(repository, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
        if (!result.successful() || result.output().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(result.output().strip());
    }

    private String required(Path repository, String... arguments)
    {
        return requiredRaw(repository, arguments).strip();
    }

    /**
     * Runs a command whose output is record-separated.
     *
     * <p>Java counts the ASCII separators as whitespace, so stripping the output
     * would silently delete a trailing empty field and corrupt the last record.
     */
    private String requiredRaw(Path repository, String... arguments)
    {
        Result result = git.run(repository, arguments);
        if (!result.successful()) {
            throw new CherryPickInvalidRequestException("git " + arguments[0] + " failed: " + result.output().strip());
        }
        if (result.truncated()) {
            throw new CherryPickInvalidRequestException("git " + arguments[0] + " produced too much output");
        }
        return result.output();
    }

    private static String shortSha(String sha)
    {
        return sha.length() <= 12 ? sha : sha.substring(0, 12);
    }

    private record RangeCommit(
            String sha,
            String parentSha,
            String title,
            String author,
            Instant committedAt,
            List<String> changedPaths)
    {
        private RangeCommit
        {
            requireNonNull(sha, "sha is null");
            requireNonNull(parentSha, "parentSha is null");
            requireNonNull(title, "title is null");
            requireNonNull(author, "author is null");
            requireNonNull(committedAt, "committedAt is null");
            changedPaths = ImmutableList.copyOf(requireNonNull(changedPaths, "changedPaths is null"));
        }
    }
}
