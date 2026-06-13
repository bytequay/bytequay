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
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.tools.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestSkillMaterializer
{
    /** Empty registry — skill bodies under test reference no
     *  concepts, so the substitution pass is a no-op. */
    private static final ConceptRegistry EMPTY_CONCEPTS = new ConceptRegistry();

    @Test
    void materializeWritesOneSkillMdPerResolvedRow(@TempDir Path dir)
            throws IOException
    {
        SkillMaterializer materializer = new SkillMaterializer(new SkillManifestService(
                new InMemorySkillStore(List.of(
                        row(1, "global library", "global", null, null, "library", "library body"),
                        row(2, "acme rubric", "repo", "acme/widgets", null, "rubric", "rubric body")))), EMPTY_CONCEPTS);

        Path out = materializer.materialize(dir, ToolContext.forRepo("acme/widgets", null));

        assertThat(out).isEqualTo(dir);
        Path libraryMd = dir.resolve("acme-rubric").resolve("SKILL.md");
        Path rubricMd = dir.resolve("global-library").resolve("SKILL.md");
        assertThat(libraryMd).exists();
        assertThat(rubricMd).exists();
        String body = Files.readString(libraryMd);
        assertThat(body).contains("rubric body");
        assertThat(body).contains("name: \"acme rubric\"");
        assertThat(body).contains("kind: rubric");
        assertThat(body).contains("scope: repo");
        assertThat(body).contains("repo: acme/widgets");
    }

    @Test
    void materializeIsByteStableAcrossCallsForTheSameInput(@TempDir Path dir)
            throws IOException
    {
        SkillMaterializer materializer = new SkillMaterializer(new SkillManifestService(
                new InMemorySkillStore(List.of(
                        row(1, "stable-skill", "global", null, null, "library", "stable body")))), EMPTY_CONCEPTS);
        Path firstDir = dir.resolve("first");
        Path secondDir = dir.resolve("second");

        materializer.materialize(firstDir, ToolContext.empty());
        materializer.materialize(secondDir, ToolContext.empty());

        String first = Files.readString(firstDir.resolve("stable-skill").resolve("SKILL.md"));
        String second = Files.readString(secondDir.resolve("stable-skill").resolve("SKILL.md"));
        assertThat(first).isEqualTo(second);
    }

    @Test
    void disabledRowsAreSkipped(@TempDir Path dir)
    {
        SkillMaterializer materializer = new SkillMaterializer(new SkillManifestService(
                new InMemorySkillStore(List.of(
                        rowEnabled(1, "muted", "global", null, null, "library", "muted body", false),
                        rowEnabled(2, "active", "global", null, null, "library", "active body", true)))), EMPTY_CONCEPTS);

        materializer.materialize(dir, ToolContext.empty());

        assertThat(dir.resolve("muted").resolve("SKILL.md")).doesNotExist();
        assertThat(dir.resolve("active").resolve("SKILL.md")).exists();
    }

    @Test
    void cleanupRecursivelyDeletesTheDirectory(@TempDir Path dir)
            throws IOException
    {
        SkillMaterializer materializer = new SkillMaterializer(new SkillManifestService(
                new InMemorySkillStore(List.of(
                        row(1, "x", "global", null, null, "library", "body")))), EMPTY_CONCEPTS);
        materializer.materialize(dir, ToolContext.empty());
        assertThat(Files.exists(dir.resolve("x").resolve("SKILL.md"))).isTrue();

        materializer.cleanup(dir);

        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void slugifyHandlesPunctuation()
    {
        assertThat(SkillMaterializer.slugify("Auth Review Checklist!")).isEqualTo("auth-review-checklist");
        assertThat(SkillMaterializer.slugify("--leading--dashes--")).isEqualTo("leading-dashes");
        assertThat(SkillMaterializer.slugify("!!!")).isEqualTo("skill");
    }

    @Test
    void conceptPlaceholderIsReplacedWithRegistryDefinition(@TempDir Path dir)
            throws IOException
    {
        // Real registry — the seed concepts (task, thread, …) are
        // enough to exercise the substitution path without mocking.
        ConceptRegistry concepts = new ConceptRegistry();
        concepts.scan();
        String body = "Use this when shipping a {{concept:task}}.";
        SkillMaterializer materializer = new SkillMaterializer(new SkillManifestService(
                new InMemorySkillStore(List.of(
                        row(1, "shipper", "global", null, null, "library", body)))),
                concepts);

        materializer.materialize(dir, ToolContext.empty());

        String rendered = Files.readString(dir.resolve("shipper").resolve("SKILL.md"));
        assertThat(rendered)
                .as("the {{concept:task}} placeholder should resolve")
                .doesNotContain("{{concept:task}}");
        assertThat(rendered)
                .contains("Use this when shipping a One unit of work within a thread");
    }

    @Test
    void unknownConceptPlaceholderIsLeftInPlace(@TempDir Path dir)
            throws IOException
    {
        ConceptRegistry concepts = new ConceptRegistry();
        concepts.scan();
        String body = "Lookup {{concept:does-not-exist}} for clarity.";
        SkillMaterializer materializer = new SkillMaterializer(new SkillManifestService(
                new InMemorySkillStore(List.of(
                        row(1, "unknown", "global", null, null, "library", body)))),
                concepts);

        materializer.materialize(dir, ToolContext.empty());

        String rendered = Files.readString(dir.resolve("unknown").resolve("SKILL.md"));
        assertThat(rendered)
                .as("unknown concept name should survive so the author notices the gap")
                .contains("{{concept:does-not-exist}}");
    }

    private static Skill row(long id, String name, String scope, String repo, String roleTag, String kind, String body)
    {
        return rowEnabled(id, name, scope, repo, roleTag, kind, body, true);
    }

    private static Skill rowEnabled(
            long id, String name, String scope, String repo, String roleTag, String kind, String body, boolean enabled)
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
