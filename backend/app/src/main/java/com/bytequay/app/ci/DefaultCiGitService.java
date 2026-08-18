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

import org.springframework.stereotype.Service;

import java.time.Clock;

import static java.util.Objects.requireNonNull;

@Service
public final class DefaultCiGitService
        implements CiGitService
{
    private final CiGitOperations operations;

    DefaultCiGitService(CiGitCommandRunner runner, Clock clock)
    {
        this.operations = new CiGitOperations(
                requireNonNull(runner, "runner is null"),
                requireNonNull(clock, "clock is null"));
    }

    @Override
    public CiGitInspection inspectAgentResult(InspectAgentResultCommand command)
    {
        return operations.inspectAgentResult(command);
    }

    @Override
    public CiPushResult push(PushCiHeadCommand command)
    {
        return operations.push(command);
    }

    @Override
    public FixupPositionCheck checkFixupPositions(CheckFixupPositionsCommand command)
    {
        return operations.checkFixupPositions(command);
    }
}
