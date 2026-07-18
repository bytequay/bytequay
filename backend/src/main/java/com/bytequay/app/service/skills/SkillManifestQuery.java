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
package com.bytequay.app.service.skills;

import java.util.Optional;
import java.util.Set;

/**
 * Filter set ByteQuay applies when selecting or inspecting skills. All fields are optional; a query
 * with everything empty matches every enabled global skill.
 *
 * @param scopes         scopes to include (defaults to global only when
 *                       empty)
 * @param touchedRepos   when non-empty, also include repo-scoped rows
 *                       targeting any of these
 * @param threadId       when present, also include thread-scoped rows
 *                       bound to this thread
 * @param role           when present, exclude rows whose {@code roleTag}
 *                       is set and doesn't match
 */
public record SkillManifestQuery(
        Set<String> scopes,
        Set<String> touchedRepos,
        Optional<String> threadId,
        Optional<String> role)
{
    public static SkillManifestQuery global()
    {
        return new SkillManifestQuery(Set.of("global"), Set.of(), Optional.empty(), Optional.empty());
    }

    public static SkillManifestQuery forRepoContext(String repo, String role)
    {
        return new SkillManifestQuery(
                Set.of("global", "repo"),
                repo == null || repo.isBlank() ? Set.of() : Set.of(repo),
                Optional.empty(),
                role == null || role.isBlank() ? Optional.empty() : Optional.of(role));
    }
}
