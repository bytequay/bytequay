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
package com.bytequay.app.service.epic;

import com.bytequay.core.domain.EpicChild;
import com.bytequay.core.domain.EpicProposal;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * An Epic's children as its view shows them.
 *
 * @param synced the identities of the children the user synced into work
 * @param former the synced children the source no longer reports
 * @param offered whether to offer syncing: an unsynced child exists that Not now did not hide
 * @param concludedAt when the user concluded the Epic with all its work done
 * @param outcome what the user accepted the Epic delivered
 * @param proposals work the development model proposed, waiting until the user accepts or dismisses each
 * @param breakingDown whether the development model is proposing work now
 * @param breakdownError why the last breakdown failed, until the next one starts
 * @param breakdownNote what the last breakdown said when it found no more work worth proposing, until the next one starts
 */
public record EpicView(String id, List<EpicChild> children, List<String> synced, List<EpicChild> former, Optional<Instant> childrenReadAt, boolean childrenTruncated,
        boolean offered, Optional<Instant> concludedAt, String outcome, List<EpicProposal> proposals, boolean breakingDown, Optional<String> breakdownError,
        Optional<String> breakdownNote)
{
    public EpicView
    {
        requireNonNull(id, "id is null");
        children = ImmutableList.copyOf(children);
        synced = ImmutableList.copyOf(synced);
        former = ImmutableList.copyOf(former);
        requireNonNull(childrenReadAt, "childrenReadAt is null");
        requireNonNull(concludedAt, "concludedAt is null");
        requireNonNull(outcome, "outcome is null");
        proposals = ImmutableList.copyOf(proposals);
        requireNonNull(breakdownError, "breakdownError is null");
        requireNonNull(breakdownNote, "breakdownNote is null");
    }
}
