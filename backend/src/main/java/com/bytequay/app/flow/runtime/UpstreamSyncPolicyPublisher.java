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
import com.bytequay.app.flow.ci.CiAutofixRecords.GitHubCheckSelector;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepairPlacement;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepositoryCompileConfiguration;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckPolicyRevision;
import com.bytequay.app.flow.runtime.LocalChecks.ProfileDefinition;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.repository.github.GitHubRequiredCheckResolver;
import com.bytequay.app.repository.github.GitHubRequiredCheckResolver.Snapshot;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.workspaces.CiJobScriptReader;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Publishes the program-owned policies an upstream Task needs before agents run. */
public final class UpstreamSyncPolicyPublisher
{
    private static final Duration CHECK_TIMEOUT = Duration.ofMinutes(10);

    private final LocalChecks localChecks;
    private final CiAutofix autofix;
    private final GitHubRequiredCheckResolver requiredChecks;
    private final CredentialStore credentials;
    private final GitRunner git;

    public UpstreamSyncPolicyPublisher(
            LocalChecks localChecks,
            CiAutofix autofix,
            GitHubRequiredCheckResolver requiredChecks,
            CredentialStore credentials,
            GitRunner git)
    {
        this.localChecks = requireNonNull(localChecks, "localChecks is null");
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.requiredChecks = requireNonNull(
                requiredChecks, "requiredChecks is null");
        this.credentials = requireNonNull(
                credentials, "credentials is null");
        this.git = requireNonNull(git, "git is null");
    }

    public void publish(
            String taskId,
            String repositoryId,
            String targetBaseRef,
            String targetBranch,
            Path worktree,
            String policyHead)
    {
        requireText(taskId, "taskId");
        requireText(repositoryId, "repositoryId");
        requireText(targetBaseRef, "targetBaseRef");
        requireText(targetBranch, "targetBranch");
        requireText(policyHead, "policyHead");
        requireNonNull(worktree, "worktree is null");
        String[] repository = splitRepository(repositoryId);
        String token = credentials.getSecret(
                        CredentialType.REPO, repositoryId)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "the target repository has no GitHub credential"));
        CiJobScriptReader.BuildInvocation build =
                CiJobScriptReader.anyBuildInvocation(worktree)
                .orElseThrow(() -> new IllegalStateException(
                        "no safe local build command was found in GitHub Actions"));
        CiJobScriptReader.BuildInvocation tests =
                CiJobScriptReader.anyTestInvocation(worktree)
                .orElseThrow(() -> new IllegalStateException(
                        "no safe local test command was found in GitHub Actions"));
        String inspectedHead = head(worktree);
        if (!inspectedHead.equals(policyHead)) {
            throw new IllegalStateException(
                    "the policy worktree moved during exact-head discovery");
        }
        LocalCheckPolicyRevision current = localChecks
                .currentPolicy(repositoryId).orElse(null);
        localChecks.recordPolicy(
                repositoryId,
                current == null ? null : current.policyRevisionId(),
                "git:" + policyHead + ":" + tests.sourceRef(),
                tests.sourceDigest(),
                List.of(new ProfileDefinition(
                        "test",
                        tests.arguments(),
                        tests.workingDirectory(),
                        List.of(),
                        CHECK_TIMEOUT,
                        List.of(GateIntent.INITIAL_PUBLISH,
                                GateIntent.CI_UPDATE))));

        Snapshot policy = requiredChecks.resolve(
                token, repository[0], repository[1], targetBranch);
        RequiredCiPolicyRevision ciPolicy = autofix.recordPolicy(
                repositoryId,
                targetBaseRef,
                targetBaseRef,
                policy.sourceRef(),
                policy.sourceDigest(),
                PolicyResolution.RESOLVED,
                null,
                policy.selectors(),
                List.of("NEUTRAL", "SKIPPED", "SUCCESS"));
        List<GitHubCheckSelector> compileChecks = policy.selectors().stream()
                .map(GitHubCheckSelector::parse)
                .filter(selector -> selector.name().equals(build.jobName()))
                .toList();
        if (autofix.placementPolicy(taskId).placement()
                == RepairPlacement.TIP) {
            autofix.resolvePlacementPolicy(
                    taskId,
                    RepairPlacement.ATTRIBUTED_FIXUP,
                    true,
                    new RepositoryCompileConfiguration(
                            build.sourceRef(),
                            build.sourceDigest(),
                            compileChecks),
                    ciPolicy,
                    boundaryCommand(build));
        }
    }

    private String head(Path worktree)
    {
        try {
            return git.headSha(worktree);
        }
        catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot inspect the upstream Task base", failure);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while inspecting the upstream Task base",
                    interrupted);
        }
    }

    private static List<String> boundaryCommand(
            CiJobScriptReader.BuildInvocation build)
    {
        if (".".equals(build.workingDirectory())) {
            return build.arguments();
        }
        ArrayList<String> command = new ArrayList<>(
                List.of("/usr/bin/env", "-C", build.workingDirectory()));
        command.addAll(build.arguments());
        return List.copyOf(command);
    }

    private static String[] splitRepository(String repositoryId)
    {
        int separator = repositoryId.indexOf('/');
        if (separator < 1 || separator == repositoryId.length() - 1
                || repositoryId.indexOf('/', separator + 1) >= 0) {
            throw new IllegalArgumentException(
                    "repositoryId must be canonical owner/name");
        }
        return new String[] {
                repositoryId.substring(0, separator),
                repositoryId.substring(separator + 1)};
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
