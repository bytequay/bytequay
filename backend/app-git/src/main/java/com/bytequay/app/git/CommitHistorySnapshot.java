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
package com.bytequay.app.git;

import com.bytequay.core.domain.CommitRefreshStatus;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Named local response containing commit cards and durable refresh state. */
public record CommitHistorySnapshot(List<CommitHistoryItem> commits, CommitRefreshStatus refresh)
{
    public CommitHistorySnapshot
    {
        commits = ImmutableList.copyOf(requireNonNull(commits, "commits is null"));
        requireNonNull(refresh, "refresh is null");
    }
}
