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
import com.bytequay.app.git.CommitHistoryRef.Source;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Strict renderer input for one source-and-branch selector. */
public final class CommitHistoryRefreshRefRequest
{
    private final Source source;
    private final String branch;
    private final Optional<String> remote;

    @JsonCreator
    public CommitHistoryRefreshRefRequest(
            @JsonProperty("source") Source source,
            @JsonProperty("branch") String branch,
            @JsonProperty("remote") String remote)
    {
        this.source = requireNonNull(source, "source is null");
        this.branch = requireNonNull(branch, "branch is null");
        this.remote = Optional.ofNullable(remote);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object ignored)
    {
        throw new IllegalArgumentException("unsupported refresh ref field: " + name);
    }

    public CommitHistoryRef toDomain()
    {
        return new CommitHistoryRef(source, branch, remote);
    }
}
