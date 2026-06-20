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
package com.bytequay.app.service.footprints;

import com.bytequay.app.domain.SurfaceType;
import com.bytequay.app.domain.SurfaceVisit;
import com.bytequay.app.repository.SurfaceVisitStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Footprints — records visits to tracked surfaces and (later) reads them
 * back as a calendar-day trail. The write path is fire-and-forget from
 * the renderer's navigation layer; it never blocks navigation.
 */
@Service
public class FootprintsService
{
    private final SurfaceVisitStore store;

    public FootprintsService(SurfaceVisitStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /**
     * Records a visit at the current instant. {@code title} / {@code context}
     * are an optional label snapshot; {@code surfaceId} is the renderer's
     * navigable key, stored verbatim.
     */
    public SurfaceVisit recordVisit(SurfaceType surfaceType, String surfaceId, String title, String context)
    {
        requireNonNull(surfaceType, "surfaceType is null");
        requireNonNull(surfaceId, "surfaceId is null");
        return store.record(new SurfaceVisit(
                UUID.randomUUID().toString(),
                surfaceType,
                surfaceId,
                title,
                context,
                Instant.now()));
    }
}
