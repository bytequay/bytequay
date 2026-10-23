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
package com.bytequay.app.config;

import com.bytequay.app.scheduler.PullRequestSyncJob;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.settings.AppPreferences;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.core.store.WorkspaceStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSchedulingConfig
{
    @Test
    void localWorkContinuesWhileARepositoryRefreshWaits()
            throws Exception
    {
        CountDownLatch refreshing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch localFinished = new CountDownLatch(1);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        CredentialService credentials = mock(CredentialService.class);
        AppSettingsService settings = mock(AppSettingsService.class);
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        when(settings.preferences()).thenReturn(AppPreferences.DEFAULT);
        doAnswer(invocation -> {
            refreshing.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(refresh).refreshScheduledDashboard();
        PullRequestSyncJob job = new PullRequestSyncJob(refresh, mock(WorkspaceMetadataService.class), mock(WorkspaceStore.class),
                credentials, settings, Clock.systemUTC());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SchedulingConfig.class);
            context.registerBean(PullRequestSyncJob.class, () -> job);
            context.registerBean(LocalWork.class, () -> new LocalWork(refreshing, localFinished));
            try {
                context.refresh();
                assertThat(refreshing.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(localFinished.await(5, TimeUnit.SECONDS)).isTrue();
            }
            finally {
                release.countDown();
            }
        }
    }

    static class LocalWork
    {
        private final CountDownLatch refreshing;
        private final CountDownLatch finished;

        LocalWork(CountDownLatch refreshing, CountDownLatch finished)
        {
            this.refreshing = refreshing;
            this.finished = finished;
        }

        @Scheduled(fixedDelay = 10)
        void tick()
        {
            if (refreshing.getCount() == 0) {
                finished.countDown();
            }
        }
    }
}
