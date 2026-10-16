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
package com.bytequay.app.web;

import com.bytequay.app.service.settings.AgentSkill;
import com.bytequay.app.service.settings.AgentSkillScope;
import com.bytequay.app.service.settings.AgentSkillService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/settings/agent-skills")
public class AgentSkillsController
{
    private final AgentSkillService skills;

    public AgentSkillsController(AgentSkillService skills)
    {
        this.skills = requireNonNull(skills, "skills is null");
    }

    @GetMapping
    public List<AgentSkill> list()
    {
        return skills.list();
    }

    @PostMapping
    public List<AgentSkill> add(@RequestBody SkillRequest request)
    {
        return skills.add(request.name(), request.instructions(), request.scope(), request.enabled());
    }

    @PutMapping("/{id}")
    public List<AgentSkill> update(@PathVariable String id, @RequestBody SkillRequest request)
    {
        return skills.update(id, request.name(), request.instructions(), request.scope(), request.enabled());
    }

    @DeleteMapping("/{id}")
    public List<AgentSkill> remove(@PathVariable String id)
    {
        return skills.remove(id);
    }

    public record SkillRequest(String name, String instructions, AgentSkillScope scope, boolean enabled)
    {
        public SkillRequest
        {
            checkText(name, "Skill name");
            checkText(instructions, "Skill instructions");
            requireNonNull(scope, "Skill scope is required");
        }
    }
}
