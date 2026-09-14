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
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewBridgeResolver;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Answers the provider broker from durable review facts. Looked up lazily: the broker is built
 * before the review service, whose task execution depends on the drivers the broker serves.
 */
@Component
public class ReviewBridgeResolverImpl
        implements ReviewBridgeResolver
{
    private final ObjectProvider<ReviewTools> tools;
    private final AgentTaskStore tasks;

    public ReviewBridgeResolverImpl(ObjectProvider<ReviewTools> tools, AgentTaskStore tasks)
    {
        this.tools = requireNonNull(tools, "tools is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @Override
    public Optional<ReviewBridge> resolve(String taskId)
    {
        return tools.getObject().bridge(taskId).or(() -> tasks.find(TaskId.valueOf(taskId))
                .filter(task -> task.interactive() && task.state() == TaskState.OPEN)
                .flatMap(task -> task.localReviewHead().map(head -> new ReviewBridge(ImmutableList.of(),
                        "This task is in private local review of commit " + head + ". "
                                + "You may edit code and write or run verification tests. Keep every experiment uncommitted. "
                                + "Never commit, change branch history, push, publish, or use development delivery tools. "
                                + "Review comments refer to the committed PR diff, never to experiment line numbers. "
                                + "Only the user can finish local review through ByteQuay before development resumes."))));
    }
}
