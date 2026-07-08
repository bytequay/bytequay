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
package com.bytequay.app.beans.review;

/** Body for adding a local review comment: anchor file + 1-based line + the
 *  markdown body. {@code side} is {@code LEFT}/{@code RIGHT} (null defaults
 *  to RIGHT); {@code startLine}/{@code startSide} are set only for a
 *  multi-line range. */
public record AddReviewCommentRequest(
        String file, Integer line, String side, Integer startLine, String startSide, String body)
{
}
