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
package com.bytequay.app.service.attention;

import com.bytequay.core.domain.AttentionMark;
import com.bytequay.core.store.AttentionMarkStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * What the user acknowledged or snoozed in Needs attention, kept in ByteQuay so every build and the assistant see the same marks. The
 * list owns a mark's shape; the assistant only adds marks the way the row's own buttons do.
 */
@Service
public class AttentionMarkService
{
    private final AttentionMarkStore store;
    private final Clock clock;

    public AttentionMarkService(AttentionMarkStore store, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** The snooze menu's choices, by the names the list shows; twins of SNOOZE_OPTIONS in home/attention.ts. */
    public enum Snooze
    {
        HOUR("1 hour"), TOMORROW("tomorrow"), MONDAY("next monday");

        private final String label;

        Snooze(String label)
        {
            this.label = label;
        }

        public String label()
        {
            return label;
        }
    }

    public List<AttentionMark> marks()
    {
        return store.all();
    }

    public void change(List<AttentionMark> saved, List<String> removed)
    {
        store.change(saved, removed);
    }

    /** The lowercased keys of items snoozed to a time still ahead: until then nothing that happens to them shows them. */
    public Set<String> snoozedKeys()
    {
        Instant now = clock.instant();
        return store.all().stream().filter(mark -> mark.until().filter(now::isBefore).isPresent())
                .map(mark -> mark.key().toLowerCase(Locale.ROOT)).collect(toImmutableSet());
    }

    /**
     * Acknowledges an item until something new happens to it. The mark carries no copy of the item, so it counts the item's updates
     * after now until the list first draws the item and records its own fingerprint.
     */
    public AttentionMark acknowledge(String key)
    {
        return save(new AttentionMark(key, AttentionMark.Kind.ACK, Optional.empty(), clock.instant().toString(), Optional.empty(), "acknowledged", Optional.empty()));
    }

    /** Snoozes an item to the end of one of the menu's choices, counted on the user's own clock as the list counts them. */
    public AttentionMark snooze(String key, Snooze snooze)
    {
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(ZoneId.systemDefault());
        Instant until = switch (snooze) {
            case HOUR -> now.plus(Duration.ofHours(1));
            case TOMORROW -> local.toLocalDate().plusDays(1).atTime(9, 0).atZone(local.getZone()).toInstant();
            case MONDAY -> local.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(9, 0).atZone(local.getZone()).toInstant();
        };
        return save(new AttentionMark(key, AttentionMark.Kind.SNOOZE, Optional.of(until), now.toString(), Optional.empty(), snooze.label(), Optional.empty()));
    }

    private AttentionMark save(AttentionMark mark)
    {
        store.change(ImmutableList.of(mark), ImmutableList.of());
        return mark;
    }
}
