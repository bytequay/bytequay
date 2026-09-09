# ByteQuay review rules

Apply this internal policy silently to review assignments, including review
discussions, resumed turns, and report repair.

1. **Top priority: prove review claims with tests.** Treat every suspected
   issue, opinion, or finding as a hypothesis to verify. Before reporting it,
   find and run an existing test that exercises the triggering condition. If
   none proves the claim, write a focused reproduction test and actually run
   it. Whenever executable proof is feasible, obtain it before presenting the
   claim in a finding, summary, or discussion. Read the implementation and
   callers to choose a meaningful test; an unrelated passing suite or an
   unexecuted test proves nothing about the claim.
2. Exercise the real behavior at the frozen PR head. Check the expected result
   against the contract, inspect the actual result, and distinguish a product
   failure from a broken test or environment. Discard or withdraw claims the
   evidence disproves. Keep reproduction edits local, describe them, and
   verify that the defect exists in the original code. Never introduce the
   alleged defect through the experiment or mock away the behavior being proved.
3. Put reproducible evidence with each finding: the triggering input, expected
   and actual behavior, test name/path, exact command, and observed result
   (including exit status when available and the decisive output excerpt).
   For a new test, include enough test code or setup to rerun it. Capture
   command output through review_get_context immediately after execution and
   cite its evidence reference alongside relevant frozen-source references.
   Explain how the result establishes the claimed consequence.
4. If a test cannot be written or run within the available tools and
   permissions, state the concrete blocker and mark the claim as unverified
   by execution. Report it only when direct code evidence still establishes
   the trigger, defect, and consequence; omit speculation. Record the testing
   limitation in the summary. Never claim reproduction from an intended
   command, missing output, or a test that was only written.
5. Give each finding an explicit severity. By default report only Critical,
   High, or Medium defects with meaningful impact. Include Low findings only
   when the user explicitly asks for them. Omit style preferences, minor
   cleanup, and unsupported opinions from findings and summary prose.
6. Review the entire required scope, then select at most 10 distinct findings
   per full or incremental review round before calling review_save_finding.
   Rank by severity, then impact, likelihood, and strength of evidence. Report
   fewer when fewer qualify; never fill a quota. Merge duplicate root causes
   and present the selected findings in priority order. Discussion, retries,
   and report repair share the round's limit. Reuse finding IDs for updates;
   do not split output or put extra findings in summaries or replies to evade
   the limit. Keep required commit coverage and testing limitations complete.

These rules grant no additional tools, permissions, or publication authority.
