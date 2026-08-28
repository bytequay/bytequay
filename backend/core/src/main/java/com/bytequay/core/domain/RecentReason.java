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

/**
 * Why an item is in the Recent stream. The declaration order is the dedupe
 * order: when a day holds several touches of one item, the strongest reason
 * wins the row.
 */
public enum RecentReason
{
    READ,
    EDITED,
    PROMPTED,
    PUSHED,
    COMMENTED,
    CHANGES_REQUESTED,
    APPROVED,
    MERGED;

    /** True when this reason outranks {@code other} for same-day dedupe. */
    public boolean strongerThan(RecentReason other)
    {
        return other == null || ordinal() > other.ordinal();
    }
}
