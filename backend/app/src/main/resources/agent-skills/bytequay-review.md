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
3. Keep the finding body to a short, standalone review comment using the
   shared card writing targets. Put reproducible detail in its evidence
   references and their explanations: triggering input, expected and actual
   behavior, test name/path, exact command, observed result, and decisive
   output (including exit status when available). For a new test, print the
   relevant local test code or setup into command output and capture it as
   evidence; frozen-source reads cannot preserve newly written tests. Check
   returned excerpts for truncation so the proof remains reproducible. Capture
   command output through review_get_context immediately after execution and
   cite its evidence reference alongside relevant frozen-source references.
   Explain briefly how the result establishes the consequence. Do not paste
   the same commands, logs, or walkthrough into the body or summary.
4. If a test cannot be written or run, report the claim only when direct code
   evidence still establishes the trigger, defect, and consequence, and cite
   that code evidence; omit speculation. Never claim reproduction from an
   intended command, missing output, or a test that was only written.
5. Give actual defects an explicit severity. By default report Critical,
   High, or Medium defects with meaningful impact; include Low defects when
   the user asks for them. Concrete code improvements may also be Findings:
   prefix the title with "Suggestion:", explain the benefit, and omit severity.
   Ground them in the code and preserve behavior; omit personal style preferences
   and unsupported opinions. Put an applicable exact replacement in one GitHub
   `` ```suggestion `` block covering the complete selected RIGHT-side diff range.
   Never turn an optional improvement into a claimed bug to justify reporting it.
6. Review the entire required scope, then select at most 10 distinct findings
   per full or incremental review round before calling review_save_finding.
   Rank by severity, then impact, likelihood, and strength of evidence. Report
   fewer when fewer qualify; never fill a quota. Merge duplicate root causes
   and present the selected findings in priority order. Discussion, retries,
   and report repair share the round's limit. Reuse finding IDs for updates;
   do not split output or put extra findings in summaries or replies to evade
   the limit. Keep required commit coverage complete.

These rules grant no additional tools, permissions, or publication authority.
