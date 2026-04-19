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

import com.bytequay.app.domain.ReviewSkill;
import com.bytequay.app.service.skills.ReviewSkillService;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@RestController
public class ReviewSkillController
{
    private final ReviewSkillService service;

    public ReviewSkillController(ReviewSkillService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    public record SkillRequest(
            String skillName,
            String repo,
            String llmProvider,
            String description,
            String context)
    {}

    /** GET /skills — all configured skills, alphabetised by name. */
    @GetMapping("/skills")
    public List<ReviewSkill> list()
    {
        return service.list();
    }

    /** GET /skills/{id} — single skill by primary key. */
    @GetMapping("/skills/{id}")
    public ReviewSkill get(@PathVariable long id)
    {
        return service.get(id);
    }

    /** POST /skills — create a skill. Returns 400 when skill_name or repo
     *  is blank, 409 when either collides with an existing row. */
    @PostMapping("/skills")
    public ReviewSkill create(@RequestBody SkillRequest req)
    {
        return service.create(
                req.skillName(),
                req.repo(),
                req.llmProvider(),
                req.description(),
                req.context());
    }

    /** PUT /skills/{id} — update an existing skill. Same validation as
     *  create; 404 when the id is missing. */
    @PutMapping("/skills/{id}")
    public ReviewSkill update(@PathVariable long id, @RequestBody SkillRequest req)
    {
        return service.update(
                id,
                req.skillName(),
                req.repo(),
                req.llmProvider(),
                req.description(),
                req.context());
    }

    /** DELETE /skills/{id} — drop a skill. */
    @DeleteMapping("/skills/{id}")
    public Map<String, String> delete(@PathVariable long id)
    {
        service.delete(id);
        return ImmutableMap.of("result", "deleted");
    }
}
