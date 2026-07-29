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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Routes the shared effect executor to its Task or zero-Task ledger. */
public final class CompositeUserRemoteActionStore
        implements UserRemoteActionOperationHandler.OperationStore
{
    private static final String EXTERNAL_ID_PREFIX = "v2-external-pr-action-";

    private final SqliteUserRemoteActionStore taskActions;
    private final SqliteExternalPrActionStore externalActions;

    public CompositeUserRemoteActionStore(
            SqliteUserRemoteActionStore taskActions,
            SqliteExternalPrActionStore externalActions)
    {
        this.taskActions = requireNonNull(taskActions, "taskActions is null");
        this.externalActions = requireNonNull(
                externalActions, "externalActions is null");
    }

    @Override
    public Action require(String operationId)
    {
        try {
            return taskActions.require(operationId);
        }
        catch (IllegalStateException taskMissing) {
            return externalActions.require(operationId);
        }
    }

    @Override
    public Action claim(
            String actionId, int expectedAttemptCount, ClaimMode mode,
            String claimOwner, Instant claimedAt, Instant leaseUntil)
    {
        return store(actionId).claim(
                actionId, expectedAttemptCount, mode, claimOwner, claimedAt,
                leaseUntil);
    }

    @Override
    public void finishSucceeded(
            String actionId, int attempt, String externalEffectId,
            String evidence, Instant completedAt)
    {
        store(actionId).finishSucceeded(
                actionId, attempt, externalEffectId, evidence, completedAt);
    }

    @Override
    public void finishFailed(
            String actionId, int attempt, String error, Instant completedAt)
    {
        store(actionId).finishFailed(actionId, attempt, error, completedAt);
    }

    @Override
    public void finishIndeterminate(
            String actionId, int attempt, String evidence, Instant completedAt)
    {
        store(actionId).finishIndeterminate(
                actionId, attempt, evidence, completedAt);
    }

    @Override
    public void finishCanceled(
            String actionId, int attempt, String error, Instant completedAt)
    {
        store(actionId).finishCanceled(actionId, attempt, error, completedAt);
    }

    @Override
    public void recordRecoveryBaseline(
            String actionId, int attempt, List<String> remoteEffectIds)
    {
        store(actionId).recordRecoveryBaseline(
                actionId, attempt, remoteEffectIds);
    }

    @Override
    public void deferProbe(
            String actionId, int attempt, Instant retryAt, String evidence)
    {
        store(actionId).deferProbe(actionId, attempt, retryAt, evidence);
    }

    private UserRemoteActionOperationHandler.OperationStore store(
            String actionId)
    {
        return requireNonNull(actionId, "actionId is null")
                .startsWith(EXTERNAL_ID_PREFIX)
                ? externalActions : taskActions;
    }
}
