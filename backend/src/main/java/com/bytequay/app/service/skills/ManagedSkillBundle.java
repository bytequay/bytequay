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

import java.util.List;
import java.util.Map;

public record ManagedSkillBundle(String version, String source, Map<String, ManagedSkill> skills)
{
    private static final ManagedSkillBundle EMPTY = new ManagedSkillBundle(
            "none", "none", Map.of());

    public static ManagedSkillBundle empty()
    {
        return EMPTY;
    }

    public List<ManagedSkill> select(List<String> names)
    {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .map(skills::get)
                .filter(skill -> skill != null && skill.body() != null && !skill.body().isBlank())
                .toList();
    }
}
