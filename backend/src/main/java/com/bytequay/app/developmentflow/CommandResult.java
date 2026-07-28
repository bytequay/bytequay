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

/** Result of one synchronous domain command. */
public record CommandResult<T>(T state, Disposition disposition)
{
    public CommandResult
    {
        requireNonNull(state, "state is null");
        requireNonNull(disposition, "disposition is null");
    }

    public static <T> CommandResult<T> applied(T state)
    {
        return new CommandResult<>(state, Disposition.APPLIED);
    }

    public static <T> CommandResult<T> duplicate(T state)
    {
        return new CommandResult<>(state, Disposition.DUPLICATE);
    }

    public static <T> CommandResult<T> superseded(T state)
    {
        return new CommandResult<>(state, Disposition.SUPERSEDED);
    }

    public enum Disposition
    {
        APPLIED,
        DUPLICATE,
        SUPERSEDED
    }
}
