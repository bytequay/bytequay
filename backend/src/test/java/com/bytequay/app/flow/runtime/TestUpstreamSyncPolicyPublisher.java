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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.flow.ci.CiAutofix;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepairPlacement;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepairPlacementPolicy;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepositoryCompileConfiguration;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.repository.github.GitHubRequiredCheckResolver;
import com.bytequay.app.repository.github.GitHubRequiredCheckResolver.Snapshot;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class TestUpstreamSyncPolicyPublisher
{
    @Test
    void publishesLocalRequiredAndAttributedPoliciesFromTheExactWorkflow(
            @TempDir Path worktree)
            throws Exception
    {
        Path workflows = worktree.resolve(".github/workflows");
        Files.createDirectories(workflows);
        Files.writeString(workflows.resolve("ci.yml"), """
                jobs:
                  build:
                    name: build
                    steps:
                      - working-directory: backend
                        run: ./mvnw -B verify -DskipTests
                  test:
                    name: test
                    steps:
                      - working-directory: backend
                        run: ./mvnw -B test
                """, StandardCharsets.UTF_8);

        LocalChecks local = mock(LocalChecks.class);
        CiAutofix autofix = mock(CiAutofix.class);
        GitHubRequiredCheckResolver resolver = mock(
                GitHubRequiredCheckResolver.class);
        CredentialStore credentials = mock(CredentialStore.class);
        GitRunner git = mock(GitRunner.class);
        when(credentials.getSecret(CredentialType.REPO, "acme/fork"))
                .thenReturn(Optional.of("secret"));
        when(git.headSha(worktree)).thenReturn("a".repeat(40));
        when(autofix.placementPolicy("task")).thenReturn(
                RepairPlacementPolicy.tip("task", Instant.EPOCH));
        when(resolver.resolve("secret", "acme", "fork", "main"))
                .thenReturn(new Snapshot(
                        "github:rules/branches/main", "sha256:rules",
                        List.of("GITHUB_CHECK:7:build",
                                "GITHUB_CHECK:9:test")));
        RequiredCiPolicyRevision required = new RequiredCiPolicyRevision(
                "policy", "acme/fork", "refs/heads/main",
                "refs/heads/main", 1, PolicyResolution.RESOLVED,
                "github:rules/branches/main", "sha256:rules", null,
                List.of("GITHUB_CHECK:7:build", "GITHUB_CHECK:9:test"),
                List.of("NEUTRAL", "SKIPPED", "SUCCESS"), Instant.EPOCH);
        when(autofix.recordPolicy(
                eq("acme/fork"), eq("refs/heads/main"),
                eq("refs/heads/main"), any(), any(),
                eq(PolicyResolution.RESOLVED), isNull(), anyList(),
                eq(List.of("NEUTRAL", "SKIPPED", "SUCCESS"))))
                .thenReturn(required);

        new UpstreamSyncPolicyPublisher(
                local, autofix, resolver, credentials, git)
                .publish("task", "acme/fork", "refs/heads/main", "main",
                        worktree, "a".repeat(40));

        ArgumentCaptor<List<LocalChecks.ProfileDefinition>> profiles =
                ArgumentCaptor.forClass(List.class);
        verify(local).recordPolicy(
                eq("acme/fork"), isNull(),
                eq("git:" + "a".repeat(40) + ":.github/workflows/ci.yml"),
                any(), profiles.capture());
        assertThat(profiles.getValue()).singleElement().satisfies(profile -> {
            assertThat(profile.command()).containsExactly(
                    "./mvnw", "-B", "test");
            assertThat(profile.workingDirectory()).isEqualTo("backend");
            assertThat(profile.requiredForGateKinds()).containsExactly(
                    FlowRuntimeRecords.GateIntent.INITIAL_PUBLISH,
                    FlowRuntimeRecords.GateIntent.CI_UPDATE);
        });

        ArgumentCaptor<RepositoryCompileConfiguration> compile =
                ArgumentCaptor.forClass(RepositoryCompileConfiguration.class);
        verify(autofix).resolvePlacementPolicy(
                eq("task"), eq(RepairPlacement.ATTRIBUTED_FIXUP), eq(true),
                compile.capture(), eq(required),
                eq(List.of("/usr/bin/env", "-C", "backend", "./mvnw",
                        "-B", "verify", "-DskipTests")));
        assertThat(compile.getValue().perCommitCompileChecks())
                .extracting(check -> check.key())
                .containsExactly("GITHUB_CHECK:7:build");
    }
}
