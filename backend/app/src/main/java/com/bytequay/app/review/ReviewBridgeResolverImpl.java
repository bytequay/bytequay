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
package com.bytequay.app.review;

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.app.context.ContextSpecCheckTools;
import com.bytequay.app.memory.MemoryHarvestTools;
import com.bytequay.app.service.goal.GoalResearchTools;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Answers the provider broker from durable review, spec check, harvest or research ownership. Looked up lazily: the broker is built
 * before the review service, whose task execution depends on the drivers the broker serves.
 */
@Component
public class ReviewBridgeResolverImpl
        implements BridgeResolver<ReviewBridge>
{
    private final ObjectProvider<ReviewTools> tools;
    private final ObjectProvider<ContextSpecCheckTools> checks;
    private final ObjectProvider<MemoryHarvestTools> harvests;
    private final ObjectProvider<GoalResearchTools> research;
    private final AgentTaskStore tasks;

    public ReviewBridgeResolverImpl(ObjectProvider<ReviewTools> tools, ObjectProvider<ContextSpecCheckTools> checks, ObjectProvider<MemoryHarvestTools> harvests,
            ObjectProvider<GoalResearchTools> research, AgentTaskStore tasks)
    {
        this.tools = requireNonNull(tools, "tools is null");
        this.checks = requireNonNull(checks, "checks is null");
        this.harvests = requireNonNull(harvests, "harvests is null");
        this.research = requireNonNull(research, "research is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @Override
    public Optional<ReviewBridge> resolve(String taskId)
    {
        return checks.getObject().bridge(taskId)
                .or(() -> harvests.getObject().bridge(taskId))
                .or(() -> research.getObject().bridge(taskId))
                .or(() -> tools.getObject().bridge(taskId))
                .or(() -> tasks.find(TaskId.valueOf(taskId))
                .filter(task -> task.interactive() && task.state() == TaskState.OPEN)
                .flatMap(task -> task.localReviewHead().map(head -> new ReviewBridge(ImmutableList.of(),
                        "This task is in private local review of commit " + head + ". "
                                + "You may edit code and write or run verification tests. Keep every experiment uncommitted. "
                                + "Never commit, change branch history, push, publish, or use development delivery tools. "
                                + "Review comments refer to the committed review diff, never to experiment line numbers. "
                                + "Only the user can finish local review through ByteQuay before development resumes."))));
    }
}
