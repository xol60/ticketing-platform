-- The taxonomy seed is gone from Java.
--
-- Taxonomy.TAGS used to be pushed into this table on every boot, which made the
-- Java list the definition and the row its shadow. That is now reversed: the
-- row is the definition, and the only writer is a reviewer going through the
-- curation API. Six of the original fifteen tags never matched anything and
-- were retired in V9; two more, seated and standing, were antonyms cosine could
-- not separate and went in V6. What is left is vocabulary the corpus was
-- observed to need, which is exactly what a reviewer would have written.
--
-- The rows are relabelled, not deleted. 130 approved and 186 rejected event_tag
-- rows reference them, event_tag has no ON DELETE CASCADE, and those verdicts
-- are the most expensive data in the schema — every one of them is a person's
-- judgement that cannot be recomputed. Dropping the four format tags alone
-- would take 43 of 56 shows out of the tag path.
UPDATE tag SET source = 'human', updated_at = now() WHERE source = 'taxonomy';

-- With no seeder there is no second source, so the column may as well state the
-- invariant instead of describing a distinction that no longer exists.
ALTER TABLE tag DROP CONSTRAINT tag_source_check;
ALTER TABLE tag ADD  CONSTRAINT tag_source_check CHECK (source = 'human');
