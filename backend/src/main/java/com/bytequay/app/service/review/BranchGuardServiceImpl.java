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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static java.util.Objects.requireNonNull;

@Service
class BranchGuardServiceImpl
        implements BranchGuardService
{
    private final BranchGuardStore store;

    BranchGuardServiceImpl(BranchGuardStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    @Override
    @Transactional
    public BranchGuard get(String taskId)
    {
        return store.findByTask(taskId).orElseGet(() -> store.save(BranchGuard.disabled(taskId)));
    }

    @Override
    @Transactional
    public BranchGuard update(String taskId, Boolean enabled, String schedule)
    {
        BranchGuard guard = get(taskId);
        if (enabled != null) {
            guard = guard.withEnabled(enabled);
        }
        if (schedule != null && !schedule.isBlank()) {
            guard = guard.withSchedule(schedule);
        }
        return store.save(guard);
    }

    @Override
    @Transactional
    public void enableOnFirstPush(String taskId)
    {
        if (store.findByTask(taskId).isPresent()) {
            return;
        }
        store.save(BranchGuard.disabled(taskId).withEnabled(true));
    }

    /** First push onto the remote spine arms the guard (R18 default: on for
     *  pushed PRs). Fires on every PUSHED_AWAITING_CI observation, but
     *  {@link #enableOnFirstPush} only acts the first time — subsequent
     *  pushes are no-ops here regardless of whether the user later
     *  disabled it. */
    @EventListener
    public void onPhaseTransitioned(TaskPhaseTransitionedEvent event)
    {
        if (event.to() == TaskPhase.PUSHED_AWAITING_CI) {
            enableOnFirstPush(event.taskId());
        }
    }
}
