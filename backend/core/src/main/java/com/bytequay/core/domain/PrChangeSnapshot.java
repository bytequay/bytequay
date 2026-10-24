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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;

import java.util.List;

/** Cached inputs for a list's changed-since-read indicator. Timeline entries carry only action, actor and time. */
public record PrChangeSnapshot(List<String> requestedReviewers, List<PrTimelineEvent> timeline, List<PrCheckRunState> checkRuns)
{
    public PrChangeSnapshot
    {
        requestedReviewers = ImmutableList.copyOf(requestedReviewers);
        timeline = ImmutableList.copyOf(timeline);
        checkRuns = ImmutableList.copyOf(checkRuns);
    }
}
