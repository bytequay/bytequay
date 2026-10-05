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
package com.bytequay.agent.cli;

import com.bytequay.agent.spi.AgentCommandPolicy;
import com.bytequay.agent.spi.AgentPermissionScope;

import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * The instructions a provider session starts with, in one order on both lanes: the command scope, plan guidance unless the
 * session is read-only, ByteQuay's managed instructions, then guidance for the tools this session carries. Only the plan
 * tool and the extra tools differ between lanes.
 */
public final class ProviderInstructions
{
    private ProviderInstructions() {}

    public static String compose(AgentPermissionScope scope, String planInstruction, String managedInstructions, String... toolInstructions)
    {
        return Stream.concat(
                        Stream.of(AgentCommandPolicy.instructions(scope), scope.access().restricted() ? "" : planInstruction, managedInstructions),
                        Stream.of(toolInstructions))
                .filter(part -> !part.isBlank())
                .collect(joining("\n\n"));
    }
}
