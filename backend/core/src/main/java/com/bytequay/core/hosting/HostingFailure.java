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
package com.bytequay.core.hosting;

import java.time.Duration;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Safe provider failure: never includes a response body, request headers, or credentials. */
public final class HostingFailure
        extends RuntimeException
{
    public enum Kind
    {
        AUTHENTICATION_REQUIRED,
        FORBIDDEN,
        MISSING_OR_INACCESSIBLE,
        RATE_LIMITED,
        REVISION_CONFLICT,
        UNAVAILABLE,
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final Optional<Duration> retryAfter;

    public HostingFailure(Kind kind, String message)
    {
        this(kind, message, Optional.empty());
    }

    public HostingFailure(Kind kind, String message, Optional<Duration> retryAfter)
    {
        super(requireNonNull(message, "message is null"));
        this.kind = requireNonNull(kind, "kind is null");
        this.retryAfter = requireNonNull(retryAfter, "retryAfter is null");
    }

    public Kind kind()
    {
        return kind;
    }

    public Optional<Duration> retryAfter()
    {
        return retryAfter;
    }
}
