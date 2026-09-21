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
package com.bytequay.agent.spi;

public enum AgentPermissionPolicy
{
    AUTO,
    CI_AUTOFIX,
    CHERRY_PICK;

    /**
     * Reports whether the policy decides tool calls from a fixed rule instead of asking the user.
     *
     * <p>{@link #AUTO} uses provider-native risk review and permits user decisions. Attended
     * development follows {@link AgentCommandPolicy}, including direct explicit authorization for
     * GitHub writes. The remaining policies never reach a user, so ByteQuay answers every escalation itself.
     *
     * @return true when no user decision is ever requested
     */
    public boolean unattended()
    {
        return this != AUTO;
    }

    /**
     * Keeps authenticated Git and GitHub commands within attended development sessions.
     *
     * @param restricted whether the session withholds write capability, which is every
     *         {@link SessionAccess} level but {@code FULL}
     */
    public boolean allowsGitHubCommands(boolean restricted, boolean reviewSession)
    {
        return this == AUTO && !restricted && !reviewSession;
    }
}
