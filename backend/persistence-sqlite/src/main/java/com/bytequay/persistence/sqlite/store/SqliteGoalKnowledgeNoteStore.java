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

import com.bytequay.core.domain.GoalKnowledgeNote;
import com.bytequay.core.store.GoalKnowledgeNoteStore;
import com.bytequay.persistence.sqlite.entity.GoalKnowledgeNoteEntity;
import com.bytequay.persistence.sqlite.repository.GoalKnowledgeNoteJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteGoalKnowledgeNoteStore
        implements GoalKnowledgeNoteStore
{
    private final GoalKnowledgeNoteJpaRepository notes;

    public SqliteGoalKnowledgeNoteStore(GoalKnowledgeNoteJpaRepository notes)
    {
        this.notes = requireNonNull(notes, "notes is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<GoalKnowledgeNote> list(String goalId)
    {
        return notes.findByGoalIdOrderBySavedAtAscIdAsc(goalId).stream().map(GoalKnowledgeNoteEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(GoalKnowledgeNote note)
    {
        GoalKnowledgeNoteEntity entity = notes.findById(note.id()).orElseGet(() -> new GoalKnowledgeNoteEntity(note));
        entity.update(note);
        notes.saveAndFlush(entity);
    }
}
