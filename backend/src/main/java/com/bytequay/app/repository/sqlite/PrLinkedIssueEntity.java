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
@Table(name = "pr_linked_issue")
class PrLinkedIssueEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pr_id", nullable = false)
    private long prId;

    @Column(name = "issue_number", nullable = false)
    private int issueNumber;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String state;

    @Column(name = "html_url", nullable = false)
    private String htmlUrl;

    protected PrLinkedIssueEntity() {}

    Long getId() { return id; }

    long getPrId() { return prId; }
    void setPrId(long prId) { this.prId = prId; }

    int getIssueNumber() { return issueNumber; }
    void setIssueNumber(int issueNumber) { this.issueNumber = issueNumber; }

    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }

    String getState() { return state; }
    void setState(String state) { this.state = state; }

    String getHtmlUrl() { return htmlUrl; }
    void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }
}
