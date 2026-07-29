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
package com.bytequay.app.service.checks;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledFuture;

import static java.util.Objects.requireNonNull;

/**
 * Fail-closed compatibility surface for historical validation controls.
 * Typed validation is dispatched by ExecutionDispatcher; this class owns no
 * executor, lease renewer, queue, claim, or worker.
 */
@Component
public final class ValidationExecutorRegistry
{
    public boolean submitIfAbsent(
            String claimKey,
            CapacityManager.CapacityRequest request,
            Runnable work)
    {
        requireNonNull(claimKey, "claimKey is null");
        requireNonNull(request, "request is null");
        requireNonNull(work, "work is null");
        throw new UnsupportedOperationException(
                "LEGACY validation execution is retired");
    }

    public static String operationId(String claimKey)
    {
        requireNonNull(claimKey, "claimKey is null");
        if (claimKey.isBlank()) {
            throw new IllegalArgumentException("claimKey must not be blank");
        }
        return "retired-validation:" + claimKey;
    }

    public boolean isInFlight(String claimKey)
    {
        requireNonNull(claimKey, "claimKey is null");
        return false;
    }

    public boolean requestStop(String claimKey)
    {
        requireNonNull(claimKey, "claimKey is null");
        return false;
    }

    public ScheduledFuture<?> scheduleLeaseRenewal(
            Runnable renew,
            long periodMillis)
    {
        requireNonNull(renew, "renew is null");
        throw new UnsupportedOperationException(
                "LEGACY validation lease renewal is retired");
    }
}
