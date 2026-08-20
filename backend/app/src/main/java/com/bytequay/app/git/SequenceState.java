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

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What Git's sequencer is actually doing, read from the repository.
 *
 * <p>This reports facts and decides nothing. The service that owns a plan maps
 * these to its own results, which is what keeps one component in charge of
 * saying what happened.
 */
public record SequenceState(
        Kind kind,
        String head,
        Optional<String> stoppedCommit,
        List<ConflictedPath> conflicts,
        String diagnostics)
{
    private static final int MAX_DIAGNOSTIC_CHARACTERS = 8 * 1024;
    private static final int MAX_PATHS = 200;

    public SequenceState
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(head, "head is null");
        requireNonNull(stoppedCommit, "stoppedCommit is null");
        conflicts = ImmutableList.copyOf(requireNonNull(conflicts, "conflicts is null"));
        requireNonNull(diagnostics, "diagnostics is null");
        checkArgument(conflicts.size() <= MAX_PATHS, "conflicts exceeds " + MAX_PATHS + " values");
        checkArgument(
                diagnostics.length() <= MAX_DIAGNOSTIC_CHARACTERS,
                "diagnostics exceeds " + MAX_DIAGNOSTIC_CHARACTERS + " characters");
        checkArgument(
                kind == Kind.COMPLETED || kind == Kind.UNSAFE || stoppedCommit.isPresent(),
                "a stopped sequence must name the commit it stopped on");
    }

    public enum Kind
    {
        /** No operation open, index and worktree clean. */
        COMPLETED,
        /** Stopped with unmerged paths. */
        STOPPED_CONFLICT,
        /** Stopped, conflicts staged, ready to continue. */
        STOPPED_RESOLVED,
        /** Stopped with nothing to apply. */
        STOPPED_EMPTY,
        /** Anything else. Never guessed at. */
        UNSAFE
    }

    /** One unmerged path and the category its index stages imply. */
    public record ConflictedPath(String path, Type type, List<IndexStage> stages)
    {
        public ConflictedPath
        {
            requireNonNull(path, "path is null");
            requireNonNull(type, "type is null");
            stages = ImmutableList.copyOf(requireNonNull(stages, "stages is null"));
        }

        public ConflictedPath(String path, Type type)
        {
            this(path, type, ImmutableList.of());
        }

        /** One immutable side recorded by {@code git ls-files -u}. */
        public record IndexStage(int stage, String mode, String blobSha)
        {
            public IndexStage
            {
                checkArgument(stage >= 1 && stage <= 3, "stage is outside 1..3");
                requireNonNull(mode, "mode is null");
                requireNonNull(blobSha, "blobSha is null");
                checkArgument(mode.matches("[0-7]{6}"), "mode is invalid");
                checkArgument(blobSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "blobSha is invalid");
            }
        }

        public enum Type
        {
            CONTENT,
            ADD_ADD,
            MODIFY_DELETE,
            SUBMODULE
        }
    }
}
