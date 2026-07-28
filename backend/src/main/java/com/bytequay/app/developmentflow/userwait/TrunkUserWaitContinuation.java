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
package com.bytequay.app.developmentflow.userwait;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.trunk.V2ThreadControlService;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Owner-specific, restart-safe continuation for answered Trunk waits. */
@Component
public final class TrunkUserWaitContinuation
        implements ExecutionPorts.MaintenanceWork
{
    private static final Logger log = LoggerFactory.getLogger(
            TrunkUserWaitContinuation.class);
    private static final int SWEEP_LIMIT = 32;

    private final V2UserWaitStore waits;
    private final ThreadStore threads;
    private final ObjectProvider<V2ThreadControlService> controls;

    public TrunkUserWaitContinuation(
            V2UserWaitStore waits,
            ThreadStore threads,
            ObjectProvider<V2ThreadControlService> controls)
    {
        this.waits = requireNonNull(waits, "waits is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.controls = requireNonNull(controls, "controls is null");
    }

    public void resumeQuestion(String questionId)
    {
        V2UserWaitStore.Question question = waits.findQuestion(questionId)
                .orElse(null);
        if (question == null
                || question.owner().kind()
                    != DispatchTicket.OwnerKind.THREAD_TURN
                || !question.continuationState().equals("READY")) {
            return;
        }
        resume(
                "QUESTION", question.id(), question.owner(),
                question.answer());
    }

    public void resumePermission(String permissionId)
    {
        V2UserWaitStore.PermissionRequest permission =
                waits.findPermissionById(permissionId).orElse(null);
        if (permission == null
                || permission.owner().kind()
                    != DispatchTicket.OwnerKind.THREAD_TURN
                || !permission.continuationState().equals("READY")) {
            return;
        }
        resume(
                "PERMISSION", permission.id(), permission.owner(),
                permission.answer());
    }

    @Override
    public void maintain(Instant ignored)
    {
        for (V2UserWaitStore.ReadyContinuation ready
                : waits.listReadyThreadContinuations(SWEEP_LIMIT)) {
            try {
                if (ready.waitKind().equals("QUESTION")) {
                    resumeQuestion(ready.waitId());
                }
                else {
                    resumePermission(ready.waitId());
                }
            }
            catch (RuntimeException e) {
                log.warn("Could not resume typed Trunk {} {}",
                        ready.waitKind(), ready.waitId(), e);
            }
        }
    }

    private void resume(
            String waitKind,
            String waitId,
            ActiveAgentContextRegistry.TypedOwner owner,
            String answer)
    {
        V2UserWaitStore.UserWaitReceipt receipt = waits
                .findUserWaitResult(owner.operationId()).orElse(null);
        if (receipt == null) {
            return;
        }
        if (!receipt.owner().equals(owner)
                || !receipt.waitKind().equals(waitKind)
                || !receipt.waitId().equals(waitId)) {
            throw new IllegalStateException(
                    "ready continuation does not match its user-wait receipt");
        }
        V2ThreadControlService control = controls.getIfAvailable();
        if (control == null) {
            return;
        }
        V2UserWaitStore.WaitOwnerContext context =
                waits.requireWaitOwnerContext(owner);
        Thread trunk = threads.findThreadById(context.trunkId())
                .orElseThrow(() -> new IllegalStateException(
                        "typed user-wait Trunk disappeared"));
        String commandId = stableId("trunk-continuation", waitKind, waitId);
        String turnId = control.continueUserWait(
                trunk, continuationInput(waitKind, answer), commandId);
        // PlanningBaseTurnRuntime reserves the id before its refresh launches
        // the physical ThreadTurn. Leave READY until that exact row exists;
        // the maintenance sweep idempotently retries the same command.
        if (waits.typedTurnExists(
                DispatchTicket.OwnerKind.THREAD_TURN, turnId)) {
            waits.markContinuationDispatched(waitKind, waitId, turnId);
        }
    }

    private static String continuationInput(String waitKind, String answer)
    {
        requireText(answer, "answer");
        return waitKind.equals("QUESTION")
                ? "User answered the question: " + answer
                : "User resolved the permission request: " + answer;
    }

    private static String stableId(String kind, String left, String right)
    {
        return UUID.nameUUIDFromBytes(
                (kind + ":" + left + ":" + right)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
