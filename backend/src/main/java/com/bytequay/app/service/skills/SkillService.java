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
import java.util.Locale;
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
    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_GLOBAL = "global";
    private static final String SCOPE_REPO = "repo";
    private static final String SCOPE_THREAD = "thread";
    private static final String KIND_LIBRARY = "library";
    private static final String KIND_PERSONA = "persona";
    private static final String KIND_RUBRIC = "rubric";
    private static final String SOURCE_AUTHORED = "authored";
    private static final String SOURCE_AI_DRAFTED = "ai_drafted";
    private static final String USAGE_KIND_DEVELOPMENT = "development";
    private static final String USAGE_BUILD = "build";
    private static final String USAGE_REVIEW = "review";
    private static final String ROLE_TRUNK = "trunk";
    private static final String ROLE_TASK = "task";
    private static final String ROLE_REVIEWER = "reviewer";
    private static final String ROLE_LEAD = "lead";
    private static final String DEFAULT_REVIEW_SKILL_ERROR = "default_only_for_review_skills";

    public static final Set<String> SCOPES = ImmutableSet.of(SCOPE_GLOBAL, SCOPE_REPO, SCOPE_THREAD);
    public static final Set<String> KINDS = ImmutableSet.of(KIND_LIBRARY, KIND_PERSONA, KIND_RUBRIC);
    public static final Set<String> SOURCES = ImmutableSet.of(SOURCE_AUTHORED, SOURCE_AI_DRAFTED);

    private final SkillStore store;

    public SkillService(SkillStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    public List<Skill> list()
    {
        return store.list();
    }

    /**
     * Filtered list for the Settings UI. {@code usageKind} accepts the
     * UI vocabulary ('development' = build surface, 'review'); null = all.
     * {@code scope} is 'all' (or null), 'global', or 'repo' (with
     * {@code repoId}). {@code q} is a case-insensitive name/description
     * substring.
     */
    public List<Skill> query(String usageKind, String scope, String repoId, String q)
    {
        String usage = usageKind == null || usageKind.isBlank() ? null
                : USAGE_KIND_DEVELOPMENT.equals(usageKind) ? USAGE_BUILD : usageKind;
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return store.list().stream()
                .filter(s -> usage == null || usage.equals(s.usage()))
                .filter(s -> scope == null || scope.isBlank() || SCOPE_ALL.equals(scope)
                        || (SCOPE_GLOBAL.equals(scope) && SCOPE_GLOBAL.equals(s.scope()))
                        || (SCOPE_REPO.equals(scope) && SCOPE_REPO.equals(s.scope())
                                && (repoId == null || repoId.equals(s.repo()))))
                .filter(s -> needle.isEmpty()
                        || s.name().toLowerCase(Locale.ROOT).contains(needle)
                        || (s.description() != null && s.description().toLowerCase(Locale.ROOT).contains(needle)))
                .toList();
    }

    /**
     * The skills that would resolve for an agent role — derived from
     * usage, never stored: Trunk / Task see development (build) skills;
     * Reviewer / Lead see review skills. Enabled rows only. Backs the
     * read-only Agent roles preview.
     */
    public List<Skill> byRole(String role)
    {
        String r = role == null ? "" : role.toLowerCase(Locale.ROOT);
        String usage = switch (r) {
            case ROLE_TRUNK, ROLE_TASK -> USAGE_BUILD;
            case ROLE_REVIEWER, ROLE_LEAD -> USAGE_REVIEW;
            default -> throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "role must be one of trunk|task|reviewer|lead, got: " + role);
        };
        return store.list().stream()
                .filter(Skill::enabled)
                .filter(s -> usage.equals(s.usage()))
                .toList();
    }

    /** Mark a review skill the default for its repo (clearing any prior
     *  default in that repo). 422 when the row isn't a review skill. */
    public Skill setDefault(long id)
    {
        Skill skill = get(id);
        if (!USAGE_REVIEW.equals(skill.usage())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    DEFAULT_REVIEW_SKILL_ERROR);
        }
        try {
            return store.setDefault(id);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404), e.getMessage());
        }
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
            String usage,
            String roleTag,
            boolean isDefault,
            String source,
            String provenance)
    {
        SkillFields fields = validateFields(
                scope,
                repo,
                threadId,
                name,
                description,
                body,
                kind,
                usage,
                roleTag,
                isDefault);
        String resolvedSource = source == null || source.isBlank() ? SOURCE_AUTHORED : source;
        validateSource(resolvedSource);
        try {
            return store.create(
                    fields.scope(),
                    fields.repo(),
                    fields.threadId(),
                    fields.name(),
                    fields.description(),
                    fields.body(),
                    fields.kind(),
                    fields.usage(),
                    fields.roleTag(),
                    fields.isDefault(),
                    resolvedSource,
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
            String usage,
            String roleTag,
            boolean isDefault)
    {
        return update(
                id,
                validateFields(
                        scope,
                        repo,
                        threadId,
                        name,
                        description,
                        body,
                        kind,
                        usage,
                        roleTag,
                        isDefault));
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

    private Skill update(long id, SkillFields fields)
    {
        try {
            return store.update(
                    id,
                    fields.scope(),
                    fields.repo(),
                    fields.threadId(),
                    fields.name(),
                    fields.description(),
                    fields.body(),
                    fields.kind(),
                    fields.usage(),
                    fields.roleTag(),
                    fields.isDefault());
        }
        catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("not found")) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(404), msg);
            }
            throw new ResponseStatusException(HttpStatusCode.valueOf(409), msg);
        }
    }

    private static SkillFields validateFields(
            String scope,
            String repo,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String usage,
            String roleTag,
            boolean isDefault)
    {
        validateScope(scope);
        validateKind(kind);
        String resolvedUsage = resolveUsage(usage);
        validateName(name);
        validateScopeFields(scope, repo, threadId);
        return new SkillFields(
                scope,
                repo,
                threadId,
                name.strip(),
                description,
                body,
                kind,
                resolvedUsage,
                roleTag,
                isDefault);
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

    /** Null/blank defaults to the build surface; anything else must
     *  be one of the two surfaces. */
    private static String resolveUsage(String usage)
    {
        if (usage == null || usage.isBlank()) {
            return USAGE_BUILD;
        }
        if (!usage.equals(USAGE_BUILD) && !usage.equals(USAGE_REVIEW)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "usage must be 'build' or 'review', got: " + usage);
        }
        return usage;
    }

    private static void validateSource(String source)
    {
        if (!SOURCES.contains(source)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "source must be one of " + SOURCES + ", got: " + source);
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
        if (SCOPE_REPO.equals(scope) && (repo == null || repo.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "repo is required when scope='repo'");
        }
        if (SCOPE_THREAD.equals(scope) && (threadId == null || threadId.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "thread_id is required when scope='thread'");
        }
    }

    private record SkillFields(
            String scope,
            String repo,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String usage,
            String roleTag,
            boolean isDefault)
    {}
}
