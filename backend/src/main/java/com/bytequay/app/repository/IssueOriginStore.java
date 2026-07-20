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
package com.bytequay.app.repository;

import java.util.Optional;

/** Immutable local attribution for GitHub issues known to this installation. */
public interface IssueOriginStore
{
    Optional<String> find(long issueId);

    /** Records the first known origin; later calls never rewrite it. */
    void saveIfAbsent(long issueId, int issueNumber, String origin);
}
