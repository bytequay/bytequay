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
package com.bytequay.app.beans.stage;

/**
 * The linked PR summary on the brain view's right rail. Derived from the
 * Task's existing PR linkage; null when the Task has no PR yet.
 *
 * @param status draft | open | merged | closed
 * @param ciStatus green | failing | pending | unknown
 * @param conflictsState none | has_conflicts | unknown
 */
public record LinkedPrDto(
        int number,
        String branch,
        String status,
        String ciStatus,
        String ciSummary,
        int reviewersApproved,
        int reviewersTotal,
        String conflictsState,
        boolean mergeable)
{
}
