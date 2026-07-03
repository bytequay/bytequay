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
package com.bytequay.app.repository;

import com.bytequay.app.domain.SurfaceVisit;

import java.time.Instant;
import java.util.List;

public interface SurfaceVisitStore
{
    /** Persists a visit and returns the stored row. */
    SurfaceVisit record(SurfaceVisit visit);

    /** Visits in the half-open window {@code [startInclusive, endExclusive)},
     *  ordered oldest-first. Used to build a calendar-day trail. */
    List<SurfaceVisit> findVisitedBetween(Instant startInclusive, Instant endExclusive);

    /** Delete every visit to a thread's own surface or any of its task surfaces.
     *  Task surfaces use a "{threadId}/{taskId}" surface id, so a prefix match
     *  on "{threadId}/" plus the exact thread id covers both. Returns the count. */
    int deleteByThread(String threadId);
}
