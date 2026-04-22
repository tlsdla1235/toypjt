-- =====================================================================
-- V2 — SYSTEM 사용자 seed
-- 근거: ssafy-throne-war-final-spec-v5.0 / v5.0 ERD(DBML)
-- SYSTEM은 별도 role enum 값이 아니라 id = 1 상수로 식별한다.
-- github_id = 0 은 실제 GitHub에 존재하지 않는 값이라 충돌하지 않는다.
-- =====================================================================

INSERT INTO users (id, github_id, login, avatar_url, role, blocked)
VALUES (1, 0, 'SYSTEM', NULL, 'ADMIN', FALSE);
