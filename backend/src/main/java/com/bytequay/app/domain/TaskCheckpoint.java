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
 * One row of {@code task_checkpoints} — an AI-written summary of a
 * conversation chunk.
 *
 * <p>Two flavours share this shape (the dev doc at
 * {@code docs/mockups/conversation-index-and-checkpoints-design.md}
 * is authoritative):
 * <ul>
 *   <li><b>Overall</b> — exactly one active per task, with
 *       {@code seq == 0} and {@code isOverall == true}. Regenerated
 *       every time a new per-segment checkpoint lands; the prior
 *       row gets its {@code supersededAt} stamped so we keep a
 *       time-travel-able history of how the rollup evolved.</li>
 *   <li><b>Per-segment</b> — {@code seq >= 1}, covers a consecutive
 *       turn range. Auto-generated when the tokens since the
 *       previous checkpoint cross the scheduler's threshold (default
 *       ~25k); never superseded.</li>
 * </ul>
 *
 * <p>Distinct from snapshots ({@code git stash} rewind points) —
 * checkpoints summarise state, snapshots rewind it. The two live
 * in different rail sections and shouldn't be conflated.
 *
 * @param seq {@code 0} for Overall, {@code 1+} for per-segment rows.
 * @param firstMsgSeq inclusive lower bound of the covered turn range.
 * @param lastMsgSeq inclusive upper bound of the covered turn range.
 * @param tokensCovered sum of tokens across the covered range.
 * @param summaryMd AI-written summary as Markdown.
 * @param bulletTitles short preview titles for the rail.
 * @param modelUsed model that produced the summary.
 * @param supersededAt set on Overall rows when a newer Overall replaces them.
 */
public record TaskCheckpoint(
        String id,
        String taskId,
        long seq,
        boolean isOverall,
        long firstMsgSeq,
        long lastMsgSeq,
        long tokensCovered,
        String summaryMd,
        List<String> bulletTitles,
        String modelUsed,
        long promptTokens,
        long completionTokens,
        long costUsdMilli,
        Instant generatedAt,
        Instant supersededAt)
{
}
