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

import com.bytequay.app.domain.PullRequest;
import com.google.common.collect.ImmutableList;

import java.util.Comparator;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Defines the supported sort orders for the PR list.
 * The user's preference is stored in {@code app_settings} under the key {@code pr.sort.order}.
 * Each constant's {@link #key()} is the value stored in the database.
 */
public enum PullRequestOrder
{
    UPDATED_AT_DESC("updated-desc") {
        @Override
        public Comparator<PullRequest> comparator()
        {
            return Comparator.comparing(PullRequest::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        }
    },

    /**
     * Unviewed PRs first, then viewed-but-not-reviewed, then reviewed/approved.
     * Within each bucket, newer updatedAt wins.
     */
    SMART("smart") {
        @Override
        public Comparator<PullRequest> comparator()
        {
            return Comparator.comparingInt(PullRequestOrder::stateScore)
                    .thenComparing(PullRequest::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        }
    };

    private final String key;

    PullRequestOrder(String key)
    {
        this.key = key;
    }

    public String key()
    {
        return key;
    }

    public abstract Comparator<PullRequest> comparator();

    public List<PullRequest> sort(List<PullRequest> prs)
    {
        requireNonNull(prs, "prs is null");
        return ImmutableList.sortedCopyOf(comparator(), prs);
    }

    public static PullRequestOrder fromKey(String key)
    {
        if (key != null) {
            for (PullRequestOrder order : values()) {
                if (order.key.equals(key)) {
                    return order;
                }
            }
        }
        return SMART;
    }

    private static int stateScore(PullRequest pr)
    {
        if (pr.reviewedAt() != null) {
            return 2;
        }
        if (pr.viewedAt() != null) {
            return 1;
        }
        return 0;
    }
}
