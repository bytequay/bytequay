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
package com.bytequay.core.hosting;

import java.util.Optional;
import java.util.stream.Stream;

/** The reactions the app offers; {@link #value()} is the app's own spelling, which provider integrations translate. */
public enum Reaction
{
    THUMBS_UP("+1"),
    THUMBS_DOWN("-1"),
    LAUGH("laugh"),
    HOORAY("hooray"),
    CONFUSED("confused"),
    HEART("heart"),
    ROCKET("rocket"),
    EYES("eyes");

    private final String value;

    Reaction(String value)
    {
        this.value = value;
    }

    public String value()
    {
        return value;
    }

    public static Optional<Reaction> parse(String value)
    {
        return Stream.of(values()).filter(reaction -> reaction.value.equalsIgnoreCase(value.trim())).findFirst();
    }
}
