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
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.SkillStore;
import com.bytequay.app.repository.WatchedRepoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestByteQuaySkillSelector
{
    private final SkillStore store = mock(SkillStore.class);
    private final WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
    private final PonytailBundleService bundles = mock(PonytailBundleService.class);
    private final ByteQuaySkillSelector selector = new ByteQuaySkillSelector(
            store, watchedRepos, bundles);

    @BeforeEach
    void setUp()
    {
        when(bundles.snapshot()).thenReturn(new ManagedSkillBundle(
                "1", "test", Map.of("builtin", new ManagedSkill("builtin", "BUILTIN"))));
        when(store.list()).thenReturn(List.of());
        when(watchedRepos.findAll()).thenReturn(List.of());
    }

    @Test
    void managedSkillsComeFirstAndAuthoredSelectionCannotExceedTheLimit()
    {
        when(store.list()).thenReturn(List.of(
                skill(1, "global", null, null, "alpha", "", "ALPHA",
                        "library", "build", null, true),
                skill(2, "global", null, null, "beta", "", "BETA",
                        "library", "build", null, true)));

        assertThat(selector.select(
                List.of("builtin"), ByteQuayRole.TASK, "thread-1", null, "work", 2))
                .extracting(ManagedSkill::name)
                .containsExactly("builtin", "alpha");
    }

    @Test
    void selectsByByteQuayTriggerWithoutSendingTheCatalogToTheProvider()
    {
        when(store.list()).thenReturn(List.of(
                skill(1, "global", null, null, "database migration",
                        "Use for Flyway schema changes", "DB", "library", "build", null, false),
                skill(2, "global", null, null, "react accessibility",
                        "Use for keyboard navigation", "A11Y", "library", "build", null, false)));

        assertThat(selector.select(
                List.of("builtin"), ByteQuayRole.TASK, "thread-1", null,
                "Implement the Flyway database migration", 5))
                .extracting(ManagedSkill::name)
                .containsExactly("builtin", "database migration");
    }

    @Test
    void appliesThreadAndRepoScopeButRejectsADifferentRoleOrUsage()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                new WatchedRepo(1, "acme", "rocket", 0, "/repos/rocket", null, null)));
        when(store.list()).thenReturn(List.of(
                skill(1, "repo", "acme/rocket", null, "repo rubric", "", "REPO",
                        "rubric", "build", "task", false),
                skill(2, "thread", null, "thread-1", "thread context", "", "THREAD",
                        "library", "build", null, false),
                skill(3, "global", null, null, "trunk persona", "", "TRUNK",
                        "persona", "build", "trunk", false),
                skill(4, "global", null, null, "review persona", "", "REVIEW",
                        "persona", "review", "task", false)));

        assertThat(selector.select(
                List.of("builtin"), ByteQuayRole.TASK, "thread-1",
                "/repos/rocket/.worktrees/task-1", "implement", 5))
                .extracting(ManagedSkill::name)
                .containsExactly("builtin", "thread context", "repo rubric");
    }

    @Test
    void missingManagedSkillFailsClosed()
    {
        assertThatThrownBy(() -> selector.select(
                List.of("missing"), ByteQuayRole.TASK, "thread-1", null, "", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing ByteQuay managed skill: missing");
    }

    private static Skill skill(
            long id,
            String scope,
            String repo,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String usage,
            String role,
            boolean isDefault)
    {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        return new Skill(id, scope, repo, threadId, name, description, body, kind,
                usage, role, true, isDefault, "authored", null, "hash", now, now);
    }
}
