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
    public static final Set<String> SCOPES = ImmutableSet.of("global", "repo", "thread");
    public static final Set<String> KINDS = ImmutableSet.of("library", "persona", "rubric");
    public static final Set<String> SOURCES = ImmutableSet.of("authored", "ai_drafted");

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
                : "development".equals(usageKind) ? "build" : usageKind;
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return store.list().stream()
                .filter(s -> usage == null || usage.equals(s.usage()))
                .filter(s -> scope == null || scope.isBlank() || "all".equals(scope)
                        || ("global".equals(scope) && "global".equals(s.scope()))
                        || ("repo".equals(scope) && "repo".equals(s.scope())
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
            case "trunk", "task" -> "build";
            case "reviewer", "lead" -> "review";
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
        if (!"review".equals(skill.usage())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "default_only_for_review_skills");
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
        validateScope(scope);
        validateKind(kind);
        String resolvedUsage = resolveUsage(usage);
        validateName(name);
        validateScopeFields(scope, repo, threadId);
        String resolvedSource = source == null || source.isBlank() ? "authored" : source;
        validateSource(resolvedSource);
        try {
            return store.create(
                    scope,
                    repo,
                    threadId,
                    name.strip(),
                    description,
                    body,
                    kind,
                    resolvedUsage,
                    roleTag,
                    isDefault,
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
        validateScope(scope);
        validateKind(kind);
        String resolvedUsage = resolveUsage(usage);
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
                    resolvedUsage,
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

    /** Null/blank defaults to the build surface; anything else must
     *  be one of the two surfaces. */
    private static String resolveUsage(String usage)
    {
        if (usage == null || usage.isBlank()) {
            return "build";
        }
        if (!usage.equals("build") && !usage.equals("review")) {
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
