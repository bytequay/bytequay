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

import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.settings.AppSettingsService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPullRequestSyncJob
{
    @Test
    void refreshesPullRequestsOnSchedule()
    {
        PullRequestRefreshService refreshService = mock(PullRequestRefreshService.class);
        PullRequestSyncJob job = new PullRequestSyncJob(refreshService, mock(AppSettingsService.class));

        job.tick();

        verify(refreshService).requestRefresh();
    }

    @Test
    void skipsRefreshDuringQuietHours()
    {
        PullRequestRefreshService refreshService = mock(PullRequestRefreshService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.inQuietHours()).thenReturn(true);
        PullRequestSyncJob job = new PullRequestSyncJob(refreshService, appSettings);

        job.tick();

        verify(refreshService, never()).requestRefresh();
    }
}
