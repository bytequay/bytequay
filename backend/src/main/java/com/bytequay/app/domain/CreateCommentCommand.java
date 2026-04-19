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
package com.bytequay.app.domain;

/**
 * Parameters for creating a new line or file comment on a pull request diff.
 * Maps to the request body of POST /repos/{owner}/{repo}/pulls/{pull_number}/comments.
 *
 * @param body       the comment text (required)
 * @param commitId   the SHA of the commit to comment on (required)
 * @param path       the relative file path within the diff (required)
 * @param line       the line number in the diff (required unless {@code inReplyTo} is set)
 * @param side       "LEFT" (deleted) or "RIGHT" (added); default "RIGHT"
 * @param startLine  first line of a multi-line comment (optional)
 * @param startSide  side for the first line of a multi-line comment (optional)
 * @param inReplyTo  ID of the comment to reply to; when set, only {@code body} is used
 */
public record CreateCommentCommand(
        String body,
        String commitId,
        String path,
        Integer line,
        String side,
        Integer startLine,
        String startSide,
        Long inReplyTo)
{
    /** Convenience factory for a single-line comment. */
    public static CreateCommentCommand singleLine(String body, String commitId, String path, int line, String side)
    {
        return new CreateCommentCommand(body, commitId, path, line, side, null, null, null);
    }
}
