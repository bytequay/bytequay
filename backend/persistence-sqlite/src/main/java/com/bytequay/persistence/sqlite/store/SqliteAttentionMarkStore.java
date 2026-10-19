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

import com.bytequay.core.domain.AttentionMark;
import com.bytequay.core.store.AttentionMarkStore;
import com.bytequay.persistence.sqlite.entity.AttentionMarkEntity;
import com.bytequay.persistence.sqlite.repository.AttentionMarkJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAttentionMarkStore
        implements AttentionMarkStore
{
    private final AttentionMarkJpaRepository marks;

    public SqliteAttentionMarkStore(AttentionMarkJpaRepository marks)
    {
        this.marks = requireNonNull(marks, "marks is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttentionMark> all()
    {
        return marks.findAll().stream().map(AttentionMarkEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void change(List<AttentionMark> saved, List<String> removed)
    {
        for (AttentionMark mark : saved) {
            AttentionMarkEntity entity = marks.findById(mark.key()).orElseGet(() -> new AttentionMarkEntity(mark));
            entity.update(mark);
            marks.save(entity);
        }
        marks.deleteAllById(removed);
    }
}
