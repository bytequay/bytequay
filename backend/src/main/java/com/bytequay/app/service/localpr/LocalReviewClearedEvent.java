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
package com.bytequay.app.service.localpr;

/**
 * Published when the dev-end brain review round (R21) concludes and the
 * task's local PR flips {@code local-drafted → local-open} — the point
 * where a human would otherwise need to click Push on the Local Review
 * page. {@code auto_merge} listens for this to push automatically instead
 * of waiting for that click; {@code approved} is false when the round
 * concluded on budget exhaustion (escalated to the user, R23) rather than a
 * clean verdict — auto-push only fires on a clean approval.
 *
 * @param taskId   the task whose PR just reached local-open
 * @param prId     the local PR id
 * @param approved whether the brain's verdict was a clean {@code approved},
 *                 as opposed to an escalation after the review budget ran out
 */
public record LocalReviewClearedEvent(String taskId, String prId, boolean approved)
{
}
