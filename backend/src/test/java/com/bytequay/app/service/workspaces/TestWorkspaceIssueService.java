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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.threads.ThreadService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorkspaceIssueService
{
    @Test
    void startSchedulesOnlyTheExactIssueReferencePrompt()
    {
        WorkspaceRepositoryResolver resolver =
                mock(WorkspaceRepositoryResolver.class);
        RepoService repos = mock(RepoService.class);
        ThreadService threads = mock(ThreadService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkspaceIssueService service = new WorkspaceIssueService(
                resolver, repos, threads, jdbc);
        Thread trunk = trunk();
        when(threads.find(trunk.id())).thenReturn(Optional.of(trunk));
        when(threads.sendTrunk(trunk.id(), "Work on issue #482"))
                .thenReturn("turn-1");

        WorkspaceIssueService.StartIssueResult result =
                service.start("ws-1", 482, trunk.id());

        assertThat(result.trunkId()).isEqualTo(trunk.id());
        assertThat(result.turnId()).isEqualTo("turn-1");
        verify(threads).sendTrunk(trunk.id(), "Work on issue #482");
    }

    private static Thread trunk()
    {
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        return new Thread(
                "trunk-issue",
                ThreadKind.CLI_AGENT,
                "claude-code",
                null,
                "Issue work",
                ThreadStatus.IDLE,
                null,
                0,
                0,
                0,
                now,
                now,
                null,
                null,
                ThreadFlow.BUILD,
                "ws-1",
                null);
    }
}
