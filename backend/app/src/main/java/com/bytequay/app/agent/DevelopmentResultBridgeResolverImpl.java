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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.core.review.AgentReviewStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Resolves only the accepted turn's selection, never mutable application or task defaults. */
@Component
public class DevelopmentResultBridgeResolverImpl
        implements BridgeResolver<DevelopmentResultBridge>
{
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentReviewStore reviews;
    private final ObjectProvider<DevelopmentBranchService> branches;

    public DevelopmentResultBridgeResolverImpl(AgentTaskStore tasks, AgentTurnStore turns, AgentReviewStore reviews, ObjectProvider<DevelopmentBranchService> branches)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.branches = requireNonNull(branches, "branches is null");
    }

    @Override
    public Optional<DevelopmentResultBridge> resolve(String taskId)
    {
        TaskId id = TaskId.valueOf(taskId);
        return tasks.find(id)
                .filter(task -> task.interactive() && task.state() == TaskState.OPEN)
                .filter(task -> reviews.findReviewByTask(taskId).isEmpty())
                .flatMap(task -> turns.findActive(id))
                .filter(turn -> turn.settings().flatMap(AgentTurnSettings::developmentAutomation).filter(DevelopmentAutomation::enabled).isPresent()
                        || turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent())
                .map(turn -> {
                    List<AgentTurn> history = turns.list(id);
                    boolean canRename = turn.developmentOrigin().isEmpty() && history.size() == 1 && history.getFirst().id().equals(turn.id());
                    return new DevelopmentResultBridge(turn.id().toString(),
                            DevelopmentResultBridge.INSTRUCTION + (canRename ? DevelopmentResultBridge.BRANCH_INSTRUCTION : ""),
                            turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent(),
                            canRename ? Optional.of(suffix -> branches.getObject().rename(id, turn.id(), suffix)) : Optional.empty());
                });
    }
}
