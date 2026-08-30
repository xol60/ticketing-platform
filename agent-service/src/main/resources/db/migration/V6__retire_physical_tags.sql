-- Retires seated and standing, added and withdrawn in the same review.
--
-- They were embedded and measured against all 21 physical facets. standing won
-- every one of them, including "grandstand setting" at 0.535 against seated's
-- 0.488 — and seated's own definition names grandstands. The margins were 0.002
-- to 0.05.
--
-- Antonyms appear in near-identical contexts and therefore sit near-identically
-- in the embedding space; the same model scores "not crowded" at 0.771 against
-- "crowded". Cosine cannot answer a question whose answers are opposites, so
-- the physical dim keeps its vectors and carries no tag until seating is a
-- structured field rather than a phrase.
--
-- TagSynchronizer is deliberately non-destructive, so removing the constants
-- only orphans the rows. This is the other half.
DELETE FROM event_tag WHERE tag_id IN (SELECT id FROM tag WHERE slug IN ('seated','standing'));
DELETE FROM tag WHERE slug IN ('seated', 'standing');
