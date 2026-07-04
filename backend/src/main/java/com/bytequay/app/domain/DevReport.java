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
package com.bytequay.app.domain;

import java.time.Instant;
import java.util.List;

/**
 * The dev agent's typed handoff to whatever addresses review comments
 * next — its last act before the local PR flips to {@code local-open}
 * (plan-rail-runs.md R14). Written once while the agent still has full
 * context; {@code read_dev_conversation} covers the rare deep dive into
 * the full transcript instead of copying it wholesale into every round.
 *
 * @param summary one-line-ish summary of the change, &lt;= ~160 chars
 * @param decisions what was decided, why, and what was rejected
 * @param invariants things future edits must not break
 * @param trickySpots files/areas that aren't as simple as they look
 * @param testMap which tests cover which areas, for a reviewer verifying a fix
 * @param followups known gaps or deferred work
 */
public record DevReport(
        String id,
        String taskId,
        String summary,
        List<Decision> decisions,
        List<String> invariants,
        List<TrickySpot> trickySpots,
        List<TestMapEntry> testMap,
        List<String> followups,
        Instant createdAt)
{
    public record Decision(String what, String why, List<String> rejectedAlternatives) {}

    public record TrickySpot(String file, String note) {}

    public record TestMapEntry(String area, List<String> tests) {}
}
