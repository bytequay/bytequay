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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Named commit-to-PR lookup result that distinguishes checked negatives from failures. */
public record PullRequestLookupResult(
        List<Association> associations,
        Set<String> failedShas)
{
    public PullRequestLookupResult
    {
        associations = ImmutableList.copyOf(requireNonNull(associations, "associations is null"));
        failedShas = ImmutableSet.copyOf(requireNonNull(failedShas, "failedShas is null"));
    }

    public record Association(String sha, PullRequestAssociation pullRequest)
    {
        public Association
        {
            requireNonNull(sha, "sha is null");
            requireNonNull(pullRequest, "pullRequest is null");
        }
    }
}
