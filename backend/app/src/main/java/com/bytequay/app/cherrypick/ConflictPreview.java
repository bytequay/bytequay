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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What a simulated cherry-pick of one commit would hit.
 *
 * <p>Simulation is exact only while every earlier planned commit applied
 * cleanly; once one conflicts, the user's resolution changes everything after it.
 *
 * @param paths conflicted paths, only for a conflict
 */
public record ConflictPreview(Status status, List<String> paths)
{
    public ConflictPreview
    {
        requireNonNull(status, "status is null");
        paths = ImmutableList.copyOf(paths);
        checkArgument(status == Status.CONFLICT || paths.isEmpty(), "only a conflict carries paths");
    }

    public static ConflictPreview clean()
    {
        return new ConflictPreview(Status.CLEAN, ImmutableList.of());
    }

    public static ConflictPreview conflict(List<String> paths)
    {
        return new ConflictPreview(Status.CONFLICT, paths);
    }

    /** Everything after the first unresolved conflict. */
    public static ConflictPreview notAnalyzed()
    {
        return new ConflictPreview(Status.NOT_ANALYZED, ImmutableList.of());
    }

    public enum Status
    {
        CLEAN,
        CONFLICT,
        NOT_ANALYZED
    }
}
