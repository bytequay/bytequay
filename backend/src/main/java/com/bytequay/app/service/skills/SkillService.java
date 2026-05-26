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

import com.bytequay.app.domain.Skill;
import com.bytequay.app.repository.SkillStore;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Service-layer facade for the {@code skill} table. Surfaces the
 * list / get / create / update / delete / setEnabled CRUD plus
 * {@link #forRepo} for the review path's repo-scoped rubric lookup.
 */
@Service
public class SkillService
{
    public static final Set<String> SCOPES = ImmutableSet.of("global", "repo", "thread");
    public static final Set<String> KINDS = ImmutableSet.of("library", "persona", "rubric");

    private final SkillStore store;

    public SkillService(SkillStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    public List<Skill> list()
    {
        return store.list();
    }

    public Skill get(long id)
    {
        return store.byId(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "skill " + id + " not found"));
    }

    /** Highest-priority enabled rubric for {@code repo}. The review path
     *  uses this to keep its existing always-applied semantics under the
     *  new model. Returns empty when no row matches. */
    public Optional<Skill> forRepo(String repo)
    {
        return store.findRubricForRepo(repo);
    }

    public Skill create(
            String scope,
            String repo,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String roleTag,
            boolean isDefault,
            String source,
            String provenance)
    {
        validateScope(scope);
        validateKind(kind);
        validateName(name);
        validateScopeFields(scope, repo, threadId);
        try {
            return store.create(
                    scope,
                    repo,
                    threadId,
                    name.strip(),
                    description,
                    body,
                    kind,
                    roleTag,
                    isDefault,
                    source == null || source.isBlank() ? "authored" : source,
                    provenance);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409), e.getMessage());
        }
    }

    public Skill update(
            long id,
            String scope,
            String repo,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String roleTag,
            boolean isDefault)
    {
        validateScope(scope);
        validateKind(kind);
        validateName(name);
        validateScopeFields(scope, repo, threadId);
        try {
            return store.update(
                    id,
                    scope,
                    repo,
                    threadId,
                    name.strip(),
                    description,
                    body,
                    kind,
                    roleTag,
                    isDefault);
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

    public Skill setEnabled(long id, boolean enabled)
    {
        try {
            return store.setEnabled(id, enabled);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404), e.getMessage());
        }
    }

    private static void validateScope(String scope)
    {
        if (scope == null || !SCOPES.contains(scope)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "scope must be one of " + SCOPES + ", got: " + scope);
        }
    }

    private static void validateKind(String kind)
    {
        if (kind == null || !KINDS.contains(kind)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "kind must be one of " + KINDS + ", got: " + kind);
        }
    }

    private static void validateName(String name)
    {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "name must not be empty");
        }
    }

    private static void validateScopeFields(String scope, String repo, String threadId)
    {
        if ("repo".equals(scope) && (repo == null || repo.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "repo is required when scope='repo'");
        }
        if ("thread".equals(scope) && (threadId == null || threadId.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "thread_id is required when scope='thread'");
        }
    }
}
