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

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Exact leased context; only ExecutionDispatcher can construct one. */
public final class ExecutionContext
{
    private final DispatchTicket.DispatchEnvelope envelope;
    private final CapacityManager.CapacityLease capacityLease;
    private final Cancellation cancellation;
    private final ExecutionPorts.ExecutionEvidencePort evidence;
    private final String executionId;
    private final Clock clock;
    private final Supplier<CapacityManager.CapacityLease> exactLeaseSupplier;

    ExecutionContext(
            DispatchTicket.DispatchEnvelope envelope,
            CapacityManager.CapacityLease capacityLease,
            Cancellation cancellation,
            ExecutionPorts.ExecutionEvidencePort evidence,
            String executionId,
            Clock clock,
            Supplier<CapacityManager.CapacityLease> exactLeaseSupplier)
    {
        this.envelope = requireNonNull(envelope, "envelope is null");
        this.capacityLease = requireNonNull(capacityLease, "capacityLease is null");
        this.cancellation = requireNonNull(cancellation, "cancellation is null");
        this.evidence = requireNonNull(evidence, "evidence is null");
        this.executionId = requireNonNull(executionId, "executionId is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.exactLeaseSupplier = requireNonNull(
                exactLeaseSupplier, "exactLeaseSupplier is null");
        if (executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
    }

    public DispatchTicket.DispatchEnvelope envelope()
    {
        return envelope;
    }

    public CapacityManager.CapacityLease capacityLease()
    {
        return capacityLease;
    }

    public long requireWriterFencingToken()
    {
        if (!envelope.capacityRequest().writerRequired()) {
            throw new IllegalStateException(
                    "operation does not hold an exact writer fencing token");
        }
        CapacityManager.CapacityLease liveLease = requireNonNull(
                exactLeaseSupplier.get(), "exact lease validation returned null");
        if (liveLease.writerFencingToken() == null) {
            throw new IllegalStateException(
                    "operation does not hold an exact writer fencing token");
        }
        return liveLease.writerFencingToken();
    }

    public boolean isCancellationRequested()
    {
        return cancellation.isCanceled();
    }

    /** Registers the exact process/provider stop action for this attempt. */
    public void onCancellation(Runnable stopAction)
    {
        cancellation.register(requireNonNull(stopAction, "stopAction is null"));
    }

    public void providerSession(String provider, String providerSessionId)
    {
        evidence.providerSession(
                executionId,
                requireNonBlank(provider, "provider"),
                requireNonBlank(providerSessionId, "providerSessionId"));
    }

    public void processStarted(long processPid, String logReference)
    {
        if (processPid < 1) {
            throw new IllegalArgumentException("processPid must be positive");
        }
        if (logReference != null && logReference.isBlank()) {
            throw new IllegalArgumentException("logReference must not be blank");
        }
        evidence.processStarted(executionId, processPid, logReference);
    }

    public void appendLog(long sequence, String payloadJson)
    {
        if (sequence < 0) {
            throw new IllegalArgumentException("log sequence must be non-negative");
        }
        evidence.appendLog(
                executionId,
                sequence,
                requireNonNull(payloadJson, "payloadJson is null"),
                clock.instant());
    }

    public void recordUsage(long inputTokens, long outputTokens, long costUsdMilli)
    {
        if (inputTokens < 0 || outputTokens < 0 || costUsdMilli < 0) {
            throw new IllegalArgumentException("usage values must be non-negative");
        }
        evidence.recordUsage(executionId, inputTokens, outputTokens, costUsdMilli);
    }

    public void heartbeatEvidence()
    {
        evidence.heartbeat(executionId, clock.instant());
    }

    private static String requireNonBlank(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static final class Cancellation
    {
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final AtomicReference<Runnable> stopAction = new AtomicReference<>();
        private final AtomicReference<RuntimeException> stopFailure = new AtomicReference<>();

        boolean isCanceled()
        {
            return canceled.get();
        }

        void register(Runnable action)
        {
            if (!stopAction.compareAndSet(null, action)) {
                throw new IllegalStateException("one cancellation action is already registered");
            }
            if (canceled.get()) {
                runStopAction();
            }
        }

        void cancel()
        {
            if (canceled.compareAndSet(false, true)) {
                runStopAction();
            }
        }

        RuntimeException takeStopFailure()
        {
            return stopFailure.getAndSet(null);
        }

        private void runStopAction()
        {
            Runnable action = stopAction.getAndSet(null);
            if (action != null) {
                try {
                    action.run();
                }
                catch (RuntimeException e) {
                    stopFailure.compareAndSet(null, e);
                }
            }
        }
    }
}
