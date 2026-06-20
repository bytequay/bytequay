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

/**
 * One stage in the read API. {@code type} / {@code state} carry the enum
 * name; {@code openedAt} / {@code closedAt} are ISO-8601 strings. The
 * frontend mock returns this exact shape, so field names are part of the
 * contract.
 *
 * @param closedAt null while the stage is open
 * @param callerStageId null for the four top-level stages; set for a
 *                      callable review sub-stage
 * @param loopIteration 0 for non-loop stages (populated when the loop
 *                      machinery lands)
 */
public record StageDto(
        String id,
        String taskId,
        String type,
        String state,
        String openedAt,
        String closedAt,
        String callerStageId,
        String summary,
        int loopIteration)
{
}
