-- Personas are gone: a review panel seat's voice is now a Skill row with
-- usage='review' (its name is the @mention identity). The reviewer_personas
-- table and its seat path are removed; nothing references it anymore.
DROP TABLE IF EXISTS reviewer_personas;
