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

import com.bytequay.app.domain.ReviewSkill;
import com.bytequay.app.repository.ReviewSkillStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Service
public class ReviewSkillService
{
    private final ReviewSkillStore store;

    public ReviewSkillService(ReviewSkillStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    public List<ReviewSkill> list()
    {
        return store.list();
    }

    public ReviewSkill get(long id)
    {
        return store.byId(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "skill " + id + " not found"));
    }

    /** Lookup by repo at review-time. Returns empty when no skill targets
     *  the repo (the AI run then proceeds with no extra context). */
    public Optional<ReviewSkill> forRepo(String repo)
    {
        return store.findByRepo(repo);
    }

    public ReviewSkill create(
            String skillName,
            String repo,
            String llmProvider,
            String description,
            String context)
    {
        validateRequiredFields(skillName, repo);
        try {
            return store.create(skillName.strip(), repo.strip(), llmProvider, description, context);
        }
        catch (IllegalStateException e) {
            // Uniqueness collisions surface as 409 Conflict so the UI can
            // show a precise "name or repo already used" hint.
            throw new ResponseStatusException(HttpStatusCode.valueOf(409), e.getMessage());
        }
    }

    public ReviewSkill update(
            long id,
            String skillName,
            String repo,
            String llmProvider,
            String description,
            String context)
    {
        validateRequiredFields(skillName, repo);
        try {
            return store.update(id, skillName.strip(), repo.strip(), llmProvider, description, context);
        }
        catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("not found")) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(404), msg);
            }
            throw new ResponseStatusException(HttpStatusCode.valueOf(409), msg);
        }
    }

    public void delete(long id)
    {
        store.delete(id);
    }

    private static void validateRequiredFields(String skillName, String repo)
    {
        if (skillName == null || skillName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "skill_name must not be empty");
        }
        if (repo == null || repo.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "repo must not be empty");
        }
    }
}
