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
package com.bytequay.core.hosting;

import com.bytequay.core.domain.HostedIssuePreview;
import com.bytequay.core.domain.HostedIssueSource;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Remote issue operations normalized for the existing workspace issue surface. */
public interface IssueClient
{
    /** One-based pages: open issues newest creation first without {@code since}, otherwise all changed issues oldest update first. */
    IssueSyncPage list(HostingTarget target, Optional<Instant> since, int page);

    Optional<RepositoryIssue> find(HostingTarget target, int number);

    HostedIssuePreview preview(HostingTarget target, int number);

    RepositoryIssueDetail detail(HostingTarget target, int number);

    HostedIssueSource source(HostingTarget target, int number, boolean requireOpen);

    HostedIssueSource create(HostingTarget target, String title, String body);

    /** Returns the note, or a command result with no remote ID when no note was created. */
    PrTimelineEvent comment(HostingTarget target, int number, String body);

    void close(HostingTarget target, int number);

    String updateType(HostingTarget target, int number, String type);

    IssueMetadata updateMetadata(HostingTarget target, int number, List<String> assignees, List<String> labels);

    IssueActivity activity(HostingTarget target, int number);

    /** The issues the provider lists as children of issue {@code number}. */
    ChildIssues children(HostingTarget target, int number);
}
