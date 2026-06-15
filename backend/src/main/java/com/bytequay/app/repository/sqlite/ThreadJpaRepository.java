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
package com.bytequay.app.repository.sqlite;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface ThreadJpaRepository
        extends JpaRepository<ThreadEntity, String>
{
    /** Newest-{@code updated_at_ms}-first; the {@code Pageable} caps
     *  page size for the list view. */
    List<ThreadEntity> findByStatusOrderByUpdatedAtMsDesc(String status, Pageable pageable);

    /** Workspace-scoped variant of the above. The thread list reaches
     *  for this when the caller supplied a workspaceId query param so
     *  a freshly-created workspace doesn't render the default's
     *  threads. */
    List<ThreadEntity> findByStatusAndWorkspaceIdOrderByUpdatedAtMsDesc(
            String status, String workspaceId, Pageable pageable);

    /** Batched id lookup used by group membership reads — turns a
     *  list of thread ids returned from {@code thread_group_members} into
     *  the actual rows in one query, newest-first by updated_at_ms. */
    List<ThreadEntity> findByIdInOrderByUpdatedAtMsDesc(Collection<String> ids);

    /** Pulls every thread whose {@code updated_at_ms} lands at or
     *  after the supplied bound, ordered most-recent-first.
     *  Workspace Insights uses this to roll up spend / counts over
     *  a 24h / 7d / 30d window without slurping the whole table. */
    List<ThreadEntity> findByUpdatedAtMsGreaterThanEqualOrderByUpdatedAtMsDesc(long sinceMs);

    /** Threads whose queue JSON contains the given fragment — used at
     *  startup to find threads with a PENDING queue entry to (re)kick.
     *  The set is small (threads rarely carry a queue), so a substring
     *  scan is fine. */
    List<ThreadEntity> findByQueueJsonContaining(String fragment);
}
