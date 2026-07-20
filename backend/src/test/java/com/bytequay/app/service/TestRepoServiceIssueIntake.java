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

import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoIssueIntakePage;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.GithubHomeCacheStore;
import com.bytequay.app.repository.PrViewStateStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.RepoMetaStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.credentials.PatResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRepoServiceIssueIntake
{
    @Test
    void firstEnableBaselinesAtNewestSharedIssueNumberWithoutCreatingWork()
    {
        Fixture fixture = new Fixture();
        when(fixture.gitHub.fetchRepoIssueIntakePage("pat", fixture.repo, 1, 100))
                .thenReturn(new RepoIssueIntakePage(List.of(issue(205)), 210, 111, true));

        RepoService.IssueIntakeBatch batch = fixture.service.getOpenRepoIssuesAfter(
                "acme", "widget", null);

        assertThat(batch.cursor()).isEqualTo(210);
        assertThat(batch.openIssues()).isEmpty();
        verify(fixture.gitHub, never()).fetchRepoIssueIntakePage("pat", fixture.repo, 2, 100);
    }

    @Test
    void paginatesCreationOrderUntilItCrossesCursorAndReturnsOldestFirst()
    {
        Fixture fixture = new Fixture();
        when(fixture.gitHub.fetchRepoIssueIntakePage("pat", fixture.repo, 1, 100))
                .thenReturn(new RepoIssueIntakePage(List.of(issue(205)), 210, 111, true));
        when(fixture.gitHub.fetchRepoIssueIntakePage("pat", fixture.repo, 2, 100))
                .thenReturn(new RepoIssueIntakePage(List.of(issue(110)), 110, 50, true));

        RepoService.IssueIntakeBatch batch = fixture.service.getOpenRepoIssuesAfter(
                "acme", "widget", 100);

        assertThat(batch.cursor()).isEqualTo(210);
        assertThat(batch.openIssues()).extracting(RepoIssue::number)
                .containsExactly(110, 205);
        verify(fixture.gitHub, never()).fetchRepoIssueIntakePage("pat", fixture.repo, 3, 100);
    }

    private static RepoIssue issue(int number)
    {
        return new RepoIssue(
                number, number, "Issue " + number, "author", "open",
                "https://github.com/acme/widget/issues/" + number,
                Instant.now(), List.of(), 0);
    }

    private static final class Fixture
    {
        private final PullRequestRepository gitHub = mock(PullRequestRepository.class);
        private final PatResolver pats = mock(PatResolver.class);
        private final IssueOriginService origins = mock(IssueOriginService.class);
        private final RepoRef repo = RepoRef.of("acme", "widget");
        private final RepoService service;

        private Fixture()
        {
            when(pats.resolve("acme/widget")).thenReturn("pat");
            when(origins.attribute(any(), any())).thenAnswer(call -> call.getArgument(1));
            service = new RepoService(
                    mock(WatchedRepoStore.class),
                    gitHub,
                    mock(PrViewStateStore.class),
                    mock(RepoListCache.class),
                    mock(RepoMetaStore.class),
                    mock(GithubHomeCacheStore.class),
                    mock(AppSettingsStore.class),
                    pats,
                    origins,
                    Runnable::run);
        }
    }
}
