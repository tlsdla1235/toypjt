-- =====================================================================
-- V1 — 초기 스키마
-- 근거: spec v4.3.1 §8
-- =====================================================================

-- ---------------------------------------------------------------------
-- users : GitHub OAuth로 생성된 사용자 (+ SYSTEM seed)
-- ---------------------------------------------------------------------
CREATE TABLE users (
                       id          BIGINT PRIMARY KEY AUTO_INCREMENT,
                       github_id   BIGINT NOT NULL UNIQUE,
                       login       VARCHAR(64) NOT NULL,
                       avatar_url  VARCHAR(512),
                       role        ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',
                       blocked     BOOLEAN NOT NULL DEFAULT FALSE,
                       created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       INDEX idx_users_login (login)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- events : 이벤트 마스터
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
                        FOREIGN KEY (created_by) REFERENCES users(id),
                        INDEX idx_events_status (status)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- event_participants : 이벤트별 참가자 (집계 캐시 역할)
-- ---------------------------------------------------------------------
CREATE TABLE event_participants (
                                    event_id          BIGINT NOT NULL,
                                    user_id           BIGINT NOT NULL,
                                    total_hold_ms     BIGINT NOT NULL DEFAULT 0,
                                    claim_success_cnt INT    NOT NULL DEFAULT 0,
                                    longest_reign_ms  BIGINT NOT NULL DEFAULT 0,
                                    joined_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                    PRIMARY KEY (event_id, user_id),
                                    FOREIGN KEY (event_id) REFERENCES events(id),
                                    FOREIGN KEY (user_id)  REFERENCES users(id),
    -- 리더보드 Top N 커버링 인덱스 (spec §8.3)
                                    INDEX idx_ep_event_total (event_id, total_hold_ms DESC)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- throne_reigns : 왕좌 보유 이력 (한 reign = 한 행)
-- ---------------------------------------------------------------------
CREATE TABLE throne_reigns (
                               id          BIGINT PRIMARY KEY AUTO_INCREMENT,
                               event_id    BIGINT NOT NULL,
                               user_id     BIGINT NOT NULL,
                               started_at  TIMESTAMP(3) NOT NULL,
                               ended_at    TIMESTAMP(3) NULL,
                               duration_ms BIGINT NULL,
                               FOREIGN KEY (event_id) REFERENCES events(id),
                               FOREIGN KEY (user_id)  REFERENCES users(id),
                               INDEX idx_reign_event_user  (event_id, user_id),
    -- "마지막 왕" 조회 최적화 (spec §8.3)
                               INDEX idx_reign_event_ended (event_id, ended_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- current_throne : 현재 왕 (이벤트당 1행) — 동시성 핫스팟
-- ---------------------------------------------------------------------
CREATE TABLE current_throne (
                                event_id   BIGINT PRIMARY KEY,
                                reign_id   BIGINT NOT NULL,
                                user_id    BIGINT NOT NULL,
                                held_since TIMESTAMP(3) NOT NULL,
                                version    BIGINT NOT NULL DEFAULT 0,
                                FOREIGN KEY (event_id) REFERENCES events(id),
                                FOREIGN KEY (reign_id) REFERENCES throne_reigns(id),
                                FOREIGN KEY (user_id)  REFERENCES users(id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- throne_claims : 찬탈 시도 append-only 로그
-- ---------------------------------------------------------------------
CREATE TABLE throne_claims (
                               id           BIGINT PRIMARY KEY AUTO_INCREMENT,
                               event_id     BIGINT NOT NULL,
                               user_id      BIGINT NOT NULL,
                               result       ENUM('SUCCESS','COOLDOWN','ALREADY_OWNER',
                      'LOCK_CONFLICT','NO_ACTIVE_EVENT','NOT_RUNNING') NOT NULL,
                               requested_at TIMESTAMP(3) NOT NULL,
    -- 시간대별 집계 (spec §8.3)
                               INDEX idx_claims_event_time (event_id, requested_at),
                               INDEX idx_claims_user       (event_id, user_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- cooldowns : 유저별 현재 쿨타임
-- ---------------------------------------------------------------------
CREATE TABLE cooldowns (
                           event_id   BIGINT NOT NULL,
                           user_id    BIGINT NOT NULL,
                           expires_at TIMESTAMP(3) NOT NULL,
                           PRIMARY KEY (event_id, user_id),
    -- 만료 정리 스케줄러용 (spec §8.3)
                           INDEX idx_cd_expires (expires_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- leaderboard_snapshot : 5분 주기 리더보드 집계 결과
-- ---------------------------------------------------------------------
CREATE TABLE leaderboard_snapshot (
                                      event_id      BIGINT NOT NULL,
                                      rank_no       INT NOT NULL,
                                      user_id       BIGINT NOT NULL,
                                      total_hold_ms BIGINT NOT NULL,
                                      captured_at   TIMESTAMP NOT NULL,
                                      PRIMARY KEY (event_id, rank_no, captured_at),
                                      INDEX idx_lb_latest (event_id, captured_at DESC)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- event_awards : finalize 시 계산된 시상 결과
-- ---------------------------------------------------------------------
CREATE TABLE event_awards (
                              event_id   BIGINT NOT NULL,
                              award_type ENUM('LONGEST_REIGN','USURPER','LAST_KING') NOT NULL,
                              user_id    BIGINT NOT NULL,
                              metric_ms  BIGINT,
                              metric_cnt INT,
                              PRIMARY KEY (event_id, award_type)
) ENGINE=InnoDB;