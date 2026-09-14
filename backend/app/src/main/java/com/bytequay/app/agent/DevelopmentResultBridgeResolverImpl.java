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

import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultBridgeResolver;
import com.bytequay.core.review.AgentReviewStore;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Resolves only the accepted turn's selection, never mutable application or task defaults. */
@Component
public class DevelopmentResultBridgeResolverImpl
        implements DevelopmentResultBridgeResolver
{
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentReviewStore reviews;

    public DevelopmentResultBridgeResolverImpl(AgentTaskStore tasks, AgentTurnStore turns, AgentReviewStore reviews)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    @Override
    public Optional<DevelopmentResultBridge> resolve(String taskId)
    {
        TaskId id = TaskId.valueOf(taskId);
        return tasks.find(id)
                .filter(task -> task.interactive() && task.state() == TaskState.OPEN && task.localReviewHead().isEmpty())
                .filter(task -> reviews.findReviewByTask(taskId).isEmpty())
                .flatMap(task -> turns.findActive(id))
                .filter(turn -> turn.settings().flatMap(AgentTurnSettings::developmentAutomation).filter(DevelopmentAutomation::enabled).isPresent()
                        || turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent())
                .map(turn -> new DevelopmentResultBridge(turn.id().toString(), DevelopmentResultBridge.INSTRUCTION,
                        turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent()));
    }
}
