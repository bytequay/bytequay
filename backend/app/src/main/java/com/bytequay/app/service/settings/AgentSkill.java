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
package com.bytequay.app.service.settings;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** App-owned prompt text, independent of provider plugins, tools, and repository files. */
public record AgentSkill(String id, String name, String instructions, AgentSkillScope scope, boolean enabled)
{
    public AgentSkill
    {
        checkNotBlank(id, "id");
        checkNotBlank(name, "name");
        checkNotBlank(instructions, "instructions");
        requireNonNull(scope, "scope is null");
        name = name.strip();
        checkArgument(name.length() <= 100, "Skill name must be at most 100 characters");
        checkArgument(instructions.length() <= 20_000, "Skill instructions must be at most 20000 characters");
    }
}
