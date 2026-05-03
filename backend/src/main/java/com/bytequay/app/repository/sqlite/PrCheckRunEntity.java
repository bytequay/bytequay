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
@Table(name = "pr_check_runs")
class PrCheckRunEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long prId;

    @Column(name = "github_id")
    private Long githubId;

    private String name;

    private String status;

    private String conclusion;

    @Column(name = "html_url")
    private String htmlUrl;

    @Column(name = "output_title")
    private String outputTitle;

    @Column(name = "output_summary")
    private String outputSummary;

    protected PrCheckRunEntity() {}

    Long getId() { return id; }

    Long getPrId() { return prId; }
    void setPrId(Long prId) { this.prId = prId; }

    Long getGithubId() { return githubId; }
    void setGithubId(Long githubId) { this.githubId = githubId; }

    String getName() { return name; }
    void setName(String name) { this.name = name; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    String getConclusion() { return conclusion; }
    void setConclusion(String conclusion) { this.conclusion = conclusion; }

    String getHtmlUrl() { return htmlUrl; }
    void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }

    String getOutputTitle() { return outputTitle; }
    void setOutputTitle(String outputTitle) { this.outputTitle = outputTitle; }

    String getOutputSummary() { return outputSummary; }
    void setOutputSummary(String outputSummary) { this.outputSummary = outputSummary; }
}
