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
package com.bytequay.app.scheduler;

import com.bytequay.app.repository.github.GitHubRateLimitException;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestPullRequestAttentionSyncJob
{
    private final WorkspaceIssueService issues = mock(WorkspaceIssueService.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final PullRequestAttentionSyncJob job = new PullRequestAttentionSyncJob(pullRequests, settings, issues);

    @Test
    void everyTickReadsOnlySummariesWithoutWaitingForTheDetailInterval()
    {
        when(pullRequests.hasDiscoveryAccounts()).thenReturn(true);

        job.tick();
        job.tick();

        verify(pullRequests, times(2)).syncAttentionSummaries();
        verify(settings, times(2)).inQuietHours();
        verify(issues, times(2)).refreshRecentIssues();
        verify(pullRequests, times(2)).hasDiscoveryAccounts();
        verifyNoMoreInteractions(pullRequests, settings, issues);
    }

    @Test
    void refreshesWorkspaceIssuesWithoutGlobalGitHubCredentialsAndSkipsQuietHours()
    {
        job.tick();
        when(pullRequests.hasDiscoveryAccounts()).thenReturn(true);
        when(settings.inQuietHours()).thenReturn(true);
        job.tick();

        verify(pullRequests, never()).syncAttentionSummaries();
        verify(issues).refreshRecentIssues();
    }

    @Test
    void failedReadDoesNotStopFollowingTicks()
    {
        when(pullRequests.hasDiscoveryAccounts()).thenReturn(true);
        doThrow(new IllegalStateException("temporary failure")).doNothing().when(pullRequests).syncAttentionSummaries();

        job.tick();
        job.tick();

        verify(pullRequests, times(2)).syncAttentionSummaries();
        verify(issues, times(2)).refreshRecentIssues();
    }

    @Test
    void rateLimitStopsTheTickBeforeIssueRequests()
    {
        when(pullRequests.hasDiscoveryAccounts()).thenReturn(true);
        doThrow(new GitHubRateLimitException("core", 123L)).when(pullRequests).syncAttentionSummaries();

        job.tick();

        verifyNoInteractions(issues);
    }
}
