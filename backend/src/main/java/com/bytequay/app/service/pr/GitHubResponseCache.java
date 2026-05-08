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

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.SuggestedReviewer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Small response caches for GitHub reads whose freshness contracts differ
 * from the durable SQLite PR-detail snapshot.
 */
@Component
public class GitHubResponseCache
{
    private static final int PAT_FINGERPRINT_HEX_LENGTH = 16;
    private static final long FILE_BLOB_MAX_WEIGHT = 50L * 1024 * 1024;
    private static final long COMMIT_DIFF_MAX_WEIGHT = 25L * 1024 * 1024;
    private static final int ELEMENT_OVERHEAD_BYTES = 16;

    private final Cache<ViewerCanWriteKey, Boolean> viewerCanWrite;
    private final Cache<FileBlobKey, ImmutableList<String>> fileBlobLines;
    private final Cache<CommitDiffKey, ImmutableList<DiffFile>> commitDiffFiles;
    private final Cache<SuggestedReviewersKey, ImmutableList<SuggestedReviewer>> suggestedReviewers;

    public GitHubResponseCache()
    {
        this(
                CacheBuilder.newBuilder()
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .maximumSize(1_000)
                        .build(),
                CacheBuilder.newBuilder()
                        .expireAfterWrite(Duration.ofHours(6))
                        .maximumWeight(FILE_BLOB_MAX_WEIGHT)
                        .<FileBlobKey, ImmutableList<String>>weigher((key, value) -> weighLines(value))
                        .build(),
                CacheBuilder.newBuilder()
                        .expireAfterWrite(Duration.ofHours(1))
                        .maximumWeight(COMMIT_DIFF_MAX_WEIGHT)
                        .<CommitDiffKey, ImmutableList<DiffFile>>weigher((key, value) -> weighDiffFiles(value))
                        .build(),
                CacheBuilder.newBuilder()
                        .expireAfterWrite(Duration.ofMinutes(2))
                        .maximumSize(1_000)
                        .build());
    }

    GitHubResponseCache(
            Cache<ViewerCanWriteKey, Boolean> viewerCanWrite,
            Cache<FileBlobKey, ImmutableList<String>> fileBlobLines,
            Cache<CommitDiffKey, ImmutableList<DiffFile>> commitDiffFiles,
            Cache<SuggestedReviewersKey, ImmutableList<SuggestedReviewer>> suggestedReviewers)
    {
        this.viewerCanWrite = requireNonNull(viewerCanWrite, "viewerCanWrite is null");
        this.fileBlobLines = requireNonNull(fileBlobLines, "fileBlobLines is null");
        this.commitDiffFiles = requireNonNull(commitDiffFiles, "commitDiffFiles is null");
        this.suggestedReviewers = requireNonNull(suggestedReviewers, "suggestedReviewers is null");
    }

    /**
     * Returns whether the PAT can write to the repository, cached briefly by
     * PAT fingerprint and repository.
     */
    public boolean getViewerCanWrite(String pat, RepoRef repo, Supplier<Boolean> loader)
    {
        requireNonNull(repo, "repo is null");
        requireNonNull(loader, "loader is null");
        return get(
                viewerCanWrite,
                new ViewerCanWriteKey(fingerprint(pat), repo.fullName()),
                () -> requireNonNull(loader.get(), "viewerCanWrite loader returned null"));
    }

    /**
     * Returns decoded file contents at a commit SHA, cached by PAT
     * fingerprint, repository, path, and SHA.
     */
    public List<String> getFileBlobLines(String pat, RepoRef repo, String path, String sha, Supplier<List<String>> loader)
    {
        requireNonNull(repo, "repo is null");
        requireNonNull(path, "path is null");
        requireNonNull(sha, "sha is null");
        requireNonNull(loader, "loader is null");
        return get(
                fileBlobLines,
                new FileBlobKey(fingerprint(pat), repo.fullName(), path, sha),
                () -> ImmutableList.copyOf(requireNonNull(loader.get(), "fileBlobLines loader returned null")));
    }

    /**
     * Returns the diff for one PR commit, cached by PAT fingerprint, pull
     * request, and commit SHA.
     */
    public List<DiffFile> getCommitDiffFiles(String pat, PullRequestRef ref, String sha, Supplier<List<DiffFile>> loader)
    {
        requireNonNull(ref, "ref is null");
        requireNonNull(sha, "sha is null");
        requireNonNull(loader, "loader is null");
        return get(
                commitDiffFiles,
                new CommitDiffKey(fingerprint(pat), ref.repoRef().fullName(), ref.number(), sha),
                () -> ImmutableList.copyOf(requireNonNull(loader.get(), "commitDiffFiles loader returned null")));
    }

    /**
     * Returns GitHub's suggested reviewers for a PR, cached briefly by PAT
     * fingerprint and pull request.
     */
    public List<SuggestedReviewer> getSuggestedReviewers(String pat, PullRequestRef ref, Supplier<List<SuggestedReviewer>> loader)
    {
        requireNonNull(ref, "ref is null");
        requireNonNull(loader, "loader is null");
        return get(
                suggestedReviewers,
                new SuggestedReviewersKey(fingerprint(pat), ref.repoRef().fullName(), ref.number()),
                () -> ImmutableList.copyOf(requireNonNull(loader.get(), "suggestedReviewers loader returned null")));
    }

    /**
     * Invalidates PR-scoped response caches for one pull request.
     */
    public void invalidatePullRequest(PullRequestRef ref)
    {
        requireNonNull(ref, "ref is null");
        String repo = ref.repoRef().fullName();
        int number = ref.number();
        commitDiffFiles.asMap().keySet().removeIf(key -> key.repoFullName().equals(repo) && key.number() == number);
        suggestedReviewers.asMap().keySet().removeIf(key -> key.repoFullName().equals(repo) && key.number() == number);
    }

    private static <K, V> V get(Cache<K, V> cache, K key, Supplier<V> loader)
    {
        try {
            return cache.get(key, loader::get);
        }
        catch (UncheckedExecutionException e) {
            throw unwrap(e.getCause());
        }
        catch (ExecutionException e) {
            throw unwrap(e.getCause());
        }
    }

    private static RuntimeException unwrap(Throwable cause)
    {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause);
    }

    private static String fingerprint(String pat)
    {
        requireNonNull(pat, "pat is null");
        // SHA-256 is cheap for current call volumes. If profiling ever shows
        // this on a hot path, add a tiny last-PAT memoization here.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(pat.getBytes(StandardCharsets.UTF_8));
            return toHex(digest).substring(0, PAT_FINGERPRINT_HEX_LENGTH);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static String toHex(byte[] bytes)
    {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >>> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static int weighLines(List<String> lines)
    {
        long weight = 0;
        for (String line : lines) {
            weight += line != null ? line.length() : 0;
            weight += ELEMENT_OVERHEAD_BYTES;
            if (weight > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) weight;
    }

    private static int weighDiffFiles(List<DiffFile> files)
    {
        long weight = 0;
        for (DiffFile file : files) {
            weight += ELEMENT_OVERHEAD_BYTES;
            weight += weighString(file.filename());
            weight += weighString(file.status());
            weight += weighString(file.patch());
            if (weight > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) weight;
    }

    private static int weighString(String value)
    {
        return value != null ? value.length() : 0;
    }

    private record ViewerCanWriteKey(String patFingerprint, String repoFullName) {}

    private record FileBlobKey(String patFingerprint, String repoFullName, String path, String sha) {}

    private record CommitDiffKey(String patFingerprint, String repoFullName, int number, String sha) {}

    private record SuggestedReviewersKey(String patFingerprint, String repoFullName, int number) {}
}
