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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextSelectedEntry.Applicability;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * How a note's cited repository lines compare with one commit. Unchanged lines establish identity, never a claim.
 *
 * @param commit compared commit; empty when the reader had none
 * @param sources one result per note source, in the note's source order
 * @param gaps why sources could not be checked
 */
public record ContextSourceCheck(Optional<String> commit, List<Source> sources, List<String> gaps)
{
    public ContextSourceCheck
    {
        requireNonNull(commit, "commit is null");
        sources = ImmutableList.copyOf(sources);
        gaps = ImmutableList.copyOf(gaps);
    }

    /** Any changed or absent source is a mismatch; a match needs every source unchanged. */
    public Applicability applicability()
    {
        if (sources.stream().anyMatch(Source::stale)) {
            return Applicability.VERSION_MISMATCH;
        }
        return !sources.isEmpty() && sources.stream().allMatch(source -> source.status() == Status.UNCHANGED) ? Applicability.MATCHED : Applicability.UNVERIFIED;
    }

    /**
     * @param ref the source reference used in the note text
     * @param kind source reference kind
     * @param path repository path of a file source
     * @param lines cited line range, such as {@code 12-18}
     * @param currentLines where the cited lines are now, when they moved and occur once
     * @param recordedCommit commit the file was read at
     */
    public record Source(String ref, String kind, Optional<String> path, Optional<String> lines, Optional<String> currentLines,
            Optional<String> recordedCommit, Status status)
    {
        public Source
        {
            requireNonNull(ref, "ref is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(path, "path is null");
            requireNonNull(lines, "lines is null");
            requireNonNull(currentLines, "currentLines is null");
            requireNonNull(recordedCommit, "recordedCommit is null");
            requireNonNull(status, "status is null");
        }

        public boolean stale()
        {
            return status == Status.CHANGED || status == Status.ABSENT;
        }
    }

    public enum Status
    {
        UNCHANGED,
        CHANGED,
        ABSENT,
        UNCHECKED
    }
}
