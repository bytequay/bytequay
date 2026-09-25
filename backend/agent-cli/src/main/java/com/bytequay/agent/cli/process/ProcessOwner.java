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
package com.bytequay.agent.cli.process;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Closed owner kind and agent-cli-local opaque owner identifier for one supervised process.
 *
 * <p>The identifier is opaque on purpose: {@code agent-cli} never imports a task or turn type, and
 * the owning driver maps the value back to durable domain state.
 *
 * @param kind runtime role that owns the process
 * @param id opaque owner identifier that carries no prompt text or credential
 */
public record ProcessOwner(Kind kind, String id)
{
    public ProcessOwner
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(id, "id is null");
        checkArgument(!id.isBlank(), "id is blank");
        checkArgument(id.length() <= 200, "id is too long");
    }

    /** Identifies which ByteQuay runtime role owns a supervised process. */
    public enum Kind
    {
        /** The one shared Codex App Server process of a backend runtime. */
        APP_SERVER,
        /** One Claude turn process. */
        CLAUDE_TURN,
        /** One short-lived provider control command or isolated text-only helper. */
        CLI_PROBE,
        /**
         * One read-only discussion session, owned by its task. It reaches a real worktree, so
         * unlike {@code CLI_PROBE} it stays attributable to that task at startup recovery.
         */
        DISCUSSION,
        /** One user-requested test run in a review working copy, owned by {@code <taskId>/<runId>}. */
        LOCAL_RUN
    }
}
