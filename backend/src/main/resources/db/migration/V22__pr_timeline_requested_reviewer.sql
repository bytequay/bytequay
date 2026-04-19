-- Persist the login of the user being invited to review on
-- review_requested timeline events. The actor is the inviter; this column
-- is the invitee. Null on every other event type.
ALTER TABLE pr_timeline ADD COLUMN requested_reviewer TEXT;
