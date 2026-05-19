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
 */
public record TaskCheckpoint(
        String id,
        String taskId,
        /** {@code 0} for Overall, {@code 1+} for per-segment rows. */
        long seq,
        boolean isOverall,
        /** Inclusive lower bound of the covered turn range. */
        long firstMsgSeq,
        /** Inclusive upper bound of the covered turn range. For
         *  Overall this is the max {@code task_messages.seq} at the
         *  moment the rollup was generated. */
        long lastMsgSeq,
        /** Sum of {@code tokens_in + tokens_out} across the covered
         *  range — drives the rail's "31.4k tok" label and the
         *  scheduler threshold check. */
        long tokensCovered,
        /** AI-written summary, Markdown. */
        String summaryMd,
        /** 1–3 short bullet titles the rail uses as a preview without
         *  parsing the full Markdown. */
        List<String> bulletTitles,
        /** Provenance — which model produced the summary, what it
         *  cost. Helps when we change models or want to budget. */
        String modelUsed,
        long promptTokens,
        long completionTokens,
        long costUsdMilli,
        Instant generatedAt,
        /** Set on Overall rows when a newer Overall replaces them;
         *  {@code null} on per-segment rows and on the currently
         *  active Overall. */
        Instant supersededAt)
{
}
