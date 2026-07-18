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
 * Read-side catalog used by ByteQuay's skill selector and settings UI.
 * Providers never query this catalog directly; writes go through
 * {@link SkillService}.
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
     * sorted by (scope, name) so selection and diagnostics are deterministic.
     */
    public List<SkillManifestEntry> query(SkillManifestQuery query)
    {
        requireNonNull(query, "query is null");
        Set<String> scopes = query.scopes() == null || query.scopes().isEmpty()
                ? Set.of("global")
                : query.scopes();
        Set<String> touchedRepos = query.touchedRepos() == null ? Set.of() : query.touchedRepos();
        Optional<String> threadId = query.threadId() == null ? Optional.empty() : query.threadId();
        return store.list().stream()
                .filter(Skill::enabled)
                // Role applicability is derived from usage, not stored: a
                // build/task agent sees every development (usage=build)
                // skill in scope; review-surface skills are reviewer voices.
                .filter(s -> !"review".equals(s.usage()))
                .filter(s -> matchesScope(s, scopes, touchedRepos, threadId))
                .sorted(Comparator
                        .comparing(Skill::scope)
                        .thenComparing(Skill::name))
                .map(SkillManifestService::toEntry)
                .collect(toImmutableList());
    }

    /**
     * Load one selected skill body. Empty when the row is missing or disabled.
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
