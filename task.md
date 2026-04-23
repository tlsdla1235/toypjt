# Day 3 — 이벤트 라이프사이클

## 목표

이벤트 CRUD + 상태 전이 + 인메모리 상태 초기화/복원까지 완성한다.

---

## 체크리스트

### 1. 도메인 패키지 구조 생성

- [ ] `domain/event/entity/Event.java` — 이벤트 엔티티
- [ ] `domain/event/entity/EventStatus.java` — `DRAFT | RUNNING | ENDED | FINALIZED` enum
- [ ] `domain/event/repository/EventRepository.java`
- [ ] `domain/event/dto/CreateEventRequest.java` — `name`, `description`, `cooldownMs`, `durationH`
- [ ] `domain/event/dto/EventResponse.java`
- [ ] `domain/event/service/EventService.java`
- [ ] `domain/event/controller/AdminEventController.java`

---

### 2. Event 엔티티

```java
events (
  id            BIGINT PK AUTO_INCREMENT
  name          VARCHAR(120) NOT NULL
  description   TEXT
  status        ENUM('DRAFT','RUNNING','ENDED','FINALIZED') NOT NULL DEFAULT 'DRAFT'
  cooldown_ms   INT NOT NULL DEFAULT 3000
  duration_h    INT NOT NULL
  started_at    TIMESTAMP NULL
  ends_at       TIMESTAMP NULL
  ended_at      TIMESTAMP NULL
  finalized_at  TIMESTAMP NULL
  created_at    TIMESTAMP NOT NULL
)
```

- [ ] `@Enumerated(EnumType.STRING)` 으로 status 매핑

---

### 3. 관리자 API 4종 (`/api/admin/events/**`)

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/admin/events` | 이벤트 생성 (DRAFT) |
| POST | `/api/admin/events/{id}/start` | DRAFT → RUNNING |
| POST | `/api/admin/events/{id}/end` | RUNNING → ENDED (조기 종료) |
| POST | `/api/admin/events/{id}/finalize` | ENDED → FINALIZED |

- [ ] 모든 관리자 API에 `@PreAuthorize("hasRole('ADMIN')")` 적용

#### 3-1. 이벤트 생성 (`POST /api/admin/events`)

- [ ] 요청: `{ name, description, cooldownMs, durationH }`
- [ ] `status = DRAFT`, `created_at = now()` 로 저장

#### 3-2. 이벤트 시작 (`POST /api/admin/events/{id}/start`)

- [ ] 대상 이벤트 상태가 `DRAFT`인지 검증
- [ ] `SELECT id FROM events WHERE status='RUNNING' FOR UPDATE` — 동시 RUNNING 1개 제약 체크 (기존 RUNNING 있으면 `409 ALREADY_RUNNING`)
- [ ] `status = RUNNING`, `started_at = now()`, `ends_at = started_at + duration_h`
- [ ] **SYSTEM 사용자(id=1)로 초기 reign 생성** (`throne_reigns` INSERT)
- [ ] **`current_throne` 1행 생성** (seed SYSTEM 사용자로)
- [ ] `throneMap.put(eventId, new ThroneState(seedReignId, SYSTEM_USER_ID, now))`
- [ ] `lockMap.computeIfAbsent(eventId, k -> new ReentrantLock(true))`
- [ ] `currentActiveEventId.set(eventId)`

#### 3-3. 이벤트 조기 종료 (`POST /api/admin/events/{id}/end`)

- [ ] 대상 이벤트 상태가 `RUNNING`인지 검증
- [ ] `status = ENDED`, `ended_at = now()`
- [ ] `afterCommit` 에서 `lockMap.remove(eventId)` (M13)
- [ ] `currentActiveEventId.compareAndSet(eventId, null)`
- [ ] `throneMap`은 유지 (finalize까지 "마지막 왕" 조회용으로 보존)

#### 3-4. Finalize (`POST /api/admin/events/{id}/finalize`)

- [ ] 대상 이벤트 상태가 `ENDED`인지 검증
- [ ] `status = FINALIZED`, `finalized_at = now()`
- [ ] `throneMap.remove(eventId)` (메모리 누수 방지)
- [ ] `currentActiveEventId.compareAndSet(eventId, null)` (방어적 CAS)
- [ ] Day 3에서는 시상 계산 없이 상태 전이만 구현 (AwardService는 Day 6)

---

### 4. `@Scheduled` 자동 종료

- [ ] `domain/event/service/EventScheduler.java` 생성
- [ ] `@Scheduled(fixedDelay = 60_000)` — `ends_at` 경과 RUNNING 이벤트를 ENDED로 전환
- [ ] 전환 로직은 `EventService.autoEnd(event)` 에 위임
- [ ] 자동 종료 시에도 `lockMap.remove`, `currentActiveEventId.clear` 수행

---

### 5. `EventService` 인메모리 상태 관리

```java
// EventService 핵심 필드
private final AtomicReference<Long> currentActiveEventId = new AtomicReference<>();
private final ConcurrentHashMap<Long, ReentrantLock> lockMap = new ConcurrentHashMap<>();
private final ConcurrentHashMap<Long, ThroneState> throneMap = new ConcurrentHashMap<>();
```

- [ ] `ThroneState` — immutable record (`reignId`, `userId`, `heldSince`)
- [ ] `getCurrentActiveEventId()` — `currentActiveEventId.get()` 반환
- [ ] `clearActiveEventIfMatch(eventId)` — `compareAndSet(eventId, null)`

#### `@PostConstruct` — 서버 부팅 시 복원

```sql
SELECT ct.event_id, ct.reign_id, ct.user_id, ct.held_since
FROM current_throne ct
JOIN events e ON ct.event_id = e.id
WHERE e.status = 'RUNNING'
```

- [ ] 조회 결과로 `throneMap` 복원
- [ ] `currentActiveEventId` 복원
- [ ] `lockMap.computeIfAbsent` 복원

---

### 6. `ApiReadinessFilter` (I2)

- [ ] `AtomicBoolean ready = false` 플래그
- [ ] `ApplicationReadyEvent` 수신 시 `ready = true`
- [ ] `ready == false` 이면 `POST /api/admin/events/**` 및 `POST /api/events/active/claim` 에 `503 NOT_READY` 반환
- [ ] 읽기 API(`GET /api/me`, 리더보드 등)는 readiness 체크 무관

---

### 7. 이벤트 상태 전이 테스트

- [ ] `DRAFT → RUNNING` 정상 전이
- [ ] 동시 RUNNING 2개 불가 (`409 ALREADY_RUNNING`)
- [ ] `RUNNING → ENDED` (관리자 조기 종료)
- [ ] `RUNNING → ENDED` (스케줄러 자동 종료 — `ends_at` 경과 시)
- [ ] `ENDED → FINALIZED`
- [ ] 잘못된 상태에서의 전이 시도 예외 확인

---

## 주요 제약 & 설계 결정

| 항목 | 내용 |
|---|---|
| 동시 RUNNING | 1개 제한. `/start` 트랜잭션 내 `SELECT ... FOR UPDATE` 체크 |
| SYSTEM 사용자 | `id = 1` PK 상수로만 식별. 이벤트 시작 시 초기 reign seed |
| throneMap 유지 시점 | `/end` 후에도 유지 → finalize 시 `remove` |
| lockMap 제거 시점 | `/end` 의 `afterCommit` 에서 즉시 제거 (M13) |
| currentActiveEventId | `/start` 에서 `set`, `/end` 또는 스케줄러에서 `compareAndSet(..., null)` |
| @PostConstruct 복원 | RUNNING 이벤트의 throneMap + lockMap + currentActiveEventId 재구성 |

---

## 완료 기준

- [ ] 관리자 API 4종 모두 동작
- [ ] 상태 전이 테스트 통과
- [ ] 서버 재기동 후 RUNNING 이벤트가 `@PostConstruct` 로 복원됨
- [ ] `ApiReadinessFilter` 가 부팅 중 POST 요청을 503으로 차단
- [ ] 스케줄러가 `ends_at` 경과 이벤트를 자동 종료
