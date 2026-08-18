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
package com.bytequay.app.ci;

import com.bytequay.core.ci.CiAutofixRunId;

public interface CiMemoryService
{
    /**
     * Rebuilds the disposable task memory from durable CI Autofix facts.
     *
     * <p>A failure is recorded as a warning when possible and never changes the
     * run status or prevents later CI Autofix work.
     *
     * @param runId run whose memory should be rebuilt
     */
    void update(CiAutofixRunId runId);

    /**
     * Reads bounded memory owned by the authenticated task.
     *
     * @param command injected task identity and optional substring query
     * @return recent or matching memory sections; unavailable memory is a normal result
     */
    CiMemoryView read(ReadCiMemoryCommand command);
}
