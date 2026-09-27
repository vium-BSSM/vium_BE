-- Reject conflicting IDs rather than changing the meaning of existing references.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM units u
    JOIN (VALUES
      (1, 'ea'), (2, 'g'), (3, 'kg'), (4, 'ml'), (5, 'l'),
      (6, 'pack'), (7, 'bag'), (8, 'bottle'), (9, 'can'), (10, 'container')
    ) AS expected(id, code) ON u.id = expected.id OR u.code = expected.code
    WHERE u.id <> expected.id OR u.code <> expected.code
  ) THEN
    RAISE EXCEPTION 'Existing unit IDs conflict with the fixed unit list';
  END IF;
END $$;

INSERT INTO units (id, code, name) VALUES
  (1, 'ea', '개'),
  (2, 'g', 'g'),
  (3, 'kg', 'kg'),
  (4, 'ml', 'ml'),
  (5, 'l', 'L'),
  (6, 'pack', '팩'),
  (7, 'bag', '봉'),
  (8, 'bottle', '병'),
  (9, 'can', '캔'),
  (10, 'container', '통')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

SELECT setval(pg_get_serial_sequence('units', 'id'),
  GREATEST((SELECT MAX(id) FROM units),
    nextval(pg_get_serial_sequence('units', 'id'))));
