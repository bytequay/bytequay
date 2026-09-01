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
 * @param kind owner kind that also fixes the execution profile
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

    /**
     * Returns the execution profile fixed by this owner kind.
     *
     * @return closed execution profile
     */
    public ExecutionProfile profile()
    {
        return kind.profile;
    }

    /** Identifies which ByteQuay runtime role owns a supervised process. */
    public enum Kind
    {
        /** The one shared Codex App Server process of a backend runtime. */
        APP_SERVER(ExecutionProfile.PROVIDER_CONTROL),
        /** One task-owned Codex exec-server process. */
        TASK_EXEC_SERVER(ExecutionProfile.TASK_EXEC_SERVER),
        /** One Claude turn process. */
        CLAUDE_TURN(ExecutionProfile.PROVIDER_CONTROL),
        /** One short-lived provider CLI probe such as a version or update command. */
        CLI_PROBE(ExecutionProfile.PROVIDER_CONTROL);

        private final ExecutionProfile profile;

        Kind(ExecutionProfile profile)
        {
            this.profile = profile;
        }

        /**
         * Returns the execution profile this owner kind always runs under.
         *
         * @return closed execution profile
         */
        public ExecutionProfile profile()
        {
            return profile;
        }
    }
}
