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
package com.bytequay.core.context;

import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalSha;
import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Application-observed merged PR or closed issue, with an exact bounded evidence excerpt.
 * Terminal state records a historical observation; it does not establish a fix or applicability to current code.
 */
public record ContextHistoryRead(
        String id,
        ContextReference reference,
        State state,
        Optional<Instant> sourceUpdatedAt,
        Optional<String> sourceRevision,
        String contentHash,
        Instant readAt,
        int utf8Bytes,
        boolean truncated,
        boolean refreshFailed,
        List<String> gaps,
        Optional<String> evidenceRef)
        implements ContextSourceRead
{
    public ContextHistoryRead
    {
        ContextLimits.text(id, "source read id", 100);
        checkArgument(id.startsWith("context-read:"), "Invalid source read id");
        requireNonNull(reference, "reference is null");
        requireNonNull(state, "state is null");
        checkArgument(switch (reference) {
            case ContextReference.PullRequest pr -> state == State.MERGED && pr.commentId().isEmpty();
            case ContextReference.Issue issue -> state == State.CLOSED && issue.commentId().isEmpty();
            default -> false;
        }, "History requires a merged PR or closed issue identity without a comment selector");
        requireNonNull(sourceUpdatedAt, "sourceUpdatedAt is null");
        checkOptionalSha(sourceRevision, "sourceRevision");
        checkArgument(reference instanceof ContextReference.PullRequest || sourceRevision.isEmpty(), "An issue has no source revision");
        ContextLimits.text(contentHash, "contentHash", 64);
        checkArgument(contentHash.matches("[0-9a-f]{64}"), "Invalid history content hash");
        requireNonNull(readAt, "readAt is null");
        checkArgument(utf8Bytes > 0 && utf8Bytes <= 16 * 1024, "History excerpt exceeds UTF-8 budget");
        gaps = ImmutableList.copyOf(gaps);
        checkArgument(gaps.size() <= 10, "Too many history limitations");
        gaps.forEach(gap -> ContextLimits.text(gap, "history limitation", 1000));
        checkOptionalText(evidenceRef, "evidenceRef", 500);
        checkArgument(evidenceRef.isPresent(), "History requires retained evidence");
    }

    @Override
    public String getKind()
    {
        return "HISTORY";
    }

    public long repositoryId()
    {
        return reference instanceof ContextReference.PullRequest pr ? pr.repositoryId() : ((ContextReference.Issue) reference).repositoryId();
    }

    public RepoRef repository()
    {
        return reference instanceof ContextReference.PullRequest pr ? pr.repository() : ((ContextReference.Issue) reference).repository();
    }

    /** Stable source identity, excluding receipt IDs and capture times so repeated equal observations can be reused. */
    public String sourceKey()
    {
        int number = reference instanceof ContextReference.PullRequest pr ? pr.number() : ((ContextReference.Issue) reference).number();
        return "history:" + reference.getKind() + ":" + repositoryId() + ":" + number;
    }

    /** Verifies the exact evidence bytes when a run or notebook is reconstructed from storage. */
    public void checkExcerpt(String excerpt)
    {
        checkArgument(ContextLimits.bytes(excerpt) == utf8Bytes && Hashing.sha256().hashString(excerpt, UTF_8).toString().equals(contentHash),
                "History evidence differs from its observed content hash");
    }

    public enum State { MERGED, CLOSED }
}
