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
package com.bytequay.app.repository.sqlite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pr_files")
class PrFileEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long prId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String filename;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;

    private String status;

    protected PrFileEntity() {}

    Long getId() { return id; }

    Long getPrId() { return prId; }
    void setPrId(Long prId) { this.prId = prId; }

    String getFilename() { return filename; }
    void setFilename(String filename) { this.filename = filename; }

    int getAdditions() { return additions; }
    void setAdditions(int additions) { this.additions = additions; }

    int getDeletions() { return deletions; }
    void setDeletions(int deletions) { this.deletions = deletions; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }
}
