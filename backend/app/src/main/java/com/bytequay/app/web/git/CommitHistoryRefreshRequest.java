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
package com.bytequay.app.web.git;

import com.bytequay.app.git.CommitHistoryRef;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Renderer-safe refresh input: source and branch selectors only. */
public record CommitHistoryRefreshRequest(List<CommitHistoryRefreshRefRequest> refs)
{
    public CommitHistoryRefreshRequest
    {
        refs = ImmutableList.copyOf(requireNonNull(refs, "refs is null"));
        checkArgument(!refs.isEmpty(), "refs is empty");
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object ignored)
    {
        throw new IllegalArgumentException("unsupported refresh field: " + name);
    }

    public List<CommitHistoryRef> historyRefs()
    {
        return refs.stream().map(CommitHistoryRefreshRefRequest::toDomain).toList();
    }
}
