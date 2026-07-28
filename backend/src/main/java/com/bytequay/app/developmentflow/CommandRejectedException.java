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
package com.bytequay.app.developmentflow;

import static java.util.Objects.requireNonNull;

/** A synchronous command did not match its exact aggregate subject. */
public final class CommandRejectedException
        extends RuntimeException
{
    private final Reason reason;

    public CommandRejectedException(Reason reason, String message)
    {
        super(message);
        this.reason = requireNonNull(reason, "reason is null");
    }

    public Reason reason()
    {
        return reason;
    }

    public enum Reason
    {
        NOT_FOUND,
        INVALID_STATE,
        STALE_VERSION,
        STALE_EPOCH,
        NOT_CURRENT_STAGE,
        STALE_GENERATION,
        WRONG_STAGE_KIND,
        COMMAND_ID_CONFLICT,
        CONCURRENT_UPDATE
    }
}
