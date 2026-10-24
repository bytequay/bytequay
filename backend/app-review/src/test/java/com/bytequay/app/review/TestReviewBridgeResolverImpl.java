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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.app.context.ContextSpecCheckTools;
import com.bytequay.app.memory.MemoryHarvestTools;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestReviewBridgeResolverImpl
{
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private final ReviewTools tools = mock(ReviewTools.class);
    private final ContextSpecCheckTools checks = mock(ContextSpecCheckTools.class);
    private final MemoryHarvestTools harvests = mock(MemoryHarvestTools.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

    @BeforeEach
    void registerChecks()
    {
        beans.registerSingleton("contextSpecCheckTools", checks);
        beans.registerSingleton("memoryHarvestTools", harvests);
    }

    @Test
    void aSpecCheckRetainsItsRestrictedBridgeWithoutCreatingAReview()
    {
        AgentTask task = task();
        ReviewBridge check = new ReviewBridge(ImmutableList.of(), "Check the directory's code against its specifications");
        when(checks.bridge(task.id().toString())).thenReturn(Optional.of(check));
        ReviewBridgeResolverImpl resolver = new ReviewBridgeResolverImpl(beans.getBeanProvider(ReviewTools.class), beans.getBeanProvider(ContextSpecCheckTools.class),
                beans.getBeanProvider(MemoryHarvestTools.class));

        assertThat(resolver.resolve(task.id().toString())).contains(check);
        verifyNoInteractions(tools);
    }

    @Test
    void pullRequestReviewRetainsItsExistingReportBridge()
    {
        AgentTask task = task();
        ReviewBridge review = new ReviewBridge(ImmutableList.of(), "The existing PR review policy");
        when(tools.bridge(task.id().toString())).thenReturn(Optional.of(review));
        beans.registerSingleton("reviewTools", tools);
        ReviewBridgeResolverImpl resolver = new ReviewBridgeResolverImpl(beans.getBeanProvider(ReviewTools.class), beans.getBeanProvider(ContextSpecCheckTools.class),
                beans.getBeanProvider(MemoryHarvestTools.class));

        assertThat(resolver.resolve(task.id().toString())).contains(review);
    }

    @Test
    void developmentDoesNotReceiveReviewPermissions()
    {
        beans.registerSingleton("reviewTools", tools);
        ReviewBridgeResolverImpl resolver = new ReviewBridgeResolverImpl(beans.getBeanProvider(ReviewTools.class), beans.getBeanProvider(ContextSpecCheckTools.class),
                beans.getBeanProvider(MemoryHarvestTools.class));

        assertThat(resolver.resolve(task().id().toString())).isEmpty();
    }

    private AgentTask task()
    {
        return created(TaskId.random(), Optional.of("workspace"), true, "Development", AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("owner", "repo"), Path.of("/tmp/development-worktree"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }
}
