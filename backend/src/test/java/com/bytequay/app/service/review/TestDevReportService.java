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

import com.bytequay.app.domain.DevReport;
import com.bytequay.app.domain.DevReport.Decision;
import com.bytequay.app.repository.DevReportStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDevReportService
{
    private static final Instant NOW = Instant.parse("2026-07-05T00:00:00Z");
    private static final String TASK_ID = "t1.k1";

    private final DevReportStore store = mock(DevReportStore.class);
    private final DevReportServiceImpl service =
            new DevReportServiceImpl(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void recordUpsertsTheSameRowOnASecondCall()
    {
        DevReport first = new DevReport(
                "report1", TASK_ID, "first summary", List.of(), List.of(), List.of(), List.of(), List.of(), NOW);
        when(store.findByTask(TASK_ID)).thenReturn(Optional.empty(), Optional.of(first));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DevReport created = service.record(TASK_ID, "first summary", null, null, null, null, null);
        DevReport updated = service.record(
                TASK_ID, "revised summary",
                List.of(new Decision("used X", "simpler", List.of("Y"))), List.of("don't break Z"),
                null, null, null);

        assertThat(created.id()).isNotBlank();
        assertThat(updated.id()).isEqualTo(first.id());
        assertThat(updated.summary()).isEqualTo("revised summary");
        assertThat(updated.decisions()).hasSize(1);
        assertThat(updated.invariants()).containsExactly("don't break Z");
    }

    @Test
    void nullListsRecordAsEmptyNotNull()
    {
        when(store.findByTask(TASK_ID)).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DevReport report = service.record(TASK_ID, "summary", null, null, null, null, null);

        assertThat(report.decisions()).isEmpty();
        assertThat(report.invariants()).isEmpty();
        assertThat(report.trickySpots()).isEmpty();
        assertThat(report.testMap()).isEmpty();
        assertThat(report.followups()).isEmpty();
    }

    @Test
    void ensurePlaceholderIsANoOpWhenAReportAlreadyExists()
    {
        DevReport existing = new DevReport(
                "report1", TASK_ID, "real summary", List.of(), List.of(), List.of(), List.of(), List.of(), NOW);
        when(store.findByTask(TASK_ID)).thenReturn(Optional.of(existing));

        DevReport result = service.ensurePlaceholder(TASK_ID);

        assertThat(result).isEqualTo(existing);
        verify(store, never()).save(any());
    }

    @Test
    void ensurePlaceholderWritesAMinimalReportWhenNoneExists()
    {
        when(store.findByTask(TASK_ID)).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DevReport result = service.ensurePlaceholder(TASK_ID);

        assertThat(result.summary()).isEqualTo("[no dev report recorded]");
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.createdAt()).isEqualTo(NOW);
    }
}
