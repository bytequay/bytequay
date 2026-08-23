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

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One validated commit-history selector. {@code WORKSPACE} reads a local
 * branch and {@code UPSTREAM} the linked upstream remote; {@code REMOTE}
 * names one of the workspace's configured extra remote sources.
 */
public record CommitHistoryRef(Source source, String branch, Optional<String> remote)
{
    public CommitHistoryRef
    {
        requireNonNull(source, "source is null");
        requireNonNull(branch, "branch is null");
        checkArgument(!branch.isBlank(), "branch is blank");
        checkArgument(!branch.startsWith("-"), "branch must not be a Git option");
        checkArgument(branch.length() <= 512, "branch is too long");
        requireNonNull(remote, "remote is null");
        checkArgument((source == Source.REMOTE) == remote.isPresent(),
                "remote is required for REMOTE and forbidden otherwise");
        remote.ifPresent(name -> {
            checkArgument(!name.isBlank(), "remote is blank");
            checkArgument(!name.startsWith("-"), "remote must not be a Git option");
            checkArgument(name.length() <= 128, "remote is too long");
        });
    }

    public CommitHistoryRef(Source source, String branch)
    {
        this(source, branch, Optional.empty());
    }

    public enum Source
    {
        WORKSPACE,
        UPSTREAM,
        REMOTE
    }
}
