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

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * The agent's answer to one review comment it was asked to fix: the reply for the reviewer and whether its change
 * addresses the comment. ByteQuay, not the agent, posts it.
 */
public record ReviewThreadReply(long commentId, String body, boolean fixed)
{
    public static final int MAXIMUM_BODY_LENGTH = 4_000;

    public ReviewThreadReply
    {
        checkArgument(commentId > 0, "commentId must be positive");
        checkText(body, "body");
        checkArgument(body.length() <= MAXIMUM_BODY_LENGTH, "body is too long");
    }
}
