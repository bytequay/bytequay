-- Cache the repo owner's avatar_url from GitHub's /repos response so the
-- repo overview avatar can paint from local storage instead of falling
-- back to the colour-and-letter placeholder while the network round-trip
-- runs. Nullable for legacy rows persisted before this column existed —
-- the next stale-while-revalidate refresh fills them in.
ALTER TABLE repo_meta ADD COLUMN owner_avatar_url TEXT;
