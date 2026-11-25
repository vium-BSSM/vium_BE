-- 장보기 리마인더를 사용자당 발송일 하루 한 건만 만들기 위한 중복 방지 인덱스.
-- 리마인더 행의 scheduled_at은 발송일 00:00으로 고정한다.
CREATE UNIQUE INDEX ON "notifications" ("user_id", "scheduled_at") WHERE "type" = 'shopping_reminder';
