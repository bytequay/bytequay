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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextGrade;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.persistence.sqlite.converter.ContextGradeCitationListConverter;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** One task's latest grade of an exact note version by one grader; the key makes a later grade replace it. */
@Entity
@Table(name = "context_grade", indexes = {
        @Index(name = "idx_context_grade_task", columnList = "task_id"),
        @Index(name = "idx_context_grade_version", columnList = "workspace_id,entry_id,entry_version"),
        @Index(name = "idx_context_grade_kind", columnList = "workspace_id,grade,graded_at_ms")})
public class ContextGradeEntity
{
    @Id
    @Column(columnDefinition = "TEXT")
    private String id;

    @Column(nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String taskId;

    private String reviewRunId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ContextUsageEvent.TaskKind taskKind;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ContextGrade.Grader grader;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ContextGrade.Grade grade;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String entryId;

    @Column(nullable = false)
    private long entryVersion;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ContextEntryKind entryKind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String scopeId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String entryTitle;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String quote;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = ContextGradeCitationListConverter.class)
    private List<ContextGrade.Citation> citations;

    private Long correctionVersion;

    @Column(columnDefinition = "TEXT")
    private String correctionStatement;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant gradedAt;

    @Column(nullable = false)
    private long gradedAtMs;

    protected ContextGradeEntity() {}

    public ContextGradeEntity(ContextGrade grade)
    {
        id = key(grade.taskId(), grade.grader(), grade.note().id(), grade.note().version());
        workspaceId = grade.workspaceId();
        taskId = grade.taskId();
        reviewRunId = grade.reviewRunId().orElse(null);
        taskKind = grade.taskKind();
        grader = grade.grader();
        this.grade = grade.grade();
        entryId = grade.note().id();
        entryVersion = grade.note().version();
        entryKind = grade.note().kind();
        scopeId = grade.note().scopeId();
        entryTitle = grade.note().title();
        reason = grade.reason();
        quote = grade.quote().orElse(null);
        citations = grade.citations();
        correctionVersion = grade.correction().map(ContextGrade.Correction::version).orElse(null);
        correctionStatement = grade.correction().map(ContextGrade.Correction::statement).orElse(null);
        gradedAt = grade.gradedAt();
        gradedAtMs = grade.gradedAt().toEpochMilli();
    }

    /** The version comes first so an entry ID containing the separator cannot collide with another key. */
    public static String key(String taskId, ContextGrade.Grader grader, String entryId, long version)
    {
        return taskId + ":" + grader + ":" + version + ":" + entryId;
    }

    public ContextGrade toDomain()
    {
        return new ContextGrade(workspaceId, taskId, Optional.ofNullable(reviewRunId), taskKind, grader, grade,
                new ContextUsageEvent.Note(entryId, entryVersion, entryKind, scopeId, entryTitle), reason, Optional.ofNullable(quote), citations,
                Optional.ofNullable(correctionVersion).map(version -> new ContextGrade.Correction(version, correctionStatement)), gradedAt);
    }
}
