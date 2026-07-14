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

import com.bytequay.app.domain.FootprintStop;
import com.bytequay.app.domain.FootprintsTrail;
import com.bytequay.app.domain.SurfaceType;
import com.bytequay.app.domain.SurfaceVisit;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.repository.SurfaceVisitStore;
import com.bytequay.app.repository.ThreadStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/**
 * Footprints — records visits to tracked surfaces and (later) reads them
 * back as a calendar-day trail. The write path is fire-and-forget from
 * the renderer's navigation layer; it never blocks navigation.
 */
@Service
public class FootprintsService
{
    /** Most-recent surfaces shown on the home trail; the rest live behind
     *  "see full day". Matches the design's "last ~8" cap. */
    static final int HOME_STOP_CAP = 8;

    private final SurfaceVisitStore store;
    private final ThreadStore threadStore;

    public FootprintsService(SurfaceVisitStore store, ThreadStore threadStore)
    {
        this.store = requireNonNull(store, "store is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
    }

    /**
     * The footprints trail for one calendar day in the given zone. Visits
     * are merged per surface (latest visit fixes the position and carries
     * the title snapshot; {@code visitCount} counts all of them), the most
     * recent {@link #HOME_STOP_CAP} surfaces are kept, and the result is
     * ordered chronologically (oldest first; the last stop is the
     * latest — "you are here"). {@code totalStops} reports the pre-cap
     * distinct-surface count.
     */
    public FootprintsTrail trailForDay(LocalDate date, ZoneId zone)
    {
        requireNonNull(date, "date is null");
        requireNonNull(zone, "zone is null");
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();

        // findVisitedBetween is oldest-first, so the last visit seen for a
        // surface key is its latest — that's the snapshot we keep.
        Map<String, FootprintStop> bySurface = new LinkedHashMap<>();
        for (SurfaceVisit visit : store.findVisitedBetween(start, end)) {
            if (!isVisible(visit)) {
                continue;
            }
            String key = visit.surfaceType().name() + '\0' + visit.surfaceId();
            FootprintStop prior = bySurface.get(key);
            int count = prior == null ? 1 : prior.visitCount() + 1;
            bySurface.put(key, new FootprintStop(
                    visit.surfaceType(),
                    visit.surfaceId(),
                    visit.title(),
                    visit.context(),
                    visit.visitedAt(),
                    count));
        }

        int totalStops = bySurface.size();
        List<FootprintStop> stops = bySurface.values().stream()
                .sorted(comparing(FootprintStop::latestVisitAt).reversed())
                .limit(HOME_STOP_CAP)
                .sorted(comparing(FootprintStop::latestVisitAt))
                .collect(toImmutableList());
        return new FootprintsTrail(date, stops, totalStops);
    }

    /**
     * The {@link SurfaceType#PR_KANBAN} bookmark row (visits to the My-PRs
     * board) never earns a trail spot — it's a static nav shortcut, not
     * activity. A {@link SurfaceType#THREAD} visit only earns a spot when
     * it's a review thread, or a build thread the human has actually
     * typed into — an untouched build thread's plain work is already
     * represented by its Task rows elsewhere, so surfacing it here too
     * would just be noise. Every other surface type is always visible. A
     * thread that's since been deleted is dropped rather than risk a
     * stale/misleading row.
     */
    private boolean isVisible(SurfaceVisit visit)
    {
        if (visit.surfaceType() == SurfaceType.PR_KANBAN) {
            return false;
        }
        if (visit.surfaceType() != SurfaceType.THREAD) {
            return true;
        }
        return threadStore.findThreadById(visit.surfaceId())
                .map(t -> t.flow() == ThreadFlow.REVIEW || threadStore.countUserMessages(t.id()) > 0)
                .orElse(false);
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
