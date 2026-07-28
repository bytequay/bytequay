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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.provisioning.GitRunnerProvisioningGit;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubRemoteEffects
{
    private static final RepoRef REPOSITORY = RepoRef.parse("acme/widget");
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of(
            "acme", "widget", 17);

    private PullRequestRepository pullRequests;
    private PatResolver pats;
    private GitHubRemoteEffects effects;
    private ExecutionContext execution;

    @BeforeEach
    void setUp()
    {
        pullRequests = mock(PullRequestRepository.class);
        pats = mock(PatResolver.class);
        effects = new GitHubRemoteEffects(
                mock(GitRunner.class), mock(GitRunnerProvisioningGit.class),
                mock(CodeFingerprints.class), List.of(), pullRequests, pats,
                new ObjectMapper());
        execution = mock(ExecutionContext.class);
        when(pats.resolve("acme/widget")).thenReturn("pat");
    }

    @Test
    void rerunsOnlyTheFailedChecksOnTheExactRemoteSubject()
            throws Exception
    {
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST))
                .thenReturn(detail("head-1", "base-1"));
        when(pullRequests.rerunFailedChecks("pat", REPOSITORY, "head-1"))
                .thenReturn(2);

        RemoteEffectOperationHandler.Result result = effects.perform(
                request("head-1", "base-1"),
                RemoteEffectOperationHandler.Mode.EXECUTE, execution, null);

        assertThat(result.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.SUCCEEDED);
        assertThat(result.headSha()).isEqualTo("head-1");
        assertThat(result.evidence()).contains("2");
        verify(pullRequests).rerunFailedChecks("pat", REPOSITORY, "head-1");
    }

    @Test
    void refusesToMutateWhenGitHubMovedToAnotherHead()
    {
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST))
                .thenReturn(detail("head-2", "base-1"));

        assertThatThrownBy(() -> effects.perform(
                request("head-1", "base-1"),
                RemoteEffectOperationHandler.Mode.EXECUTE, execution, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact effect subject");
        verify(pullRequests, never()).rerunFailedChecks(
                "pat", REPOSITORY, "head-1");
    }

    private static RemoteEffectOperationHandler.Request request(
            String headSha, String baseSha)
    {
        return new RemoteEffectOperationHandler.Request(
                "operation-1", RemoteEffectOperationHandler.RERUN_CI,
                "task-1", "remote-stage-1", "acme/widget", 17,
                "/tmp/worktree", "feature", null, headSha, baseSha,
                null, null, "rerun:head-1");
    }

    private static PrRawDetail detail(String headSha, String baseSha)
    {
        return new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), headSha, "feature", "acme/widget", "main",
                "acme/widget", "open", false, baseSha, null);
    }
}
