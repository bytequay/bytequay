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

import com.bytequay.app.domain.Skill;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.service.skills.SkillDraft;
import com.bytequay.app.service.skills.SkillService;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@RestController
public class SkillController
{
    private final SkillService service;
    private final LlmReviewerRegistry reviewers;

    public SkillController(SkillService service, LlmReviewerRegistry reviewers)
    {
        this.service = requireNonNull(service, "service is null");
        this.reviewers = requireNonNull(reviewers, "reviewers is null");
    }

    /** Mirror of the controller fields the Settings UI sends.
     *
     *  {@code source} and {@code provenance} are only consulted at
     *  create time. The UI sets {@code source='ai_drafted'} +
     *  {@code provenance=<the prompt>} after a /skills/draft proposal
     *  lands in the modal, so the row keeps a paper trail of where it
     *  came from. Both are null on a manual write — the controller
     *  defaults the source column to 'authored' in that case. */
    public record SkillRequest(
            String scope,
            String repo,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String roleTag,
            Boolean isDefault,
            String source,
            String provenance)
    {}

    @GetMapping("/skills")
    public List<Skill> list()
    {
        return service.list();
    }

    @GetMapping("/skills/{id}")
    public Skill get(@PathVariable long id)
    {
        return service.get(id);
    }

    @PostMapping("/skills")
    public Skill create(@RequestBody SkillRequest req)
    {
        return service.create(
                req.scope(),
                req.repo(),
                req.threadId(),
                req.name(),
                req.description(),
                req.body(),
                req.kind(),
                req.roleTag(),
                Boolean.TRUE.equals(req.isDefault()),
                req.source(),
                req.provenance());
    }

    @PutMapping("/skills/{id}")
    public Skill update(@PathVariable long id, @RequestBody SkillRequest req)
    {
        return service.update(
                id,
                req.scope(),
                req.repo(),
                req.threadId(),
                req.name(),
                req.description(),
                req.body(),
                req.kind(),
                req.roleTag(),
                Boolean.TRUE.equals(req.isDefault()));
    }

    @DeleteMapping("/skills/{id}")
    public Map<String, String> delete(@PathVariable long id)
    {
        service.delete(id);
        return ImmutableMap.of("result", "deleted");
    }

    @PatchMapping("/skills/{id}/enabled")
    public Skill setEnabled(@PathVariable long id, @RequestBody EnabledRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        return service.setEnabled(id, body.enabled());
    }

    /**
     * POST /skills/draft — propose a name + description + body for
     * the user to confirm before saving. One cheap call against the
     * active LLM provider; same propose-then-confirm pattern as
     * polish / diagnose.
     */
    @PostMapping("/skills/draft")
    public SkillDraft draft(@RequestBody DraftRequest body)
    {
        if (body == null || body.prompt() == null || body.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "prompt must not be blank");
        }
        try {
            return reviewers.active().draftSkill(body.prompt(), body.scope());
        }
        catch (UnsupportedOperationException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(501), e.getMessage());
        }
    }

    public record EnabledRequest(boolean enabled) {}

    public record DraftRequest(String prompt, String scope) {}
}
