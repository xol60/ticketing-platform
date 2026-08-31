-- Retires the six tags no event was ever found to carry.
--
-- They came from the seed, not from review. All fifteen original tags were
-- written in one commit fifteen hours before the first event was ingested, so
-- each was a guess at what the catalogue might hold; these six guessed wrong.
-- The two tags that came through review instead — professional and broadcast —
-- are carried by 5 and 7 events, because that flow starts from a facet nothing
-- covers and so cannot produce an empty tag.
--
-- Empty is not harmless. Over 92 events these six entered 173 candidate
-- shortlists, took rank one ten times with every one of those wrong, and were
-- approved zero times. At query time 'workshop' matched "somewhere I can learn
-- something" at 0.594 — a correct reading — and, carried by nothing, returned
-- no events and erased that request's only signal.
--
-- Deleting the event_tag rows deletes rejections, not decisions worth keeping:
-- every row here is a rejection, since none was ever approved.
DELETE FROM facet_tag_candidate WHERE tag_id IN (
    SELECT id FROM tag WHERE slug IN
        ('comedy','workshop','exhibition','food-drink','festival-outdoor','intimate'));
DELETE FROM event_tag WHERE tag_id IN (
    SELECT id FROM tag WHERE slug IN
        ('comedy','workshop','exhibition','food-drink','festival-outdoor','intimate'));
DELETE FROM tag WHERE slug IN
    ('comedy','workshop','exhibition','food-drink','festival-outdoor','intimate');

-- Shortlists were built against the old vocabulary, so ranks 2 and 3 are now
-- wrong wherever a removed tag occupied one. Clearing them lets
-- FacetCandidateBackfill rebuild every list on the next boot.
DELETE FROM facet_tag_candidate;
