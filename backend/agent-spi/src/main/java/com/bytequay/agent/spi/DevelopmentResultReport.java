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

import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A root agent's explicit assessment of its current development turn. */
public record DevelopmentResultReport(
        boolean readyForDelivery,
        Optional<String> message,
        Optional<DevelopmentStopAfter> stopAfter)
{
    public static final int MAXIMUM_MESSAGE_LENGTH = 4_000;

    public DevelopmentResultReport
    {
        checkOptionalText(message, "message");
        message.ifPresent(value -> checkArgument(value.length() <= MAXIMUM_MESSAGE_LENGTH, "message is too long"));
        requireNonNull(stopAfter, "stopAfter is null");
    }
}
