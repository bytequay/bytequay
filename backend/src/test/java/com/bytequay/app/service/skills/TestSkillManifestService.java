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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestSkillManifestService
{
    @Test
    void reviewSurfaceSkillsNeverReachTheAgentManifest()
    {
        Skill reviewVoice = new Skill(
                99, "global", null, null, "reviewer-voice", "review persona", "voice body",
                "persona", "review", null, true, false, "authored", null, "hash",
                Instant.parse("2026-05-26T00:00:00Z"),
                Instant.parse("2026-05-26T00:00:00Z"));
        List<Skill> rows = List.of(
                skill(1, "build-skill", "global", null, null, true, "library"),
                reviewVoice);
        SkillManifestService service = new SkillManifestService(new InMemorySkillStore(rows));

        List<SkillManifestEntry> entries = service.query(
                SkillManifestQuery.forRepoContext("acme/widgets", null));

        // Review-surface rows are reviewer roles, not agent context —
        // invisible to list_skills and unloadable via load_skill.
        assertThat(entries).extracting(SkillManifestEntry::name)
                .containsExactly("build-skill");
        assertThat(service.loadBody("reviewer-voice")).isEmpty();
    }

    @Test
    void queryFiltersByScopeAndRepoAndEnabled()
    {
        List<Skill> rows = List.of(
                skill(1, "global-on", "global", null, null, true, "library"),
                skill(2, "global-off", "global", null, null, false, "library"),
                skill(3, "acme-rubric", "repo", "acme/widgets", null, true, "rubric"),
                skill(4, "other-rubric", "repo", "other/repo", null, true, "rubric"));
        SkillManifestService service = new SkillManifestService(new InMemorySkillStore(rows));

        List<SkillManifestEntry> entries = service.query(
                SkillManifestQuery.forRepoContext("acme/widgets", null));

        assertThat(entries).extracting(SkillManifestEntry::name)
                .containsExactly("global-on", "acme-rubric");
    }

    @Test
    void queryIgnoresRoleTagAndResolvesEveryInScopeBuildSkill()
    {
        // Role applicability is derived from usage now, not roleTag: a
        // build agent sees every enabled development skill in scope
        // regardless of any legacy role tag on the row.
        List<Skill> rows = List.of(
                skill(1, "agnostic", "global", null, null, true, "library"),
                skill(2, "reviewer-persona", "global", null, "reviewer", true, "persona"),
                skill(3, "trunk-persona", "global", null, "trunk", true, "persona"));
        SkillManifestService service = new SkillManifestService(new InMemorySkillStore(rows));

        List<SkillManifestEntry> resolved = service.query(
                new SkillManifestQuery(
                        Set.of("global"),
                        Set.of(),
                        Optional.empty(),
                        Optional.of("reviewer")));

        assertThat(resolved).extracting(SkillManifestEntry::name)
                .containsExactly("agnostic", "reviewer-persona", "trunk-persona");
    }

    @Test
    void queryReturnsSortedOutputForCacheStability()
    {
        // Source rows out-of-order; query() must emit the same order
        // every call so the cached prefix is byte-stable.
        List<Skill> rows = List.of(
                skill(1, "zebra", "global", null, null, true, "library"),
                skill(2, "alpha", "global", null, null, true, "library"),
                skill(3, "yak", "repo", "acme/widgets", null, true, "rubric"));
        SkillManifestService service = new SkillManifestService(new InMemorySkillStore(rows));

        List<SkillManifestEntry> first = service.query(
                SkillManifestQuery.forRepoContext("acme/widgets", null));
        List<SkillManifestEntry> second = service.query(
                SkillManifestQuery.forRepoContext("acme/widgets", null));

        assertThat(first).extracting(SkillManifestEntry::name)
                .containsExactly("alpha", "zebra", "yak");
        assertThat(second).extracting(SkillManifestEntry::name)
                .containsExactly("alpha", "zebra", "yak");
    }

    @Test
    void loadBodyReturnsEmptyForDisabledSkill()
    {
        List<Skill> rows = List.of(
                skill(1, "active", "global", null, null, true, "library", "loaded body"),
                skill(2, "muted", "global", null, null, false, "library", "muted body"));
        SkillManifestService service = new SkillManifestService(new InMemorySkillStore(rows));

        assertThat(service.loadBody("active")).contains("loaded body");
        assertThat(service.loadBody("muted")).isEmpty();
        assertThat(service.loadBody("nonexistent")).isEmpty();
    }

    private static Skill skill(
            long id, String name, String scope, String repo, String roleTag, boolean enabled, String kind)
    {
        return skill(id, name, scope, repo, roleTag, enabled, kind, "body");
    }

    private static Skill skill(
            long id, String name, String scope, String repo, String roleTag, boolean enabled, String kind, String body)
    {
        return new Skill(
                id, scope, repo, null, name, "loads when …", body, kind, roleTag,
                enabled, false, "authored", null, "hash",
                Instant.parse("2026-05-26T00:00:00Z"),
                Instant.parse("2026-05-26T00:00:00Z"));
    }

    private static final class InMemorySkillStore
            implements SkillStore
    {
        private final List<Skill> rows;

        private InMemorySkillStore(List<Skill> rows)
        {
            this.rows = rows;
        }

        @Override public List<Skill> list() { return rows; }
        @Override public Optional<Skill> byId(long id)
        {
            return rows.stream().filter(s -> s.id() == id).findFirst();
        }
        @Override public Optional<Skill> byName(String name)
        {
            return rows.stream().filter(s -> s.name().equals(name)).findFirst();
        }
        @Override public List<Skill> findGlobal()
        {
            return rows.stream().filter(s -> "global".equals(s.scope()) && s.enabled()).toList();
        }
        @Override public List<Skill> findByRepo(String repo)
        {
            return rows.stream()
                    .filter(s -> "repo".equals(s.scope()) && repo.equals(s.repo()) && s.enabled())
                    .toList();
        }
        @Override public Optional<Skill> findRubricForRepo(String repo)
        {
            return findByRepo(repo).stream().filter(s -> "rubric".equals(s.kind())).findFirst();
        }
        @Override public Skill create(String scope, String repo, String threadId, String name, String description, String body, String kind, String usage, String roleTag, boolean isDefault, String source, String provenance) { throw new UnsupportedOperationException(); }
        @Override public Skill update(long id, String scope, String repo, String threadId, String name, String description, String body, String kind, String usage, String roleTag, boolean isDefault) { throw new UnsupportedOperationException(); }
        @Override public void delete(long id) { throw new UnsupportedOperationException(); }
        @Override public Skill setEnabled(long id, boolean enabled) { throw new UnsupportedOperationException(); }
    }
}
