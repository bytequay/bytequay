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
 * One {@code task_stage_event} row in the read API. {@code eventType}
 * carries the enum name; {@code eventAt} is ISO-8601; {@code payloadJson}
 * is the raw event payload blob (or null).
 */
public record StageEventDto(
        String id,
        String stageId,
        String taskId,
        String eventType,
        String eventAt,
        String payloadJson)
{
}
