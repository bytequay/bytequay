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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.ci.CiAutofix;
import com.bytequay.app.flow.ci.CiAutofixCoordinator;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.GitHubEffects;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestNewFlowConfiguration
{
    @TempDir
    private Path temporaryDirectory;

    @Test
    void composesOneSharedOwnerGraphOnTheQualifiedDatabase()
            throws Exception
    {
        Path newFlowPath = temporaryDirectory.resolve("new-flow.db");
        Path primaryPath = temporaryDirectory.resolve("primary.db");
        DataSource primary = new DriverManagerDataSource(
                "jdbc:sqlite:" + primaryPath);
        new JdbcTemplate(primary).execute(
                "CREATE TABLE primary_owner (value TEXT NOT NULL)");
        new JdbcTemplate(primary).update(
                "INSERT INTO primary_owner VALUES ('unchanged')");
        byte[] primaryBefore = Files.readAllBytes(primaryPath);

        new ApplicationContextRunner()
                .withBean("legacyPrimaryDataSource", DataSource.class,
                        () -> primary, definition -> definition.setPrimary(true))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "bytequay.new-flow.database-path=" + newFlowPath)
                .withUserConfiguration(NewFlowConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DataSource.class))
                            .isSameAs(primary);
                    DataSource newFlow = context.getBean(
                            "newFlowDataSource", DataSource.class);
                    assertThat(newFlow).isNotSameAs(primary);

                    FlowRuntime runtime = context.getBean(FlowRuntime.class);
                    LocalChecks checks = context.getBean(LocalChecks.class);
                    CiAutofix autofix = context.getBean(CiAutofix.class);
                    GitHubEffects effects = context.getBean(
                            GitHubEffects.class);
                    UserGates gates = context.getBean(UserGates.class);
                    CiAutofixCoordinator coordinator = context.getBean(
                            CiAutofixCoordinator.class);

                    assertThat(ReflectionTestUtils.getField(checks, "runtime"))
                            .isSameAs(runtime);
                    assertThat(ReflectionTestUtils.getField(effects, "runtime"))
                            .isSameAs(runtime);
                    assertThat(ReflectionTestUtils.getField(gates, "runtime"))
                            .isSameAs(runtime);
                    assertThat(ReflectionTestUtils.getField(
                            gates, "localChecks")).isSameAs(checks);
                    assertThat(ReflectionTestUtils.getField(
                            gates, "autofix")).isSameAs(autofix);
                    assertThat(ReflectionTestUtils.getField(
                            gates, "githubEffects")).isSameAs(effects);
                    assertThat(ReflectionTestUtils.getField(
                            coordinator, "runtime")).isSameAs(runtime);
                    assertThat(ReflectionTestUtils.getField(
                            coordinator, "autofix")).isSameAs(autofix);
                    assertThat(ReflectionTestUtils.getField(
                            coordinator, "userGates")).isSameAs(gates);
                    assertThat(context.getBeansOfType(
                            NewFlowDispatcher.Handler.class)).isEmpty();
                    assertThat(newFlowPath).exists();
                });
        assertThat(Files.readAllBytes(primaryPath))
                .containsExactly(primaryBefore);
    }
}
