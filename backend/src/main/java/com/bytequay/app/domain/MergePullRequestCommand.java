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

/**
 * Parameters for merging a pull request.
 * Maps to the request body of PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge.
 *
 * @param mergeMethod    merge strategy: "merge" (default), "squash", or "rebase"
 * @param commitTitle    title for the merge commit; empty means GitHub generates one
 * @param commitMessage  extra detail in the merge commit message; empty means GitHub generates one
 * @param sha            expected HEAD SHA for safety — GitHub rejects the merge if it doesn't match; empty skips the check
 */
public record MergePullRequestCommand(
        String mergeMethod,
        Optional<String> commitTitle,
        Optional<String> commitMessage,
        Optional<String> sha)
{
    public static MergePullRequestCommand mergeCommit()
    {
        return new MergePullRequestCommand("merge", Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static MergePullRequestCommand squash()
    {
        return new MergePullRequestCommand("squash", Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static MergePullRequestCommand rebase()
    {
        return new MergePullRequestCommand("rebase", Optional.empty(), Optional.empty(), Optional.empty());
    }
}
