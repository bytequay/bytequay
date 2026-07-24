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

/**
 * Fired instead of {@link TaskTurnFinishedEvent} when accounting for a
 * completed task turn pauses its AgentRun at a budget cap. Coordinators use
 * this distinct signal to park their episode rather than advancing it as a
 * normal completion.
 */
public record TaskTurnBudgetPausedEvent(String taskId, String turnId) {}
