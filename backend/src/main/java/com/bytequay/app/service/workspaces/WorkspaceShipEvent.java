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
package com.bytequay.app.service.workspaces;

/**
 * Application event fired whenever a task successfully ships or
 * parks. {@link ShipEventMemoryTrigger} listens to it and runs the
 * workspace-memory distiller (dedupped to {@code DEDUP_WINDOW}).
 *
 * <p>Using an event instead of a direct service call keeps
 * {@code TaskService} from accumulating memory-side dependencies —
 * the only thing it has to know about is "fire a workspace ship event."
 */
public record WorkspaceShipEvent(String workspaceId, String threadId, String taskId) {}
