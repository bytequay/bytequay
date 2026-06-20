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
 * Where a {@link ReviewComment} came from. {@code REMOTE_REVIEWER} rows
 * (and only those) carry a {@code remoteLink} back to the github.com
 * discussion. The {@code code} operation reads unresolved comments of all
 * sources uniformly, so addressing local and remote comments shares one
 * machinery.
 */
public enum ReviewCommentSource
{
    LOCAL_USER,
    LOCAL_AGENT,
    REMOTE_REVIEWER
}
