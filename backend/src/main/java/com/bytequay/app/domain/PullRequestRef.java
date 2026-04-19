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
