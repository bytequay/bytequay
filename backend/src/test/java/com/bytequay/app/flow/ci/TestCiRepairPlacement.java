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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.CiAutofixRecords.GitHubCheckSelector;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepairPlacement;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepositoryCompileConfiguration;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.runtime.NewFlowDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCiRepairPlacement
{
    private static final Instant NOW = Instant.parse("2026-08-10T10:15:30Z");
    private static final String COMPILE =
            "GITHUB_CHECK:15368:check-commits";
    private static final List<String> BUILD =
            List.of("/usr/bin/true");

    @TempDir
    private Path temporaryDirectory;

    private DataSource dataSource;
    private CiAutofix autofix;

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("new-flow.db")
                        + "?foreign_keys=ON");
        new NewFlowDatabase(dataSource, Clock.fixed(NOW, ZoneOffset.UTC))
                .bootstrap();
        autofix = newAutofix();
    }

    private CiAutofix newAutofix()
    {
        return new CiAutofix(
                dataSource,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                ignored -> new PublishedPrSubject(
                        "pr-1", "task-1", "repo-1", "main", "main", "H1"));
    }

    @Test
    void anOrdinaryTaskKeepsTipPlacementWithoutRecordingAnything()
    {
        var policy = autofix.placementPolicy("task-1");

        assertThat(policy.placement()).isEqualTo(RepairPlacement.TIP);
        assertThat(policy.perCommitCompileSelectors()).isEmpty();
        assertThat(policy.allowsHistoryRewrite()).isFalse();
        assertThat(policy.compileSourceRef()).isNull();
    }

    @Test
    void aSeriesTaskRecordsAttributedFixupOnceAndSurvivesRestart()
    {
        var recorded = autofix.recordPlacementPolicy(
                "task-1",
                RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(COMPILE),
                ".github/workflows/ci.yml",
                "sha256:abc",
                true,
                BUILD);

        assertThat(recorded.placement())
                .isEqualTo(RepairPlacement.ATTRIBUTED_FIXUP);
        assertThat(recorded.perCommitCompileSelectors())
                .containsExactly(COMPILE);
        assertThat(recorded.allowsHistoryRewrite()).isTrue();
        assertThat(newAutofix().placementPolicy("task-1")).isEqualTo(recorded);

        assertThat(autofix.recordPlacementPolicy(
                "task-1",
                RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(COMPILE),
                ".github/workflows/ci.yml",
                "sha256:abc",
                true,
                BUILD))
                .isEqualTo(recorded);
    }

    @Test
    void placementCannotBeChangedUnderALiveSeries()
    {
        autofix.recordPlacementPolicy(
                "task-1", RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(COMPILE), "ci.yml", "sha256:abc", true,
                BUILD);

        assertThatThrownBy(() -> autofix.recordPlacementPolicy(
                "task-1", RepairPlacement.TIP,
                List.of(), null, null, true,
                BUILD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable per Task");
        assertThatThrownBy(() -> autofix.recordPlacementPolicy(
                "task-1", RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(COMPILE), "ci.yml", "sha256:abc", false,
                BUILD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable per Task");
        assertThat(autofix.placementPolicy("task-1").allowsHistoryRewrite())
                .isTrue();
    }

    @Test
    void aCompileSelectorCannotBeStoredWithoutItsCiConfiguration()
    {
        assertThatThrownBy(() -> autofix.recordPlacementPolicy(
                "task-1", RepairPlacement.ATTRIBUTED_FIXUP,
                List.of(COMPILE), null, null, true,
                BUILD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cite its CI configuration");
        assertThat(autofix.placementPolicy("task-1").placement())
                .isEqualTo(RepairPlacement.TIP);
    }

    @Test
    void aDeclaredCompileCheckResolvesOnlyWhenThePolicyRequiresIt()
    {
        var policy = requiredPolicy(COMPILE, "GITHUB_CHECK:15368:test");

        var resolved = autofix.resolvePlacementPolicy(
                "task-1",
                RepairPlacement.ATTRIBUTED_FIXUP,
                true,
                new RepositoryCompileConfiguration(
                        ".github/workflows/ci.yml",
                        "sha256:abc",
                        List.of(
                                selector("check-commits"),
                                selector("not-required"))),
                policy,
                BUILD);

        assertThat(resolved.perCommitCompileSelectors())
                .containsExactly(COMPILE);
        assertThat(resolved.compileSourceRef())
                .isEqualTo(".github/workflows/ci.yml");
        assertThat(autofix.isPerCommitCompileSelector("task-1", COMPILE))
                .isTrue();
        assertThat(autofix.isPerCommitCompileSelector(
                "task-1", "GITHUB_CHECK:15368:not-required")).isFalse();
    }

    @Test
    void anUndeterminedCompileCheckDegradesInsteadOfBeingGuessed()
    {
        var policy = requiredPolicy(COMPILE);

        var withoutConfiguration = autofix.resolvePlacementPolicy(
                "task-1", RepairPlacement.ATTRIBUTED_FIXUP, true, null, policy,
                BUILD);
        var withoutPolicy = autofix.resolvePlacementPolicy(
                "task-2",
                RepairPlacement.ATTRIBUTED_FIXUP,
                true,
                new RepositoryCompileConfiguration(
                        "ci.yml", "sha256:abc",
                        List.of(selector("check-commits"))),
                null,
                BUILD);
        var unmatched = autofix.resolvePlacementPolicy(
                "task-3",
                RepairPlacement.ATTRIBUTED_FIXUP,
                true,
                new RepositoryCompileConfiguration(
                        "ci.yml", "sha256:abc",
                        List.of(selector("build-all-commits"))),
                policy,
                BUILD);

        for (var degraded : List.of(
                withoutConfiguration, withoutPolicy, unmatched)) {
            assertThat(degraded.placement())
                    .isEqualTo(RepairPlacement.ATTRIBUTED_FIXUP);
            assertThat(degraded.perCommitCompileSelectors()).isEmpty();
            assertThat(degraded.compileSourceRef()).isNull();
        }
        // The name reads exactly like a per-commit compile job. Nothing about
        // the name is evidence, so it buys no priority and no exception.
        assertThat(autofix.isPerCommitCompileSelector("task-1", COMPILE))
                .isFalse();
        assertThat(autofix.isPerCommitCompileSelector("task-3",
                "GITHUB_CHECK:15368:build-all-commits")).isFalse();
    }

    private RequiredCiPolicyRevision requiredPolicy(String... selectors)
    {
        return autofix.recordPolicy(
                "repo-1",
                "main",
                "main",
                "branch-protection",
                "sha256:policy",
                PolicyResolution.RESOLVED,
                null,
                List.of(selectors),
                List.of("SUCCESS"));
    }

    private static GitHubCheckSelector selector(String name)
    {
        return new GitHubCheckSelector(
                15368, name, "GITHUB_CHECK:15368:" + name);
    }
}
