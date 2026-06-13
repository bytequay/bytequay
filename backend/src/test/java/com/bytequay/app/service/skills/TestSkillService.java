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
 * Exercises the usage-derived query / by-role view and the per-repo
 * default flip on {@link SkillService}, against the real Flyway-migrated
 * SQLite schema.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSkillService
{
    @Autowired
    private SkillService service;

    @Test
    void setDefaultRefusesABuildSkill()
    {
        Skill build = service.create(
                "global", null, null, uniqueName("dev"),
                "loads when editing", "body", "library", "build", null,
                false, "authored", null);

        assertThatThrownBy(() -> service.setDefault(build.id()))
                .hasMessageContaining("default_only_for_review_skills");
    }

    @Test
    void setDefaultFlipsThePriorDefaultInTheSameRepo()
    {
        String repo = "acme/" + UUID.randomUUID();
        Skill first = reviewSkill(repo, "first");
        Skill second = reviewSkill(repo, "second");

        service.setDefault(first.id());
        assertThat(service.get(first.id()).isDefault()).isTrue();

        service.setDefault(second.id());
        // The second wins; the first is cleared — at most one default
        // per repo per surface.
        assertThat(service.get(second.id()).isDefault()).isTrue();
        assertThat(service.get(first.id()).isDefault()).isFalse();
    }

    @Test
    void byRoleDerivesUsageFromTheRole()
    {
        Skill dev = service.create(
                "global", null, null, uniqueName("byrole-dev"),
                "loads when editing", "body", "library", "build", null,
                false, "authored", null);
        Skill review = reviewSkill(null, "byrole-rev");

        assertThat(service.byRole("trunk")).extracting(Skill::id).contains(dev.id());
        assertThat(service.byRole("trunk")).extracting(Skill::id).doesNotContain(review.id());
        assertThat(service.byRole("reviewer")).extracting(Skill::id).contains(review.id());
        assertThat(service.byRole("reviewer")).extracting(Skill::id).doesNotContain(dev.id());
        assertThatThrownBy(() -> service.byRole("bogus"))
                .hasMessageContaining("trunk|task|reviewer|lead");
    }

    @Test
    void queryFiltersByUsageKindAndSubstring()
    {
        Skill dev = service.create(
                "global", null, null, uniqueName("zzq-dev"),
                "loads when editing", "body", "library", "build", null,
                false, "authored", null);

        assertThat(service.query("development", "global", null, "zzq-dev"))
                .extracting(Skill::id).contains(dev.id());
        // The development filter excludes review rows.
        assertThat(service.query("review", null, null, "zzq-dev"))
                .extracting(Skill::id).doesNotContain(dev.id());
    }

    private Skill reviewSkill(String repo, String prefix)
    {
        String scope = repo == null ? "global" : "repo";
        return service.create(
                scope, repo, null, uniqueName(prefix),
                "loads when reviewing", "voice body", "persona", "review", null,
                false, "authored", null);
    }

    private static String uniqueName(String prefix)
    {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
