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
package com.bytequay.app.developmentflow.execution;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Capacity admission for one durable LEGACY saga step. It owns no workflow
 * state and deliberately provides no queue or executor: a denied durable step
 * remains eligible for its existing recovery sweep.
 */
@Component
public class LegacySagaCapacity
{
    private final LegacyCapacityBridge bridge;
    private final LegacyTaskScopeResolver scopes;

    public LegacySagaCapacity(
            LegacyCapacityBridge bridge,
            LegacyTaskScopeResolver scopes)
    {
        this.bridge = requireNonNull(bridge, "bridge is null");
        this.scopes = requireNonNull(scopes, "scopes is null");
    }

    /** Acquire before any durable effect claim or external adapter call. */
    public Optional<Attempt> tryAcquire(
            String taskId,
            String operationId,
            Set<CapacityManager.CapacityLane> lanes)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(operationId, "operationId is null");
        requireNonNull(lanes, "lanes is null");
        if (taskId.isBlank() || operationId.isBlank()) {
            throw new IllegalArgumentException("taskId and operationId must not be blank");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("saga capacity must be acquired outside a transaction");
        }

        CapacityManager.CapacityRequest request = new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.LEGACY,
                lanes,
                scopes.resolve(taskId),
                false,
                true,
                false);
        Thread owner = Thread.currentThread();
        AtomicBoolean lost = new AtomicBoolean();
        Optional<LegacyCapacityBridge.Permit> admitted = bridge.tryAcquire(
                request,
                operationId,
                () -> {
                    lost.set(true);
                    owner.interrupt();
                });
        return admitted.map(permit -> new BridgedAttempt(permit, lost));
    }

    public interface Attempt
            extends AutoCloseable
    {
        /** Fail closed immediately before and after an external adapter call. */
        void requireLive();

        /** Re-prove the exact lease when deciding whether a failure is ambiguous. */
        boolean leaseLost();

        @Override
        void close();
    }

    private static final class BridgedAttempt
            implements Attempt
    {
        private final LegacyCapacityBridge.Permit permit;
        private final AtomicBoolean lost;
        private boolean closed;

        private BridgedAttempt(
                LegacyCapacityBridge.Permit permit,
                AtomicBoolean lost)
        {
            this.permit = requireNonNull(permit, "permit is null");
            this.lost = requireNonNull(lost, "lost is null");
        }

        @Override
        public void requireLive()
        {
            if (lost.get()) {
                throw new CapacityLeaseLostException();
            }
            try {
                permit.lease();
            }
            catch (RuntimeException e) {
                lost.set(true);
                throw new CapacityLeaseLostException(e);
            }
        }

        @Override
        public boolean leaseLost()
        {
            if (lost.get()) {
                return true;
            }
            try {
                permit.lease();
                return false;
            }
            catch (RuntimeException e) {
                lost.set(true);
                return true;
            }
        }

        @Override
        public void close()
        {
            if (closed) {
                return;
            }
            closed = true;
            try {
                permit.close();
            }
            catch (RuntimeException ignored) {
                // LegacyCapacityBridge keeps a failed release registered and
                // retries it without renewing completed work.
            }
        }
    }

    private static final class CapacityLeaseLostException
            extends RuntimeException
    {
        private CapacityLeaseLostException()
        {
            super("legacy saga capacity lease was lost");
        }

        private CapacityLeaseLostException(Throwable cause)
        {
            super("legacy saga capacity lease was lost", cause);
        }
    }
}
