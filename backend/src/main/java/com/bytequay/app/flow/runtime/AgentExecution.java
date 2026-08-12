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
package com.bytequay.app.flow.runtime;

/**
 * How an agent turn is executed: in this JVM over HTTP, or as a subprocess.
 *
 * <p>Deliberately not a member of {@code TurnSpec.Transport}. That enum names a
 * <em>wire dialect</em> — Anthropic's messages shape versus an OpenAI-compatible
 * one — and putting CLI beside them would be a category error: it says nothing
 * about a wire because there is no wire.
 *
 * <p>The old flow's {@code AgentTurnProviderSession.Transport} draws the same
 * distinction, but importing it here would make the greenfield runtime depend on
 * the component it replaces, which its replacement boundary forbids. So the
 * concept is extracted rather than shared, and the two may diverge freely.
 *
 * <p>The distinction is load-bearing in three places, which is why it is a type
 * and not a boolean:
 *
 * <ul>
 *   <li><b>Credentials.</b> An API turn is authorized by a stored credential this
 *       program holds. A CLI turn is authorized by the user's own CLI login,
 *       which this program never sees — so a CLI launch binding cannot name the
 *       account that answered, and must not pretend to.</li>
 *   <li><b>Limits.</b> Output-token and tool-iteration ceilings are properties of
 *       an API call. A subprocess is bounded by its own cost cap and its process
 *       lifetime instead.</li>
 *   <li><b>Death.</b> An API turn ends when its Java thread returns. A CLI turn
 *       ends only once its whole process group is provably gone — see
 *       {@link ProcessGroup}.</li>
 * </ul>
 */
public enum AgentExecution
{
    /** In-JVM, over HTTP, against a stored credential. */
    API,
    /** A subprocess in its own process group, authorized by the user's own CLI. */
    CLI
}
