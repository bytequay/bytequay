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
import com.bytequay.app.domain.ThreadTurn;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ManagedSkillPolicy
{
    static final String PONYTAIL = "ponytail";
    static final String PONYTAIL_REVIEW = "ponytail-review";
    static final String TRUNK_PLANNER = "trunk-planner";
    static final String CODEGRAPH_FIRST = "codegraph-first";
    static final String TASK_EXECUTION = "task-execution";
    private static final String BRAIN_REVIEW_SOURCE = "brain-review";
    private static final Set<String> TRUNK_PLANNING_SOURCES = Set.of(
            "backlog-start",
            "create-task",
            "trunk-planning");
    private static final List<String> TRUNK_PLANNING_PHRASES = List.of(
            "create_task",
            "create a task",
            "create task",
            "cut a task",
            "cut task",
            "new task",
            "start a task",
            "start work",
            "kick off",
            "go ahead",
            "go for it",
            "do it",
            "implement",
            "fix ",
            "add ",
            "build ",
            "wire ",
            "split ",
            "handle this",
            "work on this",
            "turn this into a task");
    private static final Set<StageType> CODING_STAGE_TYPES = Set.of(
            StageType.DEVELOPMENT_STAGE,
            StageType.REMOTE_DEVELOPMENT_STAGE,
            StageType.CI_FIXING_STAGE,
            StageType.BRANCH_GUARD_STAGE);

    public List<String> skillNames(ThreadKind kind, ThreadTurn turn, StageType stageType)
    {
        if (kind == ThreadKind.BRAIN_AGENT
                && turn != null
                && turn.initiator() != null
                && BRAIN_REVIEW_SOURCE.equals(turn.initiator().source())) {
            return List.of(PONYTAIL_REVIEW, CODEGRAPH_FIRST, CavemanPrompt.NAME);
        }
        if (isTrunkPlanningTurn(kind, turn, stageType)) {
            return List.of(TRUNK_PLANNER, CODEGRAPH_FIRST, CavemanPrompt.NAME);
        }
        if (stageType != null && CODING_STAGE_TYPES.contains(stageType)) {
            return List.of(TASK_EXECUTION, CODEGRAPH_FIRST, PONYTAIL, CavemanPrompt.NAME);
        }
        if (turn != null && turn.taskId() != null) {
            return List.of(TASK_EXECUTION, CODEGRAPH_FIRST, CavemanPrompt.NAME);
        }
        if (kind != ThreadKind.BRAIN_AGENT) {
            return List.of(CODEGRAPH_FIRST, CavemanPrompt.NAME);
        }
        return List.of(CODEGRAPH_FIRST, CavemanPrompt.NAME);
    }

    private static boolean isTrunkPlanningTurn(ThreadKind kind, ThreadTurn turn, StageType stageType)
    {
        if (kind == ThreadKind.BRAIN_AGENT || stageType != null || turn == null || turn.taskId() != null) {
            return false;
        }
        if (turn.initiator() != null && TRUNK_PLANNING_SOURCES.contains(turn.initiator().source())) {
            return true;
        }
        String input = turn.input() == null ? "" : turn.input().toLowerCase(Locale.ROOT);
        return TRUNK_PLANNING_PHRASES.stream().anyMatch(input::contains);
    }
}
