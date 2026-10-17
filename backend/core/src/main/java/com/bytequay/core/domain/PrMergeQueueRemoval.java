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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** GitHub's removal reason and checks on the merge-group commit, not the PR head. */
public record PrMergeQueueRemoval(Optional<String> reason, Optional<String> commitUrl, List<FailedCheck> failedChecks)
{
    public PrMergeQueueRemoval
    {
        requireNonNull(reason, "reason is null");
        requireNonNull(commitUrl, "commitUrl is null");
        failedChecks = ImmutableList.copyOf(failedChecks);
    }

    public record FailedCheck(String name, Optional<String> url)
    {
        public FailedCheck
        {
            requireNonNull(name, "name is null");
            requireNonNull(url, "url is null");
        }
    }
}
