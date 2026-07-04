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

import java.util.List;
import java.util.Optional;

/**
 * Owns the {@link DevReport} handoff — the dev agent's typed last act
 * before the local PR flips to {@code local-open} (plan-rail-runs.md
 * R14). {@link #record} upserts (idempotent update until the flip);
 * {@link #ensurePlaceholder} guarantees a row exists even if the agent
 * never called {@code record_dev_report} before the flip fired via the
 * sync-service fallback path, mirroring {@code IterationService}'s
 * summary-placeholder pattern so downstream readers never hit a gap.
 */
public interface DevReportService
{
    DevReport record(
            String taskId, String summary, List<Decision> decisions, List<String> invariants,
            List<TrickySpot> trickySpots, List<TestMapEntry> testMap, List<String> followups);

    Optional<DevReport> find(String taskId);

    /** No-op if a report already exists; otherwise writes a minimal
     *  placeholder so a round's {@code read_dev_report} call never 404s. */
    DevReport ensurePlaceholder(String taskId);
}
