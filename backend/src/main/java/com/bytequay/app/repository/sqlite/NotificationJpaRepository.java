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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface NotificationJpaRepository
        extends JpaRepository<NotificationEntity, String>
{
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationEntity n
               set n.status = 'RESOLVING',
                   n.readAtMs = case when n.readAtMs is null then :readAtMs else n.readAtMs end
             where n.id = :id
               and n.status in ('UNREAD', 'READ')
            """)
    int claimResolution(@Param("id") String id, @Param("readAtMs") long readAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationEntity n
               set n.status = 'RESOLVED'
             where n.id = :id
               and n.status = 'RESOLVING'
            """)
    int finishResolution(@Param("id") String id);

    /**
     * Release a claim back to UNREAD when an approve was rejected before
     * it changed any remote state. Returns the row to the actionable
     * (and bell-visible) feed for a retry. No-op unless the row is
     * currently RESOLVING.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationEntity n
               set n.status = 'UNREAD',
                   n.readAtMs = null
             where n.id = :id
               and n.status = 'RESOLVING'
            """)
    int releaseResolution(@Param("id") String id);

    /**
     * Atomically flip an UNREAD row to READ (stamping read_at), or
     * repair a legacy READ row that never got a timestamp. Resolving and
     * terminal (RESOLVED / DISMISSED) rows are left untouched, so this
     * can never clobber an in-flight claim. Whether a parked
     * AWAITING_REVIEW row should be marked read is the caller's decision:
     * an explicit jump-in quiets it on purpose, while a passive click in
     * the bell / strip must not (that guard lives in the UI).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationEntity n
               set n.status = 'READ',
                   n.readAtMs = :readAtMs
             where n.id = :id
               and (n.status = 'UNREAD'
                    or (n.status = 'READ' and n.readAtMs is null))
            """)
    int markRead(@Param("id") String id, @Param("readAtMs") long readAtMs);

    /**
     * Atomically set a row to DISMISSED, preserving any existing
     * read_at. A RESOLVING row is left untouched: an in-flight claim
     * must never be clobbered to DISMISSED out from under the
     * approve/discard that holds it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationEntity n
               set n.status = 'DISMISSED',
                   n.readAtMs = case when n.readAtMs is null then :readAtMs else n.readAtMs end
             where n.id = :id
               and n.status <> 'RESOLVING'
            """)
    int dismiss(@Param("id") String id, @Param("readAtMs") long readAtMs);

    /** Newest-first feed for the bell. */
    List<NotificationEntity> findAllByOrderByCreatedAtMsDesc(Pageable pageable);

    /** Status filter (newest-first), used to build the badge/action list. */
    List<NotificationEntity> findByStatusOrderByCreatedAtMsDesc(String status, Pageable pageable);

    /** Per-thread feed for the auto* row in the thread list. */
    List<NotificationEntity> findByThreadIdOrderByCreatedAtMsDesc(String threadId, Pageable pageable);
}
