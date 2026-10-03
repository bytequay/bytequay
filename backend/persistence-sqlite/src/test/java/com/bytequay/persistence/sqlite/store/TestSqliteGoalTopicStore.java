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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.GoalTopic;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteGoalTopicStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-goal-topics-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00.123Z");

    @Autowired
    private SqliteGoalTopicStore topics;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void keepsOneLevelPerTopicOfAGoal()
    {
        GoalTopic manifests = new GoalTopic(GoalTopic.id("goal-1", "Manifests"), "goal-1", "workspace-1", "Manifests", GoalTopic.Level.NEW,
                ImmutableList.of("discussion:q-1"), false, NOW);
        topics.save(manifests);
        topics.save(new GoalTopic(GoalTopic.id("goal-1", "Snapshots"), "goal-1", "workspace-1", "Snapshots", GoalTopic.Level.LEARNING, ImmutableList.of(), true, NOW));
        GoalTopic practiced = new GoalTopic(GoalTopic.id("goal-1", " manifests "), "goal-1", "workspace-1", "Manifests", GoalTopic.Level.PRACTICED,
                ImmutableList.of("discussion:q-1", "work:w-1"), false, NOW.plusSeconds(1));
        topics.save(practiced);
        assertThat(topics.list("goal-1")).extracting(GoalTopic::topic).containsExactly("Manifests", "Snapshots");
        assertThat(topics.find(GoalTopic.id("goal-1", "MANIFESTS"))).contains(practiced);
        assertThat(topics.list("goal-2")).isEmpty();
    }
}
