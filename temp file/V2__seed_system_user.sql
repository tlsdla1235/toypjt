-- =====================================================================
-- V2 — SYSTEM 사용자 seed
-- 근거: spec v5.0 §8.2 "초기 왕 정책"
--
-- 목적:
--   이벤트 /start 시 current_throne의 초기 점유자로 사용된다.
--   공석 상태(current_throne row 없음)를 원천 차단하기 위한 seed.
--
-- 식별 정책:
--   SYSTEM 유저는 **id=1 상수**로 식별한다. role이나 login으로 식별하지 않음.
--   이유: PK 상수가 가장 단순하고 변경 가능성이 낮다.
--
-- 필터링 지점 (SYSTEM_USER_ID = 1):
--   1. AggregationCache.addReignEnd/addClaimSuccess — SYSTEM은 집계 제외
--   2. participantRepo.applyDeltaBatch — flush 경로 방어 필터
--   3. 리더보드 조회 — WHERE user_id <> 1
--   4. AwardService — 3종 시상 모두 WHERE user_id <> 1
--
-- 충돌 가능성:
--   github_id = 0 은 실제 GitHub에 존재하지 않는 값이라 UNIQUE 충돌 없음.
--   (GitHub 최소 user id는 1, 여기서는 0을 SYSTEM 전용 sentinel로 사용)
-- =====================================================================

INSERT INTO users (id, github_id, login, avatar_url, role, blocked)
VALUES (1, 0, 'SYSTEM', NULL, 'ADMIN', FALSE);
