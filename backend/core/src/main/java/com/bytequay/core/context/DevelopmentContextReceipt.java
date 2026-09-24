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
package com.bytequay.core.context;

import java.time.Instant;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Exact task-owned context response saved before delivery; it is not proof that the provider received it. */
public record DevelopmentContextReceipt(String taskId, String turnId, String operationKey, String reference, Kind kind,
        String requestDigest, String payloadJson, String payloadDigest, Instant createdAt)
{
    public DevelopmentContextReceipt
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(operationKey, "operationKey is null");
        requireNonNull(reference, "reference is null");
        requireNonNull(kind, "kind is null");
        requireNonNull(requestDigest, "requestDigest is null");
        requireNonNull(payloadJson, "payloadJson is null");
        requireNonNull(payloadDigest, "payloadDigest is null");
        requireNonNull(createdAt, "createdAt is null");
        checkArgument(ContextLimits.bytes(payloadJson) <= 64 * 1024, "Context receipt payload exceeds 64 KiB");
    }

    public enum Kind { OFFER, SEARCH, READ }
}
