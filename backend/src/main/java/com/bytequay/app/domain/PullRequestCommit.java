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

import java.time.Instant;

/**
 * A commit that is part of a pull request.
 * Corresponds to a commit object from GET /repos/{owner}/{repo}/pulls/{pull_number}/commits.
 *
 * @param sha          full commit SHA
 * @param authorLogin  GitHub login of the author, or {@code null} if the commit was made
 *                     outside GitHub (e.g. pushed with an unmatched email)
 * @param authorName   display name of the author, e.g. "Alice Example"
 * @param authoredAt   when the commit was authored (not committed), in UTC
 * @param message      full commit message (subject + body)
 */
public record PullRequestCommit(
        String sha,
        String authorLogin,
        String authorName,
        Instant authoredAt,
        String message) {}
