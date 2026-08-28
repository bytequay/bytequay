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
package com.bytequay.app.web;

import com.bytequay.app.service.recent.RecentChange;
import com.bytequay.app.service.recent.RecentDayPage;
import com.bytequay.app.service.recent.RecentDraft;
import com.bytequay.app.service.recent.RecentFilter;
import com.bytequay.app.service.recent.RecentRow;
import com.bytequay.app.service.recent.RecentService;
import com.bytequay.app.service.recent.RecentWordHit;
import com.bytequay.app.service.recent.ViewerTouch;
import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentReason;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@RestController
public class RecentController
{
    private static final int DEFAULT_SEARCH_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final RecentService recent;

    public RecentController(RecentService recent)
    {
        this.recent = requireNonNull(recent, "recent is null");
    }

    /** The day-grouped stream, one day per response. */
    @GetMapping("/recent")
    public RecentDayPage stream(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String workspace,
            @RequestParam(defaultValue = "false") boolean changedSinceRead,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String cursor)
    {
        LocalDate start = day(from, "from").orElseGet(recent::windowStart);
        // The service clamps both ends to the retention window; MAX just means
        // "no upper bound the caller cares about".
        LocalDate end = day(to, "to").orElse(LocalDate.MAX);
        RecentFilter filter = new RecentFilter(
                start, end, itemTypes(types), reason(action), blankToNull(workspace), changedSinceRead, text);
        return recent.stream(filter, day(cursor, "cursor").orElse(null));
    }

    /** Items mode. An empty query answers with the most recently touched items. */
    @GetMapping("/recent/search")
    public List<RecentRow> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "" + DEFAULT_SEARCH_LIMIT) int limit)
    {
        return recent.search(q, Math.min(limit, MAX_LIMIT));
    }

    /** Words mode. */
    @GetMapping("/recent/words")
    public List<RecentWordHit> words(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "" + DEFAULT_SEARCH_LIMIT) int limit)
    {
        return recent.words(q, Math.min(limit, MAX_LIMIT));
    }

    /** What moved on the recently-touched items. Drives the dots. */
    @GetMapping("/recent/changes")
    public List<RecentChange> changes()
    {
        return recent.changes();
    }

    /** The renderer reporting a dwell or an opened diff. */
    @PostMapping("/recent/touch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void touch(@RequestBody ViewerTouch touch)
    {
        if (touch == null || touch.type() == null || touch.signal() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "touch requires a type and a signal");
        }
        recent.recordViewerTouch(touch);
    }

    /** The composer reporting text the user has not sent. */
    @PostMapping("/recent/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void draft(@RequestBody RecentDraft draft)
    {
        if (draft == null || draft.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "draft requires a type");
        }
        recent.recordDraft(draft);
    }

    @DeleteMapping("/recent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear()
    {
        recent.clear();
    }

    /**
     * A row id carries the repository, so it spans path segments. Matching the
     * remainder of the path keeps the id readable instead of forcing callers to
     * percent-encode a slash, which the servlet container rejects outright.
     */
    @DeleteMapping("/recent/{*id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String id)
    {
        String rowId = id.startsWith("/") ? id.substring(1) : id;
        if (rowId.isBlank() || !recent.clear(rowId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such recent row");
        }
    }

    private static Set<RecentItemType> itemTypes(String types)
    {
        if (types == null || types.isBlank()) {
            return ImmutableSet.of();
        }
        return ImmutableSet.copyOf(types.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> parse(RecentItemType.class, value, "type"))
                .collect(toImmutableSet());
    }

    private static RecentReason reason(String action)
    {
        return action == null || action.isBlank() ? null : parse(RecentReason.class, action.trim(), "action");
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String name)
    {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported " + name + ": " + value);
        }
    }

    private static Optional<LocalDate> day(String value, String name)
    {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.trim()));
        }
        catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be an ISO date");
        }
    }

    private static String blankToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
