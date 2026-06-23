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
package com.bytequay.app.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Wire-shape and round-trip coverage for {@link ParkedProposal}. The
 * dispatcher in {@link com.bytequay.app.service.threads.PublishService}
 * relies on Jackson polymorphism (the {@code action} discriminator) to
 * resolve each parked notification back to its concrete variant — a
 * silent break of any subtype mapping would let real publishes fall
 * through to a 400 in production but still pass the service-level
 * tests, since those build proposals on the typed side and never
 * exercise the JSON wire.
 *
 * <p>These tests serialise each variant, assert the discriminator and
 * source attribution are emitted, then read the JSON back through the
 * sealed supertype and check structural equality. A future refactor
 * that drops a {@code @JsonSubTypes.Type} entry, renames an action, or
 * changes a record's component shape will fail here before reaching
 * the publish path.
 */
class TestParkedProposalSerialization
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestReviewRoundTripsAndAdvertisesItsActionAndSource()
            throws Exception
    {
        ParkedProposal.RequestReview original = new ParkedProposal.RequestReview(
                "Ready for review",
                "Thanks for the look",
                "feature/x",
                "main",
                "/tmp/wt/feature-x",
                "origin/main",
                "diff --git a/x b/x",
                null);

        JsonNode tree = mapper.valueToTree(original);
        assertThat(tree.path("action").asText()).isEqualTo("request_review");
        assertThat(tree.path("source").asText()).isEqualTo("mcp:request_review");
        assertThat(tree.path("summary").asText()).isEqualTo("Ready for review");
        // Nullable fields ride NON_NULL — diffError stays out of the JSON.
        assertThat(tree.has("diffError")).isFalse();

        ParkedProposal back = mapper.readValue(tree.toString(), ParkedProposal.class);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void postCommentRoundTripsWithNestedPrRef()
            throws Exception
    {
        ParkedProposal.PostComment original = new ParkedProposal.PostComment(
                "LGTM",
                new ParkedProposal.PrRef("acme", "widget", 42));

        JsonNode tree = mapper.valueToTree(original);
        assertThat(tree.path("action").asText()).isEqualTo("post_comment");
        assertThat(tree.path("pr").path("owner").asText()).isEqualTo("acme");
        assertThat(tree.path("pr").path("number").asInt()).isEqualTo(42);

        ParkedProposal back = mapper.readValue(tree.toString(), ParkedProposal.class);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void pushRoundTrips()
            throws Exception
    {
        ParkedProposal.Push original = new ParkedProposal.Push(
                "feature/y", "main", "/tmp/wt/feature-y",
                "origin/main", "diff body", null);

        roundTripThroughSupertype(original);
    }

    @Test
    void replyReviewThreadRoundTrips()
            throws Exception
    {
        ParkedProposal.ReplyReviewThread original = new ParkedProposal.ReplyReviewThread(
                12345L, "Replying inline",
                new ParkedProposal.PrRef("acme", "widget", 7));
        roundTripThroughSupertype(original);
    }

    @Test
    void resolveReviewThreadRoundTripsAndCarriesItsResolvedFlag()
            throws Exception
    {
        ParkedProposal.ResolveReviewThread original = new ParkedProposal.ResolveReviewThread(
                67890L, true, new ParkedProposal.PrRef("acme", "widget", 8));

        JsonNode tree = mapper.valueToTree(original);
        assertThat(tree.path("action").asText()).isEqualTo("resolve_review_thread");
        assertThat(tree.path("source").asText()).isEqualTo("mcp:resolve_review_thread");
        assertThat(tree.path("rootCommentId").asLong()).isEqualTo(67890L);
        assertThat(tree.path("resolved").asBoolean()).isTrue();

        ParkedProposal back = mapper.readValue(tree.toString(), ParkedProposal.class);
        assertThat(back).isInstanceOf(ParkedProposal.ResolveReviewThread.class);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void approvePrRoundTrips()
            throws Exception
    {
        ParkedProposal.ApprovePr original = new ParkedProposal.ApprovePr(
                "Looks good", new ParkedProposal.PrRef("acme", "widget", 9));
        roundTripThroughSupertype(original);
    }

    @Test
    void mergePrRoundTrips()
            throws Exception
    {
        ParkedProposal.MergePr original = new ParkedProposal.MergePr(
                "squash", new ParkedProposal.PrRef("acme", "widget", 11));
        roundTripThroughSupertype(original);
    }

    @Test
    void createReviewCommentRoundTrips()
            throws Exception
    {
        ParkedProposal.CreateReviewComment original = new ParkedProposal.CreateReviewComment(
                "consider null", "src/Foo.java", 12, "RIGHT", "abc123",
                10, "RIGHT",
                new ParkedProposal.PrRef("acme", "widget", 13));
        roundTripThroughSupertype(original);
    }

    @Test
    void updatePrBodyRoundTrips()
            throws Exception
    {
        ParkedProposal.UpdatePrBody original = new ParkedProposal.UpdatePrBody(
                "## Summary\n\nNew description.",
                new ParkedProposal.PrRef("acme", "widget", 14));
        roundTripThroughSupertype(original);
    }

    @Test
    void requestReviewerRoundTrips()
            throws Exception
    {
        ParkedProposal.RequestReviewer original = new ParkedProposal.RequestReviewer(
                "alice", new ParkedProposal.PrRef("acme", "widget", 15));
        roundTripThroughSupertype(original);
    }

    @Test
    void commentOnIssueRoundTripsWithIssueRef()
            throws Exception
    {
        ParkedProposal.CommentOnIssue original = new ParkedProposal.CommentOnIssue(
                "thanks for the report",
                new ParkedProposal.IssueRef("acme", "widget", 100));

        JsonNode tree = mapper.valueToTree(original);
        assertThat(tree.path("issue").path("number").asInt()).isEqualTo(100);
        // IssueRef and PrRef have identical shape; this asserts the
        // discriminator routes back to CommentOnIssue, not PostComment.
        ParkedProposal back = mapper.readValue(tree.toString(), ParkedProposal.class);
        assertThat(back).isInstanceOf(ParkedProposal.CommentOnIssue.class);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void setIssueStateRoundTrips()
            throws Exception
    {
        ParkedProposal.SetIssueState original = new ParkedProposal.SetIssueState(
                "closed", new ParkedProposal.IssueRef("acme", "widget", 101));
        roundTripThroughSupertype(original);
    }

    @Test
    void openPrRoundTripsWithRepoRef()
            throws Exception
    {
        ParkedProposal.OpenPr original = new ParkedProposal.OpenPr(
                "Add foo", "feature/foo", "main", "Adds foo.", true,
                new ParkedProposal.RepoRef("acme", "widget"));

        JsonNode tree = mapper.valueToTree(original);
        assertThat(tree.path("draft").asBoolean()).isTrue();
        assertThat(tree.path("repo").path("owner").asText()).isEqualTo("acme");
        assertThat(tree.path("repo").has("number")).isFalse();

        ParkedProposal back = mapper.readValue(tree.toString(), ParkedProposal.class);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void publishReviewRoundTripsWithTypedComments()
            throws Exception
    {
        ParkedProposal.PublishReview.InlineComment c1 =
                new ParkedProposal.PublishReview.InlineComment(
                        "src/A.java", 5, "nit: rename", "RIGHT", null, null);
        ParkedProposal.PublishReview.InlineComment c2 =
                new ParkedProposal.PublishReview.InlineComment(
                        "src/B.java", 12, "split this", "RIGHT", 10, "RIGHT");
        ParkedProposal.PublishReview original = new ParkedProposal.PublishReview(
                "COMMENT", "Overall summary", List.of(c1, c2),
                new ParkedProposal.PrRef("acme", "widget", 200));

        JsonNode tree = mapper.valueToTree(original);
        JsonNode comments = tree.path("comments");
        assertThat(comments.isArray()).isTrue();
        assertThat(comments).hasSize(2);
        // Wire keys are snake_case via @JsonProperty.
        assertThat(comments.get(0).path("file_path").asText()).isEqualTo("src/A.java");
        assertThat(comments.get(1).path("start_line").asInt()).isEqualTo(10);
        // c1 has no start_line / start_side — NON_NULL drops them.
        assertThat(comments.get(0).has("start_line")).isFalse();
        assertThat(comments.get(0).has("start_side")).isFalse();

        ParkedProposal back = mapper.readValue(tree.toString(), ParkedProposal.class);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void nextTaskRoundTrips()
            throws Exception
    {
        ParkedProposal.NextTask original = new ParkedProposal.NextTask(
                "thread-1", "task-1", "feature/n", "main", "/tmp/wt/n",
                "Follow-up", "stacked",
                "origin/main", "diff", null);
        roundTripThroughSupertype(original);
    }

    @Test
    void shipTaskRoundTrips()
            throws Exception
    {
        ParkedProposal.ShipTask original = new ParkedProposal.ShipTask(
                "thread-1", "task-1", "feature/s", "main", "/tmp/wt/s",
                "After ship", "main",
                null, null, "git diff failed",
                "Add cache layer", "## Summary\nCaches reads.");
        roundTripThroughSupertype(original);
    }

    @Test
    void unknownActionFailsDeserialisation()
    {
        // The polymorphic supertype only permits the declared subtypes —
        // an action the @JsonSubTypes list doesn't know about must not
        // silently bind to some neighbouring variant.
        String json = "{\"action\":\"fly_drone\",\"summary\":\"nope\"}";
        assertThrows(InvalidTypeIdException.class,
                () -> mapper.readValue(json, ParkedProposal.class));
    }

    @Test
    void unknownPropertiesAreIgnoredOnDeserialisation()
            throws Exception
    {
        // The interface carries @JsonIgnoreProperties(ignoreUnknown=true)
        // so a row written by a newer publisher carrying a field this
        // reader doesn't know about still resolves cleanly. The
        // "source" field (a virtual getter on the way out, with no
        // matching constructor parameter on the way in) is the
        // canonical example.
        String json = "{"
                + "\"action\":\"post_comment\","
                + "\"body\":\"hello\","
                + "\"pr\":{\"owner\":\"acme\",\"repo\":\"w\",\"number\":1},"
                + "\"source\":\"mcp:post_comment\","
                + "\"futureField\":\"ignored\""
                + "}";
        ParkedProposal back = mapper.readValue(json, ParkedProposal.class);
        assertThat(back).isInstanceOf(ParkedProposal.PostComment.class);
        ParkedProposal.PostComment pc = (ParkedProposal.PostComment) back;
        assertThat(pc.body()).isEqualTo("hello");
        assertThat(pc.pr().number()).isEqualTo(1);
    }

    @Test
    void inlineCommentToleratesUnknownFields()
            throws Exception
    {
        // The LLM may emit extra hint fields per comment; the inner
        // record carries its own ignoreUnknown so adding such a field
        // doesn't fail the whole publish_review parse.
        String json = "{"
                + "\"file_path\":\"src/X.java\","
                + "\"line\":3,"
                + "\"body\":\"x\","
                + "\"side\":\"RIGHT\","
                + "\"hint\":\"future use\""
                + "}";
        ParkedProposal.PublishReview.InlineComment c = mapper.readValue(
                json, ParkedProposal.PublishReview.InlineComment.class);
        assertThat(c.filePath()).isEqualTo("src/X.java");
        assertThat(c.line()).isEqualTo(3);
        assertThat(c.body()).isEqualTo("x");
    }

    @Test
    void actionAndSourceAccessorsExposeTheVariantConstants()
    {
        // The dispatcher logs proposal.action() before serialising and
        // the parking helper logs it on failure; these accessors live
        // on the interface so a Java caller can read the constant
        // without inspecting JSON. Exercise them so a future rename
        // of an action string fails here too.
        assertThat(new ParkedProposal.Push(null, null, null, null, null, null).action())
                .isEqualTo("push");
        assertThat(new ParkedProposal.Push(null, null, null, null, null, null).source())
                .isEqualTo("mcp:push");
        assertThat(new ParkedProposal.MergePr("squash", null).action())
                .isEqualTo("merge_pr");
    }

    /** Serialise through the sealed supertype, deserialise back, assert
     *  the variant survives the trip with structural equality. Used for
     *  variants whose individual field shape we've already covered with
     *  a dedicated test elsewhere. */
    private void roundTripThroughSupertype(ParkedProposal original)
            throws Exception
    {
        String json = mapper.writeValueAsString(original);
        ParkedProposal back = mapper.readValue(json, ParkedProposal.class);
        assertThat(back).isEqualTo(original);
        assertThat(back).isInstanceOf(original.getClass());
    }
}
