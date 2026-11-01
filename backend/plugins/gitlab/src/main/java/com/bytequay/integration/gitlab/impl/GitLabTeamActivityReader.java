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
package com.bytequay.integration.gitlab.impl;

import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.TeamActivityEvent;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingPerson;
import com.bytequay.core.hosting.TeamActivityReader;
import com.bytequay.core.hosting.TeamIdentity;
import com.bytequay.core.hosting.TeamObservation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.RATE_LIMITED;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static com.bytequay.core.hosting.TeamIdentity.mentions;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static java.util.Objects.requireNonNull;

/**
 * Activity from the project events of watched GitLab projects. GitLab's per-user events leave out merge requests, so it has no
 * per-person stream; a request's author is read for a few of the newest requests in each pass.
 */
public final class GitLabTeamActivityReader
        implements TeamActivityReader
{
    private static final int AUTHORS_PER_PASS = 5;

    private final GitLabHttpClient http;

    public GitLabTeamActivityReader(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitLabHttpClient(http, mapper);
    }

    @Override
    public HostingPerson person(HostingAccess access, String login)
    {
        List<User> users = ImmutableList.copyOf(get(access, "users?username=" + encode(login), User[].class));
        User user = users.stream().filter(candidate -> login.equalsIgnoreCase(candidate.username())).findFirst()
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "No GitLab user has the username " + login));
        return new HostingPerson(user.id(), user.username(), Optional.ofNullable(user.name()).filter(name -> !name.isBlank()).orElse(user.username()),
                Optional.ofNullable(user.avatarUrl()).orElse(""));
    }

    @Override
    public TeamObservation personActivity(HostingAccess access, String login, TeamIdentity identity)
    {
        return new TeamObservation(ImmutableList.of(), ImmutableList.of(), false);
    }

    @Override
    public TeamObservation repositoryActivity(HostingAccess access, String repository, TeamIdentity identity, int offset,
            BiFunction<Integer, Instant, Optional<StoredPrDetail>> cachedDetails)
    {
        requireNonNull(identity, "identity is null");
        Reads reads = new Reads();
        String project = "projects/" + encode(repository);
        // The filter takes a date and excludes it, so this asks from the day before the look-back starts.
        String after = LocalDate.ofInstant(Instant.now().minus(LOOK_BACK), ZoneOffset.UTC).minusDays(1).toString();
        Optional<Event[]> events = reads.read(repository + " events", () -> get(access, project + "/events?after=" + after + "&per_page=100", Event[].class));
        List<TeamActivityEvent> observed = new ArrayList<>();
        if (events.isPresent()) {
            Map<String, Optional<User>> authors = authors(access, project, repository, events.orElseThrow(), offset, reads);
            for (Event event : events.orElseThrow()) {
                try {
                    event(access, repository, identity, event, authors).ifPresent(observed::add);
                }
                catch (RuntimeException malformed) {
                    reads.errors.add("A malformed GitLab activity record was skipped");
                }
            }
        }
        return new TeamObservation(observed, reads.errors, reads.rateLimited);
    }

    /** The authors of a few of the newest requests and issues the events name, starting at {@code offset}; the rest stay unknown. */
    private Map<String, Optional<User>> authors(HostingAccess access, String project, String repository, Event[] events, int offset, Reads reads)
    {
        List<String> subjects = new ArrayList<>();
        for (Event event : events) {
            subject(event).filter(subject -> !subjects.contains(subject)).ifPresent(subjects::add);
        }
        Map<String, Optional<User>> authors = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(AUTHORS_PER_PASS, subjects.size()) && !reads.rateLimited; index++) {
            String subject = subjects.get(Math.floorMod(offset + index, subjects.size()));
            String path = project + (subject.startsWith("MergeRequest#") ? "/merge_requests/" : "/issues/") + subject.substring(subject.indexOf('#') + 1);
            authors.put(subject, reads.read(repository + " " + subject, () -> get(access, path, Subject.class)).map(Subject::author));
        }
        return authors;
    }

    private static Optional<TeamActivityEvent> event(HostingAccess access, String repository, TeamIdentity identity, Event event, Map<String, Optional<User>> authors)
    {
        Optional<String> subject = subject(event);
        Optional<String> kind = kind(event);
        if (subject.isEmpty() || kind.isEmpty() || event.author() == null || event.createdAt() == null) {
            return Optional.empty();
        }
        boolean request = subject.orElseThrow().startsWith("MergeRequest#");
        int number = Integer.parseInt(subject.orElseThrow().substring(subject.orElseThrow().indexOf('#') + 1));
        Optional<User> author = authors.getOrDefault(subject.orElseThrow(), Optional.empty());
        long actorId = event.author().id();
        String body = Optional.ofNullable(event.note()).map(Note::body).orElse("");
        boolean review = kind.orElseThrow().equals("PR_REVIEWED");
        long authorId = author.map(User::id).orElse(0L);
        List<Long> direct = new ArrayList<>();
        identity.members().forEach((memberId, login) -> {
            if (memberId != identity.viewerId() && (actorId == memberId && (mentions(body, identity.viewerLogin()) || review && authorId == identity.viewerId())
                    || actorId == identity.viewerId() && (mentions(body, login) || review && authorId == memberId))) {
                direct.add(memberId);
            }
        });
        String url = (request ? GITLAB.pullRequestUrl(access.server().webBaseUrl(), repository, number) : GITLAB.issueUrl(access.server().webBaseUrl(), repository, number))
                .toString();
        return Optional.of(new TeamActivityEvent("gitlab-event:" + event.id(), kind.orElseThrow(), actorId, event.author().username(), repository, number,
                Optional.ofNullable(event.targetTitle()).orElse(""), url, Instant.parse(event.createdAt()), author.map(User::id), author.map(User::username), "REPOSITORY",
                direct));
    }

    /** The request or issue an event is about, as {@code MergeRequest#7} or {@code Issue#7}. */
    private static Optional<String> subject(Event event)
    {
        if (event.note() != null) {
            return Optional.ofNullable(event.note().noteableType()).filter(type -> type.equals("MergeRequest") || type.equals("Issue"))
                    .flatMap(type -> Optional.ofNullable(event.note().noteableIid()).map(iid -> type + "#" + iid));
        }
        return Optional.ofNullable(event.targetType()).filter(type -> type.equals("MergeRequest") || type.equals("Issue"))
                .flatMap(type -> Optional.ofNullable(event.targetIid()).map(iid -> type + "#" + iid));
    }

    private static Optional<String> kind(Event event)
    {
        String action = Optional.ofNullable(event.actionName()).orElse("");
        if (event.note() != null) {
            if (!action.startsWith("commented")) {
                return Optional.empty();
            }
            boolean request = "MergeRequest".equals(event.note().noteableType());
            return Optional.of(!request ? "ISSUE_COMMENT" : "DiffNote".equals(event.targetType()) ? "PR_REVIEW_COMMENT" : "PR_COMMENT");
        }
        boolean request = "MergeRequest".equals(event.targetType());
        return Optional.ofNullable(switch (action) {
            case "opened" -> request ? "PR_OPENED" : "ISSUE_OPENED";
            case "accepted" -> request ? "PR_MERGED" : null;
            case "approved" -> request ? "PR_REVIEWED" : null;
            case "closed" -> request ? null : "ISSUE_CLOSED";
            default -> null;
        });
    }

    private <T> T get(HostingAccess access, String path, Class<T> type)
    {
        return http.get(access, path, Optional.empty(), type).body().orElseThrow();
    }

    /** The failed reads of one pass; GitLab's rate limit pauses the rest. */
    private static final class Reads
    {
        private final List<String> errors = new ArrayList<>();
        private boolean rateLimited;

        <T> Optional<T> read(String label, Supplier<T> request)
        {
            if (rateLimited) {
                return Optional.empty();
            }
            try {
                return Optional.of(request.get());
            }
            catch (HostingFailure failure) {
                rateLimited = failure.kind() == RATE_LIMITED;
                errors.add(label + (rateLimited ? ": GitLab rate limit; collection paused" : ": unavailable (" + failure.kind() + ")"));
                return Optional.empty();
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(long id, String username, String name, @JsonProperty("avatar_url") String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Event(long id, @JsonProperty("action_name") String actionName, @JsonProperty("target_type") String targetType, @JsonProperty("target_iid") Long targetIid,
            @JsonProperty("target_title") String targetTitle, @JsonProperty("created_at") String createdAt, User author, Note note) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Note(String body, @JsonProperty("noteable_type") String noteableType, @JsonProperty("noteable_iid") Long noteableIid) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Subject(User author) {}
}
