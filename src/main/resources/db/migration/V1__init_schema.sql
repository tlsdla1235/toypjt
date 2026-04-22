-- =====================================================================
-- V1 — 초기 스키마
-- 근거: ssafy-throne-war-final-spec-v5.0 + v5.0 ERD(DBML)
-- 정렬 원칙:
--   1) 명세(v5.0) 우선
--   2) 데이터 모델 충돌 시 v5.0 ERD/DBML의 변경 요약 반영
--      - users/events.deleted_at 추가
--      - current_throne.version 제거
--      - throne_claims.result 에서 LOCK_CONFLICT, NO_ACTIVE_EVENT 제거
-- =====================================================================

-- ---------------------------------------------------------------------
-- users : GitHub OAuth로 생성된 사용자 (+ SYSTEM seed)
-- SYSTEM은 별도 role이 아니라 id = 1 상수로 식별
-- ---------------------------------------------------------------------
CREATE TABLE users (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  github_id   BIGINT NOT NULL UNIQUE,
  login       VARCHAR(64) NOT NULL,
  avatar_url  VARCHAR(512),
  role        ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',
  blocked     BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_at  TIMESTAMP NULL,
  INDEX idx_users_login (login),
  INDEX idx_users_deleted_at (deleted_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- events : 이벤트 마스터
-- deleted_at은 @SoftDelete 용도
-- ---------------------------------------------------------------------
CREATE TABLE events (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  name         VARCHAR(128) NOT NULL,
  status       ENUM('DRAFT','RUNNING','ENDED','FINALIZED') NOT NULL,
  cooldown_ms  INT NOT NULL DEFAULT 3000,
  duration_h   INT NOT NULL DEFAULT 24,
  started_at   TIMESTAMP NULL,
  ends_at      TIMESTAMP NULL,
  ended_at     TIMESTAMP NULL,
  finalized_at TIMESTAMP NULL,
  created_by   BIGINT NOT NULL,
  created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_at   TIMESTAMP NULL,
  FOREIGN KEY (created_by) REFERENCES users(id),
  INDEX idx_events_status (status),
  INDEX idx_events_deleted_at (deleted_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- event_participants : 이벤트별 참가자 (집계 캐시 역할)
-- SYSTEM(user_id = 1) row는 애플리케이션 레이어에서 생성 금지
-- ---------------------------------------------------------------------
CREATE TABLE event_participants (
  event_id          BIGINT NOT NULL,
  user_id           BIGINT NOT NULL,
  total_hold_ms     BIGINT NOT NULL DEFAULT 0,
  claim_success_cnt INT NOT NULL DEFAULT 0,
  longest_reign_ms  BIGINT NOT NULL DEFAULT 0,
  joined_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (event_id, user_id),
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  INDEX idx_ep_event_total (event_id, total_hold_ms DESC)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- throne_reigns : 왕좌 보유 이력 (한 reign = 한 행)
-- Phase 1: AUTO_INCREMENT
-- Phase 3: 후속 마이그레이션에서 AUTO_INCREMENT 제거 예정
-- ---------------------------------------------------------------------
CREATE TABLE throne_reigns (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id    BIGINT NOT NULL,
  user_id     BIGINT NOT NULL,
  started_at  TIMESTAMP(3) NOT NULL,
  ended_at    TIMESTAMP(3) NULL,
  duration_ms BIGINT NULL,
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  INDEX idx_reign_event_user (event_id, user_id),
  INDEX idx_reign_event_ended (event_id, ended_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- current_throne : 현재 왕 (이벤트당 1행)
-- v5.0 ERD 기준으로 version 컬럼 제거
-- ---------------------------------------------------------------------
CREATE TABLE current_throne (
  event_id    BIGINT PRIMARY KEY,
  reign_id    BIGINT NOT NULL,
  user_id     BIGINT NOT NULL,
  held_since  TIMESTAMP(3) NOT NULL,
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (reign_id) REFERENCES throne_reigns(id),
  FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- throne_claims : 찬탈 시도 append-only 로그
-- FK는 의도적으로 걸지 않음 (고볼륨 append-only)
-- NO_ACTIVE_EVENT는 DB 기록 없이 Counter 메트릭으로만 관찰
-- ---------------------------------------------------------------------
CREATE TABLE throne_claims (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id     BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  result       ENUM('SUCCESS','COOLDOWN','ALREADY_OWNER','NOT_RUNNING') NOT NULL,
  requested_at TIMESTAMP(3) NOT NULL,
  INDEX idx_claims_event_time (event_id, requested_at),
  INDEX idx_claims_user (event_id, user_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- cooldowns : 유저별 현재 쿨타임
-- ---------------------------------------------------------------------
CREATE TABLE cooldowns (
  event_id    BIGINT NOT NULL,
  user_id     BIGINT NOT NULL,
  expires_at  TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (event_id, user_id),
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  INDEX idx_cd_expires (expires_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- leaderboard_snapshot : 5분 주기 리더보드 집계 결과
-- ---------------------------------------------------------------------
CREATE TABLE leaderboard_snapshot (
  event_id       BIGINT NOT NULL,
  rank_no        INT NOT NULL,
  user_id        BIGINT NOT NULL,
  total_hold_ms  BIGINT NOT NULL,
  captured_at    TIMESTAMP NOT NULL,
  PRIMARY KEY (event_id, rank_no, captured_at),
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  INDEX idx_lb_latest (event_id, captured_at DESC)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- event_awards : finalize 시 계산된 시상 결과
-- "수상자 없음"은 row 부재로 표현 (user_id NULL 사용 안 함)
-- ---------------------------------------------------------------------
CREATE TABLE event_awards (
  event_id    BIGINT NOT NULL,
  award_type  ENUM('LONGEST_REIGN','USURPER','LAST_KING') NOT NULL,
  user_id     BIGINT NOT NULL,
  metric_ms   BIGINT,
  metric_cnt  INT,
  PRIMARY KEY (event_id, award_type),
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
