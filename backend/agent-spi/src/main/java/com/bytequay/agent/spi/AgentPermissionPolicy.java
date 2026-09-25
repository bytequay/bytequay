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
     * Reports whether the workflow runs without an attending user by default.
     *
     * <p>Providers may still raise a request that ByteQuay cannot decide from its fixed rules.
     * CI repair can then pause for user approval. Cherry-pick retains its narrower fixed scope.
     *
     * @return true for background workflows
     */
    public boolean unattended()
    {
        return this != AUTO;
    }
}
