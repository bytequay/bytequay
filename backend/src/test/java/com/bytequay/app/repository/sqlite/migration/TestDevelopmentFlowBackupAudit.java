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
package com.bytequay.app.repository.sqlite.migration;

import com.bytequay.app.developmentflow.compatibility.DevelopmentFlowInvariantAuditor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowBackupAudit
{
    private static final String DATABASE_PROPERTY = "bytequay.audit.db";
    private static final String TARGET_VERSION = "293";

    @Test
    @EnabledIfSystemProperty(named = DATABASE_PROPERTY, matches = ".+")
    void migratesAndAuditsExplicitDatabaseCopy()
            throws Exception
    {
        Path database = Path.of(System.getProperty(DATABASE_PROPERTY)).toRealPath();
        Path liveDatabase = Path.of(
                System.getProperty("user.home"),
                "Library", "Application Support", "ByteQuay", "bytequay.db");
        if (Files.exists(liveDatabase) && Files.isSameFile(database, liveDatabase)) {
            throw new IllegalArgumentException("Refusing to migrate the live ByteQuay database");
        }

        String url = "jdbc:sqlite:" + database
                + "?foreign_keys=ON&busy_timeout=30000";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertDatabaseHealthy(jdbc);

        Flyway flyway = Flyway.configure()
                .dataSource(url, "", "")
                .locations("classpath:db/migration")
                .javaMigrations(
                        new BackfillTurnLiveness(),
                        new BackfillLocalReviewSubmissions(new ObjectMapper()),
                        new NormalizeDeadLifecycleStates())
                .target(TARGET_VERSION)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .load();

        flyway.migrate();
        flyway.validate();

        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo(TARGET_VERSION);

        assertDatabaseHealthy(jdbc);

        DevelopmentFlowInvariantAuditor auditor =
                new DevelopmentFlowInvariantAuditor(jdbc);
        DevelopmentFlowInvariantAuditor.Audit audit = auditor.audit();
        assertThat(audit.findings()).isEmpty();
        assertThat(audit.healthy()).isTrue();

        DevelopmentFlowInvariantAuditor.DrainStatus drain = auditor.legacyDrainStatus();
        System.out.printf(
                "Legacy drain: drained=%s, nonterminalTasks=%d, liveTurns=%d, "
                        + "liveRuns=%d, liveValidationClaims=%d, liveEffects=%d%n",
                drain.drained(), drain.nonterminalTasks(), drain.liveTurns(),
                drain.liveRuns(), drain.liveValidationClaims(), drain.liveEffects());
    }

    private static void assertDatabaseHealthy(JdbcTemplate jdbc)
    {
        assertThat(jdbc.queryForList("PRAGMA quick_check", String.class))
                .containsExactly("ok");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
}
