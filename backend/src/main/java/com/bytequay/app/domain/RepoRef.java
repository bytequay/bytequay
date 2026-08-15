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
 * Identifies a GitHub repository by owner login and repository name.
 * Carried as a single parameter to repository methods instead of passing
 * {@code owner} and {@code repo} separately.
 *
 * <p>Example: {@code RepoRef.of("apache", "trino")}
 *
 * @param owner repository owner login (user or organisation), e.g. {@code "apache"}
 * @param repo  repository name, e.g. {@code "trino"}
 */
public record RepoRef(String owner, String repo)
{
    public RepoRef
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(repo, "repo is null");
    }

    public static RepoRef of(String owner, String repo)
    {
        return new RepoRef(owner, repo);
    }

    /** Returns the canonical {@code "owner/repo"} string used in GitHub API paths and UI. */
    public String fullName()
    {
        return owner + "/" + repo;
    }
}
