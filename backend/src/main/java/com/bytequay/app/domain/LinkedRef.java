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
 * A PR / issue / commit reference auto-detected inside an email body
 * — surfaced in the email preview pane as an "Open in ByteQuay"
 * affordance. {@code slug} is the displayable identifier:
 * {@code "29073"} for PR/issue, the abbreviated SHA for commits.
 */
public record LinkedRef(
        Kind kind,
        String owner,
        String repo,
        String slug,
        String url)
{
    public enum Kind
    {
        PR,
        ISSUE,
        COMMIT
    }
}
