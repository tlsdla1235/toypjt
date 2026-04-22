-- =====================================================================
-- V1 — 초기 스키마
-- 근거: spec v5.0 §8 데이터 모델
--
-- v1 대비 변경 요약:
--  - users.role ENUM에서 SYSTEM 제거. SYSTEM은 id=1 상수로 식별.
--  - users, events에 deleted_at 추가 (@SoftDelete)
--  - throne_claims.result ENUM에서 LOCK_CONFLICT, NO_ACTIVE_EVENT 제거
--     · LOCK_CONFLICT: ReentrantLock은 경합 실패를 노출하지 않음
--     · NO_ACTIVE_EVENT: DB 기록 없이 Counter 메트릭으로만 관찰
--  - current_throne.version 컬럼 제거 (낙관적 락 A3 실험은 19장으로 이관)
-- =====================================================================

-- ---------------------------------------------------------------------
-- users : GitHub OAuth로 생성된 사용자 (+ SYSTEM seed)
-- SYSTEM 유저(id=1)는 V2 마이그레이션에서 삽입되며 초기 reign 소유자로 사용된다.
-- 삭제 정책: @SoftDelete. FK 참조(throne_reigns, event_awards 등) 보존 필요.
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,             -- 내부 PK. SYSTEM은 id=1 상수
    github_id   BIGINT       NOT NULL UNIQUE,                        -- GitHub numeric id. SYSTEM은 0
    login       VARCHAR(64)  NOT NULL,                               -- GitHub login handle (표시용)
    avatar_url  VARCHAR(512)          DEFAULT NULL,                  -- 아바타 URL. 없으면 NULL
    role        ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',        -- 권한. ADMIN만 관리자 API 호출 가능
    blocked     BOOLEAN      NOT NULL DEFAULT FALSE,                 -- 관리자 수동 블록. TRUE면 찬탈 API 거부
    created_at  TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,     -- 가입 시각
    deleted_at  TIMESTAMP             DEFAULT NULL,                  -- @SoftDelete. null=활성, 값=비활성
    INDEX idx_users_login      (login),                              -- 로그인 핸들 검색
    INDEX idx_users_deleted_at (deleted_at)                          -- 활성 유저 필터링
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- events : 이벤트 마스터
-- 삭제 정책: @SoftDelete. 단, DRAFT 상태에서만 삭제 허용 (앱 레이어 정책).
-- FINALIZED 이벤트는 summary 영구 보존을 위해 삭제 금지.
-- RUNNING 1개 제약은 /start 시 앱 레이어에서 SELECT ... FOR UPDATE로 체크 (M12).
-- ---------------------------------------------------------------------
CREATE TABLE events (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,           -- 이벤트 PK
    name          VARCHAR(128) NOT NULL,                             -- 이벤트 이름 (UI 표시용)
    status        ENUM('DRAFT','RUNNING','ENDED','FINALIZED') NOT NULL,  -- 상태 머신 (단방향)
    cooldown_ms   INT          NOT NULL DEFAULT 3000,                -- 찬탈 쿨타임(ms). 기본 3초
    duration_h    INT          NOT NULL DEFAULT 24,                  -- 이벤트 길이(시간). 기본 24h
    started_at    TIMESTAMP             NULL,                        -- /start 호출 시각. DRAFT 상태에선 null
    ends_at       TIMESTAMP             NULL,                        -- 예정 종료 시각. 스케줄러가 이 시각에 자동 /end
    ended_at      TIMESTAMP             NULL,                        -- 실제 종료 시각
    finalized_at  TIMESTAMP             NULL,                        -- /finalize 호출 시각
    created_by    BIGINT       NOT NULL,                             -- 이벤트 생성자 (users.id, ADMIN)
    created_at    TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,   -- 생성 시각
    deleted_at    TIMESTAMP             DEFAULT NULL,                -- @SoftDelete. DRAFT 상태에서만 삭제 허용
    FOREIGN KEY (created_by) REFERENCES users(id),
    INDEX idx_events_status     (status),                            -- RUNNING 이벤트 조회용
    INDEX idx_events_deleted_at (deleted_at)                         -- 활성 이벤트 필터링
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- event_participants : 이벤트별 참가자 (집계 캐시 역할)
-- Caffeine write-back이 10초마다 UPDATE.
-- SYSTEM(user_id=1) row는 절대 생성되지 않음 (AggregationCache에서 필터링).
-- soft delete 불필요: 이벤트 종료 후에도 통계 보존, 지울 일 없음.
-- ---------------------------------------------------------------------
CREATE TABLE event_participants (
    event_id          BIGINT NOT NULL,                               -- 이벤트 FK
    user_id           BIGINT NOT NULL,                               -- 참가자 FK (SYSTEM 제외)
    total_hold_ms     BIGINT NOT NULL DEFAULT 0,                     -- 누적 왕좌 보유 시간 (ms)
    claim_success_cnt INT    NOT NULL DEFAULT 0,                     -- 찬탈 성공 횟수
    longest_reign_ms  BIGINT NOT NULL DEFAULT 0,                     -- 단일 reign 최장 기록 (시상용)
    joined_at         TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,     -- 첫 집계 반영 시각
    updated_at        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,  -- 마지막 flush 시각
    PRIMARY KEY (event_id, user_id),
    FOREIGN KEY (event_id) REFERENCES events(id),
    FOREIGN KEY (user_id)  REFERENCES users(id),
    -- 리더보드 Top N 커버링 인덱스 (spec §8.3)
    INDEX idx_ep_event_total (event_id, total_hold_ms DESC)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- throne_reigns : 왕좌 보유 이력 (한 reign = 한 행, append-only)
-- 이벤트 /start 시 SYSTEM 유저로 초기 reign 1건 자동 생성.
-- Phase 3에서는 id의 AUTO_INCREMENT가 비활성화되고 Redis 채번값 사용.
-- soft delete 불필요: 히스토리 영구 보존.
-- ---------------------------------------------------------------------
CREATE TABLE throne_reigns (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,             -- reign PK. Phase 3에서 Redis 값 직접 INSERT
    event_id    BIGINT       NOT NULL,                               -- 이벤트 FK
    user_id     BIGINT       NOT NULL,                               -- 왕좌 보유자 (초기 reign은 SYSTEM)
    started_at  TIMESTAMP(3) NOT NULL,                               -- reign 시작 시각 (ms 정밀도)
    ended_at    TIMESTAMP(3)          NULL,                          -- 종료 시각. null=현재 진행 중
    duration_ms BIGINT                NULL,                          -- ended_at - started_at (closeReign 시 계산)
    FOREIGN KEY (event_id) REFERENCES events(id),
    FOREIGN KEY (user_id)  REFERENCES users(id),
    INDEX idx_reign_event_user  (event_id, user_id),                 -- 유저별 reign 조회
    -- "마지막 왕" 조회 + 진행 중 reign 탐색 최적화 (spec §8.3)
    INDEX idx_reign_event_ended (event_id, ended_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- current_throne : 현재 왕 (이벤트당 1행) — 동시성 핫스팟
-- Phase 1: ReentrantLock으로 보호된 트랜잭션에서 UPDATE
-- Phase 3: Redis Lua로 이전. 이 테이블은 Consumer가 UPDATE하는 영속 복사본
-- 이벤트 /start 시 SYSTEM seed reign으로 초기화 (공석 상태 없음).
-- soft delete 불필요: 이벤트 종료 시 finalize에서 정리되는 현재 상태.
-- version 컬럼 제거됨: 낙관적 락(A3) 실험은 19장으로 이관, 필요 시 별도 마이그레이션.
-- ---------------------------------------------------------------------
CREATE TABLE current_throne (
    event_id   BIGINT       PRIMARY KEY,                             -- 이벤트 FK (이벤트당 1행)
    reign_id   BIGINT       NOT NULL,                                -- 현재 활성 reign 참조
    user_id    BIGINT       NOT NULL,                                -- 현재 왕의 user_id
    held_since TIMESTAMP(3) NOT NULL,                                -- 현재 왕이 보유 시작한 시각
    FOREIGN KEY (event_id) REFERENCES events(id),
    FOREIGN KEY (reign_id) REFERENCES throne_reigns(id),
    FOREIGN KEY (user_id)  REFERENCES users(id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- throne_claims : 찬탈 시도 append-only 로그
-- Phase 1: 매 요청 단건 INSERT / Phase 2 개선 #2: 500건/500ms 배치
-- FK를 의도적으로 걸지 않음 (고볼륨 append-only, 처리량 상한 회피).
-- NO_ACTIVE_EVENT는 이 테이블에 기록하지 않음 — Counter 메트릭만.
-- soft delete 불필요: append-only 감사 로그.
-- ---------------------------------------------------------------------
CREATE TABLE throne_claims (
    id           BIGINT       PRIMARY KEY AUTO_INCREMENT,            -- 로그 PK
    event_id     BIGINT       NOT NULL,                              -- 이벤트 id (FK 미설정)
    user_id      BIGINT       NOT NULL,                              -- 찬탈 시도 유저 id (FK 미설정)
    result       ENUM('SUCCESS','COOLDOWN','ALREADY_OWNER','NOT_RUNNING') NOT NULL,  -- 시도 결과 4종
    requested_at TIMESTAMP(3) NOT NULL,                              -- 요청 수신 시각
    -- 시간대별 집계 (hourly 통계, spec §8.3)
    INDEX idx_claims_event_time (event_id, requested_at),
    INDEX idx_claims_user       (event_id, user_id)                  -- 유저별 시도 히스토리
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- cooldowns : 유저별 현재 쿨타임
-- PRIMARY KEY (event_id, user_id) 기반 upsert로 관리.
-- 1분 주기 스케줄러가 expires_at < NOW() 인 행을 hard delete.
-- soft delete 불필요: TTL 성격의 데이터.
-- ---------------------------------------------------------------------
CREATE TABLE cooldowns (
    event_id   BIGINT       NOT NULL,                                -- 이벤트 FK
    user_id    BIGINT       NOT NULL,                                -- 쿨타임 중인 유저 FK
    expires_at TIMESTAMP(3) NOT NULL,                                -- 만료 시각. 과거면 찬탈 허용
    PRIMARY KEY (event_id, user_id),
    -- 만료 정리 스케줄러용 (spec §8.3)
    INDEX idx_cd_expires (expires_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- leaderboard_snapshot : 5분 주기 리더보드 집계 결과
-- captured_at은 5분 경계로 truncate되어 PK 충돌 방어 (INSERT IGNORE 조합).
-- Phase 3에서는 Redis ZSET이 실시간 리더보드를 담당하여 이 테이블은 과거 조회용.
-- soft delete 불필요: append-only.
-- ---------------------------------------------------------------------
CREATE TABLE leaderboard_snapshot (
    event_id      BIGINT    NOT NULL,                                -- 이벤트 FK
    rank_no       INT       NOT NULL,                                -- 순위 (1부터). Top 100
    user_id       BIGINT    NOT NULL,                                -- 해당 순위 유저 FK
    total_hold_ms BIGINT    NOT NULL,                                -- 스냅샷 시점 누적 보유 시간
    captured_at   TIMESTAMP NOT NULL,                                -- 스냅샷 시각 (5분 경계 truncate)
    PRIMARY KEY (event_id, rank_no, captured_at),
    -- 최신 스냅샷 조회 최적화
    INDEX idx_lb_latest (event_id, captured_at DESC)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- event_awards : finalize 시 계산된 시상 결과
-- 이벤트당 최대 3개 row (LONGEST_REIGN / USURPER / LAST_KING).
-- "수상자 없음"은 row 부재로 표현 (user_id NOT NULL 유지).
-- soft delete 불필요: finalize 결과물, 영구 보존.
-- ---------------------------------------------------------------------
CREATE TABLE event_awards (
    event_id   BIGINT     NOT NULL,                                  -- 이벤트 FK
    award_type ENUM('LONGEST_REIGN','USURPER','LAST_KING') NOT NULL, -- 시상 종류
    user_id    BIGINT     NOT NULL,                                  -- 수상자 (SYSTEM 제외 필터 적용됨)
    metric_ms  BIGINT                DEFAULT NULL,                   -- LONGEST_REIGN / LAST_KING 보유 시간
    metric_cnt INT                   DEFAULT NULL,                   -- USURPER 찬탈 성공 횟수
    PRIMARY KEY (event_id, award_type)
) ENGINE=InnoDB;
