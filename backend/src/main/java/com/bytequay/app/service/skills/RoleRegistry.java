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

import com.bytequay.app.domain.Task;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.concepts.ConceptSpec;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.SecurityType;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * ByteQuay's source of truth for agent identity, character, permission ceiling,
 * and resource access. Providers receive rendered definitions; they never
 * discover role instructions from AGENTS.md, CLAUDE.md, or provider skill
 * directories.
 */
@Service
public class RoleRegistry
{
    public static final List<String> TASK_PREAMBLE_CONCEPTS = List.of(
            "task", "thread", "trunk", "pr", "ship", "next", "awaiting_review");

    private static final Set<SecurityType> TRUNK_CAPABILITIES = ImmutableSet.of(
            SecurityType.TASK_READ,
            SecurityType.TASK_MANAGE,
            SecurityType.CODE_READ,
            SecurityType.VCS_READ,
            SecurityType.MEMORY_READ,
            SecurityType.CONCEPT_USE,
            SecurityType.MCP);

    private static final Set<SecurityType> TASK_CAPABILITIES = ImmutableSet.of(
            SecurityType.CODE_READ,
            SecurityType.CODE_WRITE,
            SecurityType.CODE_EXEC,
            SecurityType.GIT_LOCAL,
            SecurityType.GIT_PUSH,
            SecurityType.VCS_READ,
            SecurityType.VCS_PUBLISH,
            SecurityType.TASK_READ,
            SecurityType.TASK_MANAGE,
            SecurityType.MEMORY_READ,
            SecurityType.MEMORY_WRITE,
            SecurityType.CONCEPT_USE,
            SecurityType.MCP);

    private static final Set<SecurityType> REVIEWER_CAPABILITIES = ImmutableSet.of(
            SecurityType.CODE_READ,
            SecurityType.VCS_READ,
            SecurityType.VCS_PUBLISH,
            SecurityType.MEMORY_READ,
            SecurityType.CONCEPT_USE,
            SecurityType.MCP);

    private static final Map<ByteQuayRole, RoleDefinition> DEFINITIONS = Map.of(
            ByteQuayRole.TRUNK, new RoleDefinition(
                    ByteQuayRole.TRUNK,
                    "1",
                    "A deliberate senior technical lead: curious, candid, and careful about "
                            + "assumptions, workload, and risk.",
                    """
                    Operate at planning altitude. Research the repository and decide what should be built;
                    do not edit files, run builds or tests, commit, push, or publish. Your only mutating
                    action is create_task, and only after the user explicitly approves the plan in a later
                    turn. Recall before asking: use recall_memory or lookup_memory when exposed instead of
                    asking the user to repeat a known DECISION or CONVENTION. Ask when a product decision
                    is missing. Treat tool restrictions as boundaries, not puzzles to work around.
                    """,
                    AgentRole.TRUNK,
                    TRUNK_CAPABILITIES,
                    ImmutableSet.of(AgentResource.WORKSPACE_DOCUMENT, AgentResource.WORKSPACE_MEMORY,
                            AgentResource.THREAD, AgentResource.REPOSITORY,
                            AgentResource.CODEGRAPH, AgentResource.PULL_REQUEST)),
            ByteQuayRole.TASK, new RoleDefinition(
                    ByteQuayRole.TASK,
                    "1",
                    "A pragmatic senior developer who owns implementation quality and communicates "
                            + "decisions plainly.",
                    """
                    Implement the approved task inside its worktree. Ground the plan in actual code, make
                    reversible technical choices yourself, and ask only when a missing decision changes
                    user-visible behaviour or scope. Permissions and publish gates are enforced by
                    ByteQuay. Recall before asking: use recall_memory or lookup_memory when exposed instead
                    of asking the user to repeat a known DECISION or CONVENTION. Never switch roles or
                    bypass a rejected tool with a provider-native route.
                    """,
                    AgentRole.TASK,
                    TASK_CAPABILITIES,
                    ImmutableSet.of(AgentResource.WORKSPACE_DOCUMENT, AgentResource.WORKSPACE_MEMORY,
                            AgentResource.THREAD, AgentResource.TASK, AgentResource.STAGE,
                            AgentResource.REPOSITORY, AgentResource.WORKTREE,
                            AgentResource.CODEGRAPH, AgentResource.PULL_REQUEST, AgentResource.CI)),
            ByteQuayRole.BRAIN, new RoleDefinition(
                    ByteQuayRole.BRAIN,
                    "1",
                    "A concise, skeptical task planner and reviewer who separates evidence from guesses.",
                    """
                    Plan and review one developer task. You are read-only with respect to source code and
                    external systems. You may write only the local plan and review artifacts explicitly
                    exposed for this turn. Use precise task evidence and do not speculate when an available
                    resource can answer the question.
                    """,
                    AgentRole.TRUNK,
                    TRUNK_CAPABILITIES,
                    ImmutableSet.of(AgentResource.WORKSPACE_DOCUMENT, AgentResource.WORKSPACE_MEMORY,
                            AgentResource.TASK, AgentResource.STAGE, AgentResource.REPOSITORY,
                            AgentResource.CODEGRAPH, AgentResource.PULL_REQUEST, AgentResource.CI)),
            ByteQuayRole.REVIEWER, new RoleDefinition(
                    ByteQuayRole.REVIEWER,
                    "1",
                    "A precise code reviewer who prioritizes correctness, security, and actionable evidence.",
                    """
                    Review without editing the implementation. Publish or resolve review material only
                    through the explicitly exposed ByteQuay approval path.
                    """,
                    AgentRole.REVIEWER,
                    REVIEWER_CAPABILITIES,
                    ImmutableSet.of(AgentResource.WORKSPACE_DOCUMENT, AgentResource.WORKSPACE_MEMORY,
                            AgentResource.REPOSITORY, AgentResource.CODEGRAPH,
                            AgentResource.PULL_REQUEST)));

    /** Keep old entries here when a role version advances so stored task
     * references remain reproducible. */
    private static final Map<String, RoleDefinition> DEFINITIONS_BY_REFERENCE = Map.of(
            "trunk@1", DEFINITIONS.get(ByteQuayRole.TRUNK),
            "task@1", DEFINITIONS.get(ByteQuayRole.TASK),
            "brain@1", DEFINITIONS.get(ByteQuayRole.BRAIN),
            "reviewer@1", DEFINITIONS.get(ByteQuayRole.REVIEWER));

    private final ConceptRegistry concepts;

    public RoleRegistry(ConceptRegistry concepts)
    {
        this.concepts = requireNonNull(concepts, "concepts is null");
    }

    public static RoleDefinition definition(ByteQuayRole role)
    {
        RoleDefinition definition = DEFINITIONS.get(role);
        if (definition == null) {
            throw new IllegalArgumentException("unknown ByteQuay role: " + role);
        }
        return definition;
    }

    public static RoleDefinition definitionFor(AgentRole role)
    {
        return switch (role) {
            case TRUNK -> definition(ByteQuayRole.TRUNK);
            case TASK -> definition(ByteQuayRole.TASK);
            case REVIEWER -> definition(ByteQuayRole.REVIEWER);
            case ANY -> throw new IllegalArgumentException("ANY is not an executable role");
        };
    }

    public static RoleDefinition definition(String reference)
    {
        RoleDefinition definition = DEFINITIONS_BY_REFERENCE.get(reference);
        if (definition == null) {
            throw new IllegalArgumentException("unknown ByteQuay role version: " + reference);
        }
        return definition;
    }

    public String trunkTemplate()
    {
        return render(definition(ByteQuayRole.TRUNK), null);
    }

    public String brainTemplate()
    {
        return render(definition(ByteQuayRole.BRAIN), null);
    }

    public String taskRoleReference()
    {
        return definition(ByteQuayRole.TASK).reference();
    }

    public String generateForTask(String repo, String branch, String taskId, String baseBranch)
    {
        return render(definition(ByteQuayRole.TASK),
                taskContext(repo, branch, taskId, baseBranch));
    }

    /** Resolve a versioned role reference, with a raw-body fallback for old rows. */
    public String resolveForTask(Task task)
    {
        requireNonNull(task, "task is null");
        String stored = task.roleSkill();
        if (stored != null && !stored.isBlank()) {
            if (stored.startsWith(ByteQuayRole.TASK.id() + "@")) {
                return render(definition(stored), taskContext(task));
            }
            return stored; // legacy frozen prompt body
        }
        return render(definition(ByteQuayRole.TASK), taskContext(task));
    }

    public String buildConceptPreamble()
    {
        StringBuilder out = new StringBuilder("Vocabulary (the system uses these exact terms):\n");
        for (String name : TASK_PREAMBLE_CONCEPTS) {
            Optional<ConceptSpec> spec = concepts.byName(name);
            out.append("- `").append(name).append("` — ")
                    .append(spec.map(ConceptSpec::oneLineDefinition)
                            .filter(s -> !s.isEmpty())
                            .orElse("(definition unavailable)"))
                    .append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String render(RoleDefinition definition, String context)
    {
        StringBuilder out = new StringBuilder()
                .append("# ByteQuay role · ")
                .append(capitalise(definition.role().id()))
                .append("\n\nRole version: `")
                .append(definition.reference())
                .append("`\n\nCharacter: ")
                .append(definition.character())
                .append("\n\n")
                .append(definition.instructions().strip());
        if (context != null && !context.isBlank()) {
            out.append("\n\n").append(context.strip());
        }
        return out.toString();
    }

    private String taskContext(Task task)
    {
        return taskContext(null, task.branchName(), task.id(), task.baseBranch());
    }

    private String taskContext(String repo, String branch, String taskId, String baseBranch)
    {
        return """
                Task context:
                - Repository: %s
                - Branch: %s (cut from `%s`)
                - Task id: %s

                %s
                """.formatted(
                nvl(repo, "resolve with read_current_repository"),
                nvl(branch, "(unset)"),
                nvl(baseBranch, "(unset)"),
                nvl(taskId, "(unset)"),
                buildConceptPreamble());
    }

    private static String capitalise(String value)
    {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String nvl(String value, String fallback)
    {
        return value == null || value.isBlank() ? fallback : value;
    }
}
