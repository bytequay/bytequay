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
package com.bytequay.core.domain;

import java.util.Optional;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public final class Checks
{
    private static final Pattern SHA = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");

    private Checks() {}

    public static String checkText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
        return value;
    }

    public static String checkText(String value, String name, int maximumLength)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
        checkArgument(value.length() <= maximumLength, name + " exceeds " + maximumLength + " characters");
        return value;
    }

    public static Optional<String> checkOptionalText(Optional<String> value, String name, int maximumLength)
    {
        requireNonNull(value, name + " is null");
        value.ifPresent(text -> checkText(text, name, maximumLength));
        return value;
    }

    public static String checkSha(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(SHA.matcher(value).matches(), name + " is not a full Git SHA");
        return value;
    }

    public static Optional<String> checkOptionalSha(Optional<String> value, String name)
    {
        requireNonNull(value, name + " is null");
        value.ifPresent(sha -> checkSha(sha, name));
        return value;
    }
}
