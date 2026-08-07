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
import com.bytequay.app.repository.sqlite.SurfaceVisitStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the footprints read against the real Flyway-migrated SQLite
 * schema. Each test uses its own calendar day so methods sharing the
 * Spring context's database don't see each other's visits.
 */
@SpringBootTest
class TestFootprintsService
{
    private static final ZoneId ZONE = ZoneOffset.UTC;

    @Autowired
    private SurfaceVisitStore store;
    @Autowired
    private FootprintsService service;

    @Test
    void mergesRevisitsIntoOnePinWithCountAndLatestSnapshot()
    {
        LocalDate day = LocalDate.of(2030, 1, 2);
        insert(day, 9, SurfaceType.PR, "o/r#1", "first label");
        insert(day, 10, SurfaceType.PR, "o/r#1", "middle label");
        insert(day, 11, SurfaceType.PR, "o/r#1", "latest label");

        FootprintsTrail trail = service.trailForDay(day, ZONE);

        assertThat(trail.stops()).hasSize(1);
        assertThat(trail.totalStops()).isEqualTo(1);
        FootprintStop stop = trail.stops().get(0);
        assertThat(stop.visitCount()).isEqualTo(3);
        assertThat(stop.title()).isEqualTo("latest label");
        assertThat(stop.latestVisitAt()).isEqualTo(instant(day, 11));
    }

    @Test
    void ordersStopsChronologicallyOldestFirst()
    {
        LocalDate day = LocalDate.of(2030, 1, 3);
        insert(day, 11, SurfaceType.TASK, "th/k", "task");
        insert(day, 9, SurfaceType.PR, "o/r#1", "first pr");
        insert(day, 10, SurfaceType.PR, "o/r#2", "pr");

        assertThat(service.trailForDay(day, ZONE).stops())
                .extracting(FootprintStop::surfaceId)
                .containsExactly("o/r#1", "o/r#2", "th/k");
    }

    @Test
    void onlyShowsPrsAndTasks()
    {
        LocalDate day = LocalDate.of(2030, 1, 9);
        insert(day, 9, SurfaceType.THREAD, "thread", "thread");
        insert(day, 10, SurfaceType.PR_KANBAN, "my-prs", "PR kanban");
        insert(day, 11, SurfaceType.PR, "o/r#1", "pr");
        insert(day, 12, SurfaceType.TASK, "th/k", "task");

        assertThat(service.trailForDay(day, ZONE).stops())
                .extracting(FootprintStop::surfaceId)
                .containsExactly("o/r#1", "th/k");
    }

    @Test
    void hidesThePrKanbanBookmarkRow()
    {
        LocalDate day = LocalDate.of(2030, 1, 7);
        insert(day, 9, SurfaceType.PR_KANBAN, "my-prs", "PR kanban");
        insert(day, 10, SurfaceType.PR, "o/r#1", "pr");

        assertThat(service.trailForDay(day, ZONE).stops())
                .extracting(FootprintStop::surfaceId)
                .containsExactly("o/r#1");
    }

    @Test
    void capsToMostRecentStopsButReportsTotal()
    {
        LocalDate day = LocalDate.of(2030, 1, 4);
        int total = FootprintsService.HOME_STOP_CAP + 2;
        for (int hour = 0; hour < total; hour++) {
            insert(day, hour, SurfaceType.PR, "o/r#" + hour, "pr" + hour);
        }

        FootprintsTrail trail = service.trailForDay(day, ZONE);

        assertThat(trail.totalStops()).isEqualTo(total);
        assertThat(trail.stops()).hasSize(FootprintsService.HOME_STOP_CAP);
        // The two oldest (hours 0 and 1) are dropped; the rest stay ascending.
        assertThat(trail.stops().get(0).surfaceId()).isEqualTo("o/r#2");
        assertThat(trail.stops().get(FootprintsService.HOME_STOP_CAP - 1).surfaceId())
                .isEqualTo("o/r#" + (total - 1));
    }

    @Test
    void excludesVisitsOutsideTheDay()
    {
        LocalDate day = LocalDate.of(2030, 1, 5);
        insert(day.minusDays(1), 23, SurfaceType.PR, "o/r#prev", "prev");
        insert(day, 1, SurfaceType.PR, "o/r#today", "today");
        insert(day.plusDays(1), 0, SurfaceType.PR, "o/r#next", "next");

        assertThat(service.trailForDay(day, ZONE).stops())
                .extracting(FootprintStop::surfaceId)
                .containsExactly("o/r#today");
    }

    private void insert(LocalDate day, int hour, SurfaceType type, String surfaceId, String title)
    {
        store.record(new SurfaceVisit(
                UUID.randomUUID().toString(), type, surfaceId, title, null, instant(day, hour)));
    }

    private static Instant instant(LocalDate day, int hour)
    {
        return day.atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }
}
