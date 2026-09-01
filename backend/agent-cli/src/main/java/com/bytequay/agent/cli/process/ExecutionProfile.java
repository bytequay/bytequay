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

/**
 * Closed set of runtime boundaries a supervised provider process may run under.
 *
 * <p>Adding another value requires a design change and a compatibility fixture. A caller cannot
 * supply raw sandbox rules, broaden writable roots, or turn a provider approval into a different
 * profile.
 */
public enum ExecutionProfile
{
    /** Long-lived Codex App Server or one Claude control process; claims no network allowlist. */
    PROVIDER_CONTROL,
    /** One loopback Codex executor and its built-in sandbox helpers. */
    TASK_EXEC_SERVER
}
