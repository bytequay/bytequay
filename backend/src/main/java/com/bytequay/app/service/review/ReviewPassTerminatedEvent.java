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
package com.bytequay.app.service.review;

/**
 * Fired when a review pass settles to its terminal phase (TERMINATE) and
 * is linked to a callable {@code REVIEW_STAGE} — i.e. it was spawned from
 * an internal-review context. A stage-package listener reacts by closing
 * that stage; standalone (THREAD-hosted) passes carry no stage link and
 * never fire this. A pass parked at ARBITRATE is not done and does not
 * fire — it finalizes again, to TERMINATE, once the human ballot resolves.
 *
 * @param passId       the settled review pass
 * @param taskStageId  the REVIEW_STAGE row the pass was spawned for
 */
public record ReviewPassTerminatedEvent(String passId, String taskStageId)
{
}
