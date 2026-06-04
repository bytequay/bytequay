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
import com.bytequay.app.service.concepts.ConceptSpec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Resolves the role skill text the CLI / API lane feeds as the
 * system role block.
 *
 * <ul>
 *   <li><strong>Trunk</strong> = fixed template loaded once from a
 *       classpath resource. Never edited at runtime — the trunk's
 *       boundary is the same for every thread.</li>
 *   <li><strong>Task</strong> = a template-rendered string composed
 *       at task creation from the task's repo / branch / id, then
 *       frozen onto the task row so the system prefix stays
 *       byte-stable across turns within the task (the provider's
 *       prefix cache hashes that prefix verbatim).</li>
 * </ul>
 *
 * <p>A small, stable preamble of {@code @Concept} one-liners is
 * baked into the task role skill at creation time so the agent
 * always knows what the system means by "task", "ship", or
 * "AWAITING_REVIEW" without a separate {@code lookup_term} call.
 * Because the preamble is composed once and frozen, the prefix
 * cache stays valid even if a definition is later edited via a
 * Saved View — the task that was created against the old wording
 * keeps it; new tasks pick up the new wording.
 *
 * <p>Reviewer / lead role composition lives with the review-panel
 * work; neither is built here.
 */
@Service
public class RoleSkillService
{
    private static final String TRUNK_RESOURCE = "skills/trunk-role.md";
    private static final String TASK_RESOURCE = "skills/task-role.md";

    /** The fixed catalogue of concepts whose one-liners get baked
     *  into the task role preamble. Stable across releases — adding
     *  or removing a name here is a deliberate, reviewed change
     *  because every existing frozen task carries the version it
     *  was composed against. */
    static final List<String> TASK_PREAMBLE_CONCEPTS = List.of(
            "task", "thread", "trunk", "pr",
            "ship", "next", "awaiting_review");

    private final String trunkTemplate;
    private final String taskTemplate;
    private final ConceptRegistry concepts;

    public RoleSkillService(ConceptRegistry concepts)
    {
        this.trunkTemplate = loadResource(TRUNK_RESOURCE);
        this.taskTemplate = loadResource(TASK_RESOURCE);
        this.concepts = requireNonNull(concepts, "concepts is null");
    }

    /**
     * The trunk role skill text. Constant for the lifetime of the
     * JVM; resolved once at service construction.
     */
    public String trunkTemplate()
    {
        return trunkTemplate;
    }

    /**
     * Compose a task role skill from the template and the task's
     * fixed context. The result is what callers freeze onto
     * {@code tasks.role_skill}; it never changes after task creation.
     *
     * @param repo        {@code owner/name} the task targets, or null
     *                    when the task isn't repo-scoped yet
     * @param branch      the task's branch
     * @param taskId      the task's primary key
     * @param baseBranch  the branch the task was cut from
     */
    public String generateForTask(String repo, String branch, String taskId, String baseBranch)
    {
        return taskTemplate
                .replace("{{repo}}", nvl(repo, "(unset)"))
                .replace("{{branch}}", nvl(branch, "(unset)"))
                .replace("{{taskId}}", nvl(taskId, "(unset)"))
                .replace("{{baseBranch}}", nvl(baseBranch, "(unset)"))
                .replace("{{conceptPreamble}}", buildConceptPreamble());
    }

    /** Render the preamble as a small bullet list — one line per
     *  concept, name in backticks followed by the one-line definition.
     *  Always deterministic for a given registry state because
     *  {@link #TASK_PREAMBLE_CONCEPTS} is a fixed ordered list. */
    private String buildConceptPreamble()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Vocabulary (the system uses these exact terms):\n");
        for (String name : TASK_PREAMBLE_CONCEPTS) {
            Optional<ConceptSpec> spec = concepts.byName(name);
            sb.append("- `").append(name).append("` — ");
            if (spec.isPresent() && !spec.get().oneLineDefinition().isEmpty()) {
                sb.append(spec.get().oneLineDefinition());
            }
            else {
                // Concept removed from the registry since the
                // preamble list was last reviewed — keep the bullet
                // so the list still tells the human "this name is in
                // the preamble" but flag the gap explicitly.
                sb.append("(definition unavailable)");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String nvl(String s, String fallback)
    {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String loadResource(String path)
    {
        ClassPathResource resource = new ClassPathResource(path);
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to load role skill resource " + path, e);
        }
    }
}
