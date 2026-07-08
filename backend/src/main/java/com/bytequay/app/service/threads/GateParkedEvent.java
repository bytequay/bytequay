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
 * Published when an {@code AWAITING_REVIEW} publish gate is parked. The
 * auto-approve listener consumes it to approve the gate on the user's behalf
 * when its task is in auto-approve mode — merge stays the one gate that
 * never auto-resolves, and mark-ready is the one gate that always does.
 * Carries the payload so the listener can read the gate's action without
 * re-fetching, and is delivered after the parking transaction commits so the
 * approve runs against a persisted gate.
 *
 * @param notificationId the parked gate's notification id
 * @param taskId         the task the gate belongs to (null for thread-level)
 * @param payloadJson    the gate payload (carries {@code action})
 */
public record GateParkedEvent(String notificationId, String taskId, String payloadJson)
{
}
