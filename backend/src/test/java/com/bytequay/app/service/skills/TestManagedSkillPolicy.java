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

import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.RoleCapabilities;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestManagedSkillPolicy
{
    private final ManagedSkillPolicy policy = new ManagedSkillPolicy();

    @Test
    void codingStageGetsTheByteQuayExecutionBundle()
    {
        assertThat(policy.skillNames(ThreadKind.CLI_AGENT, turn("user"), StageType.DEVELOPMENT_STAGE))
                .containsExactly("task-execution", "codegraph-first", "ponytail", CavemanPrompt.NAME);
    }

    @Test
    void apiCodingStageGetsTheSameByteQuayExecutionBundle()
    {
        assertThat(policy.skillNames(ThreadKind.LOGIC_LOOP, turn("user"), StageType.DEVELOPMENT_STAGE))
                .containsExactly("task-execution", "codegraph-first", "ponytail", CavemanPrompt.NAME);
    }

    @Test
    void brainReviewGetsItsReviewAndOutputSkills()
    {
        assertThat(policy.skillNames(ThreadKind.BRAIN_AGENT, turn("brain-review"), null))
                .containsExactly("ponytail-review", "codegraph-first", "i-have-adhd", CavemanPrompt.NAME);
    }

    @Test
    void everyTrunkTurnGetsPlannerAndOutputGuidance()
    {
        assertThat(policy.skillNames(
                ThreadKind.CLI_AGENT, turn("user", "what did you find?"), null))
                .containsExactly("trunk-planner", "codegraph-first", "i-have-adhd", CavemanPrompt.NAME);
    }

    @Test
    void explicitTrunkPlanningSourceGetsPlannerAndCodeGraphGuidance()
    {
        assertThat(policy.skillNames(
                ThreadKind.LOGIC_LOOP, turn("backlog-start", "please review this"), null))
                .containsExactly("trunk-planner", "codegraph-first", "i-have-adhd", CavemanPrompt.NAME);
    }

    @Test
    void normalBrainGetsCodeGraphGuidance()
    {
        assertThat(policy.skillNames(
                ThreadKind.BRAIN_AGENT, turn("user", "go ahead and implement this", "task-1"), null))
                .containsExactly("codegraph-first", "i-have-adhd", CavemanPrompt.NAME);
    }

    @Test
    void taskTurnWithoutStageGetsTheGenericTaskBundle()
    {
        assertThat(policy.skillNames(
                ThreadKind.CLI_AGENT, turn("user", "go ahead and implement this", "task-1"), null))
                .containsExactly("task-execution", "codegraph-first", CavemanPrompt.NAME);
    }

    @Test
    void managedSkillsDoNotChangeRoleCapabilities()
    {
        var before = RoleCapabilities.forRole(AgentRole.TASK);

        policy.skillNames(ThreadKind.CLI_AGENT, turn("user"), StageType.DEVELOPMENT_STAGE);

        assertThat(RoleCapabilities.forRole(AgentRole.TASK)).isEqualTo(before);
    }

    private static ThreadTurn turn(String source)
    {
        return turn(source, "input");
    }

    private static ThreadTurn turn(String source, String input)
    {
        return turn(source, input, null);
    }

    private static ThreadTurn turn(String source, String input, String taskId)
    {
        Instant now = Instant.parse("2026-07-10T00:00:00Z");
        return new ThreadTurn(
                "turn-1", "thread-1", taskId, ThreadResourceLane.CLI,
                ThreadTurnStatus.QUEUED, input, now, now, null, null, null,
                TurnInitiator.unattended(source), null, ThreadScope.of(taskId, null));
    }
}
