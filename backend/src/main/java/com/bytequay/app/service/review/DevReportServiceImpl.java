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
import com.bytequay.app.domain.DevReport.TestMapEntry;
import com.bytequay.app.domain.DevReport.TrickySpot;
import com.bytequay.app.repository.DevReportStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Service
class DevReportServiceImpl
        implements DevReportService
{
    private final DevReportStore store;
    private final Clock clock;

    @Autowired
    DevReportServiceImpl(DevReportStore store)
    {
        this(store, Clock.systemUTC());
    }

    DevReportServiceImpl(DevReportStore store, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DevReport record(
            String taskId, String summary, List<Decision> decisions, List<String> invariants,
            List<TrickySpot> trickySpots, List<TestMapEntry> testMap, List<String> followups)
    {
        DevReport existing = store.findByTask(taskId).orElse(null);
        String id = existing == null ? UUID.randomUUID().toString() : existing.id();
        return store.save(new DevReport(
                id, taskId, summary,
                nullToEmpty(decisions), nullToEmpty(invariants), nullToEmpty(trickySpots),
                nullToEmpty(testMap), nullToEmpty(followups), Instant.now(clock)));
    }

    @Override
    public Optional<DevReport> find(String taskId)
    {
        return store.findByTask(taskId);
    }

    @Override
    public DevReport ensurePlaceholder(String taskId)
    {
        return store.findByTask(taskId).orElseGet(() -> store.save(new DevReport(
                UUID.randomUUID().toString(), taskId,
                "[no dev report recorded]", List.of(), List.of(), List.of(), List.of(), List.of(),
                Instant.now(clock))));
    }

    private static <T> List<T> nullToEmpty(List<T> list)
    {
        return list == null ? List.of() : list;
    }
}
