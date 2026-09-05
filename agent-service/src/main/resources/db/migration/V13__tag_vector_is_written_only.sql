-- A tag's vector may only ever come from text a person wrote.
--
-- V1 declared a second source: 'knn', the centroid of approved events carrying
-- the tag, to be switched over at roughly 20 approved events on the theory that
-- real usage describes a tag better than prose does. It was never implemented,
-- and it must not be — a facet's vector is built from value + span, and a span
-- is raw source text. Averaging spans would fold every author's phrasing into
-- the definition: 'sports' would drift toward whichever fixtures happened to be
-- ingested, toward "305 km distance" and "three-stage knockout qualifying",
-- until the tag no longer means what its reviewer wrote and no one could see
-- when it changed. A definition that moves on its own cannot be reviewed.
--
-- The value is removed from the CHECK rather than left unused, so the guarantee
-- is enforced by the database instead of resting on nobody getting around to
-- building it.
ALTER TABLE tag DROP CONSTRAINT IF EXISTS tag_vector_source_check;
ALTER TABLE tag ADD  CONSTRAINT tag_vector_source_check
      CHECK (vector_source IS NULL OR vector_source = 'description');
