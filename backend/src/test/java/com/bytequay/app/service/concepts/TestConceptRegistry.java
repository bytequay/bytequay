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
package com.bytequay.app.service.concepts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests that exercise the real classpath scan against
 * the project's seed annotations — no mocks. The scan walks
 * {@code com.bytequay.app} and the assertions pin (a) the names
 * the spec promised to seed, (b) deterministic ordering, and
 * (c) the conflict / scope resolution rule.
 */
class TestConceptRegistry
{
    private ConceptRegistry registry;

    @BeforeEach
    void boot()
            throws IOException
    {
        registry = new ConceptRegistry();
        // Drive the scan directly — outside Spring there's no
        // ContextRefreshedEvent to trigger it, but the method is
        // package-private precisely so tests can invoke it.
        registry.scan();
    }

    @Test
    void testScanFindsSeedNouns()
    {
        List<String> names = registry.all().stream().map(ConceptSpec::name).toList();
        assertThat(names)
                .as("seed NOUN concepts must be discoverable")
                .contains("task", "thread", "pr", "trunk");
    }

    @Test
    void testScanFindsSeedStatesAndVerbs()
    {
        List<String> names = registry.all().stream().map(ConceptSpec::name).toList();
        assertThat(names)
                .as("seed STATE + VERB concepts must be discoverable")
                .contains("awaiting_review", "needs_attention", "ship", "next", "request_review");
    }

    @Test
    void testListingIsSortedByName()
    {
        List<String> names = registry.all().stream().map(ConceptSpec::name).toList();
        List<String> resorted = names.stream().sorted(Comparator.naturalOrder()).toList();
        assertThat(names)
                .as("manifest must be alphabetical for cache stability")
                .isEqualTo(resorted);
    }

    @Test
    void testListByKind()
    {
        List<ConceptKind> kindsInNouns = registry.list(ConceptKind.NOUN)
                .stream().map(ConceptSpec::kind).distinct().toList();
        assertThat(kindsInNouns)
                .as("list(NOUN) must only return NOUN entries")
                .containsExactly(ConceptKind.NOUN);
    }

    @Test
    void testByNameResolvesSeedSpec()
    {
        ConceptSpec task = registry.byName("task").orElseThrow();
        assertThat(task.kind()).isEqualTo(ConceptKind.NOUN);
        assertThat(task.scope()).isEqualTo(ConceptScope.APP);
        assertThat(task.source()).contains("com.bytequay.app.domain.Task");
        assertThat(task.definition()).contains("unit of work");
    }

    @Test
    void testByNameMissReturnsEmpty()
    {
        assertThat(registry.byName("definitely-not-a-concept")).isEmpty();
        assertThat(registry.byName(null)).isEmpty();
        assertThat(registry.byName("")).isEmpty();
    }

    @Test
    void testUserScopeWinsOverAppOnConflict()
    {
        // Seed concept "task" lives at APP scope. Register a USER-
        // scoped spec for the same name; byName must return the
        // narrower one and lookup must carry APP as an alternate.
        ConceptSpec userTask = new ConceptSpec(
                "task",
                List.of(),
                ConceptKind.NOUN,
                "user's narrower definition for testing",
                List.of(),
                List.of(),
                List.of(),
                ConceptScope.USER,
                "test://user/task");
        registry.registerRuntime(userTask);

        ConceptSpec winner = registry.byName("task").orElseThrow();
        assertThat(winner.scope())
                .as("USER must beat APP on a name collision")
                .isEqualTo(ConceptScope.USER);
        assertThat(winner.definition()).contains("user's narrower definition");

        ConceptRegistry.Alternates lookup = registry.lookup("task").orElseThrow();
        assertThat(lookup.winner()).isSameAs(winner);
        assertThat(lookup.alternates())
                .as("the APP-scoped seed must survive as an alternate")
                .anySatisfy(a -> assertThat(a.scope()).isEqualTo(ConceptScope.APP));
    }

    @Test
    void testRegisterRuntimeRejectsAppScope()
    {
        ConceptSpec appAttempt = new ConceptSpec(
                "synthetic",
                List.of(),
                ConceptKind.NOUN,
                "should not be allowed at runtime",
                List.of(),
                List.of(),
                List.of(),
                ConceptScope.APP,
                "test://synthetic");
        assertThatThrownBy(() -> registry.registerRuntime(appAttempt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APP scope is reserved");
    }

    @Test
    void testClearScopeRemovesOnlyTheTargetedScope()
    {
        ConceptSpec workspaceTask = new ConceptSpec(
                "task",
                List.of(),
                ConceptKind.NOUN,
                "workspace-scoped task definition",
                List.of(),
                List.of(),
                List.of(),
                ConceptScope.WORKSPACE,
                "test://ws/task");
        registry.registerRuntime(workspaceTask);
        assertThat(registry.byName("task").orElseThrow().scope())
                .isEqualTo(ConceptScope.WORKSPACE);

        registry.clearScope(ConceptScope.WORKSPACE);

        ConceptSpec afterClear = registry.byName("task").orElseThrow();
        assertThat(afterClear.scope())
                .as("clearing WORKSPACE must drop back to the APP-scoped seed")
                .isEqualTo(ConceptScope.APP);
    }

    @Test
    void testRelatedToolsAndConceptsCarry()
    {
        ConceptSpec task = registry.byName("task").orElseThrow();
        assertThat(task.relatedTools())
                .as("seed back-links to action verbs must round-trip")
                .contains("ship", "next", "request_review");
        assertThat(task.relatedConcepts())
                .contains("thread", "trunk", "awaiting_review");
    }

    @Test
    void testOneLineDefinitionTrimsAtFirstStop()
    {
        ConceptSpec task = registry.byName("task").orElseThrow();
        String oneLine = task.oneLineDefinition();
        assertThat(oneLine)
                .as("one-line must drop everything after the first sentence boundary")
                .doesNotContain(".")
                .doesNotContain("\n");
    }
}
