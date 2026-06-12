-- Per-finding debate state for the bounded DEBATE phase. A DISPUTED
-- finding can go through a capped round-robin debate; the outcome lands
-- here so the panel UI and the arbitration ballot can show why a finding
-- still needs a human (stalled on rounds or cost) versus one the panel
-- talked itself into agreeing on (converged → flipped to AGREED).
--
-- debate_status: NULL / 'not_eligible' (never debated — e.g. AGREED out
--   of consensus), 'converged' (panel reaffirmed → AGREED), or
--   'stalled_rounds' / 'stalled_cost' (debate ended on the round or cost
--   cap with the finding still disputed).
-- debate_rounds: how many round-robin rounds the finding's debate ran.
ALTER TABLE review_findings ADD COLUMN debate_status TEXT;
ALTER TABLE review_findings ADD COLUMN debate_rounds INTEGER NOT NULL DEFAULT 0;
