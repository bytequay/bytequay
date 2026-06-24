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

import com.bytequay.app.service.concepts.ConceptRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class TestRoleSkillService
{
    private static RoleSkillService bootedService()
            throws IOException
    {
        ConceptRegistry concepts = new ConceptRegistry();
        concepts.scan();
        return new RoleSkillService(concepts);
    }

    @Test
    void trunkTemplateLoadsFromTheClasspath()
            throws IOException
    {
        RoleSkillService service = bootedService();

        assertThat(service.trunkTemplate())
                .contains("Role · Trunk")
                .contains("read-only");
    }

    @Test
    void taskTemplateInterpolatesAllPlaceholders()
            throws IOException
    {
        RoleSkillService service = bootedService();

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
            throws IOException
    {
        RoleSkillService service = bootedService();

        String body = service.generateForTask(null, "branch", "task-1", null);

        assertThat(body).contains("(unset)");
        assertThat(body).doesNotContain("{{");
    }

    @Test
    void taskTemplateIsByteStableForTheSameInput()
            throws IOException
    {
        // Cache stability: the frozen role skill is the first system
        // block; two reads of the same template must produce
        // byte-identical bytes so the prefix stays warm.
        RoleSkillService service = bootedService();
        String first = service.generateForTask("acme/x", "branch", "id", "main");
        String second = service.generateForTask("acme/x", "branch", "id", "main");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void taskTemplateCarriesTheRecallBeforeAskInstruction()
            throws IOException
    {
        // Phase F (memory axis): every task-role render must include
        // the recall_memory discipline. This is the load-bearing
        // sentence that turns memory into an active read instead of
        // a passive store; if it disappears from the template, the
        // policy isn't enforced anywhere else.
        RoleSkillService service = bootedService();

        String body = service.generateForTask("acme/x", "b", "t", "main");

        assertThat(body).contains("Recall before asking");
        assertThat(body).contains("recall_memory");
        assertThat(body).contains("DECISION");
        assertThat(body).contains("CONVENTION");
    }

    @Test
    void trunkTemplateCarriesTheRecallBeforeAskInstruction()
            throws IOException
    {
        RoleSkillService service = bootedService();

        assertThat(service.trunkTemplate())
                .contains("Recall before asking")
                .contains("recall_memory")
                .contains("lookup_memory");
    }

    @Test
    void taskTemplateInlinesTheConceptPreamble()
            throws IOException
    {
        // The preamble bullets each carry the seed concept's name in
        // backticks plus the one-line definition. Pin the names so a
        // future change to TASK_PREAMBLE_CONCEPTS is caught by the
        // test rather than silently changing every new task's prefix.
        RoleSkillService service = bootedService();

        String body = service.generateForTask("acme/x", "b", "t", "main");

        assertThat(body).contains("Vocabulary (the system uses these exact terms):");
        assertThat(body).contains("`task` — One unit of work within a thread");
        assertThat(body).contains("`thread` — A long-lived AI conversation");
        assertThat(body).contains("`trunk` — The long-lived assistant thread");
        assertThat(body).contains("`pr` — A GitHub pull request");
        assertThat(body).contains("`ship` — Finalise the current task");
        assertThat(body).contains("`next` — Park the current task at AWAITING_REVIEW");
        assertThat(body).contains("`awaiting_review` — A task whose agent finished");
    }
}
