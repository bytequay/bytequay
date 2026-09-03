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
package com.bytequay.agent.core;

/**
 * Where a task's current title came from, which decides whether anything may still replace it.
 *
 * <p>An interactive task is created from its first message alone, so its title starts as that
 * message. The agent's first answer proposes a better one exactly once. A person's rename is final:
 * nothing the agent says afterwards replaces it. Tasks a workflow creates with a fixed title are
 * {@link #USER} from the start, because that title is not up for proposal either.
 */
public enum TitleSource
{
    PROVISIONAL,
    AGENT,
    USER
}
