# Claude discussion on an existing review card

This isolated case checks whether a question on a saved review card receives a
new cached PR comment without changing the review's frozen context. The
disposable repository has a boundary bug: `contains(10, 20, 20)` and
`contains(20, 30, 20)` both return `True` at the reviewed head, although its
README requires windows to exclude the end.

Claude Sonnet accepted the review with complete coverage and one finding. Its
first investigation recorded an `EMPTY` project preparation decision; there
was no saved knowledge note. After acceptance, the fixture inserted a clearly
marked simulated reviewer comment into the local PR timeline. The comment
claimed the second call returned `False`. The question on the existing purpose
card asked whether the latest comment changed the review and did not repeat
the comment's claim.

The new discussion turn saved a 1,236-byte supplement containing the exact
comment, its source reference and cache freshness warning. Delivery was
`CONFIRMED`. Claude checked both calls against the frozen reviewed code,
rejected the comment's result, and kept the original purpose and finding. The
saved summary, finding, notebook revision, original packets and fingerprints
were unchanged; only the new discussion delivery and evidence were appended.
[`result.json`](result.json) retains the exact IDs, packet digests, source hash,
reply and request counts without runtime credentials or transcript.

The fixture reused the checked-in review corpus generator and PR seed helper,
the local read-only HTTPS Git helper, and a GET-only fake GitHub API. Runtime
data, certificates and the fake token stayed under `/private/tmp`. The fake
GitHub endpoint received one `GET`, and the local Git server received 21 read
requests; neither accepted a write. No real GitHub request was made.

This is one discussion behavior observation, not a measured quality rate or a
saved-note reuse case. The comment was inserted directly into the disposable
timeline, so the cached detail's recorded write time precedes the event; the
supplement preserves that provenance rather than treating it as a fresh GitHub
read.
