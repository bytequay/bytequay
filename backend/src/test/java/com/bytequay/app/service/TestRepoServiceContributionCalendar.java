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
package com.bytequay.app.service;

import com.bytequay.app.domain.ContributionCalendar;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.GithubHomeCacheStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.PrViewStateStore;
import com.bytequay.app.repository.sqlite.RepoMetaStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.workspaces.WatchedRepoPurger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRepoServiceContributionCalendar
{
    @Test
    void cachesContributionCalendarForRepeatedHomeLoads()
    {
        PullRequestRepository gitHub = mock(PullRequestRepository.class);
        PatResolver patResolver = mock(PatResolver.class);
        ContributionCalendar calendar = calendar();
        when(patResolver.resolve()).thenReturn("pat");
        when(gitHub.fetchContributionCalendar("pat", "chenjian2664")).thenReturn(calendar);

        RepoService service = service(gitHub, patResolver);

        service.getContributionCalendar("chenjian2664");
        service.getContributionCalendar("chenjian2664");

        verify(patResolver, times(1)).resolve();
        verify(gitHub, times(1)).fetchContributionCalendar("pat", "chenjian2664");
    }

    @Test
    void graphQlResourceLimitBecomesTemporaryUnavailable()
    {
        PullRequestRepository gitHub = mock(PullRequestRepository.class);
        PatResolver patResolver = mock(PatResolver.class);
        when(patResolver.resolve()).thenReturn("pat");
        when(gitHub.fetchContributionCalendar("pat", "chenjian2664"))
                .thenThrow(new IllegalStateException("RESOURCE_LIMITS_EXCEEDED"));

        RepoService service = service(gitHub, patResolver);

        assertThatThrownBy(() -> service.getContributionCalendar("chenjian2664"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).isEqualTo("Contribution graph temporarily unavailable");
                });
    }

    private static RepoService service(PullRequestRepository gitHub, PatResolver patResolver)
    {
        return new RepoService(
                mock(WatchedRepoStore.class),
                gitHub,
                mock(PrViewStateStore.class),
                mock(RepoListCache.class),
                mock(RepoMetaStore.class),
                mock(GithubHomeCacheStore.class),
                mock(AppSettingsStore.class),
                patResolver,
                mock(IssueOriginService.class),
                mock(WatchedRepoPurger.class),
                Runnable::run);
    }

    private static ContributionCalendar calendar()
    {
        return new ContributionCalendar(
                1,
                List.of(new ContributionCalendar.Week(List.of(
                        new ContributionCalendar.Day(LocalDate.parse("2026-07-17"), 1, "#40c463")))));
    }
}
