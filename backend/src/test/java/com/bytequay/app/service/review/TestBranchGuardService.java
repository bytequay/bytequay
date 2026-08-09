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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.repository.BranchGuardStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses a small stateful fake store so saved updates are visible to reads.
 */
class TestBranchGuardService
{
    private static final String TASK_ID = "task1";

    private final FakeStore store = new FakeStore();
    private final BranchGuardServiceImpl service = new BranchGuardServiceImpl(store);

    @Test
    void getNeverPersistsAPlaceholderRow()
    {
        BranchGuard guard = service.get(TASK_ID);

        assertThat(guard.enabled()).isFalse();
        assertThat(store.row).isNull();
    }

    @Test
    void updatePersistsAnExplicitEnabledChange()
    {
        BranchGuard updated = service.update(TASK_ID, true, null);

        assertThat(updated.enabled()).isTrue();
        assertThat(store.row.enabled()).isTrue();
    }

    @Test
    void explicitlyReEnablingAParkedGuardDoesNotRecoverIt()
    {
        store.row = BranchGuard.disabled(TASK_ID)
                .withEnabled(true)
                .withState(BranchGuard.STATE_NEEDS_ATTENTION);

        BranchGuard updated = service.update(TASK_ID, true, null);

        assertThat(updated.enabled()).isTrue();
        assertThat(updated.state()).isEqualTo(BranchGuard.STATE_NEEDS_ATTENTION);
    }

    private static final class FakeStore
            implements BranchGuardStore
    {
        private BranchGuard row;

        @Override
        public BranchGuard save(BranchGuard guard)
        {
            row = guard;
            return guard;
        }

        @Override
        public Optional<BranchGuard> findByTask(String taskId)
        {
            return Optional.ofNullable(row);
        }
    }
}
