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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Shared validation for provider-neutral agent values. */
public final class AgentChecks
{
    private AgentChecks() {}

    public static void checkNotBlank(String value, String name)
    {
        requireNonNull(name, "name is null");
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), "%s is blank", name);
    }

    public static void checkOptionalText(Optional<String> value, String name)
    {
        requireNonNull(name, "name is null");
        requireNonNull(value, name + " is null");
        checkArgument(value.stream().noneMatch(String::isBlank), "%s is blank", name);
    }
}
