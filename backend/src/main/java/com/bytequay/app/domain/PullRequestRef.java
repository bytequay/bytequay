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
package com.bytequay.app.domain;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Identifies a specific pull request within a GitHub repository.
 * Bundles owner, repo name, and PR number so PR-scoped repository methods
 * only need one parameter instead of three.
 *
 * <p>Example: {@code PullRequestRef.of("apache", "trino", 24601)}
 *
 * @param owner  repository owner login, e.g. {@code "apache"}
 * @param repo   repository name, e.g. {@code "trino"}
 * @param number pull request number (1-based)
 */
public record PullRequestRef(String owner, String repo, int number)
{
    public PullRequestRef
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(repo, "repo is null");
        if (number <= 0) {
            throw new IllegalArgumentException("number must be positive, got: " + number);
        }
    }

    public static PullRequestRef of(String owner, String repo, int number)
    {
        return new PullRequestRef(owner, repo, number);
    }

    /**
     * Parses the canonical {@code "owner/repo#number"} string produced by
     * {@link #fullName()} — the inverse of {@code fullName()}. Returns empty
     * for null or any malformed ref: a missing or edge-positioned {@code '#'},
     * no {@code '/'} before it, or a non-positive number.
     */
    public static Optional<PullRequestRef> parse(String ref)
    {
        if (ref == null) {
            return Optional.empty();
        }
        int hash = ref.lastIndexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) {
            return Optional.empty();
        }
        String repoFull = ref.substring(0, hash);
        int slash = repoFull.indexOf('/');
        if (slash <= 0 || slash == repoFull.length() - 1) {
            return Optional.empty();
        }
        int number;
        try {
            number = Integer.parseInt(ref.substring(hash + 1).trim());
        }
        catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (number <= 0) {
            return Optional.empty();
        }
        return Optional.of(new PullRequestRef(
                repoFull.substring(0, slash), repoFull.substring(slash + 1), number));
    }

    /** Returns the {@link RepoRef} for this pull request's repository. */
    public RepoRef repoRef()
    {
        return new RepoRef(owner, repo);
    }

    /** Returns the canonical {@code "owner/repo#number"} string. */
    public String fullName()
    {
        return owner + "/" + repo + "#" + number;
    }
}
