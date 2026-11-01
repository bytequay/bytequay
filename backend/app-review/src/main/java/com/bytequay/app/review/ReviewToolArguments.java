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
package com.bytequay.app.review;

import com.bytequay.core.review.ReviewInvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** Input validation and normalization for the fixed review tools. */
final class ReviewToolArguments
{
    private ReviewToolArguments() {}

    /** A missing field and an unknown value get the same answer, naming the allowed values. */
    static <E extends Enum<E>> E choice(JsonNode node, String field, Class<E> type, String allowed)
    {
        try {
            return Enum.valueOf(type, text(node, field, 30, true));
        }
        catch (IllegalArgumentException e) {
            throw new ReviewInvalidRequestException(field + ": use " + allowed);
        }
    }

    static String text(JsonNode node, String field, int maximum, boolean required)
    {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            if (required) {
                throw new ReviewInvalidRequestException(field + ": required non-empty text");
            }
            return "";
        }
        String text = value.asText().strip();
        if (text.length() > maximum) {
            throw new ReviewInvalidRequestException(field + ": longer than " + maximum + " characters");
        }
        return text;
    }

    static Optional<Integer> integer(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
            throw new ReviewInvalidRequestException(field + ": must be a positive integer");
        }
        return Optional.of(value.asInt());
    }

    static int page(JsonNode node)
    {
        return integer(node, "page").orElse(1);
    }

    static List<JsonNode> array(JsonNode node, String field, int maximum)
    {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new ReviewInvalidRequestException(field + ": required array");
        }
        if (value.size() > maximum) {
            throw new ReviewInvalidRequestException(field + ": at most " + maximum + " items");
        }
        return ImmutableList.copyOf(value);
    }

    static List<String> strings(JsonNode node, String field, int maximum, int length)
    {
        return array(node, field, maximum).stream()
                .map(item -> {
                    if (!item.isTextual() || item.asText().isBlank()) {
                        throw new ReviewInvalidRequestException(field + ": items must be non-empty text");
                    }
                    if (item.asText().length() > length) {
                        throw new ReviewInvalidRequestException(field + ": items longer than " + length + " characters");
                    }
                    return item.asText().strip();
                })
                .collect(toImmutableList());
    }
}
