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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNullElse;

/**
 * What an Epic keeps beyond its work items: the children its source last reported, and what the user did with them. Syncing children never
 * writes to GitHub or Jira.
 *
 * @param id the canonical identity of the issue or ticket marked as the Epic
 * @param childrenTruncated whether the source had more children than one read returns
 * @param synced the children the user synced into work, as they were then; one later missing from {@code children} is no longer a child
 * @param dismissed the unsynced children Not now hid; any other unsynced child offers syncing again
 * @param concludedAt when the user concluded the Epic with all its work done; a concluded Epic leaves the drawer
 * @param outcome what the user accepted the Epic delivered when concluding it
 */
public record Epic(
        String id,
        String workspaceId,
        List<EpicChild> children,
        Optional<Instant> childrenReadAt,
        boolean childrenTruncated,
        List<EpicChild> synced,
        List<String> dismissed,
        Optional<Instant> concludedAt,
        String outcome)
{
    public Epic
    {
        checkText(id, "id");
        checkText(workspaceId, "workspaceId");
        children = ImmutableList.copyOf(children);
        childrenReadAt = requireNonNullElse(childrenReadAt, Optional.empty());
        synced = ImmutableList.copyOf(synced);
        dismissed = ImmutableList.copyOf(dismissed);
        concludedAt = requireNonNullElse(concludedAt, Optional.empty());
        outcome = requireNonNullElse(outcome, "");
    }

    public static Epic of(String id, String workspaceId)
    {
        return new Epic(id, workspaceId, ImmutableList.of(), Optional.empty(), false, ImmutableList.of(), ImmutableList.of(), Optional.empty(), "");
    }

    /** Children the source reports that the user has not synced. */
    public List<EpicChild> unsynced()
    {
        Set<String> done = synced.stream().map(EpicChild::identity).collect(toImmutableSet());
        return children.stream().filter(child -> !done.contains(child.identity())).collect(toImmutableList());
    }

    /** Whether to offer syncing: an unsynced child exists that Not now did not hide. */
    public boolean offered()
    {
        return unsynced().stream().anyMatch(child -> !dismissed.contains(child.identity()));
    }

    /** Synced children the source no longer reports. */
    public List<EpicChild> former()
    {
        Set<String> current = children.stream().map(EpicChild::identity).collect(toImmutableSet());
        return synced.stream().filter(child -> !current.contains(child.identity())).collect(toImmutableList());
    }

    public Epic withChildren(List<EpicChild> read, Instant at, boolean truncated)
    {
        return new Epic(id, workspaceId, read, Optional.of(at), truncated, synced, dismissed, concludedAt, outcome);
    }

    /** Records the children synced now; a later sync of the same child changes nothing. */
    public Epic withSynced(List<EpicChild> added)
    {
        Set<String> present = synced.stream().map(EpicChild::identity).collect(toImmutableSet());
        return new Epic(id, workspaceId, children, childrenReadAt, childrenTruncated, ImmutableList.<EpicChild>builder().addAll(synced)
                .addAll(added.stream().filter(child -> !present.contains(child.identity())).iterator()).build(), ImmutableList.of(), concludedAt, outcome);
    }

    public Epic withDismissed()
    {
        return new Epic(id, workspaceId, children, childrenReadAt, childrenTruncated, synced,
                unsynced().stream().map(EpicChild::identity).collect(toImmutableList()), concludedAt, outcome);
    }

    public Epic withConcluded(String accepted, Instant at)
    {
        return new Epic(id, workspaceId, children, childrenReadAt, childrenTruncated, synced, dismissed, Optional.of(at), checkText(accepted, "outcome").trim());
    }
}
