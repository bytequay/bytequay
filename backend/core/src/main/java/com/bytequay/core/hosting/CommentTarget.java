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

/** The written text an edit or reaction applies to: the request itself, a conversation comment, a line comment or a review body. */
public enum CommentTarget
{
    PULL_REQUEST("pull_request"),
    ISSUE_COMMENT("issue_comment"),
    REVIEW_COMMENT("review_comment"),
    REVIEW("review");

    private final String value;

    CommentTarget(String value)
    {
        this.value = value;
    }

    public static Optional<CommentTarget> parse(String value)
    {
        return Stream.of(values()).filter(target -> target.value.equalsIgnoreCase(value.trim())).findFirst();
    }
}
