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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.LaunchIntentState;
import com.bytequay.agent.core.LaunchOwnerKind;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TestSqliteAgentProcessLaunchStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-agent-launch-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    private AgentProcessLaunchStore launches;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Test
    void advancesOneLaunchThroughItsCommittedIntentStates()
    {
        launches.insert(prepared("launch-advance"));

        assertThat(launches.find("launch-advance").orElseThrow().state())
                .isEqualTo(LaunchIntentState.PREPARED);

        launches.updateState("launch-advance", LaunchIntentState.ATTEMPTING, NOW.plusSeconds(1));
        launches.updateState("launch-advance", LaunchIntentState.RUNNING, NOW.plusSeconds(2));
        launches.recordTerminal("launch-advance", true, NOW.plusSeconds(3));

        AgentProcessLaunch terminal = launches.find("launch-advance").orElseThrow();
        assertThat(terminal.state()).isEqualTo(LaunchIntentState.TERMINAL);
        assertThat(terminal.verifiedStopped()).contains(true);
        assertThat(terminal.ownerKind()).isEqualTo(LaunchOwnerKind.CLAUDE_TURN);
        assertThat(terminal.ownerId()).isEqualTo("turn-1");
        assertThat(terminal.updatedAt()).isEqualTo(NOW.plusSeconds(3));

        launches.delete("launch-advance");

        assertThat(launches.find("launch-advance")).isEmpty();
    }

    @Test
    void rejectsADuplicateLaunchAndAnUnknownOrTerminalTransition()
    {
        launches.insert(prepared("launch-guard"));

        assertThatThrownBy(() -> launches.insert(prepared("launch-guard")))
                .isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> launches.updateState("absent", LaunchIntentState.RUNNING, NOW))
                .isInstanceOf(AgentConflictException.class);

        launches.recordTerminal("launch-guard", false, NOW);

        // A terminal receipt is the final word; it must not be reopened by a late transition.
        assertThatThrownBy(() -> launches.updateState("launch-guard", LaunchIntentState.RUNNING, NOW))
                .isInstanceOf(AgentConflictException.class);
        assertThat(launches.find("launch-guard").orElseThrow().verifiedStopped()).contains(false);

        launches.delete("launch-guard");
        launches.delete("launch-guard");
    }

    @Test
    void listsEveryCommittedLaunchInDeterministicOrder()
    {
        launches.insert(prepared("launch-b"));
        launches.insert(prepared("launch-a"));

        assertThat(launches.list())
                .extracting(AgentProcessLaunch::launchId)
                .containsExactly("launch-a", "launch-b");

        launches.delete("launch-a");
        launches.delete("launch-b");
    }

    private static AgentProcessLaunch prepared(String launchId)
    {
        return new AgentProcessLaunch(
                launchId,
                LaunchOwnerKind.CLAUDE_TURN,
                "turn-1",
                LaunchIntentState.PREPARED,
                "/usr/local/bin/claude",
                "digest",
                Optional.empty(),
                NOW);
    }
}
