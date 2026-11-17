ALTER TABLE "trades" ADD COLUMN "auto_complete_notified_at" timestamp;
COMMENT ON COLUMN "trades"."auto_complete_notified_at" IS '자동 완료 24시간 전 안내를 보낸 시각. 중복 안내 방지';
