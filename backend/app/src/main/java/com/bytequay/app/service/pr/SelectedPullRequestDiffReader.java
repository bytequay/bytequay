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
package com.bytequay.app.service.pr;

import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Reads selected default-diff artifacts; a content version identifies contents, not a verified Git range. */
@Service
public class SelectedPullRequestDiffReader
{
    private static final Logger log = LoggerFactory.getLogger(SelectedPullRequestDiffReader.class);

    private final PullRequestService pullRequests;
    private final PrDiffStore diffs;
    private final PrDetailStore details;
    private final SelectedPullRequestReader selected;
    private final PullRequestRefreshService refresh;
    private final Clock clock;

    public SelectedPullRequestDiffReader(
            PullRequestService pullRequests, PrDiffStore diffs, PrDetailStore details,
            SelectedPullRequestReader selected, PullRequestRefreshService refresh, Clock clock)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.diffs = requireNonNull(diffs, "diffs is null");
        this.details = requireNonNull(details, "details is null");
        this.selected = requireNonNull(selected, "selected is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Reuses the stored default diff without an age limit. A missing row hydrates only that PR, then
     * rereads its cache before deciding whether a diff refresh is still needed. A missing diff or
     * explicitly forced initial read uses the existing default-diff refresh. On failure its original
     * cached bytes and observed metadata are retained, including when refresh partially saved data.
     *
     * @param repo owner/repository, normalized to lowercase
     * @param number positive PR number in that repository
     * @param expectedVersion backend-issued lowercase SHA-256 content version for a continuation
     * @param forceRefresh whether an initial read must attempt a selected diff refresh
     * @return the ordered cached files and their content identity, with explicit source limitations
     * @throws IllegalArgumentException for invalid versions, conflicting options or mismatched stored identities
     * @throws ResponseStatusException with 409 when continuation bytes are missing or changed; restart the read
     *
     * Continuations read only the bound local PR row and default diff, never metadata or a provider.
     * Their metadata optionals are empty; callers retain the initial observation in their evidence.
     */
    public Read read(String repo, int number, Optional<String> expectedVersion, boolean forceRefresh)
    {
        requireNonNull(expectedVersion, "expectedVersion is null");
        expectedVersion.ifPresent(value -> checkArgument(value.matches("[0-9a-f]{64}"), "Expected diff version must be lowercase SHA-256"));
        checkArgument(expectedVersion.isEmpty() || !forceRefresh, "A diff continuation cannot force refresh");
        String repository = parseRef(repo, number).repoFullName().toLowerCase(Locale.ROOT);
        if (expectedVersion.isPresent()) {
            Cached cached = row(repository, number).flatMap(value -> cached(value, false)).orElseThrow(SelectedPullRequestDiffReader::restart);
            Read read = result(repository, number, cached, false, false);
            if (!read.version().equals(expectedVersion.orElseThrow())) {
                throw restart();
            }
            return read;
        }

        Optional<PullRequest> row = row(repository, number);
        boolean attempted = false;
        boolean failed = false;
        if (row.isEmpty()) {
            SelectedPullRequestReader.Read hydrated = selected.read(repository, number, SelectedPullRequestReader.Section.PR);
            attempted = hydrated.refreshAttempted();
            failed = hydrated.refreshFailed();
            row = row(repository, number);
        }
        Optional<Cached> original = row.flatMap(value -> cached(value, true));
        if (original.isPresent() && !forceRefresh) {
            return result(repository, number, original.orElseThrow(), attempted, failed);
        }
        try {
            refresh.refreshDiffFiles(repository, number, null, null);
            Cached updated = row(repository, number).flatMap(value -> cached(value, true))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Selected pull request diff has not been refreshed"));
            return result(repository, number, updated, true, false);
        }
        catch (RuntimeException failure) {
            log.info("Selected PR {}#{} diff refresh failed; cached fallback={}: {}", repository, number, original.isPresent(), failure.getClass().getSimpleName());
            if (original.isEmpty()) {
                throw failure;
            }
            return result(repository, number, original.orElseThrow(), true, true);
        }
    }

    private Optional<PullRequest> row(String repo, int number)
    {
        Optional<PullRequest> row = pullRequests.findStored(repo, number);
        row.ifPresent(value -> checkArgument(value.repo().equalsIgnoreCase(repo) && value.number() == number, "Stored PR identity does not match selected request"));
        return row;
    }

    private Optional<Cached> cached(PullRequest row, boolean includeMetadata)
    {
        return diffs.find(row.id(), null, null).map(diff -> {
            Set<String> paths = new HashSet<>();
            diff.files().forEach(file -> checkArgument(paths.add(file.filename()), "Selected diff contains duplicate file paths"));
            Optional<PrRawDetail> raw = includeMetadata ? details.find(row.id()).map(StoredPrDetail::raw) : Optional.empty();
            return new Cached(diff, raw);
        });
    }

    private Read result(String repo, int number, Cached cached, boolean attempted, boolean failed)
    {
        List<DiffFile> files = cached.diff().files();
        Optional<Integer> reported = cached.raw().map(PrRawDetail::changedFiles).filter(value -> value >= 0);
        Optional<String> head = cached.raw().map(PrRawDetail::headSha).filter(value -> value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"));
        ImmutableList.Builder<String> gaps = ImmutableList.builder();
        gaps.add("The content version identifies this cached default-diff artifact; it does not verify freshness, commit linkage or the final merged diff.");
        gaps.add("The existing diff cache does not record complete upstream coverage or retrieval time; an empty list does not prove no changes.");
        if (head.isEmpty()) {
            gaps.add("No valid separately observed PR head is available for this read; the diff has no verified commit coordinates.");
        }
        long missingPatches = files.stream().filter(file -> file.patch() == null).count();
        if (missingPatches > 0) {
            gaps.add(missingPatches + " file(s) have no patch text; binary, rename-only and unavailable patches cannot be distinguished from this cache.");
        }
        if (reported.isPresent() && reported.orElseThrow() != files.size()) {
            gaps.add("Separately observed PR metadata reports " + reported.orElseThrow() + " changed file(s), but this cached artifact contains " + files.size() + ".");
        }
        if (failed) {
            gaps.add("The selected refresh or its persisted reread failed; original cached bytes and metadata were retained.");
        }
        return new Read(files, version(repo, number, files), head,
                cached.raw().map(PrRawDetail::updatedAt), reported, Instant.now(clock), attempted, failed, gaps.build());
    }

    /** Ordered strings use little-endian 32-bit UTF-16 lengths and exact code units; a presence byte distinguishes null patches. */
    private static String version(String repo, int number, List<DiffFile> files)
    {
        // ponytail: hash the artifact on each page; persist a digest only if profiling shows this cost matters.
        Hasher hash = Hashing.sha256().newHasher();
        frame(hash, "bytequay-default-diff-v1");
        frame(hash, repo);
        hash.putInt(number).putInt(files.size());
        for (DiffFile file : files) {
            frame(hash, file.filename());
            frame(hash, file.status());
            hash.putInt(file.additions()).putInt(file.deletions()).putBoolean(file.patch() != null);
            if (file.patch() != null) {
                frame(hash, file.patch());
            }
        }
        return hash.hash().toString();
    }

    private static void frame(Hasher hash, String value)
    {
        hash.putInt(value.length()).putUnencodedChars(value);
    }

    private static ResponseStatusException restart()
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Selected diff content changed or is no longer cached; restart the diff read");
    }

    /**
     * Exact observed artifact contents with separately read metadata, not a verified association to that head.
     * {@code sourceUpdatedAt} is the PR detail update, not a diff retrieval time. The version is a content
     * hash scoped to repository and PR, and conveys no completeness or final-merge guarantee.
     */
    public record Read(
            List<DiffFile> files,
            String version,
            Optional<String> observedHeadSha,
            Optional<Instant> sourceUpdatedAt,
            Optional<Integer> reportedChangedFiles,
            Instant readAt,
            boolean refreshAttempted,
            boolean refreshFailed,
            List<String> gaps)
    {
        public Read
        {
            files = ImmutableList.copyOf(files);
            requireNonNull(version, "version is null");
            checkArgument(version.matches("[0-9a-f]{64}"), "Diff version must be lowercase SHA-256");
            requireNonNull(observedHeadSha, "observedHeadSha is null");
            observedHeadSha.ifPresent(value -> checkArgument(value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"), "Observed head must be a full SHA"));
            requireNonNull(sourceUpdatedAt, "sourceUpdatedAt is null");
            requireNonNull(reportedChangedFiles, "reportedChangedFiles is null");
            requireNonNull(readAt, "readAt is null");
            checkArgument(!refreshFailed || refreshAttempted, "A failed refresh must have been attempted");
            gaps = ImmutableList.copyOf(gaps);
        }
    }

    private record Cached(StoredPrDiff diff, Optional<PrRawDetail> raw) {}
}
