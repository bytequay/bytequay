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
package com.bytequay.app.flow.gate;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Optional owner proof attached to an INITIAL publication. */
public interface InitialPublishVerificationProvider
{
    InitialPublishVerificationProvider NONE = new InitialPublishVerificationProvider() {};

    record Verification(
            String taskId,
            String runId,
            String expectedBaseSha,
            String proposedHead,
            String verificationRef)
    {
        public Verification
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(expectedBaseSha, "expectedBaseSha is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(verificationRef, "verificationRef is null");
        }
    }

    default boolean owns(String taskId)
    {
        return false;
    }

    default Optional<Verification> current(String taskId)
    {
        return Optional.empty();
    }
}
