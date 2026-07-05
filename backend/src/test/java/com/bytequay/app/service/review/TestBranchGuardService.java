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
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.BranchGuardStore;
import com.bytequay.app.service.threads.TaskPhaseTransitionedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "guard never arms" bug: the stage/brain views call {@code get} on
 * every load, long before a task ever pushes. A prior version of {@code get}
 * lazily persisted a disabled row on that read, so by the time the real
 * first-push event fired, {@code enableOnFirstPush}'s "only act if absent"
 * check found a row already there and silently skipped arming it — the
 * guard stayed disabled forever despite having pushed. Uses a small
 * stateful fake store, not a plain mock, because reproducing the bug
 * depends on a save() from one call actually being visible to a later
 * findByTask() — a bare mocked return value can't capture that.
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
    void firstPushStillArmsTheGuardEvenAfterAnEarlierReadSawNoRow()
    {
        // The view loaded before the task ever pushed — must leave no trace.
        service.get(TASK_ID);
        assertThat(store.row).isNull();

        service.onPhaseTransitioned(new TaskPhaseTransitionedEvent(
                TASK_ID, TaskPhase.AWAITING_PUSH, TaskPhase.PUSHED_AWAITING_CI, "shipped_draft_pr_open"));

        assertThat(store.row).isNotNull();
        assertThat(store.row.enabled()).isTrue();
    }

    @Test
    void firstPushIsANoOpOnceARowAlreadyExists()
    {
        store.row = BranchGuard.disabled(TASK_ID);

        service.onPhaseTransitioned(new TaskPhaseTransitionedEvent(
                TASK_ID, TaskPhase.AWAITING_PUSH, TaskPhase.PUSHED_AWAITING_CI, "shipped_draft_pr_open"));

        assertThat(store.row.enabled()).isFalse();
    }

    @Test
    void updatePersistsAnExplicitEnabledChange()
    {
        BranchGuard updated = service.update(TASK_ID, true, null);

        assertThat(updated.enabled()).isTrue();
        assertThat(store.row.enabled()).isTrue();
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

        @Override
        public List<BranchGuard> findEnabled()
        {
            return row != null && row.enabled() ? List.of(row) : List.of();
        }
    }
}
