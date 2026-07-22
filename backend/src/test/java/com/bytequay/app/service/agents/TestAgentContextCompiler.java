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
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.service.skills.AgentResource;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.skills.ByteQuaySkillSelector;
import com.bytequay.app.service.skills.ManagedSkill;
import com.bytequay.app.service.skills.ManagedSkillPolicy;
import com.bytequay.app.service.tools.SecurityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAgentContextCompiler
{
    private final AgentContextCompiler compiler = new AgentContextCompiler(
            new ManagedSkillPolicy(), new ToolExposurePolicy());

    @Test
    void cliAndApiLanesResolveTheSameTaskContract()
    {
        ThreadTurn turn = turn("task-1");

        ResolvedAgentContext cli = compiler.resolve(
                ThreadKind.CLI_AGENT, turn, StageType.DEVELOPMENT_STAGE);
        ResolvedAgentContext api = compiler.resolve(
                ThreadKind.LOGIC_LOOP, turn, StageType.DEVELOPMENT_STAGE);

        assertThat(api).isEqualTo(cli);
        assertThat(cli.roleReference()).isEqualTo("task@1");
        assertThat(cli.skillNames()).containsExactly(
                "task-execution", "codegraph-first", "ponytail", "caveman");
        assertThat(cli.toolNames())
                .contains("codegraph_explore", "run_checks")
                .doesNotContain("push", "list_tools", "list_skills", "load_skill");
    }

    @Test
    void remoteCiReadsAreAvailableEquallyInCliAndApiLanes()
    {
        ThreadTurn turn = turn("task-1");
        for (StageType stage : List.of(
                StageType.REMOTE_DEVELOPMENT_STAGE,
                StageType.REVIEW_MONITOR_STAGE,
                StageType.CI_FIXING_STAGE)) {
            ResolvedAgentContext cli = compiler.resolve(ThreadKind.CLI_AGENT, turn, stage);
            ResolvedAgentContext api = compiler.resolve(ThreadKind.LOGIC_LOOP, turn, stage);

            assertThat(api).isEqualTo(cli);
            assertThat(cli.toolNames()).contains("read_remote_pr_status", "read_ci_log");
        }

        assertThat(compiler.resolve(
                ThreadKind.CLI_AGENT, turn, StageType.DEVELOPMENT_STAGE).toolNames())
                .doesNotContain("read_ci_log");
    }

    @Test
    void roleIsThePermissionAndResourceCeiling()
    {
        ResolvedAgentContext trunk = compiler.resolve(
                ThreadKind.CLI_AGENT, turn(null), null);
        ResolvedAgentContext task = compiler.resolve(
                ThreadKind.CLI_AGENT, turn("task-1"), StageType.DEVELOPMENT_STAGE);

        assertThat(trunk.role()).isEqualTo(ByteQuayRole.TRUNK);
        assertThat(trunk.capabilities())
                .contains(SecurityType.CODE_READ, SecurityType.TASK_MANAGE)
                .doesNotContain(SecurityType.CODE_WRITE, SecurityType.CODE_EXEC,
                        SecurityType.SKILL_USE, SecurityType.TOOL_DISCOVER);
        assertThat(trunk.resources())
                .contains(AgentResource.WORKSPACE_DOCUMENT, AgentResource.CODEGRAPH)
                .doesNotContain(AgentResource.WORKTREE, AgentResource.CI);

        assertThat(task.capabilities())
                .contains(SecurityType.CODE_WRITE, SecurityType.CODE_EXEC, SecurityType.GIT_PUSH)
                .doesNotContain(SecurityType.SKILL_USE, SecurityType.TOOL_DISCOVER);
        assertThat(task.resources()).contains(AgentResource.WORKTREE, AgentResource.CI);
    }

    @Test
    void resolvedAuthoredBodiesTravelInTheSameCliAndApiContract()
    {
        ByteQuaySkillSelector selector = mock(ByteQuaySkillSelector.class);
        when(selector.select(anyList(), any(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(new ManagedSkill("authored", "AUTHORED BODY")));
        AgentContextCompiler withSelector = new AgentContextCompiler(
                new ManagedSkillPolicy(), new ToolExposurePolicy(), selector);

        ResolvedAgentContext cli = withSelector.resolve(
                ThreadKind.CLI_AGENT, turn("task-1"), StageType.DEVELOPMENT_STAGE, "/repo");
        ResolvedAgentContext api = withSelector.resolve(
                ThreadKind.LOGIC_LOOP, turn("task-1"), StageType.DEVELOPMENT_STAGE, "/repo");

        assertThat(api).isEqualTo(cli);
        assertThat(cli.skillNames()).containsExactly("authored");
        assertThat(cli.skills()).containsExactly(new ManagedSkill("authored", "AUTHORED BODY"));
    }

    @Test
    void everyRoleAndStageStaysWithinHardContextBounds()
    {
        for (ThreadKind kind : List.of(
                ThreadKind.CLI_AGENT, ThreadKind.LOGIC_LOOP, ThreadKind.BRAIN_AGENT)) {
            for (StageType stage : StageType.values()) {
                ResolvedAgentContext context = compiler.resolve(kind, turn("task-1"), stage);
                assertThat(context.skillNames()).hasSizeLessThanOrEqualTo(
                        AgentContextCompiler.MAX_ACTIVE_SKILLS);
                assertThat(context.toolNames()).hasSizeLessThanOrEqualTo(
                        ToolExposurePolicy.MAX_ACTIVE_TOOLS);
            }
        }
    }

    @Test
    void promptOrderAndSelectionAreProviderNeutralAndBounded()
    {
        AgentContextCompiler.CompiledPrompt prompt = AgentContextCompiler.compilePrompt(
                "ROLE", "WORKSPACE", "MEMORY",
                List.of(new ManagedSkill("skill-a", "SKILL BODY")));

        assertThat(prompt.skillNames()).containsExactly("skill-a");
        assertThat(prompt.systemPrompt()).containsSubsequence(
                "ROLE", "# Workspace", "WORKSPACE",
                "# Workspace memory and knowledge", "MEMORY",
                "# ByteQuay managed runtime skills", "SKILL BODY");
        assertThat(prompt.characterCount()).isEqualTo(prompt.systemPrompt().length());
    }

    @Test
    void promptRejectsAnUnboundedSkillSetOrSystemBody()
    {
        List<ManagedSkill> tooMany = IntStream.range(0, 6)
                .mapToObj(i -> new ManagedSkill("skill-" + i, "body"))
                .toList();

        assertThatThrownBy(() -> AgentContextCompiler.compilePrompt(
                "role", "workspace", "memory", tooMany))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill context exceeds 5");
        assertThatThrownBy(() -> AgentContextCompiler.compilePrompt(
                "x".repeat(AgentContextCompiler.MAX_SYSTEM_PROMPT_CHARS + 1),
                "", "", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compiled agent context exceeds");
    }

    private static ThreadTurn turn(String taskId)
    {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        return new ThreadTurn(
                "turn-1", "thread-1", taskId, ThreadResourceLane.CLI,
                ThreadTurnStatus.QUEUED, "implement", now, now, null, null, null,
                TurnInitiator.user(), null, ThreadScope.of(taskId, null));
    }
}
