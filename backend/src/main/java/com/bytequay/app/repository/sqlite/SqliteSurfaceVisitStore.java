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

import com.bytequay.app.domain.SurfaceType;
import com.bytequay.app.domain.SurfaceVisit;
import com.bytequay.app.repository.SurfaceVisitStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteSurfaceVisitStore
        implements SurfaceVisitStore
{
    private final SurfaceVisitJpaRepository jpaRepository;

    public SqliteSurfaceVisitStore(SurfaceVisitJpaRepository jpaRepository)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
    }

    @Override
    public SurfaceVisit record(SurfaceVisit visit)
    {
        requireNonNull(visit, "visit is null");
        SurfaceVisitEntity saved = jpaRepository.save(new SurfaceVisitEntity(
                visit.id(),
                visit.surfaceType().name(),
                visit.surfaceId(),
                visit.title(),
                visit.context(),
                visit.visitedAt().toEpochMilli()));
        return toDomain(saved);
    }

    @Override
    public List<SurfaceVisit> findVisitedBetween(Instant startInclusive, Instant endExclusive)
    {
        requireNonNull(startInclusive, "startInclusive is null");
        requireNonNull(endExclusive, "endExclusive is null");
        return jpaRepository
                .findByVisitedAtMsGreaterThanEqualAndVisitedAtMsLessThanOrderByVisitedAtMsAsc(
                        startInclusive.toEpochMilli(), endExclusive.toEpochMilli())
                .stream()
                .map(SqliteSurfaceVisitStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public int deleteByThread(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        return jpaRepository.deleteForThread(threadId, threadId + "/%");
    }

    private static SurfaceVisit toDomain(SurfaceVisitEntity e)
    {
        return new SurfaceVisit(
                e.getId(),
                SurfaceType.valueOf(e.getSurfaceType()),
                e.getSurfaceId(),
                e.getTitle(),
                e.getContext(),
                Instant.ofEpochMilli(e.getVisitedAtMs()));
    }
}
