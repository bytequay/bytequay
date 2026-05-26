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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "skill_asset")
class SkillAssetEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    @Column(nullable = false)
    private String filename;

    @Lob
    @Column(nullable = false)
    private byte[] content;

    protected SkillAssetEntity() {}

    Long getId() { return id; }

    Long getSkillId() { return skillId; }
    void setSkillId(Long skillId) { this.skillId = skillId; }

    String getFilename() { return filename; }
    void setFilename(String filename) { this.filename = filename; }

    byte[] getContent() { return content; }
    void setContent(byte[] content) { this.content = content; }
}
