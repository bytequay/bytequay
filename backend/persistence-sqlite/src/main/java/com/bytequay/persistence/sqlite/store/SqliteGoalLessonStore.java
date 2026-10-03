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

import com.bytequay.core.domain.GoalLesson;
import com.bytequay.core.store.GoalLessonStore;
import com.bytequay.persistence.sqlite.entity.GoalLessonEntity;
import com.bytequay.persistence.sqlite.repository.GoalLessonJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteGoalLessonStore
        implements GoalLessonStore
{
    private final GoalLessonJpaRepository lessons;

    public SqliteGoalLessonStore(GoalLessonJpaRepository lessons)
    {
        this.lessons = requireNonNull(lessons, "lessons is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GoalLesson> find(String lessonId)
    {
        return lessons.findById(lessonId).map(GoalLessonEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GoalLesson> writing()
    {
        return lessons.findByState(GoalLesson.State.WRITING.name()).stream().map(GoalLessonEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(GoalLesson lesson)
    {
        GoalLessonEntity entity = lessons.findById(lesson.id()).orElseGet(() -> new GoalLessonEntity(lesson));
        entity.update(lesson);
        lessons.saveAndFlush(entity);
    }
}
