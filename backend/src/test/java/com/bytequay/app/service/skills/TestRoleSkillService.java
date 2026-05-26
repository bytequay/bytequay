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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestRoleSkillService
{
    @Test
    void trunkTemplateLoadsFromTheClasspath()
    {
        RoleSkillService service = new RoleSkillService();

        assertThat(service.trunkTemplate())
                .contains("Role · Trunk")
                .contains("create_task");
    }

    @Test
    void taskTemplateInterpolatesAllPlaceholders()
    {
        RoleSkillService service = new RoleSkillService();

        String body = service.generateForTask(
                "acme/widgets", "feature/x", "task-123", "main");

        assertThat(body).contains("acme/widgets");
        assertThat(body).contains("feature/x");
        assertThat(body).contains("task-123");
        assertThat(body).contains("cut from `main`");
        // Placeholders are all replaced — no stray `{{…}}` left over.
        assertThat(body).doesNotContain("{{");
    }

    @Test
    void taskTemplateFallsBackToUnsetWhenAFieldIsMissing()
    {
        RoleSkillService service = new RoleSkillService();

        String body = service.generateForTask(null, "branch", "task-1", null);

        assertThat(body).contains("(unset)");
        assertThat(body).doesNotContain("{{");
    }

    @Test
    void taskTemplateIsByteStableForTheSameInput()
    {
        // Cache stability: the frozen role skill is the first system
        // block; two reads of the same template must produce
        // byte-identical bytes so the prefix stays warm.
        RoleSkillService service = new RoleSkillService();
        String first = service.generateForTask("acme/x", "branch", "id", "main");
        String second = service.generateForTask("acme/x", "branch", "id", "main");

        assertThat(first).isEqualTo(second);
    }
}
