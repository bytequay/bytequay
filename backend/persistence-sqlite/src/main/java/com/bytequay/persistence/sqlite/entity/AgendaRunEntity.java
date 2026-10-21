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

import com.bytequay.core.domain.AgendaRun;
import com.bytequay.persistence.sqlite.converter.JsonColumnConverters.AgendaRunConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "agenda_run", indexes = @Index(name = "idx_agenda_run_day", columnList = "day"))
public class AgendaRunEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false)
    private String day;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = AgendaRunConverter.class)
    private AgendaRun value;

    protected AgendaRunEntity() {}

    public AgendaRunEntity(AgendaRun run)
    {
        id = run.id();
        day = run.day().toString();
        value = run;
    }
}
