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

import com.bytequay.agent.spi.AgentUnavailableException;

/** Starts bounded argv-based child processes for CLI-backed agent drivers. */
public interface ProcessSupervisor
{
    /**
     * Starts a process without a shell and drains stdout and stderr independently.
     *
     * @param spec validated argv, environment, directory, and limits
     * @param output bounded output consumer
     * @return managed process handle
     * @throws AgentUnavailableException when the process cannot be started
     */
    ManagedProcess start(ProcessSpec spec, ProcessOutputSink output);
}
