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
package com.bytequay.app.beans.stage;

import java.util.List;

/**
 * One chronological row of the brain feed. In this milestone only the
 * {@code STAGE_OPENED} / {@code STAGE_CLOSED} types are produced (derived
 * from {@code task_stage_event}); the conversational and iteration-summary
 * types fill in as later milestones write them.
 *
 * <p>A {@code STAGE_CLOSED} row's {@code body} carries the stage's rollup
 * inline — e.g. {@code "CI-fixing finished · 3m · 30k tokens · 2 files"} —
 * so the frontend renders the duration / token / changed-file counts
 * through the same markdown path as every other feed row.
 *
 * @param body markdown
 * @param images attached-screenshot file paths — only ever non-empty on a
 *               {@code USER_MESSAGE} row
 */
public record BrainFeedRow(
        String id,
        Long messageSeq,
        String type,
        String stageId,
        String stageType,
        String ts,
        String body,
        String referencedStageId,
        List<String> images)
{
}
