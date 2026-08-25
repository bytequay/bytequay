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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record PullRequestDetailRefresh(
        boolean full,
        PullRequestDetail detail,
        Status status)
{
    public PullRequestDetailRefresh
    {
        checkArgument(full == (detail != null), "full refresh must include detail");
        checkArgument(full == (status == null), "status refresh must include status only");
    }

    public static PullRequestDetailRefresh full(PullRequestDetail detail)
    {
        return new PullRequestDetailRefresh(true, requireNonNull(detail, "detail is null"), null);
    }

    public static PullRequestDetailRefresh status(PullRequestDetail detail)
    {
        requireNonNull(detail, "detail is null");
        return new PullRequestDetailRefresh(false, null, new Status(
                detail.mergeable(),
                detail.mergeableState(),
                detail.ciStatus(),
                detail.checkRuns(),
                detail.unavailableSections()));
    }

    public record Status(
            Boolean mergeable,
            String mergeableState,
            PullRequestDetail.CiStatus ciStatus,
            List<PullRequestDetail.CheckRun> checkRuns,
            List<String> unavailableSections)
    {
        public Status
        {
            requireNonNull(ciStatus, "ciStatus is null");
            checkRuns = ImmutableList.copyOf(requireNonNull(checkRuns, "checkRuns is null"));
            unavailableSections = ImmutableList.copyOf(requireNonNull(unavailableSections, "unavailableSections is null"));
        }
    }
}
