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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.RecentText;
import com.bytequay.core.domain.RecentTouch;
import com.bytequay.core.store.RecentStore;
import com.bytequay.persistence.sqlite.entity.RecentTextEntity;
import com.bytequay.persistence.sqlite.entity.RecentTouchEntity;
import com.bytequay.persistence.sqlite.repository.RecentTextJpaRepository;
import com.bytequay.persistence.sqlite.repository.RecentTouchJpaRepository;
import com.google.common.collect.ImmutableList;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteRecentStore
        implements RecentStore
{
    private final RecentTouchJpaRepository touches;
    private final RecentTextJpaRepository texts;

    public SqliteRecentStore(RecentTouchJpaRepository touches, RecentTextJpaRepository texts)
    {
        this.touches = requireNonNull(touches, "touches is null");
        this.texts = requireNonNull(texts, "texts is null");
    }

    /**
     * Read-modify-write rather than a blind save: the day's row keeps the
     * strongest reason seen so far, which needs the reason already stored.
     */
    @Override
    @Transactional
    public void recordTouch(RecentTouch touch)
    {
        requireNonNull(touch, "touch is null");
        RecentTouchEntity existing = touches.findById(touch.id()).orElse(null);
        if (existing == null) {
            touches.save(RecentTouchEntity.of(touch));
            return;
        }
        existing.apply(existing.toDomain().mergedWith(touch));
        touches.save(existing);
    }

    @Override
    public List<RecentTouch> findTouches(LocalDate from, LocalDate to)
    {
        requireNonNull(from, "from is null");
        requireNonNull(to, "to is null");
        return touches.findByTouchDayBetweenOrderByTouchedAtDesc(from.toString(), to.toString()).stream()
                .map(RecentTouchEntity::toDomain)
                .collect(toImmutableList());
    }

    /** The id is stable per source, so re-saving a draft replaces it. */
    @Override
    public void saveText(RecentText text)
    {
        requireNonNull(text, "text is null");
        texts.save(RecentTextEntity.of(text));
    }

    @Override
    public void deleteText(String textId)
    {
        requireNonNull(textId, "textId is null");
        texts.deleteById(textId);
    }

    @Override
    public List<RecentText> findTextByItemKey(String itemKey)
    {
        requireNonNull(itemKey, "itemKey is null");
        return texts.findByItemKeyOrderByWrittenAtDesc(itemKey).stream()
                .map(RecentTextEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public List<RecentText> findTextContaining(String fragment, int limit)
    {
        requireNonNull(fragment, "fragment is null");
        if (fragment.isBlank() || limit <= 0) {
            return ImmutableList.of();
        }
        return texts.findByBodyContaining(fragment, Limit.of(limit)).stream()
                .map(RecentTextEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public int deleteBefore(LocalDate cutoffExclusive)
    {
        requireNonNull(cutoffExclusive, "cutoffExclusive is null");
        // Touch days are local, this cutoff is UTC; a day of slack keeps a body
        // from being dropped while the row that points at it is still in the window.
        texts.deleteByWrittenAtBefore(cutoffExclusive.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        return touches.deleteByTouchDayBefore(cutoffExclusive.toString());
    }

    @Override
    @Transactional
    public void deleteAll()
    {
        touches.deleteAllInBatch();
        texts.deleteAllInBatch();
    }

    @Override
    @Transactional
    public boolean deleteTouch(String touchId)
    {
        requireNonNull(touchId, "touchId is null");
        if (!touches.existsById(touchId)) {
            return false;
        }
        touches.deleteById(touchId);
        return true;
    }
}
