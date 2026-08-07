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
package com.bytequay.app.service.harness;

import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubActionsProbe
{
    private static final String HEAD_SHA = "abc123";

    private final PullRequestRepository github = mock(PullRequestRepository.class);
    private final PatResolver pats = mock(PatResolver.class);
    private final HarnessStore store = mock(HarnessStore.class);
    private final GitHubActionsProbe probe = new GitHubActionsProbe(github, pats, store);

    /**
     * The cache is keyed by head SHA, so caching an empty log would answer every
     * later cycle on the same SHA from the cache and the failure would defer as
     * undiagnosable for as long as the head stood still.
     */
    @Test
    void anUnavailableLogIsNotCachedAndIsAskedForAgainNextCycle()
    {
        stubFailingCheck();
        when(github.fetchCheckRunLog(anyString(), any(), anyLong())).thenReturn(Optional.empty());
        when(store.cachedLog(anyString(), anyString(), anyLong())).thenReturn(Optional.empty());

        probe.probe(watch(), BootstrapProfile.empty());
        probe.probe(watch(), BootstrapProfile.empty());

        verify(store, never()).cacheLog(anyString(), anyString(), anyLong(), anyString(), anyLong());
        verify(github, times(2)).fetchCheckRunLog(anyString(), any(), eq(77L));
    }

    @Test
    void aLogThatArrivesIsCachedAndReadBackRatherThanRefetched()
    {
        stubFailingCheck();
        when(github.fetchCheckRunLog(anyString(), any(), anyLong()))
                .thenReturn(Optional.of("BUILD FAILURE"));
        when(store.cachedLog(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("BUILD FAILURE"));

        assertThat(probe.probe(watch(), BootstrapProfile.empty()).failedJobs())
                .singleElement()
                .satisfies(job -> assertThat(job.log()).isEqualTo("BUILD FAILURE"));
        probe.probe(watch(), BootstrapProfile.empty());

        verify(store, times(1))
                .cacheLog(anyString(), eq(HEAD_SHA), eq(77L), eq("BUILD FAILURE"), anyLong());
        verify(github, times(1)).fetchCheckRunLog(anyString(), any(), eq(77L));
    }

    private void stubFailingCheck()
    {
        when(pats.resolve(anyString())).thenReturn("pat");
        when(github.fetchPrDetail(anyString(), any())).thenReturn(detail());
        when(github.fetchPrCheckRunsStrict(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(new PrCheckRunState(
                        77L, "test (core)", "completed", "failure",
                        "https://github.com/o/r/actions/runs/5/job/6", null, null)));
    }

    private static Watch watch()
    {
        return new Watch(
                "watch-1", "ws-1", "o", "r", 3, "pr-1", "/tmp/wt", "branch", "title",
                WatchStatus.WATCHING, HEAD_SHA, "COMPLETED", "{}", 1_000L, 0L, null,
                0L, 0L, null, null, null);
    }

    private static PrRawDetail detail()
    {
        return new PrRawDetail(
                "", List.of(), false, true, "clean", 0, 0, 0, 0, List.of(),
                HEAD_SHA, "branch", "o/r", "main", "o/r", "open", false, "base123", null);
    }
}
