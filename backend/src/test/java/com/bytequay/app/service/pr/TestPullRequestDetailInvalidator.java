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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPullRequestDetailInvalidator
{
    @Test
    void testInvalidateClearsSqliteAndResponseCaches()
    {
        PullRequestStore store = mock(PullRequestStore.class);
        PrDetailStore detailStore = mock(PrDetailStore.class);
        GitHubResponseCache responseCache = mock(GitHubResponseCache.class);
        PullRequestDetailInvalidator invalidator = new PullRequestDetailInvalidator(
                store,
                detailStore,
                responseCache);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(123L));

        invalidator.invalidate("owner/repo", 7);

        verify(detailStore).deleteByPrIds(ImmutableSet.of(123L));
        verify(responseCache).invalidatePullRequest(PullRequestRef.of("owner", "repo", 7));
    }
}
