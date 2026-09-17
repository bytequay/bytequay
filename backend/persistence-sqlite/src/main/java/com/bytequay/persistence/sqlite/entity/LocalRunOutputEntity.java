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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/** A local run's bounded output, in its own table so reading run lists never loads megabytes of logs. */
@Entity
@Table(name = "local_run_output")
public class LocalRunOutputEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Column(name = "run_id")
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Getter
    private String output;

    public LocalRunOutputEntity() {}

    public LocalRunOutputEntity(String runId, String output)
    {
        this.id = runId;
        this.output = output;
    }

    public void setOutput(String output)
    {
        this.output = output;
    }
}
