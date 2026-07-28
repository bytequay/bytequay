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
package com.bytequay.app.developmentflow.stage;

/** Kind-bound recovery checkpoint. Failures leave the owner at this checkpoint. */
public enum StageCheckpoint
{
    DRAFTING,
    SELF_REVIEW,
    AWAITING_APPROVAL,

    IMPLEMENTING,
    VALIDATING,
    BRAIN_REVIEW,
    LOCAL_REVIEW,
    PUBLISHING,
    ADDRESSING_BRAIN_FINDINGS,
    ADDRESSING_LOCAL_FEEDBACK,

    WAITING_CI,
    AWAITING_READY,
    WAITING_REMOTE_REVIEW,
    ADDRESSING_REMOTE_FEEDBACK,
    READY_TO_MERGE,
    MERGING,

    WAITING_QUIESCENCE,
    CLEANING,

    COMPLETED;

    public boolean belongsTo(StageKind expectedKind)
    {
        return switch (this) {
            case DRAFTING, SELF_REVIEW, AWAITING_APPROVAL ->
                    expectedKind == StageKind.PLAN;
            case IMPLEMENTING, VALIDATING, BRAIN_REVIEW, LOCAL_REVIEW,
                    PUBLISHING, ADDRESSING_BRAIN_FINDINGS,
                    ADDRESSING_LOCAL_FEEDBACK ->
                    expectedKind == StageKind.LOCAL_DEVELOPMENT;
            case WAITING_CI, AWAITING_READY, WAITING_REMOTE_REVIEW,
                    ADDRESSING_REMOTE_FEEDBACK, READY_TO_MERGE, MERGING ->
                    expectedKind == StageKind.REMOTE_DEVELOPMENT;
            case WAITING_QUIESCENCE, CLEANING -> expectedKind == StageKind.CLEANUP;
            case COMPLETED -> true;
        };
    }

    boolean allowsStructuralTransition(StageCheckpoint target)
    {
        return switch (this) {
            case DRAFTING -> target == SELF_REVIEW;
            case SELF_REVIEW -> target == DRAFTING || target == AWAITING_APPROVAL;
            case AWAITING_APPROVAL -> target == DRAFTING || target == COMPLETED;
            case IMPLEMENTING -> target == VALIDATING;
            case VALIDATING -> target == BRAIN_REVIEW;
            case BRAIN_REVIEW -> target == LOCAL_REVIEW
                    || target == ADDRESSING_BRAIN_FINDINGS;
            case ADDRESSING_BRAIN_FINDINGS -> target == IMPLEMENTING;
            case LOCAL_REVIEW -> target == ADDRESSING_LOCAL_FEEDBACK
                    || target == PUBLISHING;
            case ADDRESSING_LOCAL_FEEDBACK -> target == IMPLEMENTING;
            case PUBLISHING -> target == COMPLETED;
            case WAITING_CI -> target == AWAITING_READY;
            case AWAITING_READY -> target == WAITING_REMOTE_REVIEW;
            case WAITING_REMOTE_REVIEW -> target == ADDRESSING_REMOTE_FEEDBACK
                    || target == READY_TO_MERGE;
            case ADDRESSING_REMOTE_FEEDBACK -> target == WAITING_CI
                    || target == WAITING_REMOTE_REVIEW;
            case READY_TO_MERGE -> target == MERGING;
            case MERGING -> target == READY_TO_MERGE || target == COMPLETED;
            case WAITING_QUIESCENCE -> target == CLEANING;
            case CLEANING -> target == COMPLETED;
            case COMPLETED -> false;
        };
    }
}
