INSERT INTO "users" ("email", "display_name", "password_hash", "created_at", "updated_at")
VALUES ('dev@example.com', '개발용 테스트 계정', NULL, now(), now())
ON CONFLICT ("email") DO NOTHING;
