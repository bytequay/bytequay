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

import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.app.context.ContextSpecCheckTools;
import com.bytequay.app.memory.MemoryHarvestTools;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Answers the provider broker from durable review, spec check or harvest ownership. Looked up lazily: the broker is built
 * before the review service, whose task execution depends on the drivers the broker serves.
 */
@Component
public class ReviewBridgeResolverImpl
        implements BridgeResolver<ReviewBridge>
{
    private final ObjectProvider<ReviewTools> tools;
    private final ObjectProvider<ContextSpecCheckTools> checks;
    private final ObjectProvider<MemoryHarvestTools> harvests;

    public ReviewBridgeResolverImpl(ObjectProvider<ReviewTools> tools, ObjectProvider<ContextSpecCheckTools> checks, ObjectProvider<MemoryHarvestTools> harvests)
    {
        this.tools = requireNonNull(tools, "tools is null");
        this.checks = requireNonNull(checks, "checks is null");
        this.harvests = requireNonNull(harvests, "harvests is null");
    }

    @Override
    public Optional<ReviewBridge> resolve(String taskId)
    {
        return checks.getObject().bridge(taskId)
                .or(() -> harvests.getObject().bridge(taskId))
                .or(() -> tools.getObject().bridge(taskId));
    }
}
