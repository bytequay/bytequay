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
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubRemoteMarkReadyGateway
{
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of(
            "acme", "widget", 17);

    private PullRequestRepository pullRequests;
    private GitHubRemoteMarkReadyGateway gateway;
    private ExecutionContext execution;

    @BeforeEach
    void setUp()
    {
        pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        when(pats.resolve("acme/widget")).thenReturn("pat");
        gateway = new GitHubRemoteMarkReadyGateway(pullRequests, pats);
        execution = mock(ExecutionContext.class);
    }

    @Test
    void marksOnlyTheExactDraft()
            throws Exception
    {
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST))
                .thenReturn(detail(true, "head-1"));

        gateway.markReady(operation(), execution);

        verify(pullRequests).setPullRequestDraft("pat", PULL_REQUEST, false);
    }

    @Test
    void refusesAHeadThatMovedBeforeMutation()
    {
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST))
                .thenReturn(detail(true, "head-2"));

        assertThatThrownBy(() -> gateway.markReady(operation(), execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside its authorization");
        verify(pullRequests, never()).setPullRequestDraft(
                "pat", PULL_REQUEST, false);
    }

    @Test
    void alreadyReadyIsAnIdempotentSuccess()
            throws Exception
    {
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST))
                .thenReturn(detail(false, "head-1"));

        gateway.markReady(operation(), execution);

        verify(pullRequests, never()).setPullRequestDraft(
                "pat", PULL_REQUEST, false);
    }

    private static RemoteMarkReadyOperationHandler.Operation operation()
    {
        return new RemoteMarkReadyOperationHandler.Operation(
                "row-1", "operation-1", "authorization-1", "task-1", 1,
                "remote-stage-1", 1, 1, "acme/widget", 17,
                "head-1", "base-1",
                RemoteMarkReadyOperationHandler.Status.REQUESTED,
                0, 3, null, null);
    }

    private static PrRawDetail detail(boolean draft, String headSha)
    {
        return new PrRawDetail(
                "body", List.of(), draft, true, "clean", 1, 1, 1,
                0, List.of(), headSha, "feature", "acme/widget", "main",
                "acme/widget", "open", false, "base-1", null);
    }
}
