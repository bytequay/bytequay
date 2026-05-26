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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.Skill;
import com.bytequay.app.repository.SkillStore;
import com.bytequay.app.service.skills.SkillManifestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestSkillTools
{
    @Test
    void anthropicDefinitionsShape()
    {
        // The constant lives in code as a literal string, so byte
        // stability is by construction — no concatenation, no
        // formatting. We assert the wire shape instead so a future
        // edit that breaks the prefix surface lights up here.
        String json = SkillTools.ANTHROPIC_DEFINITIONS_JSON;
        assertThat(json).startsWith("[{\"name\":\"list_skills\"");
        assertThat(json).contains("\"name\":\"list_tools\"");
        assertThat(json).contains("\"name\":\"load_skill\"");
        assertThat(json).endsWith("}]");
    }

    @Test
    void openAiDefinitionsShape()
    {
        String json = SkillTools.OPENAI_DEFINITIONS_JSON;
        assertThat(json)
                .contains("\"type\":\"function\"")
                .contains("\"function\":{\"name\":\"list_skills\"");
        assertThat(json).startsWith("[{\"type\":\"function\"");
    }

    @Test
    void toolOrderIsListSkillsListToolsLoadSkill()
    {
        // Order is part of the cache; document the spec lives in code.
        assertThat(SkillTools.TOOL_NAMES)
                .containsExactly("list_skills", "list_tools", "load_skill");
    }

    @Test
    void listSkillsReturnsEntriesForTheContext()
            throws Exception
    {
        SkillTools tools = newToolsWith(List.of(
                skill(1, "auth-review", "global", null, null, true, "rubric"),
                skill(2, "acme-rubric", "repo", "acme/widgets", null, true, "rubric")));
        ObjectMapper mapper = new ObjectMapper();

        RuntimeToolInvocation out = tools.dispatch(
                "list_skills",
                mapper.createObjectNode(),
                ToolContext.forRepo("acme/widgets", null));

        assertThat(out.isError()).isFalse();
        JsonNode parsed = mapper.readTree(out.result());
        assertThat(parsed.isArray()).isTrue();
        assertThat(parsed).hasSize(2);
        // The output is ordered (global / name), so the global row
        // comes first.
        assertThat(parsed.get(0).path("name").asText()).isEqualTo("auth-review");
        assertThat(parsed.get(1).path("name").asText()).isEqualTo("acme-rubric");
    }

    @Test
    void listSkillsAppliesQuerySubstring()
            throws Exception
    {
        // Two rows with distinct trigger descriptions; only the auth
        // one matches the query and so survives the filter.
        SkillTools tools = newToolsWith(List.of(
                skillWithDescription(1, "auth-row", "loads when reviewing a PR that touches authentication"),
                skillWithDescription(2, "css-row", "loads when reviewing a CSS-only PR")));
        ObjectMapper mapper = new ObjectMapper();
        var args = mapper.createObjectNode();
        args.put("query", "auth");

        RuntimeToolInvocation out = tools.dispatch("list_skills", args, ToolContext.empty());

        JsonNode parsed = mapper.readTree(out.result());
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).path("name").asText()).isEqualTo("auth-row");
    }

    private static Skill skillWithDescription(long id, String name, String description)
    {
        return new Skill(
                id, "global", null, null, name, description, "body", "library", null,
                true, false, "authored", null, "hash",
                Instant.parse("2026-05-26T00:00:00Z"),
                Instant.parse("2026-05-26T00:00:00Z"));
    }

    @Test
    void listToolsCatalogIsConstant()
    {
        SkillTools tools = newToolsWith(List.of());

        RuntimeToolInvocation first = tools.dispatch(
                "list_tools", null, ToolContext.empty());
        RuntimeToolInvocation second = tools.dispatch(
                "list_tools", null, ToolContext.empty());

        assertThat(first.isError()).isFalse();
        assertThat(first.result()).isEqualTo(second.result());
        assertThat(first.result()).contains("\"name\":\"list_skills\"");
        assertThat(first.result()).contains("\"name\":\"load_skill\"");
    }

    @Test
    void loadSkillReturnsBodyForKnownName()
            throws Exception
    {
        SkillTools tools = newToolsWith(List.of(
                skill(1, "house-style", "global", null, null, true, "library", "Use 4-space indents.")));
        ObjectMapper mapper = new ObjectMapper();
        var args = mapper.createObjectNode();
        args.put("name", "house-style");

        RuntimeToolInvocation out = tools.dispatch("load_skill", args, ToolContext.empty());

        assertThat(out.isError()).isFalse();
        JsonNode parsed = mapper.readTree(out.result());
        assertThat(parsed.path("name").asText()).isEqualTo("house-style");
        assertThat(parsed.path("body").asText()).isEqualTo("Use 4-space indents.");
    }

    @Test
    void loadSkillSurfacesErrorWhenMissing()
    {
        SkillTools tools = newToolsWith(List.of());
        ObjectMapper mapper = new ObjectMapper();
        var args = mapper.createObjectNode();
        args.put("name", "nope");

        RuntimeToolInvocation out = tools.dispatch("load_skill", args, ToolContext.empty());

        assertThat(out.isError()).isTrue();
        assertThat(out.result()).contains("skill not found or disabled");
    }

    @Test
    void unknownToolReturnsError()
    {
        SkillTools tools = newToolsWith(List.of());

        RuntimeToolInvocation out = tools.dispatch(
                "nonexistent", null, ToolContext.empty());

        assertThat(out.isError()).isTrue();
        assertThat(out.result()).contains("unknown tool");
    }

    private static SkillTools newToolsWith(List<Skill> rows)
    {
        return new SkillTools(new SkillManifestService(new InMemorySkillStore(rows)));
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
        @Override public Skill create(String scope, String repo, String threadId, String name, String description, String body, String kind, String roleTag, boolean isDefault, String source, String provenance) { throw new UnsupportedOperationException(); }
        @Override public Skill update(long id, String scope, String repo, String threadId, String name, String description, String body, String kind, String roleTag, boolean isDefault) { throw new UnsupportedOperationException(); }
        @Override public void delete(long id) { throw new UnsupportedOperationException(); }
        @Override public Skill setEnabled(long id, boolean enabled) { throw new UnsupportedOperationException(); }
    }

    // Silences IDE warning on the unused Set import while keeping the
    // import list aligned with the other tool tests.
    @SuppressWarnings("unused")
    private static final Set<String> UNUSED = Set.of();
}
