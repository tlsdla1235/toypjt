# Day 5 Task - Aggregation Cache & Leaderboard Snapshot

## Goal
- Day 5의 목표는 찬탈 핫패스에서 집계 쓰기를 떼어내는 것이다.
- 매 찬탈마다 `event_participants`를 직접 UPDATE하면 또 다른 락 핫스팟이 생기므로, 메모리 누적 + 주기 write-back 구조로 분리한다.
- 만료된 쿨타임 row가 1분 안에 정리되어 `findActive` 인덱스가 비대해지지 않아야 한다.
- RUNNING 이벤트가 있으면 5분 주기로 `leaderboard_snapshot`에 Top 100이 기록되어야 한다.
- 셧다운 시 메모리에 남은 누적분이 유실되지 않아야 한다.

## Scope For Today
- [x] `EventParticipant` 엔티티 + `EventParticipantId` 복합키 작성
- [x] `EventParticipantRepository` 작성 (`applyDeltaBatch`, `findTopByEventId`, `findMostClaims` 준비)
- [x] `LeaderboardSnapshot` 엔티티 + `LeaderboardSnapshotId` 복합키 작성
- [x] `LeaderboardSnapshotRepository` 작성 (`saveAllIgnore`)
- [x] `AggregationCache` 컴포넌트 작성 (`AggregationDelta` record + `pending` ConcurrentHashMap)
- [x] `AggregationCache.flush()` `@Scheduled(fixedDelay = 10_000)` 작성
- [x] `AggregationCache.finalFlush()` `@PreDestroy` 작성
- [x] `AggregationCache.forceFlush()` 동기 진입점 작성
- [x] `CooldownService.purgeExpired()` `@Scheduled(fixedDelay = 60_000)` 작성
- [x] `CooldownRepository.deleteExpired(threshold)` 메서드 추가
- [x] `LeaderboardService.snapshotLeaderboard()` `@Scheduled(cron = "0 */5 * * * *")` 작성
- [x] `ThroneService.doClaim()` afterCommit에 `addReignEnd` / `addClaimSuccess` 호출 추가
- [x] Day 6 `finalize` 대비: 마지막 왕의 열린 reign 종료분도 집계 캐시에 반영되도록 후속 TODO 명시

## What Should Be Implemented

### 1. EventParticipant 도메인
- [x] `EventParticipant` 엔티티 (`event_id`, `user_id` 복합키)
- [x] 필드: `totalHoldMs`, `claimSuccessCnt`, `longestReignMs`, `joinedAt`, `updatedAt`
- [x] `EventParticipantRepository.applyDeltaBatch(drained)`
  - flush 1회마다 여러 key를 반영하는 write-back 진입점으로 설계
  - 내부 upsert 규칙: `INSERT ... ON DUPLICATE KEY UPDATE total_hold_ms = total_hold_ms + VALUES(...), claim_success_cnt = claim_success_cnt + VALUES(...), longest_reign_ms = GREATEST(longest_reign_ms, VALUES(...))`
  - repo 레벨에서도 `user_id == 1L` 방어 필터 유지
- [x] `EventParticipantRepository.findTopByEventIdOrderByTotalHoldDesc(eventId, limit)`
  - `WHERE user_id <> 1` 조건
  - Pageable 또는 setMaxResults로 Top N
  - `ParticipantRow(userId, totalHoldMs, claimSuccessCnt, longestReignMs)` projection 반환
- [x] `EventParticipantRepository.findMostClaims(eventId)`
  - Day 6 `USURPER` 시상 계산용
  - `WHERE user_id <> 1 ORDER BY claim_success_cnt DESC, user_id ASC`

### 2. LeaderboardSnapshot 도메인
- [x] `LeaderboardSnapshot` 엔티티 (`event_id`, `rank_no`, `captured_at` 복합키)
- [x] 필드: `userId`, `totalHoldMs`
- [x] `LeaderboardSnapshotRepository.saveAllIgnore(rows)`
  - native bulk `INSERT IGNORE INTO leaderboard_snapshot ...`
  - 동일 5분 주기 중복 호출 시 PK 충돌 조용히 skip

### 3. AggregationCache
- [x] `AggregationDelta(long totalHoldMs, int claimSuccessCnt, long maxReignMs)` record + `merge()` + `ofHold(ms)` / `ofClaim()` 팩토리
- [x] `Key(long eventId, long userId)` record
- [x] `pending: ConcurrentHashMap<Key, AggregationDelta>`
- [x] `SYSTEM_USER_ID = 1L` 상수
- [x] `addReignEnd(eventId, prevUserId, durationMs)` — `prevUserId == 1L`이면 early return, 아니면 `pending.merge(..., AggregationDelta::merge)`
- [x] `addClaimSuccess(eventId, userId)` — 동일 패턴, SYSTEM 방어 필터 포함
- [x] `flush()` `@Scheduled(fixedDelay = 10_000)` — **반드시 `pending.remove(key)` 기반 원자 추출**, SYSTEM 이중 방어 필터, 빈 맵일 때 DB 호출 없이 return
- [x] `flush()`는 drained map 전체를 `applyDeltaBatch(...)`에 넘긴다 — key별 개별 DB 호출 루프를 기본안으로 삼지 않는다
- [x] `finalFlush()` `@PreDestroy` — `flush()` 위임
- [x] `forceFlush()` — `LeaderboardService`에서 동기 호출용, `flush()` 위임

### 4. CooldownService
- [x] `CooldownService.purgeExpired()` `@Scheduled(fixedDelay = 60_000)` + `@Transactional`
- [x] `cooldownRepository.deleteExpired(now.minusSeconds(10))` 호출
- [x] `CooldownRepository`에 `@Modifying @Query("delete from Cooldown c where c.expiresAt < :threshold") int deleteExpired(...)` 추가

### 5. LeaderboardService
- [x] 의존성: `EventService`(또는 `EventRepository`), `EventParticipantRepository`, `LeaderboardSnapshotRepository`, `AggregationCache`
- [x] `snapshotLeaderboard()` `@Scheduled(cron = "0 */5 * * * *")`
  1. RUNNING 이벤트 조회, 없으면 return
  2. `aggregationCache.forceFlush()` — 최신 누적치 반영
  3. `findTopByEventIdOrderByTotalHoldDesc(eventId, 100)`
  4. `truncateTo5Minutes(LocalDateTime.now())` — epoch-second mod 300으로 5분 경계 정규화
  5. `rank_no`를 1부터 채워 `saveAllIgnore`
  6. SSE 발행은 Day 6 — TODO 주석만 남기고 호출하지 않음

### 6. ThroneService 연동
- [x] `ThroneService` 생성자 주입 필드에 `AggregationCache` 추가
- [x] `doClaim()` 마지막 `runAfterCommit(...)` 블록에 두 줄 추가:
  ```
  aggregationCache.addReignEnd(eventId, currentState.currentKingId(), heldMs);
  aggregationCache.addClaimSuccess(eventId, userId);
  ```
- [x] 첫 찬탈(이전 왕 = SYSTEM)도 `addReignEnd`는 호출되지만 내부에서 early return으로 무시
- [x] Day 6 `finalize` 구현 시, 이벤트 종료 시점까지 왕좌를 들고 있던 마지막 유저의 열린 reign duration도 `addReignEnd`로 반영해야 함
  - 그렇지 않으면 마지막 왕의 마지막 보유 시간이 `event_participants.total_hold_ms` / `longest_reign_ms`에서 누락될 수 있음

## Required Domain / Infra Pieces

### Entities / repositories likely needed
- [x] `EventParticipant` 엔티티 + `EventParticipantId`
- [x] `EventParticipantRepository` (`applyDeltaBatch`, `findTopByEventIdOrderByTotalHoldDesc`, `findMostClaims`)
- [x] `LeaderboardSnapshot` 엔티티 + `LeaderboardSnapshotId`
- [x] `LeaderboardSnapshotRepository` (`saveAllIgnore`)
- [x] `CooldownRepository.deleteExpired(LocalDateTime threshold)` 추가

### Service support
- [x] `AggregationCache` 컴포넌트 (write-back 핵심)
- [x] `CooldownService` (1분 주기 만료 정리)
- [x] `LeaderboardService` (5분 주기 스냅샷)
- [x] `ThroneService` → `AggregationCache` 단방향 호출
- [x] `LeaderboardService` → `AggregationCache.forceFlush()` 단방향 호출

### Migration
- [x] 신규 Flyway 마이그레이션 **불필요** (V1__init_schema.sql에 `event_participants`, `leaderboard_snapshot` 모두 존재)

## Behavior Rules To Keep
- [x] SYSTEM(`user_id = 1`) row는 절대로 `event_participants` / `leaderboard_snapshot`에 들어가지 않는다 (4중 방어: `addReignEnd`, `addClaimSuccess`, `applyDelta`, `findTop...`의 `user_id <> 1`)
- [x] `flush()`는 반드시 `pending.remove(key)` 원자 추출 사용 — 단순 `clear()` 또는 iterate-after-snapshot 금지 (race로 신규 누적분 유실)
- [x] `flush()`는 빈 맵일 때 DB 호출 없이 return
- [x] write-back의 기본 단위는 "flush 1회당 drained map 전체"다 — 참가자별 개별 upsert 반복을 기본 구조로 두지 않는다
- [x] `purgeExpired()`는 `now - 10s` 기준 (시간 동기 오차 grace)
- [x] `snapshotLeaderboard()`는 RUNNING 이벤트 없으면 no-op
- [x] `snapshotLeaderboard()`의 `captured_at`은 5분 경계로 truncate (M11 — GC pause로 실행 시각이 흔들려도 같은 PK)
- [x] `INSERT IGNORE`로 중복 호출 시 조용히 skip (I5)
- [x] 찬탈 트랜잭션 롤백 시 `addReignEnd` / `addClaimSuccess` 호출 안 됨 (afterCommit 안에 있음)
- [x] 찬탈 핫패스(`doClaim`)는 DB 집계 UPDATE를 직접 하지 않는다
- [x] Day 6 finalize는 열린 마지막 reign을 닫은 뒤 그 duration을 집계 캐시에 반영해야 한다

## Verification Checklist For Today

### Build / compile
- [x] `./gradlew compileJava` 성공
- [x] `./gradlew test` 성공

### AggregationCache 단위 테스트
- [x] `addReignEnd(SYSTEM)`은 pending에 들어가지 않는다
- [x] 같은 키로 `addReignEnd`를 두 번 호출하면 `totalHoldMs`가 합산된다
- [x] `maxReignMs`는 `GREATEST` 의미로 누적된다 (작은 값 뒤 큰 값 → 큰 값 유지)
- [x] `flush()` 호출 후 pending이 비워지고 `applyDelta`가 키별로 호출된다
- [x] `flush()` 호출 중 `addReignEnd`가 들어와도 신규 분이 유실되지 않는다
- [x] `finalFlush()`가 `flush()`를 위임한다
- [x] pending이 비어있으면 `applyDelta`가 한 번도 호출되지 않는다
- [x] drained map이 한 번의 `applyDeltaBatch(...)` 호출로 전달된다

### CooldownService 검증
- [ ] `purgeExpired()` 호출 후 `expiresAt < now - 10s`인 row가 사라진다
- [ ] `expiresAt > now`인 row는 살아남는다

### LeaderboardService 단위 테스트
- [x] RUNNING 이벤트 없으면 `forceFlush` / `applyDelta` / `saveAllIgnore` 모두 호출되지 않는다
- [x] RUNNING 이벤트 있으면 `forceFlush()` → `findTop...` → `saveAllIgnore` 순서로 호출된다
- [x] `captured_at`이 5분 경계로 truncate된다 (`12:03:42` → `12:00:00`)
- [x] `rank_no`가 1부터 순차 채워진다

### ThroneService 통합 테스트 (기존 테스트 확장)
- [x] 찬탈 성공 + commit 시 `aggregationCache.addReignEnd(prevUserId, heldMs)`가 호출된다
- [x] 찬탈 성공 + commit 시 `aggregationCache.addClaimSuccess(newUserId)`가 호출된다
- [x] 첫 찬탈(이전 왕 = SYSTEM)이라도 `addReignEnd` 호출 자체는 정상 (내부에서 early return)
- [x] 트랜잭션 롤백 시 `addReignEnd` / `addClaimSuccess`가 호출되지 않는다 (afterCommit 보장)
  - 이 항목은 mock 기반 단위 테스트만으로는 부족할 수 있으므로 실제 transaction synchronization이 있는 통합 테스트로 검증
- [x] `COOLDOWN`, `ALREADY_OWNER`, `NOT_RUNNING` 실패 시 집계 호출 없음

### 시간/스케줄러 동작
- [ ] 빠르게 두 유저로 찬탈 반복 → 10초 이내 `event_participants`에 행이 생긴다
- [ ] `total_hold_ms`, `claim_success_cnt`, `longest_reign_ms`가 누적된다
- [ ] `cooldowns`에 `expires_at` 과거 row 수동 삽입 → 1분 안에 삭제된다
- [ ] RUNNING 이벤트 상태에서 5분 경계가 지나면 `leaderboard_snapshot`에 row가 기록된다
- [ ] 같은 5분 구간 강제 두 번 호출 → 두 번째는 PK 충돌로 IGNORE
- [ ] graceful shutdown(SIGTERM)으로 종료 → 마지막 누적분이 `event_participants`에 반영된다

### Data integrity
- [ ] `SELECT * FROM event_participants WHERE user_id = 1` 결과 0행
- [ ] `SELECT * FROM leaderboard_snapshot WHERE user_id = 1` 결과 0행
- [ ] `event_participants.total_hold_ms` ≈ Σ(`throne_reigns.duration_ms` WHERE `user_id <> 1`)
- [ ] 이벤트 종료 직전 마지막 왕의 마지막 보유 구간도 최종적으로 `event_participants`에 반영된다

## Human-Owned Responsibility
- [ ] Day 4 상태에서 RUNNING 이벤트 + 일반 사용자 2명 이상 준비
- [ ] 빠른 찬탈 반복(5~10회)으로 메모리 누적 동작을 직접 확인
- [ ] 10초 후 `SELECT * FROM event_participants` 직접 조회
- [ ] `cooldowns`에 만료된 row를 INSERT 한 뒤 1분 대기 → 사라지는지 확인
- [ ] 5분 경계에 맞춰 `SELECT * FROM leaderboard_snapshot ORDER BY captured_at DESC LIMIT 200` 확인
- [ ] graceful shutdown (`Ctrl+C`로 SIGTERM, `kill -9` 금지) → 재기동 후 직전 누적분 반영 확인
- [ ] `event_participants`의 SYSTEM row 부재를 직접 SQL로 검증
- [ ] 본인이 "왜 write-back인가, 왜 `pending.remove`인가, 왜 5분 truncate인가" 설명 가능

## Suggested Manual Test Flow
1. Day 4 상태에서 RUNNING 이벤트 1개 준비
2. 유저 A, B로 번갈아 `claim` 5~10회 호출
3. 10초 대기
4. `SELECT * FROM event_participants WHERE event_id = ?` — A, B 행 존재 + SYSTEM 부재 확인
5. `total_hold_ms`, `claim_success_cnt`, `longest_reign_ms` 값 확인
6. `INSERT INTO cooldowns (event_id, user_id, expires_at) VALUES (?, ?, NOW() - INTERVAL 1 MINUTE)` 수동 삽입
7. 1분 대기 → `SELECT * FROM cooldowns` 해당 row 삭제 확인
8. RUNNING 이벤트 유지하며 5분 경계 대기 (예: 12:00, 12:05, 12:10)
9. `SELECT * FROM leaderboard_snapshot ORDER BY captured_at DESC` — 새 batch 기록 확인, `rank_no` 1부터 순차
10. 앱 SIGTERM 종료 → 재기동 후 직전 누적분이 `event_participants`에 반영됐는지 확인

## Definition Of Done For Day 5
- [x] 코드가 빌드된다
- [x] `AggregationCache`가 10초 주기로 메모리 → DB write-back을 수행한다
- [x] `pending.remove` 기반 race-safe flush가 동작한다
- [ ] `@PreDestroy`로 셧다운 시 누적분이 보존된다
- [x] SYSTEM 유저가 `event_participants` / `leaderboard_snapshot`에 절대 들어가지 않는다 (4중 방어)
- [ ] 만료 쿨타임이 1분 안에 삭제된다
- [ ] 5분 주기 리더보드 스냅샷이 기록된다
- [ ] 동일 5분 구간 중복 호출이 PK 충돌로 조용히 skip된다
- [x] 찬탈 핫패스(`doClaim`)가 DB 집계 UPDATE를 직접 하지 않는다
- [ ] 본인이 흐름을 설명할 수 있다

## Notes
- Day 5의 핵심은 "찬탈 핫패스에서 집계 쓰기를 떼어내는 것"이다.
- 이 단계에서는 SSE 발행, 시상, summary, 보안 보강(rate limit, CSRF)은 모두 Day 6 범위이다.
- 감사 로그 배치 INSERT(개선 #2)는 Phase 2(Day 9) 범위이며, Day 5에서 `throne_claims`는 여전히 개별 INSERT.
- `LeaderboardService.snapshotLeaderboard()`의 spec 9.4 마지막 줄 `ssePublisher.publishLeaderboardSnapshot(...)`은 Day 6에서 채울 자리만 TODO 주석으로 남긴다.
- Day 6 finalize 구현 시에는 `closeOpenReign(eventId, endedAt)` 이후 마지막 왕의 duration을 집계 캐시에 반영하고 `forceFlush()`까지 이어지는 흐름을 반드시 연결한다.
- 신규 Flyway 마이그레이션 불필요 — V1에 `event_participants`, `leaderboard_snapshot` 테이블 모두 존재.

## Critical Files To Modify / Create

### Modify
- `src/main/java/com/sst/flaggame/domain/throne/service/ThroneService.java`
- `src/main/java/com/sst/flaggame/domain/throne/repository/CooldownRepository.java`
- `src/test/java/com/sst/flaggame/domain/throne/service/ThroneServiceTest.java`

### Create
- `src/main/java/com/sst/flaggame/domain/throne/entity/EventParticipant.java`
- `src/main/java/com/sst/flaggame/domain/throne/entity/EventParticipantId.java`
- `src/main/java/com/sst/flaggame/domain/throne/repository/EventParticipantRepository.java`
- `src/main/java/com/sst/flaggame/domain/throne/service/AggregationCache.java`
- `src/main/java/com/sst/flaggame/domain/throne/service/CooldownService.java`
- `src/main/java/com/sst/flaggame/domain/leaderboard/entity/LeaderboardSnapshot.java`
- `src/main/java/com/sst/flaggame/domain/leaderboard/entity/LeaderboardSnapshotId.java`
- `src/main/java/com/sst/flaggame/domain/leaderboard/repository/LeaderboardSnapshotRepository.java`
- `src/main/java/com/sst/flaggame/domain/leaderboard/service/LeaderboardService.java`
- `src/test/java/com/sst/flaggame/domain/throne/service/AggregationCacheTest.java`
- `src/test/java/com/sst/flaggame/domain/throne/service/CooldownServiceTest.java`
- `src/test/java/com/sst/flaggame/domain/leaderboard/service/LeaderboardServiceTest.java`
