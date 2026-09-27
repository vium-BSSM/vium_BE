LOCK TABLE users IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
  IF EXISTS (
    SELECT lower(btrim(email)) FROM users
    GROUP BY lower(btrim(email)) HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'Email normalization would create duplicates; resolve conflicting accounts before retrying V4';
  END IF;
END $$;

UPDATE users SET email = lower(btrim(email))
WHERE email <> lower(btrim(email));

ALTER TABLE users ADD CONSTRAINT chk_users_email_normalized
  CHECK (email = lower(btrim(email)));
