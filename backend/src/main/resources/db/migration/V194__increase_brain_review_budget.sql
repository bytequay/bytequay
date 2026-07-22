-- New Brain review rounds are created with the application default. Extend
-- review loops that were already live when that default increased to five.
UPDATE response_round
SET budget = 5
WHERE budget = 3
  AND status IN ('triaging', 'addressing');
