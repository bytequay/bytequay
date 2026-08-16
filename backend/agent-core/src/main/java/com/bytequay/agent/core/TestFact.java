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

import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.bytequay.agent.spi.AgentChecks.checkPresent;

public record TestFact(String name, Status status, Optional<String> detail)
{
    public TestFact
    {
        checkNotBlank(name, "name");
        checkPresent(status, "status");
        checkOptionalText(detail, "detail");
    }

    public enum Status
    {
        PASSED,
        FAILED,
        UNKNOWN
    }
}
