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

/** How a request is merged; {@link #DEFAULT} leaves the choice to the provider integration. */
public enum MergeStrategy
{
    DEFAULT("default"),
    WHEN_READY("when-ready"),
    QUEUE("queue"),
    MERGE("merge"),
    SQUASH("squash"),
    REBASE("rebase");

    private final String value;

    MergeStrategy(String value)
    {
        this.value = value;
    }

    /** An absent strategy is the default one. */
    public static Optional<MergeStrategy> parse(String value)
    {
        return value == null ? Optional.of(DEFAULT) : Stream.of(values()).filter(strategy -> strategy.value.equalsIgnoreCase(value.trim())).findFirst();
    }
}
