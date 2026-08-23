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

import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.persistence.sqlite.converter.DiffCommitListConverter;
import com.bytequay.persistence.sqlite.converter.DiffFileContentMapConverter;
import com.bytequay.persistence.sqlite.converter.DiffFileListConverter;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "pr_diff")
public class PrDiffEntity
{
    @Id
    private String id;

    @Column(nullable = false)
    private Long prId;

    private String baseSha;

    private String headSha;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = DiffFileListConverter.class)
    private List<DiffFile> files;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = DiffCommitListConverter.class)
    private List<DiffCommit> commits;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = DiffFileContentMapConverter.class)
    private Map<String, DiffFileContent> fileContents;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant syncedAt;

    public PrDiffEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getPrId() { return prId; }
    public void setPrId(Long prId) { this.prId = prId; }

    public String getBaseSha() { return baseSha; }
    public void setBaseSha(String baseSha) { this.baseSha = baseSha; }

    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }

    public List<DiffFile> getFiles() { return files; }
    public void setFiles(List<DiffFile> files) { this.files = files; }

    public List<DiffCommit> getCommits() { return commits; }
    public void setCommits(List<DiffCommit> commits) { this.commits = commits; }

    public Map<String, DiffFileContent> getFileContents() { return fileContents; }
    public void setFileContents(Map<String, DiffFileContent> fileContents) { this.fileContents = fileContents; }

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
}
