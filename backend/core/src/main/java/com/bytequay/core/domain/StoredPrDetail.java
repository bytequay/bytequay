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

import static java.util.Objects.requireNonNull;

public record StoredPrDetail(
        PrRawDetail raw,
        List<PrReviewState> reviews,
        List<PrTimelineEvent> timeline,
        List<PrCheckRunState> checkRuns,
        List<String> unavailableSections,
        boolean viewerCanWrite)
{
    public static final String REVIEWS = "reviews";
    public static final String CHECKS = "checks";
    public static final String TIMELINE = "timeline";
    public static final String COMMENTS = "comments";
    public static final String COMMITS = "commits";
    public static final String REVIEW_THREADS = "reviewThreads";
    public static final String REACTIONS = "reactions";

    private static final List<String> TIMELINE_SOURCES =
            ImmutableList.of(TIMELINE, COMMENTS, COMMITS, REVIEW_THREADS, REACTIONS);

    public StoredPrDetail
    {
        requireNonNull(raw, "raw is null");
        reviews = ImmutableList.copyOf(requireNonNull(reviews, "reviews is null"));
        timeline = ImmutableList.copyOf(requireNonNull(timeline, "timeline is null"));
        checkRuns = ImmutableList.copyOf(requireNonNull(checkRuns, "checkRuns is null"));
        unavailableSections = ImmutableList.copyOf(requireNonNull(unavailableSections, "unavailableSections is null"));
    }

    public StoredPrDetail(
            PrRawDetail raw,
            List<PrReviewState> reviews,
            List<PrTimelineEvent> timeline,
            List<PrCheckRunState> checkRuns,
            List<String> unavailableSections)
    {
        this(raw, reviews, timeline, checkRuns, unavailableSections, false);
    }

    public StoredPrDetail(
            PrRawDetail raw,
            List<PrReviewState> reviews,
            List<PrTimelineEvent> timeline,
            List<PrCheckRunState> checkRuns)
    {
        this(raw, reviews, timeline, checkRuns, ImmutableList.of(), false);
    }

    /**
     * Whether every source behind a section answered. A section that failed
     * arrives as an empty list, which is indistinguishable from "GitHub says
     * there are none" — so callers that would delete rows the list omits must
     * check this first.
     */
    public boolean reviewsComplete()
    {
        return !unavailableSections.contains(REVIEWS);
    }

    public boolean timelineComplete()
    {
        return TIMELINE_SOURCES.stream().noneMatch(unavailableSections::contains);
    }

    public boolean checkRunsComplete()
    {
        return !unavailableSections.contains(CHECKS);
    }
}
