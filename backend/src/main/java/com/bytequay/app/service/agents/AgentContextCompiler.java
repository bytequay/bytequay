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
package com.bytequay.app.service.agents;

import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.skills.ByteQuaySkillSelector;
import com.bytequay.app.service.skills.ManagedSkill;
import com.bytequay.app.service.skills.ManagedSkillPolicy;
import com.bytequay.app.service.skills.ManagedSkillPrompt;
import com.bytequay.app.service.skills.RoleDefinition;
import com.bytequay.app.service.skills.RoleRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Compiles the one provider-neutral contract used by CLI and API turns. */
@Component
public class AgentContextCompiler
{
    public static final int MAX_ACTIVE_SKILLS = 5;
    public static final int MAX_SYSTEM_PROMPT_CHARS = 96_000;

    private final ManagedSkillPolicy skills;
    private final ToolExposurePolicy tools;
    private final ByteQuaySkillSelector selector;

    @Autowired
    public AgentContextCompiler(
            ManagedSkillPolicy skills,
            ToolExposurePolicy tools,
            ByteQuaySkillSelector selector)
    {
        this.skills = requireNonNull(skills, "skills is null");
        this.tools = requireNonNull(tools, "tools is null");
        this.selector = requireNonNull(selector, "selector is null");
    }

    /** Compatibility constructor for focused policy tests. */
    public AgentContextCompiler(ManagedSkillPolicy skills, ToolExposurePolicy tools)
    {
        this.skills = requireNonNull(skills, "skills is null");
        this.tools = requireNonNull(tools, "tools is null");
        this.selector = null;
    }

    public ResolvedAgentContext resolve(ThreadKind kind, ThreadTurn turn, StageType stageType)
    {
        return resolve(kind, turn, stageType, null);
    }

    public ResolvedAgentContext resolve(
            ThreadKind kind, ThreadTurn turn, StageType stageType, String workingDir)
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(turn, "turn is null");
        ByteQuayRole role = roleFor(kind, turn);
        RoleDefinition definition = RoleRegistry.definition(role);
        List<String> policySkills = skills.skillNames(kind, turn, stageType);
        List<ManagedSkill> selectedBodies = selector == null
                ? List.of()
                : selector.select(policySkills, role, turn.threadId(), workingDir,
                        turn.input(), MAX_ACTIVE_SKILLS);
        List<String> selectedSkills = selector == null
                ? policySkills
                : selectedBodies.stream().map(ManagedSkill::name).toList();
        if (selectedSkills.size() > MAX_ACTIVE_SKILLS) {
            throw new IllegalStateException("skill context exceeds " + MAX_ACTIVE_SKILLS
                    + " entries for " + role + "/" + stageType);
        }
        if (new HashSet<>(selectedSkills).size() != selectedSkills.size()) {
            throw new IllegalStateException("duplicate managed skill in resolved context: "
                    + selectedSkills);
        }
        return new ResolvedAgentContext(
                role,
                definition.version(),
                definition.permissionRole(),
                stageType,
                definition.capabilities(),
                selectedSkills,
                selectedBodies,
                definition.resources(),
                tools.activeTools(role, stageType));
    }

    /**
     * Assemble provider-neutral system instructions. Provider adapters decide
     * whether this string travels as a system field or an explicit CLI prompt.
     */
    public static CompiledPrompt compilePrompt(
            String rolePrompt,
            String workspaceDocument,
            String workspaceMemory,
            List<ManagedSkill> selectedSkills)
    {
        List<ManagedSkill> safeSkills = selectedSkills == null ? List.of() : List.copyOf(selectedSkills);
        if (safeSkills.size() > MAX_ACTIVE_SKILLS) {
            throw new IllegalStateException("skill context exceeds " + MAX_ACTIVE_SKILLS + " entries");
        }
        StringBuilder out = new StringBuilder();
        append(out, rolePrompt);
        appendSection(out, "Workspace", workspaceDocument);
        appendSection(out, "Workspace memory and knowledge", workspaceMemory);
        append(out, ManagedSkillPrompt.render(safeSkills));
        String prompt = out.toString().strip();
        if (prompt.length() > MAX_SYSTEM_PROMPT_CHARS) {
            throw new IllegalStateException("compiled agent context exceeds "
                    + MAX_SYSTEM_PROMPT_CHARS + " characters");
        }
        return new CompiledPrompt(
                prompt,
                safeSkills.stream().map(ManagedSkill::name).toList(),
                prompt.length());
    }

    private static ByteQuayRole roleFor(ThreadKind kind, ThreadTurn turn)
    {
        if (kind == ThreadKind.BRAIN_AGENT) {
            return ByteQuayRole.BRAIN;
        }
        if (turn.initiator() != null
                && TurnInitiator.SOURCE_PARKED_STEERING
                .equals(turn.initiator().source())) {
            return ByteQuayRole.BRAIN;
        }
        return turn.taskId() == null ? ByteQuayRole.TRUNK : ByteQuayRole.TASK;
    }

    private static void appendSection(StringBuilder out, String heading, String body)
    {
        if (body != null && !body.isBlank()) {
            append(out, "# " + heading + "\n\n" + body.strip());
        }
    }

    private static void append(StringBuilder out, String body)
    {
        if (body == null || body.isBlank()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append("\n\n");
        }
        out.append(body.strip());
    }

    public record CompiledPrompt(String systemPrompt, List<String> skillNames, int characterCount) {}
}
