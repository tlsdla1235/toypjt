# Day 6 Task - SSE (동기) + Finalize & Awards + Security Hardening

## Goal
- Day 6의 목표는 MVP를 "운영 가능한 시연 가능 상태"로 만드는 것이다.
- 찬탈/리더보드/이벤트 상태 변화가 클라이언트에 SSE로 실시간 전달돼야 한다 (단일 인스턴스, 동기 발행 — Phase 2 baseline 측정용).
- finalize 시 3종 시상이 정확히 계산되고 `event_awards`에 영속화되며, summary API가 finalize 결과를 응답해야 한다.
- 마지막 왕의 열린 reign duration이 집계 캐시까지 흘러가 누락되지 않아야 한다 (Day 5에서 남긴 후속 TODO 처리).
- 부팅 중 / 미인증 / 과도 호출 / CSRF 공격 같은 운영 경계 케이스가 503/401/429/403으로 정확히 reject돼야 한다.

## Scope For Today
- [ ] `SsePublisher` 컴포넌트 작성 (`Map<Long, Set<SseEmitter>>` 기반, 유저당 다중 탭 허용 — M4)
- [ ] `SseController` 작성 — `GET /api/events/active/stream` (JWT handshake, 미인증 401)
- [ ] `SsePublisher.publishThroneChanged()` 동기 발행 (`afterCommit`에서 호출)
- [ ] `SsePublisher.publishLeaderboardSnapshot()` 동기 발행 (5분 스냅샷 직후)
- [ ] `SsePublisher.publishEventState()` 동기 발행 (start/end/finalize)
- [ ] `ThroneService.doClaim()` afterCommit에 `ssePublisher.publishThroneChanged(...)` 추가
- [ ] `LeaderboardService.snapshotLeaderboard()`의 Day 5 TODO 위치에 `ssePublisher.publishLeaderboardSnapshot(...)` 채우기
- [ ] `EventService.start/end/finalize` 커밋 이후 `ssePublisher.publishEventState(...)` 호출
- [ ] `AwardService` 작성 — 3종 시상 계산 (LONGEST_REIGN, USURPER, LAST_KING)
- [ ] `EventAward` 엔티티 + `EventAwardId` 복합키 작성
- [ ] `EventAwardRepository` 작성 (`findByEventIdAndAwardType` Optional 반환)
- [ ] `ThroneReignRepository`에 `findLongestReign(eventId)`, `findLastKingUserId(eventId)`, `closeOpenReign(eventId, endedAt)` 추가
- [ ] `EventSummary` DTO + `buildSummary(eventId)` 작성
- [ ] `EventService.finalizeEvent()` 흐름을 명세 15.2 순서대로 재정렬 (lastKing 조회 → closeOpenReign → 마지막 왕 duration을 `AggregationCache.addReignEnd` → `forceFlush` → 시상 계산 → award row 저장 → summary build → throneMap 정리)
- [ ] `EventController`에 `GET /api/events/{id}/summary` 작성 (FINALIZED 또는 ENDED 상태 허용 정책 결정)
- [ ] `ApiReadinessFilter` 작성 (I2) — `ApplicationReadyEvent` 이전 `/api/admin/events/**`, `/api/events/active/claim` POST 요청 503 reject
- [ ] `IpRateLimitFilter` 작성 (M3, Bucket4j) — `/api/events/active/claim` 10 req/s
- [ ] `build.gradle`에 Bucket4j 의존성 추가
- [ ] `SecurityConfig`에 CSRF 토큰 활성화 (M9) — `CookieCsrfTokenRepository.withHttpOnlyFalse()`, `XSRF-TOKEN` 쿠키
- [ ] `SecurityConfig`에 SSE 경로(`/api/events/active/stream`) + OAuth2 경로 CSRF 제외
- [ ] JWT 쿠키 발급 경로에 `HttpOnly; Secure; SameSite=Lax; Path=/` 적용 (M9)
- [ ] **Day 6 인증 모델: access token 쿠키 추가 + Authorization 헤더 유지 (hybrid)** — 헤더 경로는 부하 테스트/CLI 도구 호환을 위해 그대로 둠
- [ ] `JwtFilter`는 **`Authorization` 헤더 우선, 헤더 부재 시 `ACCESS_TOKEN` 쿠키 fallback** — 둘 다 없으면 401
- [ ] **refresh token 쿠키와 `/api/auth/refresh`는 Day 6 범위 제외 — Day 7+로 미룸** (Day 6은 access token 쿠키까지만)
- [ ] M13 라이프사이클 검증 — `/end`에서 `lockMap` 제거 / finalize에서 `throneMap` 제거가 실제로 일어나는지 재확인 (Day 4에서 추가됨, 명세 9.1-bis 기준 검증만)

## What Should Be Implemented

### 1. SSE 퍼블리셔 (MVP — 동기, 9.5)
- [ ] `SsePublisher` 컴포넌트
  - `private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();`
  - `subscribe(long userId)` → `SseEmitter(Duration.ofMinutes(30).toMillis())` + `CopyOnWriteArraySet`에 add
  - `onCompletion / onTimeout / onError` 콜백에서 `removeEmitter(userId, em)` 호출, set이 비면 `emitters.remove(userId)`로 메모리 회수
  - `safeSend(em, eventName, payload)` — `IOException` 발생 시 emitter 제거
  - 발행 메서드 3종: `publishThroneChanged`, `publishLeaderboardSnapshot`, `publishEventState`
  - **반드시 동기 발행**: `emitters.forEach(...)` 단일 스레드. 비동기화는 Phase 2 개선 #3 — 지금 비동기로 만들면 baseline 측정 서사가 깨짐.
- [ ] `SseController`
  - `GET /api/events/active/stream` — `@AuthenticationPrincipal AppUserPrincipal` 강제, 미인증은 시큐리티 체인에서 401
  - `produces = MediaType.TEXT_EVENT_STREAM_VALUE`
  - `ssePublisher.subscribe(userId)` 결과 반환

### 2. 발행 시점 통합
- [ ] `ThroneService.doClaim()` `runAfterCommit` 마지막에 추가:
  ```
  ssePublisher.publishThroneChanged(eventId, userId, newReignId, now);
  ```
  - afterCommit이라 롤백 시 발행 안 됨 (보장)
  - 락 보유 중이라 SSE I/O가 락 보유 시간에 포함됨 — Phase 2 개선 #3에서 비동기로 전환할 baseline
- [ ] `LeaderboardService.snapshotLeaderboard()` 마지막 줄(Day 5 TODO 자리)에 추가:
  ```
  ssePublisher.publishLeaderboardSnapshot(running.getId(), capturedAt);
  ```
  - 같은 5분 구간 두 번째 호출은 `INSERT IGNORE`로 row가 안 생기므로, 그 경우 publish도 skip (snapshotRepo가 영향 row 수 0이면 publish 생략)
- [ ] `EventService.start/end/finalize`
  - 각 트랜잭션 commit 이후 `publishEventState(eventId, EventStatus)` 호출
  - 트랜잭션 outer에서 호출(현재 구조상 `@Transactional` 메서드 종료 후 일반 메서드에서 직접 호출하는 패턴 권장)
  - 또는 `TransactionSynchronizationManager.registerSynchronization(...).afterCommit`으로 통일

### 3. 시상 계산 (15.1, 15.2)
- [ ] `EventAward` 엔티티
  - 복합키 `(event_id, award_type)` — `EventAwardId`
  - 필드: `userId BIGINT NOT NULL`, `metricMs BIGINT NULL`, `metricCnt INT NULL`
  - V1 마이그레이션에 이미 테이블 존재 — Flyway 추가 마이그레이션 불필요
  - enum `AwardType { LONGEST_REIGN, USURPER, LAST_KING }`
- [ ] `EventAwardRepository`
  - `Optional<EventAward> findByEventIdAndAwardType(Long eventId, AwardType type)` (B7 — 부재로 "수상자 없음" 판정)
- [ ] `ThroneReignRepository` 확장
  - `Optional<ReignRow> findLongestReign(Long eventId)` — `WHERE event_id = ? AND user_id <> 1 ORDER BY duration_ms DESC, started_at ASC LIMIT 1`
  - `Long findLastKingUserId(Long eventId)` — `WHERE event_id = ? AND user_id <> 1 ORDER BY started_at DESC LIMIT 1` (없으면 null)
  - `int closeOpenReign(Long eventId, LocalDateTime endedAt)` — `UPDATE throne_reigns SET ended_at = :endedAt, duration_ms = TIMESTAMPDIFF(...) WHERE event_id = ? AND ended_at IS NULL`
    - row count 반환, 1 또는 0이어야 함 (I1 invariant)
  - 추가로 lastKing이 close 직전 들고 있던 reign의 `duration_ms`를 알 수 있게 `findOpenReign(eventId)` 단건 조회 (Optional) — 마지막 왕 duration을 집계 캐시에 반영하기 위함
- [ ] `AwardService`
  - 의존성: `EventAwardRepository`, `ThroneReignRepository`, `EventParticipantRepository`
  - `compute(Long eventId, Long lastKingUserId)` — Optional 3종을 받아 row 생성. 결과 부재면 row 생성 안 함 (B7).
  - 모든 시상 쿼리에서 `user_id <> 1` 필터 보장 (4중 방어와 동일한 SYSTEM 방어 원칙)

### 4. finalize 흐름 (15.2)
- [ ] `EventService.finalizeEvent(Long eventId)` 재구성 — 명세 15.2 순서 엄수:
  1. `Event` 조회, 상태 검증 (`ENDED` → `FINALIZED`만 허용)
  2. `lastKingUserId = reignRepo.findLastKingUserId(eventId)` (close 이전에 확보 — 관측 시점 고정)
  3. `Optional<ReignRow> openReign = reignRepo.findOpenReign(eventId)` 후 `closeOpenReign(eventId, event.getEndedAt())`
  4. **마지막 왕 duration을 집계 캐시에 반영** — Day 5에서 남긴 후속 TODO:
     ```
     openReign.ifPresent(r -> aggregationCache.addReignEnd(eventId, r.userId(), r.durationMs()));
     ```
     - 이걸 빼먹으면 마지막 왕의 마지막 보유 시간이 `event_participants.total_hold_ms` / `longest_reign_ms`에서 누락됨
  5. `aggregationCache.forceFlush()` — 집계 캐시 → DB 동기 반영
  6. `awardService.compute(eventId, lastKingUserId)` — 3종 시상 row 저장
  7. `event.finalize_(now)` — 상태 전이
  8. `EventSummary summary = buildSummary(eventId)` 후 반환 (DTO만 생성, DB write 추가로 없음)
  9. `throneService.finalizeEvent(eventId)` — `throneMap.remove(eventId)` (M13 — `/end`에서 이미 제거된 lockMap은 방어적 noop)
  10. `EventService.clearActiveEventIfMatch(eventId)` — `AtomicReference` CAS (`/end` 시점에 이미 null이지만 방어적으로 한 번 더)
  11. afterCommit으로 `ssePublisher.publishEventState(eventId, FINALIZED)` 호출

### 5. Summary API (FR-08, 16.2)
- [ ] `EventSummary` DTO
  - `eventId`, `meta(name, startedAt, endedAt)`, `participation(participants, claimers)`, `claimStats(total, success, cooldown, alreadyOwner)`, `holdDistribution`, `hourly`, `awards(longestReign, usurper, lastKing)`
  - 시상 row 부재 시 해당 필드 `null` (B7)
- [ ] `EventController.summary(Long eventId)`
  - `GET /api/events/{id}/summary`
  - 권한: 인증된 JWT (FR-08-1 — 과거 이벤트 조회 가능, 누구든 인증되면 OK)
  - 상태: FINALIZED 우선 — ENDED는 시상 계산 전이므로 awards가 비어있게 응답할지, 404로 막을지 결정 필요. **명세 FR-02-6 "FINALIZED는 조회만 가능"** 기조상 FINALIZED만 허용 권장.
- [ ] `EventSummaryService`(또는 `AwardService`에 합치기) — `buildSummary(eventId)` 구현
  - `claimStats`는 `throne_claims`에서 `result`별 COUNT
  - `holdDistribution`은 `throne_reigns.duration_ms`를 5구간으로 그룹핑 (`0-1s/1-5s/5-30s/30s-5m/5m+`)
  - `hourly`는 `throne_claims`를 시간대별로 그룹핑 (성공 + transfers 수)
  - `awards`는 `event_awards` row 부재 시 null

### 6. ApiReadinessFilter (I2)
- [ ] `ApiReadinessFilter` (`OncePerRequestFilter`)
  - `private final AtomicBoolean ready = new AtomicBoolean(false);`
  - `@EventListener(ApplicationReadyEvent.class)`로 `ready.set(true)`
  - 보호 대상 경로: `/api/admin/events/**` (POST/PUT/DELETE), `/api/events/active/claim` (POST)
  - `ready == false`이면 503 + `{"error": "NOT_READY"}` 응답, DB 기록 없음
  - 읽기 API(`/api/events/active`, `/api/events/{id}/leaderboard`, `/api/me`)는 readiness와 무관
  - **부팅 중 admin이 `/start`를 부르면 throneMap 복원 순서가 어긋날 수 있음** → 503으로 reject
- [ ] `SecurityConfig`에 필터 등록 (JWT 검증 필터 이전 단계)

### 7. IP Rate Limit (M3, NFR-06)
- [ ] `build.gradle`에 의존성 추가:
  ```
  implementation 'com.bucket4j:bucket4j-core:8.10.1'
  ```
- [ ] `IpRateLimitFilter` (`OncePerRequestFilter`)
  - `Bucket` 캐시: `ConcurrentHashMap<String, Bucket>` (key = client IP)
  - 정책: `Bandwidth.simple(10, Duration.ofSeconds(1))` (10 req/s per IP)
  - 적용 대상: `POST /api/events/active/claim`만
  - 초과 시 429 + `{"error": "RATE_LIMITED"}`, DB 기록 없음
  - X-Forwarded-For 신뢰 정책 결정(Nginx 뒷단 가정 시 첫 토큰 사용)
- [ ] `SecurityConfig` 또는 `WebConfig`에 필터 등록

### 8. Hybrid 인증 + CSRF & SameSite 쿠키 (M9, 13.7)

**Day 6 인증 모델 — access token 쿠키 추가 + Authorization 헤더 유지 (hybrid):**
- 브라우저는 `ACCESS_TOKEN` 쿠키로 인증 → 표준 `EventSource('/api/events/active/stream')` 직동작
- 비브라우저(k6, curl, Postman)는 기존 `Authorization: Bearer` 헤더 그대로 사용 → 부하 테스트 서사 유지
- `JwtFilter`는 **헤더 우선, 헤더 부재 시 쿠키 fallback** — 둘 다 없으면 401
- CSRF는 **쿠키 인증으로 들어오는 변경 요청에만 적용**. `Authorization` 헤더가 있는 요청은 cross-site 위험이 없으므로 CSRF 검증 우회
- CSRF bootstrap은 로그인 후 `GET /api/me`로 수행한다. 이 첫 authenticated GET에서 `XSRF-TOKEN` 쿠키가 발급/보장되어야 하며, 이후 프론트는 변경 요청에 `X-XSRF-TOKEN` 헤더를 첨부한다
- **refresh token 쿠키와 `/api/auth/refresh`는 Day 6 범위 제외 — Day 7+로 미룸** (Day 6은 access token 쿠키까지만)
- split-origin 환경(예: `localhost:5173` ↔ `localhost:8080`)의 CORS/credentials 정합성은 Day 6 목표가 아니다. Day 6은 same-origin 또는 백엔드 단독 검증 기준으로 완료하고, 분리 프론트 연동은 Day 7에서 `allowCredentials` 및 credential 포함 요청 설정과 함께 다룬다

- [ ] `SecurityConfig` 수정
  - `.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).ignoringRequestMatchers("/api/events/active/stream", "/login/oauth2/**", "/login/**"))`
  - 보호 대상: `/api/admin/**`, `/api/events/active/claim`
  - 제외 대상: `GET /api/events/active/stream` (SSE), OAuth2 경로
  - **부하테스트 우회**: `Authorization` 헤더 인증 경로에서는 CSRF 검증 우회 (헤더 있으면 cross-site 위험 없음 — Bearer + JWT 직접 첨부)
- [ ] JWT 쿠키 응답 헤더에 `HttpOnly; Secure; SameSite=Lax; Path=/` 적용
  - `SameSite=Strict` 대신 `Lax`인 이유: GitHub OAuth2 redirect 복귀(GET)가 cross-site이므로 Strict 시 첫 로그인 후 쿠키 미전송
  - 로컬 개발(http) 환경 호환을 위해 `Secure`는 프로파일 분기(dev: false, prod: true)
- [ ] 프론트는 `XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더로 echo (Day 7 프론트 작업과 함께 검증)

## Required Domain / Infra Pieces

### Entities / repositories likely needed
- [ ] `EventAward` 엔티티 + `EventAwardId`
- [ ] `EventAwardRepository`
- [ ] `ThroneReignRepository.findLongestReign`, `findLastKingUserId`, `findOpenReign`, `closeOpenReign`
- [ ] `ThroneClaimRepository`에 summary 통계용 group-by 쿼리 (선택 — 통계 쿼리는 native or `@Query`)

### Service support
- [ ] `SsePublisher` (단일 컴포넌트)
- [ ] `AwardService` (finalize에서 호출)
- [ ] `EventSummaryService`(또는 `AwardService` 내 메서드)
- [ ] `ApiReadinessFilter`, `IpRateLimitFilter` (둘 다 `common/security/`에 배치 — 6.2 패키지 구조 원칙)
- [ ] `EventService -> ThroneService -> AggregationCache` 단방향 호출 유지 (finalize 흐름에서 cycle 만들지 말 것)

### Migration
- [ ] **신규 Flyway 마이그레이션 불필요** — `event_awards` 테이블이 V1__init_schema.sql에 이미 존재

## Behavior Rules To Keep
- [ ] SSE 발행은 모두 commit 이후 (afterCommit) 또는 트랜잭션 외부에서 — 롤백 시 발행 안 됨
- [ ] SSE는 **MVP에서 동기 발행**. 비동기화는 Phase 2 개선 #3에서. 지금 비동기로 만들면 baseline 서사가 깨짐.
- [ ] `Map<Long, Set<SseEmitter>>`로 유저당 다중 탭 허용 (M4)
- [ ] emitter 정리: `onCompletion / onTimeout / onError` 모두에서 `removeEmitter` 호출, set이 비면 key까지 제거
- [ ] finalize는 명세 15.2 순서를 엄수: lastKing 조회 → closeOpenReign → 마지막 왕 duration 집계 반영 → forceFlush → 시상 계산 → award 저장 → summary → throneMap 정리
- [ ] **마지막 왕의 열린 reign duration이 반드시 `AggregationCache.addReignEnd`로 흘러가야 한다** (Day 5 후속 TODO — 빠뜨리면 마지막 왕 보유 시간 누락)
- [ ] `event_awards.user_id NOT NULL` 유지 — "수상자 없음"은 row 부재로 표현 (B7)
- [ ] 3종 시상 모두 `user_id <> 1` 필터 (SYSTEM 제외)
- [ ] 동률 처리: 군림상은 `started_at ASC` (먼저 달성), 찬탈왕은 `user_id ASC`
- [ ] `ApiReadinessFilter`는 보호 대상 외 read-only API는 통과 (DB만 있으면 동작)
- [ ] `IpRateLimitFilter`는 `/api/events/active/claim` POST에만 적용 — 다른 API에 글로벌 적용하지 말 것
- [ ] CSRF 제외: SSE GET, OAuth2 redirect. 보호 대상: 변경 API 전체 + claim
- [ ] JWT 쿠키는 `HttpOnly; SameSite=Lax`, `Secure`는 prod 프로파일에서만 true
- [ ] `/end`에서 `lockMap.remove`, finalize에서 `throneMap.remove` (M13) — 이미 Day 4에 구현됨, Day 6에서는 검증만
- [ ] **JwtFilter는 `Authorization` 헤더 우선, 헤더 부재 시 `ACCESS_TOKEN` 쿠키 fallback** — 둘 다 없으면 401 (Hybrid 인증)
- [ ] CSRF는 **쿠키 인증으로 들어오는 변경 요청에만 적용**, `Authorization` 헤더 요청은 우회 (M9-bis)
- [ ] **refresh token / `/api/auth/refresh`는 Day 7+로 미룸** — Day 6은 access token 쿠키까지만

## Verification Checklist For Today

### Build / compile
- [ ] `./gradlew compileJava` 성공
- [ ] `./gradlew test` 성공

### SSE 단위/통합 테스트
- [ ] handshake에 JWT가 없으면 401
- [ ] handshake 성공 시 `Map<userId, Set<SseEmitter>>`에 emitter가 등록된다
- [ ] 동일 유저가 두 탭으로 접속 시 set에 emitter 2개가 들어간다 (M4)
- [ ] `onCompletion` 호출 시 emitter가 set에서 제거되고, 마지막이면 key까지 제거된다
- [ ] 찬탈 성공 commit 후 `publishThroneChanged`가 정확히 1회 호출된다
- [ ] 찬탈 롤백 시 SSE 발행이 일어나지 않는다 (afterCommit 보장)
- [ ] 리더보드 스냅샷 성공 시 `publishLeaderboardSnapshot`이 호출된다
- [ ] 같은 5분 구간 두 번째 호출(INSERT IGNORE) 시 publish는 skip된다
- [ ] start/end/finalize 시 `publishEventState`가 commit 이후 발행된다

### Award / finalize 통합 테스트
- [ ] finalize 호출 시 `event_awards`에 LONGEST_REIGN row가 생성된다
- [ ] finalize 호출 시 `event_awards`에 USURPER row가 생성된다
- [ ] 마지막 왕이 일반 유저면 LAST_KING row가 생성된다
- [ ] 아무도 찬탈 안 한 이벤트의 finalize는 award row 0개 (B7)
- [ ] award 쿼리 모두 `user_id <> 1` 적용 — SYSTEM이 수상자로 등록되지 않는다
- [ ] LONGEST_REIGN 동률 시 `started_at` 빠른 사람이 선정된다
- [ ] USURPER 동률 시 `user_id` 작은 사람이 선정된다
- [ ] **finalize 후 마지막 왕의 `event_participants.total_hold_ms`에 마지막 reign의 duration이 가산돼 있다** (Day 5 후속 TODO 회귀 방지)
- [ ] finalize 흐름이 commit된 뒤에만 throneMap이 비워진다 (롤백 시 throneMap 유지)

### Summary API
- [ ] `GET /api/events/{id}/summary` — FINALIZED 이벤트에 대해 200 + payload
- [ ] DRAFT/RUNNING 이벤트에 대해 명시적 에러 (404 또는 409)
- [ ] award row 부재 시 `awards.{lastKing|longestReign|usurper}` 필드가 `null`
- [ ] `holdDistribution`이 5구간으로 정확히 분할된다
- [ ] `claimStats.total = success + cooldown + alreadyOwner + not_running` (NO_ACTIVE_EVENT는 DB 기록 없으니 제외)

### ApiReadinessFilter
- [ ] 부팅 중 (`ApplicationReadyEvent` 이전) `POST /api/events/active/claim` → 503 NOT_READY
- [ ] 부팅 중 `POST /api/admin/events` → 503 NOT_READY
- [ ] 부팅 중 `GET /api/events/active`(읽기) → 정상 응답 또는 DB 의존 응답
- [ ] `ApplicationReadyEvent` 이후 모든 API 정상

### IP Rate Limit
- [ ] 동일 IP에서 1초 내 11번째 `claim` → 429 RATE_LIMITED
- [ ] 다른 IP에서는 영향 없이 통과
- [ ] `claim` 외 API에는 적용되지 않는다 (예: `/api/events/active`는 무제한)
- [ ] 429 응답은 DB 기록 없음 (`throne_claims`에 row 안 생김)

### CSRF & 쿠키
- [ ] 로그인 후 `GET /api/me` 호출 시 `XSRF-TOKEN` 쿠키가 발급/보장된다
- [ ] `POST /api/admin/events`에 `X-XSRF-TOKEN` 헤더 없으면 403
- [ ] `POST /api/events/active/claim`에 `X-XSRF-TOKEN` 헤더 동봉 시 정상
- [ ] `GET /api/events/active/stream`은 CSRF 제외 (SSE 정상 동작)
- [ ] OAuth2 redirect callback 시 CSRF로 막히지 않음
- [ ] JWT 쿠키 속성 `HttpOnly; Secure(prod); SameSite=Lax; Path=/` 확인 (Network 탭)
- [ ] `Authorization` 헤더 경로 (k6/curl)는 CSRF 우회 — 헤더 직접 첨부 시 cross-site 위험 없음
- [ ] refresh token / `/api/auth/refresh`는 Day 6 테스트 범위에 포함하지 않는다

### Hybrid 인증 (M9 회귀 검증)
- [ ] `Authorization` 헤더만으로 보호 API 호출 → 200 (헤더 경로 보존)
- [ ] `ACCESS_TOKEN` 쿠키만으로 보호 API 호출 → 200 (쿠키 fallback 작동)
- [ ] 헤더와 쿠키가 다른 토큰을 동시에 보낼 때 → **헤더 토큰 기준으로 인증** (헤더 우선 정책)
- [ ] 헤더/쿠키 모두 없으면 → 401
- [ ] `ACCESS_TOKEN` 쿠키 + `X-XSRF-TOKEN` 누락한 변경 요청 → 403
- [ ] `Authorization` 헤더로 들어온 변경 요청은 `X-XSRF-TOKEN` 없어도 → 200 (CSRF 우회)
- [ ] `EventSource('/api/events/active/stream')`가 쿠키만으로 handshake 성공
- [ ] `curl -N -H "Authorization: Bearer ..."`로 `/api/events/active/stream` 접속 시에도 SSE 스트림 수신
- [ ] `ACCESS_TOKEN` 쿠키 속성: `HttpOnly; SameSite=Lax; Path=/`, prod에서 `Secure` 추가 (Network 탭/응답 헤더 확인)
- [ ] OAuth2 redirect 복귀 시 쿠키 정상 발급 (SameSite=Lax 의도 확인)
- [ ] split-origin CORS/credentials 연동은 Day 6 테스트 범위에서 제외한다 (Day 7 프론트 작업으로 이관)

### M13 회귀 검증
- [ ] `/end` commit 후 `lockMap.get(eventId) == null`
- [ ] `/end` 후 `throneMap.get(eventId)`은 여전히 존재 (ENDED에서도 마지막 왕 조회 가능)
- [ ] finalize commit 후 `throneMap.get(eventId) == null`
- [ ] `currentActiveEventId`는 `/end` 시점에 이미 null

### Data integrity
- [ ] `SELECT * FROM event_awards WHERE user_id = 1` 결과 0행
- [ ] finalize 직후 `event_participants` 합산이 `throne_reigns.duration_ms` 합과 ±1ms 오차 이내 일치 (`user_id <> 1`)
- [ ] 마지막 왕의 `longest_reign_ms`가 마지막 reign duration보다 작지 않다

## Human-Owned Responsibility
- [ ] Day 5 상태 + RUNNING 이벤트 1개 + 일반 유저 2명 이상 준비
- [ ] 브라우저에서 `EventSource('/api/events/active/stream')`로 SSE 실수신 확인
- [ ] 두 탭 동시 접속 → 한 탭에서 찬탈 시 두 탭 모두 throne 이벤트 수신 확인 (M4)
- [ ] 한 탭 닫으면 서버 로그 또는 디버그 메트릭으로 emitter 1개만 남는지 확인
- [ ] `claim` 빠르게 12회/sec → 429 응답 직접 확인
- [ ] curl/Postman으로 `X-XSRF-TOKEN` 누락 시 403 확인
- [ ] 부팅 중(앱 시작 직후 1~2초 창) `claim` 호출 → 503 NOT_READY 확인 (재현 어려우면 readiness 플래그 임시 false 고정 후 검증)
- [ ] 이벤트를 ENDED → finalize → summary 호출까지 순서대로 진행
- [ ] DB에서 `event_awards` 3행(또는 부재) + `event_participants.total_hold_ms` 누적 확인
- [ ] 마지막 왕이 일반 유저인 시나리오 / 마지막 왕이 SYSTEM인 시나리오(아무도 안 찬탈) 둘 다 검증
- [ ] 본인이 "왜 SSE를 동기로 두는가, 왜 마지막 왕 duration을 finalize에서 다시 집계 캐시에 넣는가, 왜 ApiReadinessFilter가 필요한가" 설명 가능

## Suggested Manual Test Flow
1. Day 5 상태에서 RUNNING 이벤트 1개 + 유저 A, B 준비
2. 브라우저 두 탭으로 `/api/events/active/stream` 접속 (JWT 쿠키 포함)
3. 유저 A로 `claim` 호출 → 두 탭 모두 `event: throne` 수신 확인
4. 유저 B로 `claim` → 두 탭 모두 새 throne 이벤트 수신
5. 5분 경계 대기 또는 강제 트리거 → 두 탭 `event: leaderboard` 수신
6. 동일 IP에서 12회/sec `claim` → 11번째부터 429 확인
7. 관리자 `POST /api/admin/events`를 `X-XSRF-TOKEN` 없이 호출 → 403, 헤더 추가 시 정상
8. `POST /api/admin/events/{id}/end` → 두 탭 `event: state` (ENDED) 수신
9. `POST /api/admin/events/{id}/finalize` → 두 탭 `event: state` (FINALIZED) 수신
10. `GET /api/events/{id}/summary` 호출 → awards 3종 + 통계 확인
11. DB: `SELECT * FROM event_awards WHERE event_id = ?` — 수상자 row 확인 (또는 부재 시 null 응답)
12. DB: `SELECT * FROM event_participants WHERE event_id = ?` — 마지막 왕 `total_hold_ms`에 마지막 reign 반영 확인
13. 앱 재시작 직후 `claim` → 503 NOT_READY 확인 (재시작 직후 1초 내)

## Definition Of Done For Day 6
- [ ] 코드가 빌드된다
- [ ] SSE handshake가 JWT 인증으로 보호된다 (FR-05-5)
- [ ] 찬탈/리더보드 스냅샷/이벤트 상태 변화가 SSE로 실시간 발행된다 (FR-05-1~4)
- [ ] 유저당 다중 탭이 모두 이벤트를 수신한다 (M4)
- [ ] finalize가 명세 15.2 순서대로 동작하고, 마지막 왕의 duration이 집계까지 반영된다
- [ ] `event_awards`에 3종 시상이 정확히 저장되고, "수상자 없음"은 row 부재로 표현된다 (B7)
- [ ] `GET /api/events/{id}/summary`가 finalize 결과를 응답한다 (FR-08)
- [ ] `ApiReadinessFilter`가 부팅 중 변경 API를 503으로 reject한다 (I2)
- [ ] `IpRateLimitFilter`가 claim API의 IP당 10 req/s를 강제한다 (M3, NFR-06)
- [ ] CSRF 토큰과 SameSite=Lax 쿠키가 적용되고, SSE/OAuth2 경로는 제외된다 (M9)
- [ ] `/end`에서 lockMap, finalize에서 throneMap이 정리된다 (M13 — Day 4 구현 회귀 검증)
- [ ] 본인이 SSE 동기/비동기 트레이드오프, finalize 순서, 보안 보강 3종(I2/M3/M9)을 설명할 수 있다

## Notes
- Day 6의 핵심은 "MVP를 끝내는 것"이다. 새 최적화는 Day 6 범위가 아님.
- SSE는 의도적으로 동기 — Phase 2 baseline 측정에서 SSE 연결 수가 늘수록 찬탈 p99가 어떻게 튀는지 보여줘야 개선 #3 서사가 성립한다.
- 감사 로그 배치 INSERT(개선 #2)는 Phase 2(Day 9) 범위 — Day 6에서 `throne_claims`는 여전히 개별 INSERT.
- 개선 #1 현재 왕 읽기 캐시는 Day 9 범위.
- Bucket4j 기반 rate limit은 단일 인스턴스 in-memory — 멀티 인스턴스 확장 시 Redis 기반으로 전환 (19장 추후 개발).
- `event_awards.user_id NOT NULL` 제약은 v5에서 유지된다는 결정 (B7) — DDL 변경 금지.
- 마지막 왕 duration이 집계에 안 들어가는 버그가 가장 잘 빠지는 함정. 단위 테스트로 finalize 직후 `event_participants.total_hold_ms` 합산 검증을 반드시 추가할 것.
- `lastKingUserId`를 `closeOpenReign` 이전에 잡는 이유는 명세 15.2 주석 참고 — `started_at DESC`로 통일하면 순서 무관이지만 "관측 시점을 finalize 진입 순간으로 고정"이라는 의도를 명확히 하기 위함.
- Day 6은 same-origin 또는 백엔드 단독 검증 기준으로 마감한다. split-origin CORS/credentials 연동은 Day 7 프론트 작업에서 다룬다.

## Critical Files To Modify / Create

### Modify
- `build.gradle` (Bucket4j 의존성 추가)
- `src/main/java/com/sst/flaggame/common/config/SecurityConfig.java` (CSRF, SameSite, 필터 등록)
- `src/main/java/com/sst/flaggame/domain/throne/service/ThroneService.java` (afterCommit에 SSE publish 추가)
- `src/main/java/com/sst/flaggame/domain/throne/repository/ThroneReignRepository.java` (findLongestReign / findLastKingUserId / findOpenReign / closeOpenReign 추가)
- `src/main/java/com/sst/flaggame/domain/leaderboard/service/LeaderboardService.java` (Day 5 TODO 위치에 SSE publish 채우기)
- `src/main/java/com/sst/flaggame/domain/event/service/EventService.java` (finalize 흐름 재구성, start/end/finalize 후 SSE publish)
- `src/main/java/com/sst/flaggame/domain/event/controller/EventController.java` (`GET /api/events/{id}/summary` 추가)

### Create
- `src/main/java/com/sst/flaggame/domain/sse/service/SsePublisher.java`
- `src/main/java/com/sst/flaggame/domain/sse/controller/SseController.java`
- `src/main/java/com/sst/flaggame/domain/event/entity/EventAward.java`
- `src/main/java/com/sst/flaggame/domain/event/entity/EventAwardId.java`
- `src/main/java/com/sst/flaggame/domain/event/entity/AwardType.java`
- `src/main/java/com/sst/flaggame/domain/event/repository/EventAwardRepository.java`
- `src/main/java/com/sst/flaggame/domain/event/service/AwardService.java`
- `src/main/java/com/sst/flaggame/domain/event/service/EventSummaryService.java` (또는 AwardService에 합치기)
- `src/main/java/com/sst/flaggame/domain/event/dto/EventSummary.java`
- `src/main/java/com/sst/flaggame/common/security/ApiReadinessFilter.java`
- `src/main/java/com/sst/flaggame/common/security/IpRateLimitFilter.java`
- `src/test/java/com/sst/flaggame/domain/sse/service/SsePublisherTest.java`
- `src/test/java/com/sst/flaggame/domain/event/service/AwardServiceTest.java`
- `src/test/java/com/sst/flaggame/domain/event/service/EventServiceFinalizeTest.java` (마지막 왕 duration 집계 반영 회귀 테스트)
- `src/test/java/com/sst/flaggame/common/security/ApiReadinessFilterTest.java`
- `src/test/java/com/sst/flaggame/common/security/IpRateLimitFilterTest.java`
