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
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Resolves the projection the {@code list_skills} tool returns at
 * runtime and the body the {@code load_skill} tool returns. Pure
 * read-side — the writes go through {@link SkillService}.
 *
 * <p>The query is intentionally explicit (one struct of filters)
 * rather than chained-builder style so the cached prefix can include
 * a deterministic serialisation of the manifest tool result without
 * surprises.
 */
@Service
public class SkillManifestService
{
    private final SkillStore store;

    public SkillManifestService(SkillStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /**
     * Resolve the manifest entries that match the query. The result is
     * sorted by (scope, name) so the same input always serialises to
     * the same bytes — important once the projection lands in a tool
     * result and becomes part of the cached prefix on the next turn.
     */
    public List<SkillManifestEntry> query(SkillManifestQuery query)
    {
        requireNonNull(query, "query is null");
        Set<String> scopes = query.scopes() == null || query.scopes().isEmpty()
                ? Set.of("global")
                : query.scopes();
        Set<String> touchedRepos = query.touchedRepos() == null ? Set.of() : query.touchedRepos();
        Optional<String> threadId = query.threadId() == null ? Optional.empty() : query.threadId();
        Optional<String> role = query.role() == null ? Optional.empty() : query.role();
        return store.list().stream()
                .filter(Skill::enabled)
                // Review-surface skills are reviewer roles, not agent
                // context — they never reach a build/task agent's
                // list_skills catalog.
                .filter(s -> !"review".equals(s.usage()))
                .filter(s -> matchesScope(s, scopes, touchedRepos, threadId))
                .filter(s -> matchesRole(s, role))
                .sorted(Comparator
                        .comparing(Skill::scope)
                        .thenComparing(Skill::name))
                .map(SkillManifestService::toEntry)
                .collect(toImmutableList());
    }

    /**
     * Load the body for a named skill — the {@code load_skill} tool's
     * return value. Empty when the row is missing or disabled; callers
     * surface that to the model as a "skill not available" tool error.
     */
    public Optional<String> loadBody(String name)
    {
        return store.byName(name)
                .filter(Skill::enabled)
                .filter(s -> !"review".equals(s.usage()))
                .map(Skill::body);
    }

    private static boolean matchesScope(
            Skill skill, Set<String> scopes, Set<String> touchedRepos, Optional<String> threadId)
    {
        if (!scopes.contains(skill.scope())) {
            return false;
        }
        return switch (skill.scope()) {
            case "global" -> true;
            case "repo" -> skill.repo() != null && touchedRepos.contains(skill.repo());
            case "thread" -> threadId.isPresent()
                    && skill.threadId() != null
                    && skill.threadId().equals(threadId.get());
            default -> false;
        };
    }

    private static boolean matchesRole(Skill skill, Optional<String> role)
    {
        if (skill.roleTag() == null) {
            return true;
        }
        return role.isPresent() && skill.roleTag().equals(role.get());
    }

    private static SkillManifestEntry toEntry(Skill s)
    {
        return new SkillManifestEntry(
                s.id(),
                s.name(),
                s.description(),
                s.scope(),
                s.repo(),
                s.roleTag(),
                s.kind());
    }
}
