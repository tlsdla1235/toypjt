-- =====================================================================
-- V2 — SYSTEM 사용자 seed
-- 근거: spec v4.3.1 §8.2 "초기 왕 정책"
-- 이벤트 시작 시 current_throne의 초기 점유자로 사용된다.
-- github_id = 0 은 실제 GitHub에 존재하지 않는 값이라 충돌 없음.
-- =====================================================================

INSERT INTO users (id, github_id, login, avatar_url, role, blocked)
VALUES (1, 0, 'SYSTEM', NULL, 'ADMIN', FALSE);