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
package com.bytequay.app.service.tools;

import com.google.common.collect.ImmutableSet;

import java.util.Optional;
import java.util.Set;

/**
 * Per-turn context the runtime tool dispatcher needs to resolve the
 * right manifest projection. Fixed for the duration of one turn so the
 * tool result bytes are stable.
 *
 * @param touchedRepos  the repos the turn might touch — used by catalog
 *                      diagnostics and compatibility queries
 * @param threadId      the thread this turn runs in, if scoping to
 *                      thread-specific skills is desired
 * @param role          agent role identifier (e.g. "trunk", "task",
 *                      "reviewer") — filters role-tagged rows
 */
public record ToolContext(
        Set<String> touchedRepos,
        Optional<String> threadId,
        Optional<String> role)
{
    public static ToolContext empty()
    {
        return new ToolContext(ImmutableSet.of(), Optional.empty(), Optional.empty());
    }

    public static ToolContext forRepo(String repo, String role)
    {
        return new ToolContext(
                repo == null || repo.isBlank() ? ImmutableSet.of() : ImmutableSet.of(repo),
                Optional.empty(),
                role == null || role.isBlank() ? Optional.empty() : Optional.of(role));
    }
}
