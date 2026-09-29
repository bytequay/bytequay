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
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** A locally persisted diff snapshot for a pull request or commit range. */
public record StoredPrDiff(
        List<DiffFile> files,
        List<DiffCommit> commits,
        Map<String, DiffFileContent> fileContents,
        Optional<String> baseSha,
        Optional<String> headSha,
        Optional<Instant> validatedAt)
{
    public StoredPrDiff
    {
        requireNonNull(baseSha, "baseSha is null");
        requireNonNull(headSha, "headSha is null");
        requireNonNull(validatedAt, "validatedAt is null");
        files = ImmutableList.copyOf(requireNonNull(files, "files is null"));
        commits = ImmutableList.copyOf(requireNonNull(commits, "commits is null"));
        fileContents = ImmutableMap.copyOf(requireNonNull(fileContents, "fileContents is null"));
    }

    public StoredPrDiff(List<DiffFile> files, List<DiffCommit> commits, Map<String, DiffFileContent> fileContents)
    {
        this(files, commits, fileContents, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public StoredPrDiff(List<DiffFile> files, List<DiffCommit> commits)
    {
        this(files, commits, ImmutableMap.of());
    }
}
