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
package com.bytequay.app.utils;

import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public final class PullRequestRefUtil
{
    private PullRequestRefUtil() {}

    public static PullRequestRef parseRef(String repo, int number)
    {
        String[] parts = repo.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "repo must be owner/name");
        }
        return PullRequestRef.of(parts[0], parts[1], number);
    }

    /**
     * Splits {@code owner/repo} into a {@link RepoRef}.
     * Use this for endpoints that target a repo but not a specific PR
     * (e.g. the reactions endpoints, which take {@code commentId} from
     * the path and ignore PR number entirely). {@link #parseRef} would
     * trip over its number-must-be-positive invariant for that case.
     */
    public static RepoRef parseRepoRef(String repo)
    {
        String[] parts = repo.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "repo must be owner/name");
        }
        return RepoRef.of(parts[0], parts[1]);
    }
}
