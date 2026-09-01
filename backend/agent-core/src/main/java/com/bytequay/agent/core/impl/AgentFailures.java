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
package com.bytequay.agent.core.impl;

final class AgentFailures
{
    private AgentFailures() {}

    /**
     * Runs cleanup that must not replace the failure already being propagated.
     *
     * <p>A cleanup failure is attached to {@code failure} instead of thrown, so
     * the original cause still reaches the caller.
     */
    static void suppressFailure(RuntimeException failure, Runnable cleanup)
    {
        try {
            cleanup.run();
        }
        catch (RuntimeException cleanupFailure) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /** Returns the root cause message, or its class name when the message is absent. */
    static String failureMessage(Throwable failure)
    {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return message(cause);
    }

    /** Returns the message of {@code failure} itself, or its class name when absent. */
    static String message(Throwable failure)
    {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
