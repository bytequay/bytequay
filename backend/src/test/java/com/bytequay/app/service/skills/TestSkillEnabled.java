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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end exercise of the {@code enabled} column on the skill
 * table — a fresh skill defaults to enabled, the toggle persists,
 * and the review-time lookup ({@link SkillService#forRepo}) skips
 * disabled rows so the review path's behaviour follows the user's
 * choice. Runs against the real Flyway-migrated SQLite schema so
 * a stray default-clause / NOT NULL drift would surface here.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSkillEnabled
{
    @Autowired
    private SkillService service;

    @Test
    void newSkillIsEnabledByDefault()
    {
        Skill row = service.create(
                "repo",
                "acme/" + UUID.randomUUID(),
                null,
                uniqueName("default-on"),
                "loads when reviewing a backend PR",
                "Prefer constructor injection over field injection.",
                "rubric",
                null,
                false,
                "authored",
                null);

        assertThat(row.enabled()).isTrue();
    }

    @Test
    void setEnabledFlipsTheFlagAndPersists()
    {
        Skill row = service.create(
                "repo",
                "acme/" + UUID.randomUUID(),
                null,
                uniqueName("toggle"),
                "loads when reviewing a frontend PR",
                "Avoid `any` in new code.",
                "rubric",
                null,
                false,
                "authored",
                null);

        Skill off = service.setEnabled(row.id(), false);
        assertThat(off.enabled()).isFalse();

        Skill again = service.get(row.id());
        assertThat(again.enabled()).isFalse();

        Skill back = service.setEnabled(row.id(), true);
        assertThat(back.enabled()).isTrue();
    }

    @Test
    void forRepoSkipsDisabledSkill()
    {
        String repo = "acme/" + UUID.randomUUID();
        Skill row = service.create(
                "repo",
                repo,
                null,
                uniqueName("skip"),
                "loads when reviewing a PR on this repo",
                "House style: prefer expression-bodied lambdas.",
                "rubric",
                null,
                false,
                "authored",
                null);

        assertThat(service.forRepo(repo)).map(Skill::id).hasValue(row.id());

        service.setEnabled(row.id(), false);
        assertThat(service.forRepo(repo)).isEmpty();
    }

    @Test
    void setEnabledOnMissingIdSurfacesAs404()
    {
        // Spring's ResponseStatusException carries the 404 — match on
        // the message rather than the exact type so the test stays
        // robust to Spring wrapping.
        assertThatThrownBy(() -> service.setEnabled(987654321L, false))
                .hasMessageContaining("not found");
    }

    private static String uniqueName(String prefix)
    {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
