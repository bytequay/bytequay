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
 * Closed set of ByteQuay runtime roles that may own a supervised provider process.
 *
 * <p>This mirrors the process-side owner kind of {@code agent-cli}. The two are deliberately
 * separate: one is a process concern and one is a storage concern, and the application composition
 * root maps between them.
 */
public enum LaunchOwnerKind
{
    APP_SERVER,
    TASK_EXEC_SERVER,
    CLAUDE_TURN,
    CLI_PROBE
}
