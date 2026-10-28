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
package com.bytequay.app.repository.github;

import com.bytequay.core.domain.GitHubPullRequestIdentity;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Fresh check evidence for one exact PR revision, separate from the Actions repair result. */
public record GitHubDeliveryChecks(String nodeId, GitHubPullRequestIdentity pullRequest, List<String> waiting, List<String> failures)
{
    public GitHubDeliveryChecks
    {
        checkText(nodeId, "nodeId", 255);
        requireNonNull(pullRequest, "pullRequest is null");
        waiting = ImmutableList.copyOf(waiting);
        failures = ImmutableList.copyOf(failures);
    }

    public boolean green()
    {
        return waiting.isEmpty() && failures.isEmpty();
    }
}
