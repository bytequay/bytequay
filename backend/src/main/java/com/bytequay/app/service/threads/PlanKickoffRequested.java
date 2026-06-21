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
package com.bytequay.app.service.threads;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Fired the moment a Task is materialised, to start the brain agent's
 * planning turn on the task's PlanStage. Carries the user's opening prompt
 * as the planning seed (it is <em>not</em> enqueued onto the dev thread —
 * development can't start until the plan is approved). The brain layer
 * listens and enqueues the planning turn via the Agent Scheduler.
 *
 * @param taskId        the freshly-created task
 * @param initialPrompt the user's opening request, or null/blank when the
 *                      task was created without one
 * @param trunkPlan     optional trunk-supplied {@code PlanResult} JSON to
 *                      seed the PlanStage with ({@code source=trunk}); null
 *                      when the task was cut without a prior plan
 */
public record PlanKickoffRequested(String taskId, String initialPrompt, JsonNode trunkPlan) {}
