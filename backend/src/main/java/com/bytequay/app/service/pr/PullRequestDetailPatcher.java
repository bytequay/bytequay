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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.StoredPrDetail;

import java.util.ArrayList;
import java.util.List;

final class PullRequestDetailPatcher
{
    private PullRequestDetailPatcher() {}

    static StoredPrDetail withTimelineCommentAppended(StoredPrDetail detail, PrTimelineEvent comment)
    {
        List<PrTimelineEvent> timeline = new ArrayList<>(detail.timeline());
        timeline.removeIf(event -> event.githubId() != null
                && event.githubId().equals(comment.githubId())
                && "commented".equals(event.event()));
        timeline.add(comment);
        return new StoredPrDetail(detail.raw(), detail.reviews(), List.copyOf(timeline), detail.checkRuns());
    }
}
