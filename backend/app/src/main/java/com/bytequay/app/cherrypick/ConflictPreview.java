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

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What a simulated cherry-pick of one commit would hit.
 *
 * <p>{@link Basis} is the honest label for how much the result can be trusted.
 * Simulation is exact only while every earlier planned commit applied cleanly;
 * once one conflicts, the user's resolution changes everything after it.
 */
public record ConflictPreview(Status status, Basis basis, List<ConflictFile> files)
{
    private static final int MAX_FILES = 100;

    public ConflictPreview
    {
        requireNonNull(status, "status is null");
        requireNonNull(basis, "basis is null");
        files = ImmutableList.copyOf(requireNonNull(files, "files is null"));
        checkArgument(files.size() <= MAX_FILES, "files exceeds " + MAX_FILES + " values");
        checkArgument(status == Status.CONFLICT || files.isEmpty(), "only a conflict carries files");
    }

    public static ConflictPreview clean()
    {
        return new ConflictPreview(Status.CLEAN, Basis.AFTER_CLEAN_PREFIX, ImmutableList.of());
    }

    public static ConflictPreview conflict(List<ConflictFile> files)
    {
        return new ConflictPreview(Status.CONFLICT, Basis.AFTER_CLEAN_PREFIX, files);
    }

    /** Everything after the first unresolved conflict. */
    public static ConflictPreview notAnalyzed()
    {
        return new ConflictPreview(Status.NOT_ANALYZED, Basis.ESTIMATE, ImmutableList.of());
    }

    public enum Status
    {
        CLEAN,
        CONFLICT,
        NOT_ANALYZED
    }

    public enum Basis
    {
        /** Simulated after every earlier planned commit applied cleanly. */
        AFTER_CLEAN_PREFIX,
        /** Simulated directly against the frozen target. */
        AT_FROZEN_TARGET,
        /** Scope information only; execution may encounter something else. */
        ESTIMATE
    }

    /**
     * One conflicted path.
     *
     * @param module nearest ancestor Maven module, absent for repository-root files
     * @param sections textual conflict ranges, empty when Git produced no usable hunks
     */
    public record ConflictFile(String path, Type type, Optional<String> module, List<Section> sections)
    {
        private static final int MAX_SECTIONS = 50;

        public ConflictFile
        {
            requireNonNull(path, "path is null");
            requireNonNull(type, "type is null");
            requireNonNull(module, "module is null");
            sections = ImmutableList.copyOf(requireNonNull(sections, "sections is null"));
            checkArgument(sections.size() <= MAX_SECTIONS, "sections exceeds " + MAX_SECTIONS + " values");
        }
    }

    /** Native Git conflict categories, read from index stages rather than guessed. */
    public enum Type
    {
        CONTENT,
        ADD_ADD,
        MODIFY_DELETE,
        BINARY,
        SUBMODULE,
        UNKNOWN
    }

    /** One-based inclusive line range of a conflict block. */
    public record Section(int startLine, int endLine)
    {
        public Section
        {
            checkArgument(startLine >= 1, "startLine is not one-based");
            checkArgument(endLine >= startLine, "endLine precedes startLine");
        }
    }
}
