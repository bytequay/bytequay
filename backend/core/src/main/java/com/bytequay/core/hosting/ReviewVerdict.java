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

/** What a submitted review says about the request; each provider maps or rejects each verdict. */
public enum ReviewVerdict
{
    COMMENT,
    APPROVE,
    REQUEST_CHANGES;

    public static Optional<ReviewVerdict> parse(String value)
    {
        return Stream.of(values()).filter(verdict -> verdict.name().equalsIgnoreCase(value.trim())).findFirst();
    }
}
