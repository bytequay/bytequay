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
package com.bytequay.app.utils;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public final class StringInputUtil
{
    private StringInputUtil() {}

    public static boolean isNotBlank(String value)
    {
        return value != null && !value.isBlank();
    }

    public static void requireNotBlank(String value, String message)
    {
        if (!isNotBlank(value)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), message);
        }
    }

    public static List<String> normalizeNames(List<String> values, String name)
    {
        requireNonNull(values, name + " is null");
        return values.stream()
                .map(value -> {
                    requireNotBlank(value, name + " must not contain blanks");
                    return value.trim();
                })
                .distinct()
                .collect(toImmutableList());
    }
}
