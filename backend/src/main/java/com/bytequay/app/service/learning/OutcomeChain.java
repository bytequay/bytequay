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
package com.bytequay.app.service.learning;

/**
 * One reconstructed reviewer-concern -> author-change -> resolution -> merge
 * chain. Depth measures the resolved concern->change linkage, not comment
 * count, so a 40-comment naming debate that changed nothing scores 0 while a
 * three-message fix that changed code, resolved its thread, and merged scores
 * high.
 *
 * @param concernAuthor the reviewer who raised the concern.
 * @param concernPath the file (or symbol) the concern was about, or null for a
 * review-level concern with no line anchor.
 * @param concernRef stable ref to the concern (root review-comment id, or a
 * review id) so Phase 3 can re-read it.
 * @param addressedByCommit SHA of the author follow-up commit that addressed
 * the concern, or null when the author never changed code in response.
 * @param resolved true iff the concern's thread was resolved.
 * @param merged true iff the PR ultimately landed.
 * @param depth linkage depth: +1 addressed-by-change, +1 resolved, +1
 * merged-after-change. A pure debate stays at 0.
 */
public record OutcomeChain(
        String concernAuthor,
        String concernPath,
        String concernRef,
        String addressedByCommit,
        boolean resolved,
        boolean merged,
        int depth,
        String contentDigest)
{
    /** True when the concern actually drove a code change. */
    public boolean addressed()
    {
        return addressedByCommit != null && !addressedByCommit.isBlank();
    }
}
