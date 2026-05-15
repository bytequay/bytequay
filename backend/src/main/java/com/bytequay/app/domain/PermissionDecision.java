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
package com.bytequay.app.domain;

/**
 * User's response to a {@link StreamEvent.PermissionRequested}.
 * Delivered back to the agent loop via
 * {@code AgentSession.decide(callId, decision)}.
 */
public enum PermissionDecision
{
    /** Run the requested tool call. */
    ALLOW,

    /** Refuse the tool call; the agent loop should fall back or stop. */
    DENY,
}
