# SSAFY 왕좌전 (Throne War) — 최종 서비스 정의서 v5.0

> **v5.0 변경 요약 (신입 포폴 scope 현실화)**
>
> v4.3.3 → v4.3.7까지 5번의 정합성 검증 라운드를 거치며 Critical/Important 결함을 모두 해결했으나, 그 과정에서 운영 수준의 방어 장치(Consumer DLQ + 재시도 3회 + backoff, 3중 finalize gate, Redis 전체 유실 복구, Streams 전환 경로, 동시성 3모델 비교, WebSocket 구현 등)가 본문에 쌓였다. 1인 2주 포폴 프로젝트 스코프에 비해 과하다는 결론이 섰다.
>
> **v5.0의 편집 원칙**:
> - **실제로 코드가 안 돌아가면 곤란한 정합성 핵심**(판정 순서, closeReign 조건부 UPDATE, ThroneState immutable record, SYSTEM 필터, SSE 다중 탭, Lua 원자 찬탈 + 전역 reign_seq + Lua 내 RPUSH 등)은 본문에 그대로 유지.
> - **200 TPS 규모에서 관측 확률이 낮은 운영 방어 장치**(DLQ/재시도 큐잉, 3중 finalize gate, Redis 전체 유실 복구, Streams 전환, A2/A3 동시성 실험, WebSocket 비교 실험)는 문서 끝 **"추후 개발 예정" 섹션**으로 이관하고 각 항목에 "왜 지금은 안 하는가" 근거를 명시.
> - **Phase 3은 유지하되 "최소 구현"으로 본문 재작성** — Lua 원자 찬탈, 전역 `global:reign_seq`, Lua 내부 `RPUSH`, 단일 Consumer + MySQL 영속화, `@PostConstruct` 1회 부트스트랩, k6 재측정. Phase 3의 본질인 "MySQL 한계를 Redis로 돌파"를 수치로 증명하는 최소 단위만 남긴다.
> - **v4.3.3 → v4.3.7 정합성 수정 이력**은 라운드별 변경 요약 블록으로 쌓는 대신 이 v5.0 헤더 하나로 통합. 수정된 결과(판정 순서, closeReign 조건, ThroneState record 등)는 본문 코드와 주석에 녹아 있음.
> - **부록 C(T1/T2/T3 분류)는 제거** — 본문 재구성으로 이미 반영됐으며, 문서 끝 "추후 개발 예정" 섹션이 부록 C의 역할을 대체한다.
>
> **포폴 핵심 산출물 (본문에 그대로 살아있음)**:
> - 3단계 서사 (Phase 1 MVP → Phase 2 측정·개선 → Phase 3 Redis)
> - Phase 2 개선 3종 (읽기 캐시, 감사로그 배치, SSE 비동기)
> - 동시성 전략 비교 A1 (ReentrantLock vs FOR UPDATE) — "RDBMS 락 경험" 증빙
> - k6 before/after 비교 표 (10.9)
> - 구조화 로깅 (JSON + MDC + 마스킹), Actuator + Prometheus 관측성
> - SSE 인증된 handshake + 비동기 브로드캐스트

---

## 한 줄 요약

> **관리자가 원하는 시점에 이벤트를 열면, GitHub으로 로그인한 참가자들이 24시간 동안 단 하나의 "왕좌"를 3초 쿨타임으로 뺏고 지키는 실시간 경쟁 서비스. MySQL + Caffeine으로 "동작하는 최소 버전"을 먼저 만들고, k6 부하테스트로 한계를 드러낸 뒤 단계적 개선과 Redis 도입으로 성능을 끌어올리는 과정을 포트폴리오로 남긴다.**

---

## 목차

1. [프로젝트 목적 & 학습 서사](#1-프로젝트-목적--학습-서사)
2. [서비스 범위](#2-서비스-범위)
3. [이벤트 생애주기](#3-이벤트-생애주기)
4. [기능적 요구사항](#4-기능적-요구사항)
5. [비기능적 요구사항](#5-비기능적-요구사항)
6. [확정 기술 스택](#6-확정-기술-스택)
7. [인프라 & 배포](#7-인프라--배포)
8. [데이터 모델 (MySQL)](#8-데이터-모델-mysql)
9. [Phase 1 — MVP 설계](#9-phase-1--mvp-설계)
10. [Phase 2 — 측정 & 개선 사이클](#10-phase-2--측정--개선-사이클)
11. [Phase 3 — Redis 도입 & 개선](#11-phase-3--redis-도입--개선)
12. [SSE 선택 근거](#12-sse-선택-근거)
13. [로깅 전략](#13-로깅-전략)
14. [메트릭 & 관측성](#14-메트릭--관측성)
15. [시상 & 결과 집계](#15-시상--결과-집계)
16. [도메인 서비스 & API 명세](#16-도메인-서비스--api-명세)
17. [개발 일정 (2주)](#17-개발-일정-2주)
18. [포폴화 체크리스트](#18-포폴화-체크리스트)
19. [추후 개발 예정](#19-추후-개발-예정)

---

## 1. 프로젝트 목적 & 학습 서사

### 1.1. 왜 이 프로젝트인가

**이 프로젝트는 "빠른 MVP → 정량적 병목 측정 → 기술 도입으로 개선" 이라는 실무 의사결정 사이클을 1인 프로젝트 규모로 재현한다.** 단순히 기술 스택을 나열하는 포폴이 아니라, **"왜 이 기술을 썼나"에 수치로 답할 수 있는 포폴**을 만드는 것이 목표다.

### 1.2. 학습 포인트 (채용 관점)

| # | 학습 목표 | 포폴 증빙 |
|---|---|---|
| 1 | **DB 레이어에서의 동시성 제어** — 트랜잭션, 잠금 전략 | MySQL `SELECT ... FOR UPDATE` 10.8 실험 구현 + k6 검증 |
| 2 | **캐시 도입 설계** — write-back / write-through / read-through | Caffeine으로 집계 캐시 + 주기적 flush 구현 |
| 3 | **실시간 푸시 구현** — SSE 선택 근거 명시 | Spring MVC `SseEmitter` + 비동기 브로드캐스트. WebSocket과의 정량 비교는 추후 과제 |
| 4 | **구조화 로깅 & MDC** — 트레이싱 가능한 애플리케이션 | JSON 로그 + requestId/userId 추적 |
| 5 | **부하테스트 기반 병목 분석** | k6 baseline → 개선 → 재측정의 before/after 수치 |
| 6 | **기술 선택의 근거 정량화** | "Redis 도입 전 MySQL 한계 수치 → Redis 도입 후 개선 수치" |
| 7 | **OAuth2 인증 연동** | GitHub OAuth2 + SSE handshake 보안 |
| 8 | **APM/모니터링** | Prometheus + 커스텀 메트릭 + PromQL 기반 before/after 수치 비교 |

### 1.3. 3단계 서사 구조

```
   Phase 1 (Day 1~7)            Phase 2 (Day 8~11)                 Phase 3 (Day 12~13)
 ┌──────────────────────┐    ┌──────────────────────────────┐    ┌─────────────────────┐
 │ MVP 구축             │ →  │ 측정 → 개선 → 재측정 반복    │ →  │ Redis 도입          │
 │ - 동작하는 최소 버전 │    │ - baseline                   │    │ - Lua 원자 처리     │
 │ - ReentrantLock +    │    │ - 개선 #1 읽기 캐시          │    │ - 실시간 리더보드   │
 │   집계캐시           │    │ - 개선 #2 감사로그 배치      │    │ - After 수치 비교   │
 │ - SSE (동기)         │    │ - 개선 #3 SSE 비동기         │    │                     │
 │                      │    │ - 동시성 A0 vs A1 비교       │    │                     │
 └──────────────────────┘    └──────────────────────────────┘    └─────────────────────┘
```

### 1.4. 서사의 핵심 가설 (⭐ 포폴 차별점)

이 프로젝트는 "성공한 프로젝트"를 만드는 게 아니다. **"MySQL 기반 MVP는 어디서 무너지는가를 수치로 드러내고, 단계적 개선으로 어디까지 끌어올릴 수 있는지, 그리고 그 한계를 Redis가 어떻게 돌파하는지"**를 보여주는 것이 목표다.

- **Phase 1 MVP는 의도적으로 "최적화 전의 단순 구현"이다.** 여기엔 구현의 함정과 한계가 많이 숨어 있다. 특히 찬탈 요청을 `ReentrantLock`으로 직렬화하기 때문에, VU 수 증가에 비례해 p99가 늘어나는 구조적 특성을 가진다.
- **Phase 2는 그 함정을 하나씩 노출하고 수치 기반으로 수정**한다. 개선 하나당 "증상 → 원인 → 조치 → 효과"를 문서화한다. 다만 "직렬화로 인한 p99 증가" 자체는 Phase 2에서 건드리지 않는다 — 그건 Phase 3의 몫이다.
- **Phase 3은 MySQL로 끌어올릴 수 있는 상한을 넘어서기 위해** Redis를 도입하고, 단일 스레드 원자 처리(Lua)로 "직렬성은 유지하되 처리량을 한 차원 끌어올리는" 방식으로 MVP의 구조적 한계를 돌파한다.

이 구조의 장점: **포트폴리오에 담을 "이야기"가 Phase 2에서만 3~4개 생성된다.** 각 개선이 독립된 서사 단위가 된다.

### 1.5. 원칙

- **Phase 1은 "동작하는 최소 버전"** — 과한 최적화를 피하고, 단순하고 이해하기 쉬운 코드로.
- **Phase 2는 "측정 후 필요한 것만"** — 추측이 아닌 수치 기반 개선.
- **Phase 3은 "MySQL의 구조적 한계 때문에"** — 앞 단계에서 충분히 노력한 뒤의 정당화.

---

## 2. 서비스 범위

### 2.1. Core (반드시 구현)

| 영역 | Phase | 설명 |
|------|---|------|
| GitHub OAuth2 인증 | 1 | 로그인, 세션, JWT 발급 |
| 이벤트 라이프사이클 | 1 | admin 주도 생성/시작/종료/finalize, 반복 개최 |
| 왕좌 찬탈 (MVP) | 1 | JVM `ReentrantLock`으로 순차 처리 + Caffeine write-back 집계 |
| 집계 write-back | 1 | Caffeine으로 `event_participants` UPDATE 핫스팟 회피 |
| 5분 배치 리더보드 | 1 | 스냅샷 테이블 |
| SSE 실시간 푸시 | 1 | OAuth2 인증된 세션만 handshake (커밋 후 동기 발행) |
| 3대 시상 | 1 | 최장 보유 / 최다 탈취 / 마지막 왕 |
| 구조화 로깅 | 1 | JSON + MDC + 마스킹 |
| 기본 메트릭 | 1 | Spring Actuator + Prometheus |
| k6 baseline 측정 | 2 | MVP의 한계 수치 확보 |
| 개선 #1 현재 왕 읽기 캐시 | 2 | Caffeine 2초 TTL |
| 개선 #2 감사 로그 배치 INSERT | 2 | 500건/500ms 큐 + `rewriteBatchedStatements` |
| 개선 #3 SSE 비동기 브로드캐스트 | 2 | 별도 ExecutorService |
| 동시성 A1 (FOR UPDATE) 비교 실험 | 2 | `ThroneServiceForUpdate` 별도 구현체 |
| Redis Lua 원자 처리 | 3 | 찬탈 핫패스 이전 |
| Redis ZSET 실시간 리더보드 | 3 | 5분 배치 대체 |
| Redis Pub/Sub SSE fan-out | 3 | 멀티 인스턴스 대비 (본 프로젝트는 단일 인스턴스) |

### 2.2. 명시적 제외

- 유저 프로필 / 팔로잉 / 채팅 등 소셜 기능
- 동시 복수 이벤트 (1 RUNNING per time)
- 모바일 앱, PWA
- 이메일 알림, SMS
- Python/R 기반 오프라인 분석 — Java로 대체
- **WebSocket 구현** — SSE만 구현. WebSocket과의 정량 비교 실험은 19장 "추후 개발 예정"

---

## 3. 이벤트 생애주기

### 3.1. 상태 머신

```
  [DRAFT]  ← admin이 이벤트 생성 시
     │  admin POST /events/{id}/start
     ▼
  [RUNNING]  ← 최대 24h
     │  (A) endsAt 경과 → 스케줄러 자동 전환
     │  (B) admin POST /events/{id}/end → 강제 종료
     ▼
  [ENDED]
     │  admin POST /events/{id}/finalize
     ▼
  [FINALIZED]  ← 조회만 가능
```

### 3.2. 반복 개최 규칙

- **동시 RUNNING 이벤트는 1개로 제한.** 새 이벤트 시작 시 기존 RUNNING이 없어야 함.
- 과거 이벤트는 MySQL에 영구 보관 (v3처럼 휘발성이 아님).
- 이벤트마다 독립된 `event_id`로 네임스페이싱 → 참가자 기록, 리더보드, 시상 모두 이벤트별로 분리.
- `/api/events/active/claim`은 RUNNING 이벤트가 없으면 항상 `404 NO_ACTIVE_EVENT`를 반환한다.

---

## 4. 기능적 요구사항

### FR-01. 인증 (GitHub OAuth2)

- **FR-01-1**: 최초 접속 시 "GitHub으로 로그인" 버튼 노출.
- **FR-01-2**: OAuth2 authorization code flow → Spring Security가 처리.
- **FR-01-3**: 최초 로그인 시 `users` 테이블에 레코드 생성 (github_id, login, avatar_url, created_at).
- **FR-01-4**: 로그인 성공 시 자체 JWT 발급 (stateless API 호출용).
- **FR-01-5**: 재접속 시 JWT로 자동 인증. JWT 만료 시 `/api/auth/refresh`로 갱신(refresh 토큰 사용). refresh 토큰까지 만료된 경우에만 OAuth2 재인증.
- **FR-01-6**: 이벤트 유무와 무관하게 로그인 가능. 참가는 RUNNING 이벤트에서만.
- **FR-01-7**: 관리자 권한은 `users.role = ADMIN` 컬럼으로 판별 (초기 시드로 주입).

### FR-02. 이벤트 관리 (관리자)

- **FR-02-1**: 관리자는 이벤트를 DRAFT 상태로 생성 (이름, 설명, 쿨타임ms, 최대 지속시간h).
- **FR-02-2**: 관리자는 DRAFT 이벤트를 시작 → RUNNING, `started_at = now()`, `ends_at = started_at + duration_h`.
- **FR-02-3**: 관리자는 RUNNING 이벤트를 조기 종료 가능.
- **FR-02-4**: `@Scheduled(fixedDelay = 60s)`가 `ends_at` 경과한 RUNNING을 ENDED로 전환.
- **FR-02-5**: 관리자는 ENDED 이벤트를 finalize → 시상 계산 + 통계 summary 생성 → FINALIZED.
- **FR-02-6**: FINALIZED는 조회만 가능, 수정 불가.
- **FR-02-7**: 관리자 전용 API는 `@PreAuthorize("hasRole('ADMIN')")`.

### FR-03. 왕좌 찬탈

- **FR-03-1**: RUNNING 이벤트 없음 → `404 NO_ACTIVE_EVENT`. **이 결과는 `throne_claims`에 기록하지 않는다** — Counter 메트릭(`throne_claim_total{result="NO_ACTIVE_EVENT",event_id="none"}`)으로만 집계하여 `throne_claims` 인덱스 스큐를 방지한다 (I3 이슈 해결).
- **FR-03-2**: 본인이 현재 왕 → `409 ALREADY_OWNER`.
- **FR-03-3**: 쿨타임 중 → `429 COOLDOWN` + 남은 ms.
- **FR-03-4**: 락 획득 후 이벤트 상태가 RUNNING이 아닌 경우(락 대기 중 admin이 `/end` 호출) → `409 NOT_RUNNING`. 참고: 정상 흐름에선 발생 빈도가 낮지만, 레이스 경로이므로 별도 코드로 노출한다.
- **FR-03-5**: 성공 시 3초 쿨타임 시작, 이전 왕의 보유 시간 확정.
- **FR-03-6**: 찬탈 시도 결과는 `throne_claims` 테이블에 **best-effort**로 기록한다. 다음 3개 경로는 DB 기록 없이 메트릭 Counter로만 관찰된다:
  1. `NO_ACTIVE_EVENT`: `throne_claim_total{result="NO_ACTIVE_EVENT",event_id="none"}` (FR-03-1, I3)
  2. **queue full drop** (Phase 2 개선 #2 이후): `claim_log_drop_total` Counter (개선 #2의 드롭 정책)
  3. **Consumer 영속화 실패** (Phase 3): `throne_consumer_failure_total` Counter — MySQL 장애 등으로 Lua는 성공했으나 Consumer 단계에서 실패한 건수

  DB 기록되는 나머지 경로(정상 SUCCESS/COOLDOWN/ALREADY_OWNER/NOT_RUNNING)에 대해서만 "모든 시도 기록" 원칙이 엄격히 유지된다. **감사 기록은 락 안에서 단 한 번만 수행한다** — 락 바깥 쿨타임 선체크는 **메트릭 관찰용**(`throne_fast_reject_hint_total` Counter, 증가만 기록)이며 실제 rejection은 하지 않는다 (B1, C1 이슈 해결).
- **FR-03-7**: (Phase 1) 이벤트별 JVM `ReentrantLock`으로 요청을 순차 처리. `current_throne`은 인메모리 상태로 관리하고, 한 트랜잭션 안에서 `throne_reigns`/`cooldowns` 영속화와 `current_throne` DB 갱신을 함께 수행한다. **락 안 검증 순서는 `NOT_RUNNING → ALREADY_OWNER → COOLDOWN 더블체크 → 성공 처리`이다** — 방금 왕이 된 본인이 재클릭하면 `COOLDOWN`이 아닌 `ALREADY_OWNER`를 받도록 `ALREADY_OWNER`를 쿨타임 더블체크보다 앞에 배치한다 (C2 이슈 해결). (Phase 3) Redis Lua로 대체.

> **찬탈 시도 결과는 `SUCCESS`, `COOLDOWN`, `ALREADY_OWNER`, `NO_ACTIVE_EVENT`, `NOT_RUNNING` 5종이다. Phase 1은 락 경합으로 인한 중간 실패 상태를 노출하지 않는다 — 요청은 큐에 들어가서 반드시 처리된다.**

### FR-04. 리더보드

- **FR-04-1**: Top 20 조회 (누적 보유 시간 기준).
- **FR-04-2**: 본인 순위 & 누적 시간 조회.
- **FR-04-3**: 과거 FINALIZED 이벤트 리더보드 조회 가능.
- **FR-04-4**: (Phase 1) 5분마다 배치 집계된 `leaderboard_snapshot` 뷰 사용.
- **FR-04-5**: (Phase 3) Redis ZSET으로 실시간 갱신.

### FR-05. SSE (실시간 푸시)

- **FR-05-1**: 현재 왕 / 왕의 heldSince 푸시.
- **FR-05-2**: 왕좌 교체 즉시 브로드캐스트.
- **FR-05-3**: 리더보드 변경 알림 (Phase 1: 5분 주기 / Phase 3: 1초 배치).
- **FR-05-4**: 이벤트 상태 변화 푸시 (시작/종료/finalize).
- **FR-05-5**: **SSE handshake 시 JWT 검증 → 미인증은 `401`.** 운영 브라우저 클라이언트는 **HttpOnly 쿠키 기반**으로 통일하고, 부하테스트/비브라우저 클라이언트에 한해 Cookie 헤더 또는 Authorization 헤더를 허용한다.
- **FR-05-6**: REST 폴링 degrade 엔드포인트 제공.

> **FR-06 — WebSocket 구현**은 19장 "추후 개발 예정"으로 이관. Phase 1/2 모두 실시간 push는 SSE만 사용하며, SSE 선택 근거는 12장에 상세히 기술.

### FR-07. 시상 (3종)

- **FR-07-1**: 👑 **군림상 (The Longest Reign)** — 단일 세션 기준 가장 오래 보유한 사람. 동률 시 먼저 달성한 사람.
- **FR-07-2**: ⚔️ **찬탈왕 (The Usurper)** — 찬탈 성공 횟수가 가장 많은 사람.
- **FR-07-3**: 🌅 **최후의 왕 (The Last King)** — 이벤트 종료 시점에 왕좌를 보유하고 있던 사람.

### FR-08. 결과/통계 조회

- **FR-08-1**: 과거 이벤트 summary 조회 API.
- **FR-08-2**: 시간대별 찬탈/교체 추이, 보유 시간 분포 포함.
- **FR-08-3**: 시상 수상자 3인 + 주요 지표.

---

## 5. 비기능적 요구사항

### NFR-01. 동시성

- **Phase 1**: 이벤트별 `ReentrantLock`으로 찬탈 요청을 JVM 레벨에서 직렬화. DB 커넥션을 블로킹하지 않으므로 커넥션 고갈 위험이 없고, 유저 관점에서는 "요청은 반드시 결과(성공 or 쿨타임)를 반환"하는 단순한 UX가 된다. 쿨타임은 `cooldowns`의 `PRIMARY KEY (event_id, user_id)` 기반 upsert로 관리한다. **단일 인스턴스 전제이며, 멀티 인스턴스 확장 시 Redis Lua로 전환한다(Phase 3).**
- **Phase 3**: Redis Lua 스크립트 (원자 처리).
- 동일 k6 시나리오로 양쪽 모두 "동시 200 요청 → 1건만 성공" 검증.

### NFR-02. 성능 목표 (가설 검증용)

**공통 목표 (서비스 SLO — 이벤트 당일 기준)**

| 지표 | 목표 |
|---|---|
| 찬탈 API p99 | < 100ms |
| 리더보드 p99 | < 50ms |
| 동시 SSE 연결 | 300 |
| 피크 TPS | 200 |

**단계별 가설**

| 지표 | MVP (Day 7) | Phase 2 개선 후 (Day 11) | Phase 3 (Day 13) |
|---|---|---|---|
| 찬탈 API p99 | VU 수에 비례하는 수백 ms 예상 (직렬화 특성) | 일부 개선 (읽기/SSE) | < 100ms 달성 |
| 찬탈 성공 TPS | ~30~50 TPS | 일부 개선 | 200 TPS |
| 리더보드 p99 | < 300ms | < 200ms (읽기 캐시) | < 50ms |
| 동시 SSE 연결 | 200 제한적 | 200 안정 (비동기화) | 300 안정 |

> MVP는 "동작하는 최소"를 목표로 한다. 성능 SLO 달성은 Phase 2의 단계적 개선 + Phase 3의 Redis 도입으로 이룬다. 각 단계의 수치를 10.9 종합 비교 표에 기록한다.

**MVP의 구조적 특성** (Phase 2에서 수치로 입증)
- **이벤트당 직렬화**: `ReentrantLock`으로 찬탈이 완전히 순차 처리되므로, 동시 요청 수(VU)에 비례해 찬탈 p99가 증가한다. 이는 버그가 아니라 MVP의 의도된 특성이다.
- **락 보유 시간 = 찬탈 트랜잭션 전체 시간**: DB 쓰기(`throne_reigns`, `cooldowns`, `current_throne`)가 락 안에서 모두 일어나므로, DB 레이턴시가 곧 락 대기열의 처리 속도가 된다.
- **감사 로그 매 요청 INSERT**: `throne_claims` INSERT QPS가 찬탈 요청 QPS와 1:1.
- **SSE 동기 브로드캐스트**: 연결 수 증가 시 `afterCommit` 송신 루프가 응답 tail latency를 늘린다.
- **읽기 경로의 DB 히트**: `event_participants` 조회가 쓰기 경합에 영향을 받는다.

Phase 2에서는 이 중 **`ReentrantLock` 직렬화 자체는 건드리지 않는다**(그건 Phase 3 Redis의 몫이다). Phase 2는 그 외 3종(감사로그, SSE, 읽기캐시)을 개선하고, Phase 3에서 Redis Lua로 직렬화 구조 자체를 원자 연산으로 대체한다.

### NFR-03. 영속성

- **Phase 1 & 3 공통**: MySQL이 Source of Truth. 찬탈 이벤트는 `throne_claims` 테이블에 **best-effort append**. 누락 경로(NO_ACTIVE_EVENT / queue full drop / Consumer 영속화 실패)는 Counter 메트릭으로 관찰하며, 영속성 갭이 발생하면 메트릭 알람이 트리거된다.
- Phase 3에서 Redis는 **핫 캐시 + 동시성 제어 레이어**로 도입, 영속성은 여전히 MySQL.
- Caffeine 캐시는 JVM 프로세스 메모리 → write-back 주기마다 MySQL로 flush.

### NFR-04. 로깅

- JSON 포맷 구조화 로깅.
- MDC로 `requestId / userId / eventId / action` 자동 주입.
- 로그 레벨 규율 (13장).
- 민감정보 마스킹 (JWT, Authorization, OAuth access_token).
- 파일 롤링 (100MB × 7일).

### NFR-05. 관측성

- Spring Actuator + Micrometer로 기본 메트릭 노출.
- Prometheus가 scrape, Prometheus UI(PromQL)로 조회.
- **v3 대비 제거**: node-exporter, AlertManager, Discord 웹훅 (필요 시 추후 추가).
- **v4.3.2에서 제거**: Grafana (6.1 참조).

### NFR-06. 부정 방지

- IP 기반 rate limit 10 req/s (Bucket4j 또는 Spring filter).
- 1 GitHub 계정 = 1 참가자.
- 관리자 수동 블록 (`users.blocked = true`).

---

## 6. 확정 기술 스택

| 구분 | 기술 | 비고 |
|------|------|------|
| Language | Java 17 | |
| Framework | Spring Boot 3.x | MVC + SseEmitter |
| Security | Spring Security + OAuth2 Client + JWT | GitHub provider |
| DB | MySQL 8.x | Source of truth |
| ORM | Spring Data JPA + QueryDSL (선택) | |
| 로컬 캐시 | Caffeine | Phase 1 write-back |
| 분산 캐시/원자 연산 | Redis 7 (Lettuce) | **Phase 3에서만 도입** |
| 실시간 | SseEmitter | 서버 → 클라이언트 단방향 push (선택 근거 12장) |
| 로깅 | Logback + logstash-logback-encoder | JSON |
| 기본 메트릭 | Micrometer + Prometheus | PromQL 쿼리 기반 수치 비교 |
| 부하테스트 | k6 | 다중 시나리오 |
| 빌드 | Gradle | |
| 프론트 | React + Vite | 최소 SPA |
| 인프라 | Docker Compose → EC2 | |

### 6.1. v3 대비 제거한 스택과 이유

| 제거한 것 | 이유 |
|---|---|
| node-exporter | 호스트 OS 메트릭은 이번 서사(앱/DB 병목)에 직접 기여하지 않음. JVM/DB 메트릭으로 충분. |
| AlertManager / Discord 웹훅 | 1인 실험 환경, 장애 알림보다 측정/분석에 집중. |
| Chart.js 결과 페이지 초기 필수화 | React 단일 페이지 내 간단 렌더링으로 대체, 필수 아님. |
| Grafana (v4.3.2에서 추가 제거) | Prometheus UI(PromQL)로 수치 조회 충분. 1인 프로젝트에서 대시보드 구성 비용 대비 포폴 서사 기여가 낮음. 포폴의 핵심은 before/after 수치 비교이지 대시보드 시각화가 아님. |

> 로깅과 기본 메트릭은 "취업시장에서 요구하는 수준"이므로 유지. 단 대시보드 대신 PromQL 쿼리 리스트(14.4)와 `docs/performance-report.md`의 before/after 표를 포폴의 정량 증빙으로 삼는다.

### 6.2. 패키지 구조

최상위를 `common`과 `domain`으로 이원화. `common`에는 **도메인을 알 필요가 없는 횡단 관심사**(설정/예외/로깅/보안/공통 DTO·Entity)만 두고, `domain`은 비즈니스 경계별로 분리한다. 각 도메인 내부는 전통적 `controller / service / repository / entity / dto` 레이어링.

```
com.sst.flaggame
├─ common/                       횡단 관심사. 도메인 의존 금지.
│  ├─ config/                    SecurityConfig, JwtConfig, RedisConfig,
│  │                             SseExecutorConfig, MeterRegistryConfig
│  ├─ dto/                       ApiResponse<T>, ErrorResponse (공통 응답 포맷)
│  ├─ entity/                    BaseEntity (createdAt / updatedAt 공통 컬럼)
│  ├─ exception/                 ErrorCode (enum), CustomException 베이스,
│  │                             GlobalExceptionHandler (@RestControllerAdvice)
│  ├─ logging/                   LogContextFilter, UserIdMdcFilter, MaskingPatternLayout
│  └─ security/                  JwtTokenProvider, OAuth2SuccessHandler,
│                                AppUserPrincipal, AdminApiReadinessFilter (I2),
│                                IpRateLimitFilter (Bucket4j, M3)
└─ domain/
   ├─ auth/                      OAuth2 콜백 처리, JWT 발급/갱신
   │  ├─ controller/             AuthController (/api/auth/refresh, OAuth2 콜백)
   │  ├─ service/                AuthService
   │  └─ dto/
   ├─ user/                      유저 조회, 관리자 블록
   │  ├─ controller/             UserController (/api/me)
   │  ├─ service/                UserService
   │  ├─ repository/             UserRepository
   │  ├─ entity/                 User
   │  └─ dto/
   ├─ event/                     이벤트 라이프사이클, 스케줄러, 시상
   │  ├─ controller/             AdminEventController, EventController
   │  ├─ service/                EventService, EventScheduler, AwardService
   │  ├─ repository/             EventRepository, EventAwardRepository
   │  ├─ entity/                 Event, EventAward
   │  └─ dto/
   ├─ throne/                    찬탈 핵심 도메인
   │  ├─ controller/             ThroneController (/api/events/active/claim)
   │  ├─ service/                ThroneService, AggregationCache, ClaimAuditService
   │  │                          (Phase 3: ThroneEventConsumer, RedisLuaExecutor 추가)
   │  ├─ repository/             ReignRepository, CurrentThroneRepository,
   │  │                          ThroneClaimRepository, CooldownRepository,
   │  │                          EventParticipantRepository
   │  ├─ entity/                 ThroneReign, CurrentThrone, ThroneClaim,
   │  │                          Cooldown, EventParticipant
   │  ├─ state/                  ThroneState (immutable record), ThroneChangedEvent
   │  └─ dto/                    ClaimRequest, ClaimResult
   ├─ leaderboard/               5분 스냅샷 배치 (Phase 3에서 Redis ZSET으로 대체)
   │  ├─ service/                LeaderboardBatchService
   │  ├─ repository/             LeaderboardSnapshotRepository
   │  └─ entity/                 LeaderboardSnapshot
   └─ sse/                       SSE Emitter 관리 + Publisher
      ├─ controller/             SseController (/api/events/active/stream)
      └─ service/                SseEmitterRegistry, SsePublisher
FlagGameApplication.java
```

**설계 원칙**:
- `common`은 "도메인을 모르는 것만" 둔다. 필터·설정·예외 베이스·로깅·보안·공통 응답 포맷·BaseEntity가 전부. 도메인 엔티티나 서비스를 `import`하는 순간 `common` 자격을 잃는다.
- **필터는 `common/security/`에 편입**(`AdminApiReadinessFilter`, `IpRateLimitFilter` 등). 별도 `filter/` 패키지를 두지 않고 책임 맥락(보안) 아래에 묶는다.
- **공통 `dto`, `entity`**: `ApiResponse<T>` / `ErrorResponse` 같은 모든 컨트롤러가 쓰는 응답 포맷은 `common/dto/`. `BaseEntity`(JPA `@MappedSuperclass`로 `createdAt`/`updatedAt` 공통) 같은 최상위 추상 클래스만 `common/entity/`. 구체 엔티티는 각 도메인 안에 둔다.
- `domain`은 **비즈니스 경계별 패키지**가 최상위. `controller / service / repository / entity`는 각 도메인 **내부**에만 존재한다. 전통적 레이어 최상위 분할(`controller.*`, `service.*`)은 도메인 경계를 가로질러 응집도가 떨어지므로 피한다.
- `throne/state/`에 `ThroneState` record와 `ThroneChangedEvent`를 분리 — 9.1-bis에서 다루는 JVM 메모리 상태가 여기에 응집된다.
- Phase 3에서 추가되는 Consumer·Lua Executor는 기존 `throne/service/`에 **그대로 추가**. 도메인 경계를 바꾸지 않는 것이 Phase 전환의 일관성을 보장한다.
- `event_participants` 엔티티는 `throne` 도메인 소속 — 집계 대상이 "왕좌 찬탈 누적"이라 throne 도메인의 응집된 상태다. `event` 도메인은 이벤트 자체의 라이프사이클·시상만 다룬다.

---

## 7. 인프라 & 배포

### 7.1. 트래픽 추정 (200명 기준)

| 항목 | 계산 | 결과 |
|---|---|---|
| 지속 TPS | 200명 × (1/3.5s) | ≈ 57 TPS |
| 피크 TPS | 시작 순간 200명 동시 클릭 | 200 TPS |
| 네트워크 | 찬탈(요청+응답 ~1KB) × 200 + SSE(이벤트당 ~300B × 200 연결) + 기타 | ~6 Mbps |
| DB QPS (Phase 1) | 피크 200 TPS 중 성공 ~40% (쿼리 8개: 쿨타임 선체크 SELECT, 쿨타임 더블체크 SELECT, event SELECT, closeReign UPDATE, openReign INSERT, current_throne UPDATE, cooldowns upsert, throne_claims INSERT) + 실패 ~60% (쿼리 2~3개) | 약 1,100~1,500 QPS peak |

> 실제 베이스라인 측정(Day 8) 때 PromQL `mysql_global_status_questions` 증가율로 검증한다. 개선 #2(감사 로그 배치)와 개선 #1(이벤트 상태/현재 왕 캐시) 적용 시 이 수치가 어떻게 떨어지는지가 포폴 수치 중 하나가 된다.

### 7.2. 메모리/CPU 산정 (t3.medium 기준)

- Spring Boot JVM: 1.3GB (heap 1GB + non-heap 300MB)
- MySQL: 500MB (innodb_buffer_pool 256MB)
- Redis (Phase 3): 100MB
- Prometheus: 400MB
- Nginx: 50MB
- OS: 300MB
- **Phase 1 합계: ~2.55GB / 4GB**, **Phase 3 포함: ~2.65GB / 4GB**, **2 vCPU 적정**

### 7.3. 인스턴스 선택

| 등급 | 인스턴스 | 비용 | 판단 |
|---|---|---|---|
| **권장** | **t3.medium (2 vCPU/4GB)** | $1/day | 메모리 여유로 관측 스택 포함 가능 |
| 최소 | t3.small | $0.5/day | 부하 시 OOM 위험, 추천 안 함 |

> 인스턴스 사양은 v3과 동일 유지. Grafana 제거 덕분에 메모리 여유가 더 커졌다.

### 7.4. 배포 아키텍처 (Phase 1 기준)

```
┌─────────────────────────────────────────────┐
│  EC2 t3.medium (ap-northeast-2)             │
│                                             │
│  ┌──────────────────────────────────────┐   │
│  │ Nginx (443)                          │   │
│  │  proxy_buffering off (SSE)           │   │
│  │  proxy_read_timeout 300s             │   │
│  └──────────────┬───────────────────────┘   │
│                 │                           │
│  ┌──────────────▼───────────────────────┐   │
│  │ Spring Boot (:8080)                  │   │
│  │  - OAuth2 Client (GitHub)            │   │
│  │  - Caffeine (in-JVM)                 │   │
│  │  - ReentrantLock per event           │   │
│  └──────────────┬───────────────────────┘   │
│                 │                           │
│  ┌──────────────▼───────────────────────┐   │
│  │ MySQL 8 (:3306)                      │   │
│  │  (Phase 3에서 Redis 추가)            │   │
│  └──────────────────────────────────────┘   │
│                                             │
│  ┌──────────────────────────────────────┐   │
│  │ Prometheus (:9090)                   │   │
│  │  /actuator/prometheus scrape (5s)    │   │
│  │  PromQL UI로 직접 조회               │   │
│  └──────────────────────────────────────┘   │
│                                             │
│  Volume: /var/throne-war/                   │
│    ├─ logs/app.log                          │
│    └─ mysql/                                │
└─────────────────────────────────────────────┘
```

### 7.5. 보안/네트워크

- 22 (SSH): 본인 IP만
- 443, 80: 공개
- 3306, 6379, 9090: localhost only

### 7.6. OAuth2 콜백 URL

- `https://<도메인>/login/oauth2/code/github`
- GitHub OAuth App에 등록 필요 (README에 셋업 절차 명시).

---

## 8. 데이터 모델 (MySQL)

### 8.1. 테이블 개요

```
users                    — GitHub OAuth로 생성된 사용자
events                   — 이벤트 마스터 (관리자가 생성)
event_participants       — 이벤트별 참가자 (이벤트에 처음 찬탈 시 insert)
throne_reigns            — 왕좌 보유 이력 (각 reign의 시작/종료)
throne_claims            — 찬탈 시도 append-only 로그 (성공/실패 모두)
cooldowns                — 유저별 현재 쿨타임 (expires_at)
leaderboard_snapshot     — 5분 주기 리더보드 집계 결과
event_awards             — finalize 시 계산된 시상 결과
```

### 8.2. 스키마 (요약)

```sql
-- 사용자
CREATE TABLE users (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  github_id    BIGINT NOT NULL UNIQUE,
  login        VARCHAR(64) NOT NULL,
  avatar_url   VARCHAR(512),
  role         ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',
  blocked      BOOLEAN NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_users_login (login)
) ENGINE=InnoDB;

-- 이벤트
CREATE TABLE events (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(128) NOT NULL,
  status        ENUM('DRAFT','RUNNING','ENDED','FINALIZED') NOT NULL,
  cooldown_ms   INT NOT NULL DEFAULT 3000,
  duration_h    INT NOT NULL DEFAULT 24,
  started_at    TIMESTAMP NULL,
  ends_at       TIMESTAMP NULL,
  ended_at      TIMESTAMP NULL,
  finalized_at  TIMESTAMP NULL,
  created_by    BIGINT NOT NULL,
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (created_by) REFERENCES users(id),
  INDEX idx_events_status (status)
) ENGINE=InnoDB;
```

> **RUNNING 1개 제약 (M12) — 앱 레이어 보강**: "동시 RUNNING 이벤트는 최대 1개"는 비즈니스 invariant이며, DB 보강은 하지 않는다. 대신 **`/start` 트랜잭션 시작 시 `SELECT id FROM events WHERE status='RUNNING' FOR UPDATE`로 RUNNING 개수를 체크 + 1건 이상이면 `409 ALREADY_RUNNING` 반환**. 이 쿼리는 `idx_events_status`를 활용하므로 성능 부담 없음. DB 레벨 `generated column` + `UNIQUE` 보강은 MySQL 버전 의존성이 있어 19장 "추후 개발 예정"으로 이관.

```sql
-- 이벤트별 참가자 (집계 캐시 테이블 역할)
CREATE TABLE event_participants (
  event_id          BIGINT NOT NULL,
  user_id           BIGINT NOT NULL,
  total_hold_ms     BIGINT NOT NULL DEFAULT 0,   -- 누적 보유 시간
  claim_success_cnt INT    NOT NULL DEFAULT 0,   -- 찬탈 성공 횟수
  longest_reign_ms  BIGINT NOT NULL DEFAULT 0,   -- 단일 reign 최장
  joined_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (event_id, user_id),
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (user_id)  REFERENCES users(id),
  INDEX idx_ep_event_total (event_id, total_hold_ms DESC)  -- 리더보드 커버링
) ENGINE=InnoDB;

-- 왕좌 보유 이력 (한 reign = 한 행)
CREATE TABLE throne_reigns (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id    BIGINT NOT NULL,
  user_id     BIGINT NOT NULL,
  started_at  TIMESTAMP(3) NOT NULL,
  ended_at    TIMESTAMP(3) NULL,         -- null이면 현재 왕
  duration_ms BIGINT NULL,               -- ended_at - started_at
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (user_id)  REFERENCES users(id),
  INDEX idx_reign_event_user (event_id, user_id),
  INDEX idx_reign_event_ended (event_id, ended_at)
) ENGINE=InnoDB;

-- 현재 왕 (이벤트당 1행) — Source of Truth (메모리 throneMap의 복원 소스)
CREATE TABLE current_throne (
  event_id    BIGINT PRIMARY KEY,
  reign_id    BIGINT NOT NULL,
  user_id     BIGINT NOT NULL,
  held_since  TIMESTAMP(3) NOT NULL,
  version     BIGINT NOT NULL DEFAULT 0,  -- 낙관적 락 실험(10.8 A3)용 예비 컬럼
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (reign_id) REFERENCES throne_reigns(id),
  FOREIGN KEY (user_id)  REFERENCES users(id)
) ENGINE=InnoDB;
```

> **초기 왕 정책 (SYSTEM seed)**: `current_throne.reign_id`, `user_id`가 `NOT NULL`이므로, 이벤트 시작 시 seed된 `SYSTEM` 사용자(`id = 1`, `login = 'SYSTEM'`, `role = 'ADMIN'`, `github_id = 0`)로 초기 reign을 1건 생성하고 `current_throne` 1행을 채운다. 이후 첫 성공 찬탈이 이 reign을 종료하고 실제 참가자 reign으로 전환한다. SYSTEM 유저 식별은 **`id == 1` 상수**로만 한다 — `role`이나 `login`이 아닌 PK 상수로 식별하는 것이 가장 단순하고 변경 가능성이 낮다. SYSTEM seed는 `V2__seed_system_user.sql` Flyway 마이그레이션으로 삽입.
>
> **SYSTEM 필터링 규칙** (네 군데에서 반드시 제외):
> 1. `AggregationCache.addReignEnd(eventId, prevUserId, heldMs)`: `prevUserId == SYSTEM_USER_ID`이면 early return. `event_participants`에 SYSTEM row가 생기지 않도록 한다.
> 2. `participantRepo.applyDeltaBatch(drained)`: flush 경로에서도 `user_id == SYSTEM_USER_ID` 방어 필터 (addReignEnd에서 이미 걸러지지만 이중 안전장치).
> 3. 리더보드 조회(`participantRepo.findTopByEventId`): `WHERE user_id <> 1` 조건. snapshot 저장 시에도 동일.
> 4. 시상 계산(`AwardService`): `LONGEST_REIGN`/`USURPER`/`LAST_KING` 모두 `user_id <> 1`로 필터. `LAST_KING`이 SYSTEM이 되는 경우(아무도 찬탈 안 함)는 "수상자 없음"으로 기록하고 summary의 `awards.lastKing`을 `null`로 반환.

```sql
-- 찬탈 시도 로그 (성공/실패, append-only)
-- NOTE: FK를 의도적으로 걸지 않는다. 고볼륨 append-only 경로이며, Phase 2 개선 #2(배치 INSERT)와
--       Phase 3(Redis 핫패스 → Consumer INSERT)에서 FK 제약이 처리량 상한으로 작용하는 것을 회피한다.
--       참조 무결성은 애플리케이션 레이어에서 보장한다 (찬탈 성공/실패 로그는 반드시 유효한 user/event에서만 발생).
-- NOTE: NO_ACTIVE_EVENT는 event_id가 없으므로 이 테이블에 기록하지 않는다. 대신 Counter 메트릭으로 집계.
--       이 정책으로 인해 event_id는 항상 유효한 events.id를 가리킨다(FK는 없지만 애플리케이션 보장).
CREATE TABLE throne_claims (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id     BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  result       ENUM('SUCCESS','COOLDOWN','ALREADY_OWNER','NOT_RUNNING') NOT NULL,
  requested_at TIMESTAMP(3) NOT NULL,
  INDEX idx_claims_event_time (event_id, requested_at),
  INDEX idx_claims_user (event_id, user_id)
) ENGINE=InnoDB;

-- 쿨타임
CREATE TABLE cooldowns (
  event_id    BIGINT NOT NULL,
  user_id     BIGINT NOT NULL,
  expires_at  TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (event_id, user_id),
  INDEX idx_cd_expires (expires_at)
) ENGINE=InnoDB;

-- 리더보드 스냅샷 (5분 배치)
CREATE TABLE leaderboard_snapshot (
  event_id      BIGINT NOT NULL,
  rank_no       INT NOT NULL,
  user_id       BIGINT NOT NULL,
  total_hold_ms BIGINT NOT NULL,
  captured_at   TIMESTAMP NOT NULL,
  PRIMARY KEY (event_id, rank_no, captured_at),
  INDEX idx_lb_latest (event_id, captured_at DESC)
) ENGINE=InnoDB;

-- 시상
CREATE TABLE event_awards (
  event_id    BIGINT NOT NULL,
  award_type  ENUM('LONGEST_REIGN','USURPER','LAST_KING') NOT NULL,
  user_id     BIGINT NOT NULL,
  metric_ms   BIGINT,
  metric_cnt  INT,
  PRIMARY KEY (event_id, award_type)
) ENGINE=InnoDB;
```

### 8.3. 인덱스 설계 근거

| 인덱스 | 목적 |
|---|---|
| `event_participants(event_id, total_hold_ms DESC)` | 리더보드 Top N 커버링 인덱스 |
| `throne_reigns(event_id, ended_at)` | 이벤트 종료 시 "마지막 왕" 조회 |
| `throne_claims(event_id, requested_at)` | 시간대별 집계 (통계) |
| `cooldowns(expires_at)` | 만료된 쿨타임 일괄 삭제 (스케줄러) |

> **운영상의 알려진 한계**: `throne_claims`는 append-only 로그이므로 이벤트 당일 피크 트래픽에서 빠르게 증가할 수 있다. 본 프로젝트는 Phase 2의 배치 INSERT와 Phase 3의 Redis 핫패스 전환 전후 비교를 우선 목표로 하며, 장기 운영 시에는 파티셔닝 또는 보관 정책을 추가한다.

---

## 9. Phase 1 — MVP 설계

**목표**: 동작하는 최소 버전. 과한 최적화 없이, 단순하고 이해하기 쉬운 코드로.

### 9.1. 찬탈 로직 (MVP 버전)

Phase 1은 **이벤트당 단일 락 + 단일 트랜잭션**으로 찬탈을 순차 처리한다. 락을 먼저 잡고 트랜잭션을 내부에서 명시적으로 연다 — 이 순서가 반대가 되면 락 해제와 커밋 사이에 경합이 생긴다.

**`/api/events/active/claim`의 활성 이벤트 탐색**: 매 요청마다 DB를 치지 않기 위해, `EventService`가 `AtomicReference<Long> currentActiveEventId`를 보관한다. 이 값은 이벤트 시작(`/start`), 종료(`/end`, 스케줄러 자동 종료) 시점에만 set/clear된다. 서버 부팅 시 `@PostConstruct`에서 `SELECT id FROM events WHERE status='RUNNING'`으로 복원한다(동시 RUNNING은 1개 제약). null이면 컨트롤러에서 즉시 `404 NO_ACTIVE_EVENT` 반환하고, `throne_claim_total{result="NO_ACTIVE_EVENT",event_id="none"}` Counter만 증가시킨다(`throne_claims` DB 기록 없음 — I3).

```java
// 상태 객체 — immutable record. throneMap에는 "교체(put)"로만 갱신.
public record ThroneState(long reignId, long userId, Instant heldSince) {}

@Component
public class ThroneService {
    // 이벤트당 락 (JVM 메모리)
    private final ConcurrentHashMap<Long, ReentrantLock> lockMap = new ConcurrentHashMap<>();
    // 이벤트당 현재 왕 상태 — immutable record, put으로 교체. Source of Truth는 MySQL current_throne.
    private final ConcurrentHashMap<Long, ThroneState> throneMap = new ConcurrentHashMap<>();

    private final TransactionTemplate tx;  // propagation=REQUIRED, timeout=3
    // ... 기타 의존성

    public ClaimResult claim(long eventId, long userId) {
        // 1. 쿨타임 선체크 (락 바깥, PK 단건 조회) — B1: 메트릭 관찰 전용.
        //    fast-return 하지 않는다. 이유:
        //    - 본인이 방금 왕이 된 상태로 재클릭하면 COOLDOWN과 ALREADY_OWNER가 동시에 매칭됨.
        //    - 락 안에서 `NOT_RUNNING → ALREADY_OWNER → COOLDOWN` 순으로 판정해야
        //      "본인 재클릭은 ALREADY_OWNER" UX 규칙(C2)이 유지된다.
        //    - 따라서 선체크는 "곧 COOLDOWN일 가능성이 높다"는 힌트만 메트릭에 남기고
        //      실제 rejection은 락 안에서만 수행한다.
        Instant now = Instant.now();
        Cooldown cd = cooldownRepo.findActive(eventId, userId, now);
        if (cd != null) {
            fastRejectHintCounter.increment();  // throne_fast_reject_hint_total
            // NOTE: 실제 판정은 락 안에서. 여기서 throw하지 않는다.
        }

        // 2. 이벤트별 락 획득 — 큐에 들어가 순서대로 처리됨
        ReentrantLock lock = lockMap.computeIfAbsent(eventId, k -> new ReentrantLock(true));
        lock.lock();
        try {
            // 3. 트랜잭션 내부 작업
            return tx.execute(status -> doClaim(eventId, userId));
        } finally {
            lock.unlock();
        }
    }

    // 락을 이미 보유한 상태에서 호출됨. 트랜잭션 경계 안.
    // 판정 순서: NOT_RUNNING → ALREADY_OWNER → COOLDOWN 더블체크 → 성공 처리
    //   ALREADY_OWNER가 COOLDOWN보다 앞에 있어야 "방금 왕이 된 본인 재클릭" 시
    //   COOLDOWN이 아닌 ALREADY_OWNER를 반환한다 (C2).
    private ClaimResult doClaim(long eventId, long userId) {
        Instant now = Instant.now();

        // 4. 메모리에서 현재 왕 조회 (서버 부팅/이벤트 시작 시 미리 로딩됨, 9.1-bis 참조)
        ThroneState throne = throneMap.get(eventId);
        if (throne == null) {
            // 컨트롤러에서 이미 NO_ACTIVE_EVENT 필터링되지만, 레이스 대비 방어.
            // NO_ACTIVE_EVENT는 DB에 기록하지 않는다(FR-03-1). 예외만 던지고 컨트롤러에서 카운터.
            throw new NoActiveEventException();
        }

        // 5. 이벤트 상태 검증 (락 대기 중 admin이 /end를 호출했을 수 있음)
        Event event = eventRepo.findById(eventId).orElseThrow();
        if (event.getStatus() != RUNNING) {
            claimAudit.recordFailureInNewTx(eventId, userId, NOT_RUNNING, now);
            throw new NotRunningException();  // → 409 NOT_RUNNING
        }

        // 6. ALREADY_OWNER 체크 (COOLDOWN 더블체크보다 먼저!)
        if (throne.userId() == userId) {
            claimAudit.recordFailureInNewTx(eventId, userId, ALREADY_OWNER, now);
            throw new AlreadyOwnerException();
        }

        // 7. 쿨타임 더블체크 (락 바깥 체크 이후 같은 유저의 다른 요청이 먼저 성공해 쿨타임을 생성했을 케이스 방어)
        Cooldown cd = cooldownRepo.findActive(eventId, userId, now);
        if (cd != null) {
            claimAudit.recordFailureInNewTx(eventId, userId, COOLDOWN, now);
            throw new CooldownException(cd.remainingMs(now));
        }

        // 8. DB 영속화 — 트랜잭션 안에서 완료
        long prevUserId = throne.userId();
        long heldMs = Duration.between(throne.heldSince(), now).toMillis();

        // closeReign은 조건부 UPDATE (C3): ended_at IS NULL인 행만 갱신.
        // row 수가 1이 아니면 invariant 위반 → 롤백.
        int closed = reignRepo.closeReign(throne.reignId(), now, heldMs);
        if (closed != 1) {
            throw new IllegalStateException(
                "closeReign affected " + closed + " rows for reignId=" + throne.reignId()
                + " (expected 1). throneMap/DB invariant violated.");
        }

        long newReignId = reignRepo.openReign(eventId, userId, now);  // DB AUTO_INCREMENT (Phase 1)
        currentThroneRepo.update(eventId, newReignId, userId, now);
        cooldownRepo.upsert(eventId, userId, now.plusMillis(event.getCooldownMs()));

        // 9. 집계는 Caffeine write-back (동기 UPDATE 금지)
        //    SYSTEM 필터는 AggregationCache 내부에서 처리 (9.2 참조).
        aggregationCache.addReignEnd(eventId, prevUserId, heldMs);
        aggregationCache.addClaimSuccess(eventId, userId);

        // 10. 메모리 상태 갱신 (DB와 원자적으로 묶이도록 afterCommit에서 수행)
        //     롤백되면 메모리도 변경되지 않아야 함.
        ThroneState next = new ThroneState(newReignId, userId, now);
        ThroneChangedEvent evt = new ThroneChangedEvent(eventId, userId, newReignId, now);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                throneMap.put(eventId, next);                    // immutable 교체
                claimAudit.recordSuccess(eventId, userId, now);  // 성공 로그
                ssePublisher.publishThroneChanged(evt);          // SSE 동기 발행 (Phase 2에서 비동기화)
            }
        });

        return ClaimResult.success(newReignId);
    }
}
```

**`closeReign` 리포지토리 쿼리 (C3)**

```java
// ReignRepository
@Modifying
@Query("""
    UPDATE ThroneReign r
       SET r.endedAt = :endedAt,
           r.durationMs = :durationMs
     WHERE r.id = :id
       AND r.endedAt IS NULL
""")
int closeReign(@Param("id") long id,
               @Param("endedAt") Instant endedAt,
               @Param("durationMs") long durationMs);  // row count 반환
```

**설계 주석**

1. **락을 트랜잭션 바깥에서 획득**하고 내부에서 `TransactionTemplate`로 트랜잭션을 연다. `@Transactional` 메서드 안에서 `lock.lock()/unlock()`을 하면 **락 해제 시점과 커밋 시점이 어긋나** 다음 스레드가 커밋 전의 상태를 볼 수 있다. 이 구조에서는 락 보유 = 트랜잭션 완전 종료 시까지이므로 그런 틈이 없다.
2. **메모리 갱신을 `afterCommit`으로 미룬다**. 트랜잭션이 롤백된 경우 메모리도 바뀌지 않아야 DB와 메모리의 정합성이 유지된다. 다만 락은 아직 내가 보유 중이므로(트랜잭션 종료 → 락 해제 순서), 다음 스레드는 메모리 갱신이 완료된 뒤에 진입한다.
3. **`ThroneState`는 immutable record**. 락을 보유하지 않은 경로(읽기 API 등)가 `throneMap.get(eventId)`로 조회하더라도 필드의 torn read / stale half-update가 발생하지 않는다. 갱신은 `throneMap.put(eventId, new ThroneState(...))`로 참조 교체만 일어난다(`ConcurrentHashMap`의 put은 원자적).
4. **reignId는 DB AUTO_INCREMENT 유지 (Phase 1)**. 앱 레벨 카운터는 재기동 시 리셋 위험이 있어 쓰지 않는다. Phase 3에서는 Redis INCR로 전환되며, 이때 `throne_reigns.id`의 AUTO_INCREMENT를 끄고 Redis가 확정한 id를 그대로 INSERT한다 (11.2 참조).
5. **락 바깥 선체크는 메트릭 힌트 전용 (B1)**. v4.3.4까지는 락 바깥에서 `CooldownException`을 즉시 던졌으나, 이는 "본인이 방금 왕이 된 직후 재클릭"에서 C2의 판정 순서(`ALREADY_OWNER`가 `COOLDOWN`보다 먼저)를 깨는 결함이 있었다. v4.3.5부터 선체크는 `throne_fast_reject_hint_total` Counter만 증가시키고 진행 — 실제 rejection은 락 안의 `NOT_RUNNING → ALREADY_OWNER → COOLDOWN` 순서에서 결정된다. 선체크의 의의는 "락 대기 없이도 곧 COOLDOWN이 될 확률"을 관측 가능하게 남기는 것이다.
6. **판정 순서 `NOT_RUNNING → ALREADY_OWNER → COOLDOWN` (C2)**. `ALREADY_OWNER`를 `COOLDOWN` 더블체크보다 먼저 두는 이유: 방금 왕이 된 본인이 동일 요청으로 재클릭하면 쿨타임과 소유권이 동시에 맞는데, UX상 "당신은 이미 왕입니다"가 "쿨타임 중"보다 정확하다. 또한 `throne_claims` 결과 분포에서 "본인 왕 상태에서의 의도된 클릭"을 `ALREADY_OWNER`로 집계해 통계 의미를 살린다.
7. **`closeReign`은 조건부 UPDATE (C3)**. `WHERE id=? AND ended_at IS NULL` 조건으로 invariant를 DB 레벨에서도 방어한다. row 수가 1이 아니면 `IllegalStateException`으로 롤백하여, 메모리-DB 불일치 상태가 영속화되지 않도록 한다.
8. **`ReentrantLock(true)` 공정 모드**: FIFO로 먼저 온 요청이 먼저 처리된다. 이벤트 참가자의 "먼저 누른 사람이 왕이 된다"는 직관과 맞는다.

**MVP의 의도적 단순화**
- ❌ DB 레벨 행 락 없음 — JVM ReentrantLock으로 메모리에서 직렬화
- ❌ 감사 로그 배치 큐 없음 — 성공/실패 모두 개별 INSERT
- ❌ 읽기 캐시 없음
- ❌ SSE 비동기 브로드캐스트 없음
- ❌ 락 경합 실패 응답 없음 — 요청은 반드시 결과가 나온다 (UX 단순화)

**MVP가 지키는 핵심 3가지**
- ✅ **이벤트당 공정(fair) ReentrantLock** — FIFO 순차 처리, DB 커넥션 블로킹 없음
- ✅ **집계 Caffeine write-back** — `event_participants` UPDATE 핫스팟 회피
- ✅ **성공 로그/SSE/메모리 갱신은 `afterCommit`** — 롤백 시 외부 관측 및 메모리가 원복

**트레이드오프** (README 기재)
- ✅ UX 단순화 — 버튼을 누르면 반드시 결과가 나온다
- ✅ DB 커넥션 고갈 없음 — JVM 스레드만 대기
- ⚠️ **이벤트당 직렬화로 찬탈 p99가 VU 수에 비례하여 증가** → Phase 2에서 수치로 입증하고, Phase 3 Redis Lua로 해결
- ⚠️ 단일 인스턴스 전제 — 멀티 인스턴스 확장 불가 (Phase 3 Redis Pub/Sub 도입 이유)
- ⚠️ 크래시 시 `throneMap` 유실 → 서버 재기동 시 `current_throne` + `throne_reigns`에서 복원(9.1-bis)

### 9.1-bis. `throneMap` / `lockMap` 라이프사이클

`throneMap`과 `lockMap`은 JVM 메모리 상태이므로 아래 4개 시점에 일관되게 관리한다.

**(a) 이벤트 시작 시** (관리자 `/events/{id}/start` 또는 스케줄러)
- `current_throne` DB row를 seed `SYSTEM` 사용자(PK=1)로 생성
- `throneMap.put(eventId, new ThroneState(seedReignId, SYSTEM_USER_ID, now))`
- `lockMap.computeIfAbsent(eventId, ...)`
- `EventService.currentActiveEventId.set(eventId)` — 컨트롤러의 active 탐색 경로

**(b) 이벤트 `/end` 시** (RUNNING → ENDED — 관리자 `/end` 또는 자동 스케줄러)
- `lockMap.remove(eventId)` 실행 (M13) — 이후 `claim` 요청은 `NOT_RUNNING` 또는 `NO_ACTIVE_EVENT`로 reject됨
- `throneMap`은 **유지** — ENDED 상태에서도 "마지막 왕" 조회 경로가 필요
- `EventService.currentActiveEventId.compareAndSet(eventId, null)` — active 탐색 캐시 클리어
- Phase 3 추가: `HSET event:{id}:meta status ENDED` (11.2-bis)

**(c) 이벤트 finalize 시** (`/events/{id}/finalize`)
- 15.2 finalize 처리 마지막 단계에서 `throneMap.remove(eventId)` 실행
- `lockMap.remove`는 이미 (b)에서 수행됐으므로 생략 (M13)
- 메모리 누수 방지
- `EventService.currentActiveEventId.compareAndSet(eventId, null)` — ENDED 전환 시점에 이미 null이 되어 있는 것이 정상이지만 방어적으로 한 번 더.

**(d) 서버 부팅 시** (`@PostConstruct` 또는 `ApplicationReadyEvent`)
- `SELECT event_id, reign_id, user_id, held_since FROM current_throne ct JOIN events e ON ct.event_id = e.id WHERE e.status = 'RUNNING'`
- 조회 결과를 `throneMap`에 모두 put (immutable `ThroneState`로)
- `EventService.currentActiveEventId`도 동일 쿼리로 복원
- 크래시 후 재기동 시 이 복원 경로로 진행 중 이벤트가 자동 복구된다

> **`ReentrantLock`의 공정 모드는 JVM 내부에서만 유효하다.** 서버 재기동 시 대기 중이던 요청은 모두 끊기지만, 클라이언트 재시도 시 새 락 큐에 다시 줄을 선다. 재기동 중 유실된 인메모리 상태는 `throne_reigns` 이력으로 사후 재집계 가능하다.

> **Invariant 명세 (I1 — `throne_reigns` 단일 열린 reign)**: 이벤트당 `ended_at IS NULL`인 `throne_reigns` 행은 **항상 정확히 1개**이다(이벤트가 RUNNING인 동안). 이 invariant는 다음 세 가지로 방어된다:
> 1. 정상 경로: `closeReign(oldId, ...)` 성공 후에만 `openReign(...)`이 실행되는 트랜잭션 순서 (9.1 코드 스텝 8).
> 2. DB 방어: `closeReign`의 `WHERE id=? AND ended_at IS NULL` 조건 + row 수 1 검증으로 덮어쓰기 차단 (9.1 코드 스텝 8, C3).
> 3. 디버그 sanity check: `@Profile("dev")` 전용 `InvariantChecker.verifyOpenReignSingleton(eventId)`가 매 찬탈 후 `SELECT COUNT(*) FROM throne_reigns WHERE event_id=? AND ended_at IS NULL`을 확인하고, 1이 아니면 ERROR 로그 + 메트릭 `throne_invariant_violation_total` 증가. 운영 프로파일에서는 성능을 위해 비활성.
>
> MySQL functional unique index로 강제하는 방안은 공식 `CREATE UNIQUE INDEX ... WHERE` 지원 부재로 도입 보류. Phase 3 마이그레이션 시 재검토.

> **Readiness (I2)**: `ApplicationReadyEvent` 수신 전까지 관리자 API(`/api/admin/events/**`)와 찬탈 API(`/api/events/active/claim`)를 **503 SERVICE_UNAVAILABLE**로 reject하는 `ApiReadinessFilter`를 도입. 이유:
> - 부팅 중 `@PostConstruct`가 `currentActiveEventId`와 `throneMap`을 복원하는 동안, admin이 `/start`를 호출하면 복원과 set 순서가 어긋날 수 있다.
> - 찬탈 요청이 복원 완료 전에 들어오면 `throneMap.get`이 null을 반환하여 `NoActiveEventException`이 오발 발생.
>
> 필터는 `AtomicBoolean ready` 플래그로 간단히 구현하며, 읽기 API(리더보드 조회, `/api/me`)는 readiness와 무관하게 허용(DB만 있으면 동작).

### 9.2. Caffeine Write-Back 집계 (MVP 필수 유지)

Phase 1에서 **유일하게 처음부터 적용하는 "최적화"**. 집계 UPDATE를 write-back 없이 매 찬탈마다 하면 `event_participants`가 락 경합의 또 다른 핫스팟이 되어 찬탈 API 전체가 느려진다.

```java
private static final long SYSTEM_USER_ID = 1L;

// AggregationDelta는 immutable value. merge로 누적.
// - totalHoldMs: 누적 보유 시간 (합)
// - claimSuccessCnt: 성공 횟수 (합)
// - maxReignMs: 단일 reign 최장 (max) — event_participants.longest_reign_ms 갱신에 사용
public record AggregationDelta(long totalHoldMs, int claimSuccessCnt, long maxReignMs) {
    public static AggregationDelta ofHold(long ms)   { return new AggregationDelta(ms, 0, ms); }
    public static AggregationDelta ofClaim()         { return new AggregationDelta(0, 1, 0); }
    public AggregationDelta merge(AggregationDelta o) {
        return new AggregationDelta(
            this.totalHoldMs + o.totalHoldMs,
            this.claimSuccessCnt + o.claimSuccessCnt,
            Math.max(this.maxReignMs, o.maxReignMs)
        );
    }
}

// 키: (eventId, userId), 값: AggregationDelta
private final ConcurrentHashMap<Key, AggregationDelta> pending = new ConcurrentHashMap<>();

public void addReignEnd(long eventId, long userId, long durationMs) {
    if (userId == SYSTEM_USER_ID) return;   // SYSTEM은 집계에서 제외
    pending.merge(new Key(eventId, userId),
                  AggregationDelta.ofHold(durationMs),
                  AggregationDelta::merge);
}

public void addClaimSuccess(long eventId, long userId) {
    // 성공 찬탈 주체는 정의상 SYSTEM이 될 수 없지만 방어적으로 한 번 더 필터.
    if (userId == SYSTEM_USER_ID) return;
    pending.merge(new Key(eventId, userId),
                  AggregationDelta.ofClaim(),
                  AggregationDelta::merge);
}

@Scheduled(fixedDelay = 10_000)
public void flush() {
    Map<Key, AggregationDelta> drained = new HashMap<>();
    for (Key k : pending.keySet()) {
        if (k.userId() == SYSTEM_USER_ID) continue;  // 방어 필터 (M1 — 이중 안전장치)
        AggregationDelta d = pending.remove(k);   // 원자 추출
        if (d != null) drained.put(k, d);
    }
    if (drained.isEmpty()) return;
    // Repo 내 UPDATE 문 (repo 구현에서도 user_id != SYSTEM_USER_ID 방어):
    //   UPDATE event_participants
    //     SET total_hold_ms     = total_hold_ms     + :dHold,
    //         claim_success_cnt = claim_success_cnt + :dClaim,
    //         longest_reign_ms  = GREATEST(longest_reign_ms, :dMaxReign)
    //   WHERE event_id = :e AND user_id = :u AND user_id <> 1;
    // row가 없으면 INSERT ... ON DUPLICATE KEY UPDATE (첫 참가 시 삽입, user_id != 1 가드).
    participantRepo.applyDeltaBatch(drained);
}

@PreDestroy
public void finalFlush() { flush(); }
```

**트레이드오프 (README 기재)**:
- ✅ `event_participants` UPDATE QPS를 1/N로 축소
- ✅ `longest_reign_ms`가 `GREATEST()`로 단일 UPDATE에서 함께 갱신됨 — 별도 쿼리 없음
- ⚠️ 최대 10초 집계 지연 — 5분 배치 리더보드에 흡수되어 UX 영향 없음
- ⚠️ 크래시 시 유실 → 원본 `throne_reigns`로 재집계 가능 (복구 스크립트 제공)

### 9.3. 쿨타임 만료 정리 (간단 유지)

`cooldowns` 테이블은 upsert 구조라 row 수가 유저 수로 제한되지만, 만료된 값이 남으면 `findActive`가 느려진다.

```java
@Scheduled(fixedDelay = 60_000)
public void purgeExpired() {
    cooldownRepo.deleteExpired(Instant.now().minusSeconds(10));
}
```

### 9.4. 리더보드 배치 (5분)

```java
@Scheduled(cron = "0 */5 * * * *")
public void snapshotLeaderboard() {
    Event running = eventRepo.findRunning().orElse(null);
    if (running == null) return;

    aggregationCache.forceFlush();   // 최신 집계 반영

    List<ParticipantRow> rows =
        participantRepo.findTopByEventIdOrderByTotalHoldDesc(running.getId(), 100);
    // captured_at을 5분 경계로 truncate (M11) — GC pause 등으로 실행 시각이 흔들려도
    // 같은 주기라면 동일한 captured_at을 쓰도록 정규화. PK 충돌 가능성을 최소화.
    Instant capturedAt = truncateTo5Minutes(Instant.now());
    // 재실행/중복 호출 방어 (I5): PK (event_id, rank_no, captured_at) 충돌 시 IGNORE.
    snapshotRepo.saveAllIgnore(toSnapshotRows(rows, running.getId(), capturedAt));

    ssePublisher.publishLeaderboardSnapshot(running.getId(), capturedAt);
}

private static Instant truncateTo5Minutes(Instant t) {
    long epochSec = t.getEpochSecond();
    long truncated = epochSec - (epochSec % 300);   // 5분 = 300초
    return Instant.ofEpochSecond(truncated);
}
```

> `saveAllIgnore`는 내부에서 `INSERT IGNORE INTO leaderboard_snapshot ...`로 구현. `captured_at` truncate와 결합하면 동일 5분 구간의 중복 호출은 PK 충돌로 조용히 skip된다.

### 9.5. SSE 퍼블리셔 (MVP — 동기)

단일 인스턴스 전제. `SseEmitter` 컬렉션을 in-memory로 관리하고, **`afterCommit` 이후 같은 요청 스레드에서 동기로 발행**한다. Phase 2에서 이 동기 발행이 문제를 일으키는지 측정한다.

```java
@Component
public class SsePublisher {
    // 유저당 다중 탭 허용 (M4). CopyOnWriteArraySet은 iterate가 잦은 broadcast 경로에 유리.
    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long userId) {
        SseEmitter em = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(em);
        em.onCompletion(() -> removeEmitter(userId, em));
        em.onTimeout(() -> removeEmitter(userId, em));
        em.onError(ex -> removeEmitter(userId, em));
        return em;
    }

    private void removeEmitter(long userId, SseEmitter em) {
        Set<SseEmitter> set = emitters.get(userId);
        if (set != null) {
            set.remove(em);
            if (set.isEmpty()) emitters.remove(userId);  // 메모리 회수
        }
    }

    public void publishThroneChanged(long eventId, long newOwnerId, long reignId, Instant at) {
        // Phase 1: 동기 루프 (Phase 2 개선 #3에서 ExecutorService로 비동기화)
        emitters.forEach((uid, set) ->
            set.forEach(em -> safeSend(em, "throne", payload(...)))
        );
    }
}
```

**의도적 단순화**: Phase 2에서 SSE 연결 수가 늘수록 찬탈 p99가 어떻게 튀는지를 측정하기 위한 baseline. 처음부터 비동기로 만들면 이 서사가 사라진다.

---

## 10. Phase 2 — 측정 & 개선 사이클

**목표**: MVP의 한계를 수치로 드러내고, 개선 하나당 "증상 → 원인 → 조치 → 효과" 사이클을 돌려 **포폴에 담을 서사 단위를 만든다**.

### 10.1. 진행 방식

```
 [Baseline 측정] → [개선 #1 적용] → [재측정] → [개선 #2] → [재측정] → [개선 #3] → [재측정]
                                                                                     ↓
                                                                         [동시성 A1 비교 실험]
                                                                                     ↓
                                                                              [종합 비교 표]
```

각 개선은 **독립 커밋 + 독립 측정 + 독립 문서화**. Phase 3 진입 전에 "MySQL + JVM 레벨에서 할 수 있는 개선은 다 해봤다"가 입증돼야 한다.

### 10.2. k6 시나리오 매트릭스

| 시나리오 | 목표 | VU | 기간 |
|---|---|---|---|
| A. 찬탈 스파이크 | `ReentrantLock` 대기열 측정 | 300 | 3분 |
| B. SSE 동시 연결 | SSE 서버 스레드/메모리 | 200 | 5분 |
| D. 믹스 (찬탈 + SSE) | 실제 이벤트 날 재현 | 200 + 100 | 5분 |
| E. 리더보드 조회 | 읽기 부하 | 100 | 2분 |

> 시나리오 C(WebSocket 동시 연결)는 19장 "추후 개발 예정"으로 이관 — SSE vs WS 정량 비교 실험이 확장 과제로 분류됐기 때문. 시나리오 번호 B, D, E는 기존 표기를 그대로 유지해 10.9 종합 비교 표와의 참조 일관성을 지킨다.

### 10.3. 시나리오 A 예시

```javascript
// k6/scenario-a-claim-spike.js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '1m',  target: 200 },
        { duration: '2m',  target: 200 },
        { duration: '30s', target: 10 },
      ],
    }
  },
  thresholds: {
    'http_req_duration{status:200}': ['p(99)<100'],   // SLO 목표 (Phase 3에서 달성)
    'http_req_failed': ['rate<0.01'],
  }
};

  export default function () {
  const token = __ENV.JWT;
  const res = http.post(
    `${__ENV.BASE}/api/events/active/claim`,
    null,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  // 200 SUCCESS / 404 NO_ACTIVE_EVENT / 409 ALREADY_OWNER | NOT_RUNNING / 429 COOLDOWN
  check(res, { 'valid response': (r) => [200, 404, 409, 429].includes(r.status) });
}
```

### 10.4. 측정 항목 (공통)

| 분류 | 지표 |
|---|---|
| API | p50/p95/p99, RPS, 에러율, 응답 코드 분포 |
| JVM | heap, GC pause, thread count |
| MySQL | QPS, slow query, `innodb_row_lock_waits`, `innodb_row_lock_time_avg` |
| HikariCP | active/idle/pending, `connection_acquisition_time` |
| Caffeine (집계) | `pending_keys`, `flush_duration` |
| ReentrantLock | 대기 시간, 큐 길이 (`throne_lock_wait_duration`, `throne_lock_queue_length`) |
| SSE/WS | 동시 연결 수, publish latency |

### 10.5. Baseline 측정 (Day 8)

MVP 상태 그대로 시나리오 A~E + D 실행. 수치를 `docs/baseline-YYYYMMDD.md`에 기록.

**예측하는 주요 증상** (실측 전 가설)
- 찬탈 p99가 VU 수 증가에 비례해 선형 증가 (직렬화 특성)
- `throne_lock_wait_duration` p99가 VU 200에서 수백 ms 예상
- SLO 100ms 미달 — 구조적 한계이므로 Phase 2 개선으로도 해결 불가 (Phase 3 Redis의 몫)
- SSE 연결 수가 올라갈수록 찬탈 p99가 함께 상승 (동기 발행 영향, 개선 #3에서 해결)
- `throne_claims` INSERT가 초당 수백 건 발생 (개선 #2에서 해결)
- NOWAIT 체제였다면 발생했을 409 응답은 **없음** — 대신 대기 시간으로 나타난다

### 10.6. 개선 사이클

각 개선은 **Baseline 대비 변화량**과 **SLO 진전도**를 표로 정리한다.

#### 개선 #1 — 현재 왕 읽기 캐시 도입

**증상**: 시나리오 E(리더보드 조회 + 현재 왕 조회)에서 읽기 p99가 쓰기 경합에 영향받아 튐.
**원인**: `GET /api/events/active`가 매 호출마다 DB. 쓰기 트랜잭션과 같은 `current_throne` 행을 조회하려 대기.
**조치**: Caffeine 읽기 캐시 (TTL 2s) + 찬탈 성공 시 즉시 `put()`.

```java
private final Cache<Long, CurrentThroneView> cache =
    Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(2))
        .recordStats()
        .build();

public CurrentThroneView get(long eventId) {
    return cache.get(eventId, id -> currentThroneRepo.findViewByEventId(id).orElse(null));
}

// 찬탈 성공 시 ThroneService에서 호출
public void putAfterClaim(long eventId, CurrentThroneView fresh) {
    cache.put(eventId, fresh);
}
```

**측정 & 문서화**: 시나리오 E 재실행. 읽기 p99 변화 + 히트율 `caffeine_current_throne_hit_ratio` 기록.

#### 개선 #2 — 감사 로그 배치 INSERT

**증상**: Baseline에서 `throne_claims` INSERT QPS가 찬탈 API QPS와 1:1. 실패 응답까지 포함하면 피크에 초당 수백.
**원인**: 모든 응답 결과를 `INSERT` 한 건씩 실행.
**조치**: 메모리 큐 + 500건/500ms 배치 워커, JDBC `rewriteBatchedStatements=true`.

```java
private final BlockingQueue<ClaimLogEntry> queue = new LinkedBlockingQueue<>(10_000);
private final AtomicLong droppedCount = new AtomicLong();
// Micrometer: claim_log_drop_total Counter에 droppedCount를 노출
// Guava com.google.common.util.concurrent.RateLimiter.create(1.0) — 초당 최대 1회 tryAcquire.
// tryLog(...)는 tryAcquire()가 true일 때만 WARN 로그 출력 (과도 로깅 방지, M2).
private final WarnLimiter warnLimiter = new WarnLimiter(1.0);  // 1 log/sec

public void enqueue(ClaimLogEntry e) {
    if (!queue.offer(e)) {
        // 큐 포화 — 감사 로그 drop을 허용하고 요청은 계속 처리.
        //   (1) 카운터 증가 (메트릭)
        //   (2) 초당 최대 1회 WARN 로그 (Guava RateLimiter 기반)
        long n = droppedCount.incrementAndGet();
        warnLimiter.tryLog("claim_log queue full, dropped total={}", n);
    }
}

@Scheduled(fixedDelay = 500)
public void drainAndInsert() {
    List<ClaimLogEntry> batch = new ArrayList<>(500);
    queue.drainTo(batch, 500);
    if (!batch.isEmpty()) claimRepo.batchInsert(batch);
}
@PreDestroy public void drainAll() { drainAndInsert(); }
```

**drop 정책**: 서비스 가용성 > 감사 로그 완결성. 큐 full은 곧 시스템 과부하 상태이므로 요청 처리를 막기보다 로그를 희생한다. drop이 발생했다면 그 자체가 용량 부족 신호로 보고서에 기록한다.

**찬탈 로직 변경**: `claimRepo.insert(...)` 호출을 `claimLogQueue.enqueue(new ClaimLogEntry(...))`로 교체.

**측정 & 문서화**: 시나리오 A 재실행. 찬탈 API p99, MySQL `Com_insert` QPS, 큐 사이즈 추이, `claim_log_drop_total`.

#### 개선 #3 — SSE 커밋 후 동기 → 비동기 브로드캐스트

**증상**: 시나리오 D(믹스)에서 찬탈 API p99가 시나리오 A 단독 대비 유의미하게 상승. SSE 연결 수에 비례.
**원인**: `afterCommit`은 **락 해제 이전에** 같은 요청 스레드에서 실행된다. Phase 1의 `publishThroneChanged`는 동기 루프로 모든 `SseEmitter`에 write를 수행하므로, **SSE I/O 시간 전체가 락 보유 시간에 포함된다**. 200 연결이면 200번의 write가 직렬화 큐 앞에서 "자리 차지"를 하고, 그동안 대기하는 다음 요청들의 `throne_lock_wait_duration`이 함께 늘어난다. 즉 이 개선은 "자기 자신의 tail latency 단축"보다 **"대기 큐 회전율 향상"**으로 전체 p99를 끌어내린다.
**조치**:
1. `afterCommit` 구조는 유지한다 (정합성 유지).
2. 별도 ExecutorService로 송신을 오프로딩한다 — 락 보유 시간에서 SSE I/O를 분리.

```java
private final ExecutorService sseExecutor = Executors.newFixedThreadPool(4);

public void publishThroneChangedAsync(ThroneChangedEvent evt) {
    sseExecutor.submit(() -> emitters.forEach((uid, set) ->
        set.forEach(em -> safeSend(em, "throne", evt))
    ));
}

// 앱 종료 시 graceful shutdown (M5). pending 메시지 최대 3초 대기 후 drop.
@PreDestroy
public void shutdown() {
    sseExecutor.shutdown();
    try {
        if (!sseExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
            sseExecutor.shutdownNow();
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        sseExecutor.shutdownNow();
    }
}

// ThroneService 변경: 기존 afterCommit 콜백의 본문만 async 호출로 변경
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() { ssePublisher.publishThroneChangedAsync(evt); }
});
```

**측정 & 문서화**: 시나리오 D 재실행. 찬탈 p99 + `throne_lock_wait_duration` p99 + SSE 수신 지연 세 지표를 동시에 기록. 이 개선은 찬탈 p99와 락 대기 p99가 **함께** 줄어드는 것이 기대 결과 — 그래야 "락 보유 시간 단축 → 큐 회전율 향상"이라는 원인 가설이 입증된다.

### 10.7. WebSocket 비교 실험 (추후 개발 예정)

SSE vs WebSocket의 정량 비교 실험은 **19장 "추후 개발 예정"으로 이관**. Phase 1/2의 운영 기본값은 SSE로 확정(선택 근거는 12장). WebSocket 구현과 k6 시나리오 B/C 비교는 `/ws/throne` 엔드포인트, `WsPublisher`, 동등성 보장 방법까지 19장에 설계만 남기고 코드 구현은 포폴 이후 확장 과제.

### 10.8. 보조 실험: 동시성 제어 전략 비교 (⭐ optional)

개선 사이클이 끝난 상태에서, **동시성 제어 메커니즘만 바꿔가며** 한 번 더 측정한다. 목적은 "JVM 레벨 직렬화 vs DB 레벨 직렬화" 두 모델의 trade-off를 수치로 비교하는 것.

| 실험 | 전략 | UX | 기대 결과 |
|---|---|---|---|
| **A0 (기준선)** | `ReentrantLock` (현재 MVP) | 무조건 대기 후 결과 | p99 = VU에 비례, 409 없음 |
| **A1** | MySQL `FOR UPDATE` (대기) | 무조건 대기 후 결과 | p99 최악, DB 커넥션 압박 |

**서사 포인트**: A0(기준선)이 A1과 다른 유일한 축은 **"대기 비용이 DB 커넥션이 아닌 JVM 스레드에 있다"**는 점. 같은 UX(무조건 대기)인데 DB 압박이 훨씬 적음을 수치로 보여준다.

**실험 범위**: A1은 `ThroneService`의 `ReentrantLock` 블록을 `currentThroneRepo.selectForUpdate(eventId)`로 교체한 **별도 구현체** `ThroneServiceForUpdate`를 작성해 전략 패턴으로 주입. `innodb_lock_wait_timeout = 3`을 한정 적용. 측정은 같은 k6 시나리오 A로 반복, p99·DB 커넥션 사용률·커넥션 대기 수를 `docs/improvement-concurrency.md`에 기록.

> **`FOR UPDATE NOWAIT` (A2) / 낙관적 락 (A3)** 비교는 19장 "추후 개발 예정"으로 이관. A1 하나만으로도 "DB 락 vs JVM 락"의 수치적 정당화가 성립하며, A2/A3은 각각 별도 서비스 구현체가 필요해 2주 scope를 초과한다.

이 표가 **"MVP에서 `ReentrantLock`을 고른 이유"**를 수치로 정당화하고, **"그럼에도 p99를 더 끌어올리려면 Redis가 필요하다"**는 Phase 3 서사의 마지막 조각이 된다.

### 10.9. Phase 2 종합 비교 표 (포폴용)

| 단계 | 찬탈 p99 | 성공 TPS | 읽기 p99 | 비고 |
|---|---|---|---|---|
| Baseline (MVP) | ???ms | ??? | ???ms | 측정 기준선 |
| 개선 #1 (읽기 캐시) | ???ms | ??? | ???ms | 읽기 p99 주목 |
| 개선 #2 (감사로그 배치) | ???ms | ??? | ???ms | INSERT 감소 효과 |
| 개선 #3 (SSE 비동기) | ???ms | ??? | ???ms | 믹스 시 p99 안정 |
| 동시성 전략 A1 (FOR UPDATE) | ???ms | ??? | - | DB 락 비용 비교 |
| **Phase 1 최선** | **???ms** | **???** | **???ms** | MySQL 한계 |
| **Phase 3 (Redis)** | **???ms** | **???** | **???ms** | **배율 개선** |

이 표 하나가 이력서 "성능 최적화 경험"의 증빙이 된다.

---

## 11. Phase 3 — Redis 도입 & 개선

### 11.1. 역할 재정의

| 관심사 | Phase 1 | Phase 3 |
|---|---|---|
| 현재 왕 저장소 | `current_throne` (MySQL) + `throneMap` (JVM) | `event:{id}:throne` (Redis Hash: `ownerId`, `heldSince`, `reignId`) |
| reign_id 채번 | MySQL AUTO_INCREMENT (`throne_reigns.id`) | **Redis `INCR global:reign_seq` (전역 단일 시퀀스, B3)** — Lua 내부에서 원자 채번 |
| 쿨타임 | `cooldowns` 테이블 | `event:{id}:cooldown:{uid}` (TTL) |
| 리더보드 | `event_participants` + 5분 배치 스냅샷 | `event:{id}:lb` (ZSET, 실시간) |
| 찬탈 원자성 | 이벤트별 `ReentrantLock` + 트랜잭션 | Redis Lua |
| 찬탈 → MySQL 전달 | 같은 트랜잭션에서 동기 | **Lua 내부 `RPUSH queue:throne_events` (B5)** → Consumer가 MySQL 영속화 |
| 영속 이벤트 로그 | `throne_claims`, `throne_reigns` | **여전히 MySQL** (Consumer가 Redis가 확정한 reign_id 그대로 INSERT) |
| SSE fan-out | in-memory `ConcurrentHashMap` | Redis Pub/Sub |

**B3 — 전역 시퀀스 선택 이유**: 이벤트별 시퀀스(`event:{id}:reign_seq`)는 각 이벤트가 `1, 2, 3...`을 발급하므로 MySQL의 전역 PK `throne_reigns.id`와 충돌한다. 이벤트 2개가 나란히 돌지는 않지만(동시 RUNNING 1개 제약), 과거 이벤트와 현재 이벤트의 reign_id가 겹쳐 두 번째 이벤트 시작 시 PK 충돌이 발생한다. **전역 단일 시퀀스**(`global:reign_seq`)로 통일하면 이벤트 간 id 공간이 자연스럽게 분리되면서 MySQL의 AUTO_INCREMENT와 동일한 의미를 유지한다.

**부트스트랩**: 애플리케이션 기동 시 `@PostConstruct`에서 `SELECT IFNULL(MAX(id), 0) FROM throne_reigns`를 실행해 **`global:reign_seq`를 이 값으로 초기 `SET`**. Redis 데이터 유실 시에도 MySQL이 source of truth이므로 재부팅으로 복구 가능.

**포인트**: Redis는 "핫 패스 + 원자성"을 가져가고, MySQL은 "최종 진실 + 쿼리 가능한 이력"을 맡는다. MVP의 "JVM 스레드 직렬화" 비용이 Redis의 "단일 스레드 원자 처리"로 대체되면서, 같은 직렬성 UX를 유지하면서도 처리량이 한 차원 올라간다.

### 11.2. 찬탈 Lua

**Key 네이밍 규약**
- `KEYS[1] = event:{eventId}:throne` (Hash: `ownerId`, `heldSince`, `reignId`)
- `KEYS[2] = event:{eventId}:cooldown:{userId}` (String: `expiresAt`, TTL로 자동 만료)
- `KEYS[3] = event:{eventId}:meta` (Hash: `status`, `cooldownMs`)
- `KEYS[4] = global:reign_seq` (String: 전역 monotonic counter, `INCR`로 원자 채번 — B3)
- `KEYS[5] = queue:throne_events` (List: Consumer가 BLPOP로 꺼내는 영속화 큐 — B5)

**설계 포인트**
- **전역 reign_id 시퀀스 (B3)**: v4.3.4의 `event:{id}:reign_seq`는 이벤트별이라 MySQL의 전역 PK `throne_reigns.id`와 의미가 불일치. **모든 이벤트가 공유하는 단일 시퀀스**로 통일해 과거-현재 이벤트 간 id 겹침을 원천 차단.
- **큐 적재 원자화 (B5)**: Lua가 `HSET` + `SET` + `RPUSH`를 **한 번의 Lua 실행 안에서** 모두 수행. 앱 레이어는 Lua 호출 한 번으로 Redis 상태 갱신과 큐 적재가 동시에 완료됨을 보장받는다. 앱이 Lua 반환 직후 크래시해도 Redis 상태 == 큐 상태가 맞다.
- Phase 1은 MySQL AUTO_INCREMENT로 `reign_id`를 채번했지만, Phase 3은 **Redis가 `reign_id`를 먼저 확정**하고 MySQL은 그 값을 그대로 INSERT한다.

Flyway `V_phase3_1__disable_auto_increment.sql`로 Phase 3 진입 시 `throne_reigns.id`의 AUTO_INCREMENT를 비활성화(`ALTER TABLE throne_reigns MODIFY id BIGINT NOT NULL`). Phase 3 Consumer는 Redis가 확정한 `reignId`를 `INSERT INTO throne_reigns (id, ...) VALUES (?, ...)`로 그대로 기록.

```lua
-- KEYS: [throne, cooldown, meta, global_reign_seq, persist_queue]
-- ARGV: [eventId, userId, nowMs, cooldownMs]

local status = redis.call('HGET', KEYS[3], 'status')
if status ~= 'RUNNING' then
  -- meta가 없거나(이벤트 미세팅), status != RUNNING 모두 NOT_RUNNING으로 정규화.
  -- NO_ACTIVE_EVENT는 Lua 실행 이전 컨트롤러 레이어에서 판정 (11.1의 active eventId 캐시).
  return {0, 'NOT_RUNNING', '', 0, 0, 0}
end

local prevOwnerId   = redis.call('HGET', KEYS[1], 'ownerId')
local prevHeldSince = redis.call('HGET', KEYS[1], 'heldSince')
local prevReignId   = redis.call('HGET', KEYS[1], 'reignId')

-- ALREADY_OWNER 체크 (COOLDOWN보다 먼저 — Phase 1과 순서 통일, C2)
if prevOwnerId == ARGV[2] then
  return {0, 'ALREADY_OWNER', prevOwnerId, prevHeldSince or 0, prevReignId or 0, 0}
end

local cdExpiresAt = redis.call('GET', KEYS[2])
if cdExpiresAt and tonumber(cdExpiresAt) > tonumber(ARGV[3]) then
  return {0, 'COOLDOWN', '', 0, 0, tonumber(cdExpiresAt) - tonumber(ARGV[3])}
end

-- 성공 경로: 전역 reign_id 채번 + 왕좌 갱신 + 쿨타임 세팅 + 영속화 큐 적재 (모두 원자)
local newReignId = redis.call('INCR', KEYS[4])   -- global:reign_seq (B3)
redis.call('HSET', KEYS[1],
           'ownerId',   ARGV[2],
           'heldSince', ARGV[3],
           'reignId',   newReignId)
redis.call('SET', KEYS[2],
           tonumber(ARGV[3]) + tonumber(ARGV[4]),
           'PX', tonumber(ARGV[4]))

-- 영속화 큐 적재 (B5) — Lua 내부에서 HSET/SET과 함께 원자 실행.
-- payload: eventId|newReignId|newOwnerId|prevReignId|prevOwnerId|prevHeldSince|nowMs|attempt
local payload = string.format('%s|%d|%s|%s|%s|%s|%s|0',
    ARGV[1], newReignId, ARGV[2],
    prevReignId or '0', prevOwnerId or '0',
    prevHeldSince or '0', ARGV[3])
redis.call('RPUSH', KEYS[5], payload)

-- 반환: [ok, code, prevOwnerId, prevHeldSince, prevReignId, extra]
--   SUCCESS: [1, 'SUCCESS', prevOwnerId, prevHeldSince, prevReignId, newReignId]
--   COOLDOWN: [0, 'COOLDOWN', '', 0, 0, remainingMs]
--   기타: [0, code, ...]
return {1, 'SUCCESS', prevOwnerId or '', prevHeldSince or 0, prevReignId or 0, newReignId}
```

> **큐 payload 포맷**: 파이프 구분 문자열은 Redis Lua에서 JSON 직렬화가 번거로워 선택한 단순 포맷. 필드 순서는 `eventId | newReignId | newOwnerId | prevReignId | prevOwnerId | prevHeldSince | nowMs | attempt`. Consumer가 `split("\\|")`로 파싱. 모든 숫자는 정수 밀리초 또는 ID이므로 이스케이프 필요 없음.

> **부트스트랩 절차 (B3)**: `global:reign_seq`는 **앱 기동 시 `@PostConstruct` 1회만** MySQL의 `MAX(throne_reigns.id)`와 재동기화한다. 원자 조건부 `SET`으로 수행 (현재값이 MySQL MAX보다 작을 때만 덮어쓰기):
> ```lua
> -- EVAL 1 global:reign_seq <MAX(throne_reigns.id)>
> local current = tonumber(redis.call('GET', KEYS[1])) or 0
> if current < tonumber(ARGV[1]) then
>   redis.call('SET', KEYS[1], ARGV[1])
> end
> return current
> ```
> 이는 "Redis가 더 앞서 가는" 정상 케이스를 건드리지 않고, "Redis가 뒤처진" 유실 케이스만 복구한다. **`/start` 직전 재동기화(R2 2차)**는 "Redis 컨테이너만 독립 재시작된 시나리오"를 대비한 것으로, 200 TPS 규모에서 관측 확률이 낮아 19장 "추후 개발 예정"으로 이관.

> **`NO_ACTIVE_EVENT`의 위치**: Phase 1에서는 `throneMap.get(eventId) == null`로 서비스 레이어에서 검출했지만, Phase 3에서는 **컨트롤러에서 active eventId 캐시로 판정** 후 Lua에 진입한다. Lua 내부에서 meta 누락을 NOT_RUNNING으로 정규화하면 Redis 키 상태가 비정상일 때의 fallback이 된다. 이 일원화로 14.2의 `throne_claim_total{result}` label cardinality가 Phase 간 안정적으로 유지된다.

> **판정 순서 정합성 (C2)**: Phase 1과 Phase 3 모두 `NOT_RUNNING → ALREADY_OWNER → COOLDOWN → SUCCESS` 순서. 결과 분포 메트릭이 Phase 간 비교 가능하다.

### 11.2-bis. Redis `meta` 해시 동기화 경로

`event:{id}:meta`의 set/update는 **MySQL `events.status` 변경 직후**에 일어난다. MySQL이 최종 진실이므로, Redis는 eventual consistency를 허용한다.

| MySQL 이벤트 | Redis 갱신 |
|---|---|
| 앱 기동 (`@PostConstruct`) | **시퀀스 부트스트랩**: `EVAL 'local c=tonumber(redis.call("GET",KEYS[1])) or 0; if c < tonumber(ARGV[1]) then redis.call("SET",KEYS[1],ARGV[1]) end; return c' 1 global:reign_seq <MAX(throne_reigns.id)>`로 `global:reign_seq`를 MySQL `MAX(throne_reigns.id)`와 원자 재동기화 (1회만). |
| `/start` 커밋 (DRAFT → RUNNING) | ① `seedReignId = INCR global:reign_seq` → ② `INSERT throne_reigns (id, ...) VALUES (seedReignId, ..., SYSTEM_USER_ID, now)` → ③ `HSET event:{id}:meta status RUNNING cooldownMs <v>` → ④ `DEL event:{id}:throne` → ⑤ `HSET event:{id}:throne ownerId 1 heldSince <now> reignId <seedReignId>`. |
| `/end` 커밋 (RUNNING → ENDED) | `HSET event:{id}:meta status ENDED` |
| `/finalize` 커밋 (ENDED → FINALIZED) | `DEL event:{id}:throne`, `DEL event:{id}:meta`, cooldown 키 prefix 스캔 삭제. **`global:reign_seq`는 전역 공유이므로 절대 삭제하지 않는다.** |
| 자동 스케줄러 종료 | `/end`와 동일 |

- 갱신은 트랜잭션 `afterCommit`에서 수행. Redis 실패 시 WARN 로그 + 단순 재시도(최대 3회). MySQL이 이미 커밋되어 있으므로 서비스는 계속 동작(다음 요청이 `status != RUNNING`으로 rejection).
- 경합 구간: MySQL이 RUNNING → ENDED로 바뀌었으나 Redis meta가 아직 RUNNING인 짧은 순간. 이 경우 성공한 찬탈이 1~2건 더 들어올 수 있다. 이는 `throne_claims` 로그와 `throne_reigns`의 타임스탬프 비교로 사후 감지 가능하며, "운영상 수용되는 race window"로 문서화한다.

> **이관된 항목**: "Redis 전체 유실 시 `@PostConstruct`에서 `meta`/`throne` Hash 재구성" (R4), "`/start` 직전 reign_seq 2차 재동기화" (R2 2차)는 19장 "추후 개발 예정"으로 이관. 1인 2주 포폴 scope에서는 **앱 기동 1회 부트스트랩**만으로 재시작 시 PK 충돌을 충분히 차단하며, Redis 통째 유실은 운영 레벨의 드문 사건이라 복구 절차 문서화를 우선한다.

### 11.3. 영속성 — 이벤트 큐 Consumer

Lua 성공 시 **Lua 내부에서** `RPUSH queue:throne_events`까지 수행(B5). 앱 레이어는 Consumer만 돌리면 된다.

```
[Lua 원자 실행] throne 갱신 + cooldown 세팅 + RPUSH queue:throne_events
              ↓
[Consumer] BLPOP → MySQL 트랜잭션 1개로:
              1) UPDATE throne_reigns
                   SET ended_at=?, duration_ms=?
                   WHERE id=:prevReignId AND ended_at IS NULL   -- C3과 동일한 조건부 UPDATE
                 → affected=0이면 "이미 닫힌 reign" 로그 후 스킵 (중복 처리 방어)
              2) INSERT INTO throne_reigns (id, event_id, user_id, started_at)
                   VALUES (:newReignId, ...)                    -- AUTO_INCREMENT 비활성화됨(V_phase3_1)
              3) UPDATE current_throne
                   SET reign_id=:newReignId, user_id=:newOwnerId, held_since=:nowMs
                   WHERE event_id=? AND reign_id=:prevReignId   -- 순서 보장
              4) INSERT INTO throne_claims (event_id, user_id, result, requested_at)
                   VALUES (?, :newOwnerId, 'SUCCESS', :nowMs)
              5) AggregationCache.addReignEnd(eventId, prevOwnerId, heldMs)
                 + AggregationCache.addClaimSuccess(eventId, newOwnerId)
```

**reign_id 주입 방식 (B3)**: Redis Lua가 `INCR global:reign_seq`로 이미 `newReignId`를 확정했으므로, Consumer는 이 값을 `throne_reigns.id`에 **그대로 INSERT** 한다. Phase 3 마이그레이션 `V_phase3_1__disable_auto_increment.sql`이 `ALTER TABLE throne_reigns MODIFY id BIGINT NOT NULL`로 AUTO_INCREMENT를 비활성화해 앱이 id를 지정할 수 있게 한다.

```java
@Component
public class ThroneEventConsumer {
    private static final String QUEUE = "queue:throne_events";

    @Scheduled(fixedDelay = 100)  // 단일 인스턴스 전제, 동시 Consumer 1
    public void consumeOne() {
        // BLPOP: 5초 블로킹 (메시지 없으면 타임아웃 후 다음 스케줄로)
        String raw = redis.opsForList().leftPop(QUEUE, Duration.ofSeconds(5));
        if (raw == null) return;

        try {
            ThroneEventMsg msg = ThroneEventMsg.parse(raw);
            persistToMysql(msg);  // 위 1~5 스텝 (단일 트랜잭션)
            meterRegistry.counter("throne_consumer_success_total").increment();
        } catch (Exception e) {
            // Phase 3 MVP scope: 실패 시 에러 로그만 남기고 다음 메시지로.
            // 재시도 큐잉/DLQ/멱등 UNIQUE 등의 엄밀한 내결함성 장치는 19장 "추후 개발 예정"으로 이관.
            log.error("throne event consumer failed — message dropped: {}", raw, e);
            meterRegistry.counter("throne_consumer_failure_total").increment();
        }
    }
}
```

- **Consumer는 단일 인스턴스, 단일 스레드** (`@Scheduled(fixedDelay = 100)`)로 실행. `BLPOP` 특성상 다음 메시지는 이전 메시지가 완료된 뒤에만 꺼내지므로 **처리 순서는 자연스럽게 Lua의 RPUSH 순서와 일치**한다.
- **실패 정책**: 1회 시도 후 실패면 에러 로그 + 메트릭 카운트만 기록하고 다음 메시지로 진행한다. 200 TPS 규모 + 단일 MySQL 환경에서 DB 일시 장애 확률이 낮고, 장애가 나더라도 `current_throne`은 그 이전 상태 그대로 유지되므로 서비스는 계속 동작한다. 이 실패는 운영자가 로그/메트릭을 보고 수동 복구 대상으로 분류한다.
- **Known limitation (README 명시)**:
  1. Consumer가 `leftPop` 직후 `persistToMysql` 실행 중에 크래시하면 해당 메시지는 유실된다. `BRPOPLPUSH ... processing` 패턴이나 Redis Streams로 전환하면 막을 수 있으나 Phase 3 후속 과제로 이관.
  2. 재시도 큐잉·DLQ·멱등 UNIQUE가 없으므로 같은 메시지가 두 번 처리될 일은 없지만(단일 Consumer + BLPOP), DB 장애 기간의 메시지는 유실된다.

**k6 재측정**: 동일 시나리오 A 재실행 → 목표 p99 < 80ms 달성 검증.

### 11.4. 실시간 리더보드

- Redis `ZINCRBY event:{id}:lb <addMs> <userId>`
- 조회는 `ZREVRANGE ... WITHSCORES`
- SSE로 1초 배치 브로드캐스트
- `leaderboard_snapshot` 배치는 Phase 3에서 폐기 or 과거 이벤트 조회용으로만 유지

### 11.5. Phase 1 vs Phase 3 수치 비교 (PromQL)

Phase 1 baseline 측정 시점에 핵심 지표를 `docs/baseline-YYYYMMDD.md`에 수치로 기록. Phase 3 재측정 후 동일 쿼리로 수치를 뽑아 **동일 문서에 "After" 열을 추가**한다. PromQL 예시:

- 찬탈 p99: `histogram_quantile(0.99, rate(throne_claim_duration_bucket[1m]))`
- 락 대기 p99 (Phase 1 전용): `histogram_quantile(0.99, rate(throne_lock_wait_duration_bucket[1m]))`
- 찬탈 성공 TPS: `rate(throne_claim_total{result="SUCCESS"}[1m])`
- SSE 연결 수: `sse_active_connections`
- Caffeine flush 지연: `histogram_quantile(0.99, rate(caffeine_flush_duration_bucket[5m]))`

---

## 12. SSE 선택 근거

### 12.1. 왜 SSE인가

실시간 왕좌 변경·리더보드 알림은 **서버 → 클라이언트 단방향 push**가 유일한 요구사항이다. 양방향·바이너리·저지연 RPC가 필요한 시나리오가 아니다. 이 요구사항에서 SSE와 WebSocket을 비교했을 때:

| 항목 | SSE | WebSocket |
|---|---|---|
| 통신 방향 | 서버 → 클라이언트 단방향 | 양방향 |
| 프로토콜 | HTTP/1.1 장기 연결 (text/event-stream) | HTTP Upgrade → WS 독립 프레임 |
| 재연결 | 브라우저가 자동 재연결 (`EventSource`) | 직접 구현 필요 |
| 인프라 | HTTP 인프라(Nginx, CDN, 인증) 그대로 재사용 | Upgrade 프로토콜 지원 필요 |
| 서버 스택 | Spring MVC `SseEmitter` — 의존성 없음 | STOMP/WebSocket config + 별도 broker |
| 인증 경로 | 쿠키/헤더 자동 전송 (HTTP 표준) | handshake에 수동 인증 주입 |
| 메시지 포맷 | UTF-8 텍스트 (JSON) | 텍스트/바이너리 |
| 브라우저 호환 | IE 제외 모두 (EventSource) | 모든 최신 브라우저 |

### 12.2. 결정 근거

1. **요구사항 적합성**: 왕좌 변경 알림은 순수 단방향 push. WebSocket의 양방향성은 이 프로젝트에서 **잉여 기능**이고, 잉여 기능은 복잡도만 더한다.
2. **인증·재연결 무료**: SSE는 HTTP 표준 위에서 동작하므로 기존 JWT 쿠키(`HttpOnly; SameSite=Lax`)가 그대로 전달된다. WebSocket은 handshake에서 `Sec-WebSocket-Protocol` 경유로 토큰을 전달하거나 첫 메시지로 인증을 수행해야 해 복잡도가 한 단계 높다. 또한 `EventSource`가 네트워크 단절 시 자동 재연결하므로 프론트 코드가 간결해진다.
3. **관측성·로깅**: HTTP 접근 로그(`http_server_requests_seconds`)와 Actuator가 그대로 SSE 스트림도 커버한다. WebSocket은 Upgrade 이후 별도 메트릭 파이프라인이 필요.
4. **Nginx 튜닝 1줄**: `proxy_buffering off` + `proxy_read_timeout` 상향으로 충분. WebSocket은 `Upgrade`/`Connection` 헤더 보존 + 타임아웃 튜닝 + proxy 버전 요구사항까지 신경 써야 한다.
5. **서버 리소스**: SSE는 Servlet 스레드가 아닌 비동기 `SseEmitter`를 사용하므로 200 연결 정도는 넉넉히 유지 가능(실측 기준 Tomcat 기본 설정에서 문제없음).

### 12.3. SSE의 한계와 수용

- **클라이언트 → 서버 메시지 불가**: 찬탈은 `POST /api/events/active/claim` (일반 HTTP)로 처리하므로 제약 아님.
- **브라우저당 동시 연결 6개 제한 (HTTP/1.1)**: 한 유저가 같은 도메인에 탭을 7개 이상 띄우면 7번째부터 pending. 실사용 시나리오에서 비현실적이라 수용.
- **바이너리 전송 불가**: 모든 메시지가 JSON이라 제약 아님.

### 12.4. 비교 실험은 추후 과제

SSE vs WebSocket의 **정량 비교 실험**(k6 시나리오 B/C 동일 조건 측정, RAM/스레드/메시지 p99/LoC 수치 비교)은 19장 "추후 개발 예정"으로 이관. 선택 근거가 이미 설계상으로 명확하고, 200 VU 규모에서는 두 방식 모두 "동작은 한다" 수준의 결과가 나올 가능성이 높아 포폴 서사 기여가 낮기 때문. 면접에서 수치 비교를 요구받으면 "설계상의 적합성 판단으로 SSE를 선택했으며, 정량 비교는 확장 과제로 분류"라고 답하는 편이 더 정직하다.

---

## 13. 로깅 전략

취업 시장에서 "로그 기반 문제 해결 경험"을 증명할 수 있는 수준으로 설계.

### 13.1. 구조화 로깅 (JSON)

`logstash-logback-encoder` 사용. 모든 로그는 JSON 한 줄.

```json
{
  "@timestamp": "2026-04-17T14:23:01.234+09:00",
  "level": "INFO",
  "logger": "c.s.t.throne.ThroneService",
  "thread": "http-nio-8080-exec-3",
  "message": "claim success",
  "requestId": "req-5f8c2a",
  "userId": 42,
  "eventId": 7,
  "action": "claim",
  "result": "SUCCESS",
  "prevOwnerId": 18,
  "heldMs": 12450
}
```

### 13.2. MDC 주입 (LogContextFilter + UserIdMdcFilter)

requestId는 인증 이전 단계에서, userId는 Spring Security 인증 완료 이후에 주입해야 한다. 필터 두 개로 분리.

```java
// (1) 최외곽: requestId 주입
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LogContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String reqId = Optional.ofNullable(req.getHeader("X-Request-Id"))
            .orElse("req-" + UUID.randomUUID().toString().substring(0, 8));
        MDC.put("requestId", reqId);
        res.setHeader("X-Request-Id", reqId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();  // userId까지 함께 제거됨
        }
    }
}

// (2) Spring Security 이후: userId 주입
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)  // FilterSecurityInterceptor 뒤
public class UserIdMdcFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            MDC.put("userId", String.valueOf(p.getUserId()));
        }
        chain.doFilter(req, res);
        // MDC.clear()는 LogContextFilter의 finally에서 일괄 처리
    }
}
```

SecurityFilterChain 등록 순서: `LogContextFilter` → Spring Security 체인 → `UserIdMdcFilter` → 컨트롤러.

### 13.3. 로그 레벨 규율 (문서화 필수)

| 레벨 | 기준 | 예시 |
|---|---|---|
| ERROR | 즉각 조치 필요, 5xx 유발, 외부 시스템 장애 | DB 연결 실패, OAuth 제공자 5xx |
| WARN | 기능은 동작하나 비정상 흐름 | JWT 만료, rate limit 걸림, 블록된 유저 접근 |
| INFO | 주요 비즈니스 이벤트 | 이벤트 시작/종료, 찬탈 성공, 로그인 |
| DEBUG | 개발 시 흐름 추적 | 캐시 히트/미스, flush 건수 |
| TRACE | 고볼륨, 기본 off | 모든 SQL 파라미터 |

### 13.4. 민감정보 마스킹

`MaskingPatternLayout`에서 정규식으로:
- `Bearer [A-Za-z0-9._-]+` → `Bearer ***`
- `access_token=...` → `access_token=***`
- GitHub access_token (`gho_...`, `ghp_...`) → `***`

### 13.5. 파일 롤링

- `RollingFileAppender` + `SizeAndTimeBasedRollingPolicy`
- 최대 100MB, 7일, 총 1GB 상한

### 13.6. 비동기 Appender (성능)

- 모든 파일 Appender는 `AsyncAppender`로 감싼다.
- 큐 사이즈 512, `discardingThreshold 0` (INFO 이상은 drop 금지).
- 이는 Phase 2 부하테스트에서 p99 안정화에 기여 — before/after 측정 항목 중 하나.

### 13.7. 쿠키 보안 / CSRF (M9)

운영 기준은 `HttpOnly` 쿠키이므로(FR-05-5) POST 변경 API에 대한 CSRF 방어가 필요하다.

- **쿠키 속성**: `HttpOnly; Secure; SameSite=Lax; Path=/`.
  - `SameSite=Strict`가 아닌 `Lax`인 이유: GitHub OAuth2 리다이렉트 복귀(GET) 경로가 cross-site이므로 `Strict`면 첫 로그인 후 쿠키가 전송되지 않는다.
- **CSRF 토큰**: Spring Security의 `CookieCsrfTokenRepository.withHttpOnlyFalse()`를 사용하여 `XSRF-TOKEN` 쿠키를 발급하고, 프론트는 `X-XSRF-TOKEN` 헤더에 담아 POST 요청에 첨부.
  - 보호 대상: `/api/admin/**`, `/api/events/active/claim`, `/api/auth/refresh`.
  - 제외 대상: `/api/events/active/stream`(GET — SSE), `/login/oauth2/**`.
- **부하테스트 예외**: k6 시나리오는 JWT를 `Authorization` 헤더로만 쓰므로 쿠키/CSRF 모두 우회. 운영에선 브라우저 쿠키 경로, 부하테스트엔 헤더 경로로 이원화된다.

---

## 14. 메트릭 & 관측성

### 14.1. 수집 방식

- Spring Actuator `/actuator/prometheus` → Prometheus scrape (5초 주기)
- Prometheus UI에서 PromQL로 직접 조회. 포폴용 수치는 `docs/` 폴더에 before/after 표로 기록.

### 14.2. 커스텀 메트릭 (도입 시점별)

**Phase 1 MVP부터 수집**

| 메트릭 | 타입 | 용도 |
|---|---|---|
| `throne_claim_total{result,event_id}` | Counter | 찬탈 결과별 건수. `result`는 SUCCESS/COOLDOWN/ALREADY_OWNER/NO_ACTIVE_EVENT/NOT_RUNNING. NO_ACTIVE_EVENT는 `event_id="none"`으로만 집계(I3 — DB 기록 없음), 나머지는 실제 `event_id` label. |
| `throne_claim_duration{event_id}` | Histogram | 찬탈 API 레이턴시 |
| `throne_lock_wait_duration{event_id}` | Histogram | `ReentrantLock` 획득까지 대기 시간 (직렬화 비용 측정) |
| `throne_lock_queue_length{event_id}` | Gauge | 이벤트별 락 대기 중인 스레드 수 (M6 — `eventId` label 필수) |
| `throne_invariant_violation_total` | Counter | dev 프로파일 sanity check 위반 (9.1-bis, I1) |
| `throne_current_owner_changes_total{event_id}` | Counter | 왕좌 교체 횟수 |
| `caffeine_flush_duration` | Histogram | 집계 캐시 flush 지연 |
| `caffeine_pending_keys` | Gauge | 집계 캐시 대기 키 수 |
| `sse_active_connections` | Gauge | 현재 SSE 연결 수 (유저당 복수 가능) |
| `leaderboard_snapshot_duration` | Histogram | 5분 배치 소요 시간 |

> `throne_lock_wait_duration`과 `throne_lock_queue_length`는 **"Phase 1의 직렬화 특성을 수치로 보여주는 증빙"**이다. 포폴 서사와 1:1 연결된다.

**Phase 2 개선 시 추가**

| 메트릭 | 타입 | 추가 시점 |
|---|---|---|
| `caffeine_current_throne_hit_ratio` | Gauge | 개선 #1 (읽기 캐시 도입) |
| `claim_log_queue_size` | Gauge | 개선 #2 (감사 로그 배치) |
| `claim_log_drop_total` | Counter | 개선 #2 (큐 포화 시 drop 카운트) |
| `sse_publish_duration` | Histogram | 개선 #3 (SSE 비동기 전환 검증) |

**Phase 3 도입 시 추가**

| 메트릭 | 타입 | 설명 |
|---|---|---|
| `throne_lua_duration` | Histogram | Lua 스크립트 실행 시간 |
| `throne_consumer_success_total` | Counter | Consumer MySQL 영속화 성공 건수 |
| `throne_consumer_failure_total` | Counter | Consumer 영속화 실패 건수 — 0 유지를 목표로 하며 증가 시 즉시 로그 확인 |
| `throne_queue_depth` | Gauge | `LLEN queue:throne_events` (Lua 성공 → Consumer 처리 간 백로그) |

> **이관된 메트릭**: `throne_consumer_retry_total`, `throne_consumer_dlq_total`은 재시도 큐잉·DLQ 이관에 연동되어 19장 "추후 개발 예정"으로 이동. Phase 3 MVP Consumer는 1회 시도 + 실패 시 로그만이므로 retry/DLQ 개념 자체가 없다.

이 구분은 **각 메트릭이 왜 존재하는지가 개선 서사와 1:1로 연결**된다는 포폴 포인트이기도 하다.

### 14.3. 기본 제공 메트릭 활용

별도 설정 없이 Actuator 기본 노출을 조회 목록에 올리면 되는 것들:
- `http_server_requests_seconds_*` → API p50/p95/p99
- `jvm_memory_*`, `jvm_gc_*`, `jvm_threads_*`
- `tomcat_threads_busy`, `tomcat_threads_config_max`
- `hikaricp_connections_*`

### 14.4. Prometheus 수치 조회 기준

대시보드 대신 **핵심 PromQL 쿼리 7종**을 문서화한다. Phase 1 baseline / Phase 2 개선 후 / Phase 3 after 세 시점에서 동일 쿼리로 수치를 뽑아 표로 비교한다.

| 관심사 | PromQL |
|---|---|
| 찬탈 p99 | `histogram_quantile(0.99, rate(throne_claim_duration_bucket[1m]))` |
| 락 대기 p99 (Phase 1) | `histogram_quantile(0.99, rate(throne_lock_wait_duration_bucket[1m]))` |
| 찬탈 성공 TPS | `rate(throne_claim_total{result="SUCCESS"}[1m])` |
| 찬탈 결과 분포 | `sum by (result) (rate(throne_claim_total[1m]))` |
| SSE 연결 수 | `sse_active_connections` |
| Caffeine flush 지연 | `histogram_quantile(0.99, rate(caffeine_flush_duration_bucket[5m]))` |
| HikariCP 사용률 | `hikaricp_connections_active / hikaricp_connections_max` |

결과는 `docs/performance-report.md`에 3열(Phase 1 / Phase 2 개선 후 / Phase 3) 표로 정리한다.

---

## 15. 시상 & 결과 집계

### 15.1. 시상 정의

| 상 | 정의 | 계산 기준 |
|---|---|---|
| 👑 **군림상 (Longest Reign)** | 단일 reign 최장 보유자 | `SELECT user_id FROM throne_reigns WHERE event_id = ? AND user_id <> 1 ORDER BY duration_ms DESC LIMIT 1`. 동률 시 `started_at` 오름차순(먼저 달성한 사람). |
| ⚔️ **찬탈왕 (Usurper)** | 찬탈 성공 총 횟수 최다 | `event_participants.claim_success_cnt DESC` (SYSTEM 필터 `user_id <> 1`). 동률 시 user_id 오름차순. |
| 🌅 **최후의 왕 (Last King)** | 이벤트 종료 시 왕좌 보유자 | finalize 순서상 `closeOpenReign` 이후에 호출되므로 `ended_at IS NULL` 조회는 사용하지 않는다. **`SELECT user_id FROM throne_reigns WHERE event_id = ? AND user_id <> 1 ORDER BY started_at DESC LIMIT 1`**로 조회. 이 결과가 없으면(아무도 찬탈 안 함) **`event_awards`에 row를 생성하지 않는다** — summary의 `awards.lastKing`은 row의 부재로 `null` 판정 (B7 — `event_awards.user_id NOT NULL` 제약 유지). |

> **"수상자 없음" 정책 (B7)**: `event_awards.user_id BIGINT NOT NULL`은 유지한다. "수상자 없음"은 row의 **부재**로 표현한다 — 이 규칙은 3종 시상 모두에 적용된다. `LAST_KING`이 가장 흔한 케이스지만, 극단적으로 이벤트 시작 직후 바로 종료되고 아무 찬탈도 없었다면 `LONGEST_REIGN`/`USURPER`도 row를 만들지 않는다. summary 빌드 시 `awardRepo.findByEventIdAndAwardType(eventId, type)`을 `Optional<EventAward>`로 받아 존재 여부로 분기.

> **SYSTEM 필터의 의의**: 세 시상 모두 `user_id <> SYSTEM_USER_ID`(= `<> 1`)를 포함한다. 이벤트 시작 직후 아무도 찬탈하지 않고 종료된 병리적 케이스에서도 SYSTEM이 수상자로 올라가지 않는다.

### 15.2. finalize 처리

```java
@Transactional
public EventSummary finalize(long eventId) {
    Event event = eventRepo.findById(eventId).orElseThrow();

    // 0. Phase 3 전용: Consumer backlog drain 대기 (B6)
    //    Phase 1에서는 Consumer가 없으므로 no-op.
    //    Phase 3에서는 LLEN queue:throne_events == 0 이 되어야 영속 상태가 finalize 기준에 부합.
    //    조건 미충족 시 최대 30초 polling, 끝까지 안 비워지면 409 CONSUMER_BACKLOG 응답.
    consumerBacklogGate.awaitOrThrow(eventId, Duration.ofSeconds(30));

    // 1. lastKingUserId는 closeOpenReign 이전/이후 어느 쪽이든 동일한 결과가 나오도록
    //    started_at 기준으로 조회. SYSTEM(user_id=1) 제외.
    Long lastKingUserId = reignRepo.findLastKingUserId(eventId);  // null 가능 (아무도 찬탈 안 함)

    // 2. 진행 중 reign이 있으면 ended_at = event.endedAt 으로 마감
    reignRepo.closeOpenReign(eventId, event.getEndedAt());

    // 3. Caffeine flush (아직 안 들어간 집계 반영)
    aggregationCache.forceFlush();

    // 4. 시상 계산 (모두 SYSTEM 필터)
    var longest = reignRepo.findLongestReign(eventId);       // Optional — user_id <> 1
    var usurper = participantRepo.findMostClaims(eventId);   // Optional — user_id <> 1
    // lastKing은 1단계에서 이미 확보됨. null이면 수상자 없음.

    // 5. 수상자가 있는 경우만 row 생성 (B7).
    //    event_awards.user_id는 NOT NULL, "수상자 없음"은 row 부재로 표현.
    longest.ifPresent(r -> awardRepo.save(eventId, LONGEST_REIGN, r.userId(), r.durationMs(), null));
    usurper.ifPresent(r -> awardRepo.save(eventId, USURPER, r.userId(), null, r.claimCount()));
    if (lastKingUserId != null) {
        awardRepo.save(eventId, LAST_KING, lastKingUserId, null, null);
    }

    // 6. 통계 요약 생성 → summary JSON 반환용 (event_awards row 존재 여부로 null 판정)
    EventSummary summary = buildSummary(eventId);

    // 7. 메모리 상태 정리 (9.1-bis 참조). lockMap은 /end 시점에 이미 제거됨 (M13).
    throneMap.remove(eventId);
    // lockMap.remove(eventId); — 이미 /end에서 제거됨 (M13)
    eventService.clearActiveEventIfMatch(eventId);   // AtomicReference CAS

    return summary;
}
```

> 1단계를 2단계보다 앞에 두는 것이 포인트. `closeOpenReign`은 `ended_at IS NULL` 행을 업데이트하므로 그 이후 "마지막 왕"을 조회하는 쿼리는 정의가 달라진다. `started_at DESC LIMIT 1`로 통일하면 순서 의존성이 없어 어느 쪽에 두어도 동일한 결과를 내지만, 1단계에 두는 편이 "관측 시점을 finalize 진입 순간으로 고정"한다는 의도가 더 명확해 선택.

> **Consumer backlog gate (Phase 3, B6 — 단순 버전)**: Phase 3 `ConsumerBacklogGate.awaitOrThrow`는 1초 간격으로 `LLEN queue:throne_events == 0`을 polling한다. 최대 30초 대기 후에도 0이 안 되면 `ConsumerBacklogException` (`409 CONSUMER_BACKLOG`) 반환. Phase 1에서는 no-op 구현체로 주입되어 바로 반환.
>
> 이 단순 버전은 **Consumer가 `leftPop` 직후 `persistToMysql` 실행 중인 in-flight 메시지**는 관측하지 못한다(극히 짧은 창). 200 TPS 규모에서 실제로 이 타이밍 창에 걸릴 확률이 낮고 걸려도 `current_throne`은 일관성이 유지되므로 수용. 엄밀한 3중 조건(`LLEN == 0 && inFlightCount == 0 && current_dlq_total == baseline`) 게이트는 19장 "추후 개발 예정"으로 이관.

> **lockMap /end 정리 (M13)**: `/end` 커밋 `afterCommit`에서 `lockMap.remove(eventId)`를 수행. 이후 해당 이벤트에 대한 `claim` 호출은 컨트롤러의 `currentActiveEventId == null` 체크에서 `NO_ACTIVE_EVENT`로 걸러지거나, 레이스 경로에서 `throneMap.get(eventId) == null`로도 걸러진다. `throneMap`은 finalize까지 유지 — ENDED 상태에서 "마지막 왕" 조회 편의를 위해.

### 15.3. summary 응답 예

```json
{
  "eventId": 7,
  "meta": { "name": "SSAFY 왕좌전 #3", "startedAt": "...", "endedAt": "..." },
  "participation": { "participants": 187, "claimers": 112 },
  "claimStats": { "total": 4823, "success": 3104, "cooldown": 1203, "alreadyOwner": 516 },
  "holdDistribution": {
    "0-1s": 823, "1-5s": 1421, "5-30s": 642, "30s-5m": 182, "5m+": 36
  },
  "hourly": [
    { "hour": "2026-04-17T00", "success": 142, "transfers": 89 }
  ],
  "awards": {
    "longestReign": { "userId": 42, "login": "...", "durationMs": 1823400 },
    "usurper":      { "userId": 17, "login": "...", "claimCount": 128 },
    "lastKing":     { "userId": 91, "login": "...", "heldSince": "..." }
  }
}
```

---

## 16. 도메인 서비스 & API 명세

### 16.1. 서비스 레이어 분해

```
# MVP (Phase 1)
AuthService           OAuth2 콜백 처리, JWT 발급
UserService           유저 조회/블록
EventService          CRUD + 상태 전환 + 스케줄러 + throneMap/lockMap 초기화/정리
ThroneService         찬탈 핵심 (MVP: ReentrantLock + 단일 트랜잭션)
CooldownService       쿨타임 관리 + 만료 정리
LeaderboardService    5분 배치 스냅샷 + 조회
AwardService          finalize 시 시상 계산
AggregationCache      Caffeine write-back (집계 증분)
SsePublisher          SSE fan-out (MVP: 동기)

# Phase 2 개선에서 추가 또는 전환
CurrentThroneCache    (개선 #1) 현재 왕 읽기 캐시
ClaimLogQueue         (개선 #2) 감사 로그 배치 INSERT 큐
SsePublisher (async)  (개선 #3) ExecutorService 기반 비동기 전환

# Phase 3에서 추가
ThroneLuaExecutor     Redis Lua 원자 연산
ThroneEventConsumer   queue:throne_events → MySQL 이벤트 소싱
RedisLeaderboard      ZSET 실시간 리더보드
RedisPubSubBridge     SSE fan-out을 Pub/Sub으로 전환 (멀티 인스턴스 대비 설계, 본 프로젝트는 단일 인스턴스)
```

### 16.2. API 명세 (요약)

| Method | URI | Auth | 설명 |
|---|---|---|---|
| GET  | `/login/oauth2/authorization/github` | - | OAuth2 시작 |
| GET  | `/login/oauth2/code/github`           | - | OAuth2 콜백 |
| POST | `/api/auth/refresh`                   | Refresh Token | JWT 갱신. refresh token을 HttpOnly 쿠키(권장) 또는 요청 바디로 제출. JWT 만료 여부와 무관하게 호출 가능 (B8) |
| GET  | `/api/me`                             | JWT | 내 정보 |
| POST | `/api/admin/events`                   | ADMIN | 이벤트 생성 |
| POST | `/api/admin/events/{id}/start`        | ADMIN | 시작 |
| POST | `/api/admin/events/{id}/end`          | ADMIN | 조기 종료 |
| POST | `/api/admin/events/{id}/finalize`     | ADMIN | finalize |
| GET  | `/api/events/active`                  | JWT | 현재 RUNNING 이벤트 |
| POST | `/api/events/active/claim`            | JWT | 찬탈 |
| GET  | `/api/events/{id}/leaderboard`        | JWT | Top N |
| GET  | `/api/events/{id}/me`                 | JWT | 내 순위 |
| GET  | `/api/events/{id}/summary`            | JWT | finalize 후 요약 |
| GET  | `/api/events/active/stream`           | JWT (handshake) | SSE |

#### 16.2.1. 찬탈 API 응답 코드 표 (`POST /api/events/active/claim`)

| HTTP | body.result | 발생 경로 | DB 기록 |
|---|---|---|---|
| 200 | `SUCCESS` | 정상 찬탈 성공 | `throne_claims.result='SUCCESS'` |
| 404 | `NO_ACTIVE_EVENT` | `EventService.currentActiveEventId == null` | **DB 기록 없음** — Counter 메트릭 `throne_claim_total{result="NO_ACTIVE_EVENT",event_id="none"}`만 증가 (I3) |
| 409 | `ALREADY_OWNER` | 본인이 현재 왕 | `throne_claims.result='ALREADY_OWNER'` |
| 409 | `NOT_RUNNING` | 락 대기 중 admin `/end` 호출 → 상태 변경 | `throne_claims.result='NOT_RUNNING'` |
| 429 | `COOLDOWN` (+ `remainingMs`) | 쿨타임 잔여 | `throne_claims.result='COOLDOWN'` |
| 503 | `NOT_READY` | 부팅 중 (ApplicationReadyEvent 이전) | DB 기록 없음 (I2) |

#### 16.2.2. `/api/events/active/claim` 컨트롤러 개요

```java
@RestController
@RequestMapping("/api/events/active")
public class ActiveClaimController {
    private final EventService eventService;
    private final ThroneService throneService;
    private final Counter noActiveEventCounter;   // throne_claim_total{result="NO_ACTIVE_EVENT",event_id="none"}

    @PostMapping("/claim")
    public ClaimResponse claim(@AuthenticationPrincipal AppUserPrincipal user) {
        Long eventId = eventService.getCurrentActiveEventId();  // AtomicReference.get()
        if (eventId == null) {
            // NO_ACTIVE_EVENT는 DB 기록 없음 (FR-03-1, I3).
            // 메트릭 Counter만 증가 → PromQL로 감시.
            noActiveEventCounter.increment();
            throw new NoActiveEventException();
        }
        return throneService.claim(eventId, user.getUserId()).toResponse();
    }
}
```

> **`event_id=0` sentinel 폐기 (I3)**: v4.3.3까지는 NO_ACTIVE_EVENT 감사 로그를 `event_id=0`으로 `throne_claims`에 기록했으나, 이는 인덱스 스큐(`idx_claims_event_time`의 `event_id=0` 페이지 비대화)를 유발. v4.3.4부터는 DB 기록을 폐기하고 Counter 메트릭만 사용.

---

## 17. 개발 일정 (2주)

총 14일 기준. Phase별 데드라인을 두되 필요에 따라 축소/확장 가능.

### Phase 1 — MVP 구축 (Day 1~7)

**Day 1 — 프로젝트 셋업**
- Gradle, Spring Boot 3 초기화
- Docker Compose: MySQL, Prometheus
- Flyway 마이그레이션 v1 (users, events, throne_reigns, current_throne, throne_claims, cooldowns, leaderboard_snapshot, event_awards)
- `LogContextFilter` + Logback JSON 설정

**Day 2 — 인증 & 유저**
- Spring Security + OAuth2 Client (GitHub)
- JWT 발급/검증 (jjwt)
- `users` upsert, 관리자 role 시드
- `/api/me`, `/api/auth/refresh`

**Day 3 — 이벤트 라이프사이클**
- `EventService` + `@Scheduled` 자동 종료
- `EventService.currentActiveEventId` (`AtomicReference<Long>`) — `/start`에서 set, `/end`·스케줄러 자동 종료에서 clear. 서버 부팅 시 `@PostConstruct`에서 RUNNING 이벤트로 복원
- 관리자 API 4종 (생성/시작/종료/finalize)
- 이벤트 상태 전이 테스트
- 이벤트 시작 시 seed된 `SYSTEM` 사용자로 초기 reign + `current_throne` 1행 생성 + `throneMap.put` + `lockMap.computeIfAbsent`
- 서버 부팅 시 RUNNING 이벤트 throneMap 복원 (`@PostConstruct`, 9.1-bis)

**Day 4 — 찬탈 MVP**
- `ThroneService.claim()` 이벤트별 `ReentrantLock(true)` + `TransactionTemplate` 구현 (9.1)
- 락 보유 시간 = 트랜잭션 완료 시까지가 되도록 `lock → tx → afterCommit → unlock` 순서 엄수
- 감사 로그는 배치 없이 개별 INSERT (실패는 REQUIRES_NEW, 성공은 afterCommit)
- 쿨타임 upsert

**Day 5 — 집계 & 리더보드**
- `AggregationCache` write-back (`pending.remove` 기반 안전 flush) (9.2)
- `@PreDestroy` 최종 flush
- `cooldowns` 1분 주기 만료 정리 (9.3)
- 5분 배치 `leaderboard_snapshot` (9.4)

**Day 6 — SSE (동기) + finalize & 시상 + 보안 보강**
- `SseEmitter` handshake 시 JWT 검증, 유저당 다중 연결(Set) 허용
- 찬탈 성공 시 `afterCommit` 동기 브로드캐스트 (9.5)
- 리더보드 스냅샷 완료 시 SSE 알림
- `AwardService` — 3종 시상 계산 (군림/찬탈왕/최후의 왕)
- `/api/events/{id}/summary` 구현 + `/end`에서 lockMap 제거, finalize에서 throneMap 제거 (M13)
- **`ApiReadinessFilter` 추가** (I2) — `ApplicationReadyEvent` 이전 POST API 503 reject
- **IP rate limit (Bucket4j) — 10 req/s** (M3, NFR-06) — `/api/events/active/claim` 엔드포인트 대상
- CSRF 토큰 설정(`CookieCsrfTokenRepository`) + SameSite=Lax 쿠키 (M9)

**Day 7 — 프론트 + 로컬 e2e**
- React 최소 SPA: 로그인 → 게임 화면 → 관리자 페이지
- 결과 페이지 (summary 렌더링)
- 로컬에서 10명 자체 테스트
- **Phase 1 MVP 완료 태그 (git tag `v1.0-mvp`)**

### Phase 2 — 측정 & 개선 사이클 (Day 8~11)

**Day 8 — 배포 & Baseline 측정**
- EC2 t3.medium 프로비저닝, Docker Compose 배포
- Nginx + Let's Encrypt + SSE 프록시 설정
- GitHub OAuth App 리다이렉트 URL 등록
- Prometheus scrape 설정 확인, PromQL 쿼리 7종(14.4)으로 baseline 수치 기록 템플릿 준비
- **k6 baseline 측정** — 시나리오 A~E 실행
- `docs/baseline-YYYYMMDD.md` 작성

**Day 9 — 개선 #1, #2**
- 개선 #1: 현재 왕 읽기 캐시 추가 → 시나리오 E 재측정 (10.6)
- 개선 #2: 감사 로그 배치 INSERT로 전환 → 시나리오 A 재측정 (10.6)
- 각 개선의 before/after 수치 기록

**Day 10 — 개선 #3 + 동시성 전략 A1 비교**
- 개선 #3: SSE 비동기 브로드캐스트 (기존 `afterCommit` 유지) → 시나리오 D 재측정 (10.6)
- 보조 실험: 동시성 제어 전략 비교 (10.8)
  - **A0 ReentrantLock(기준선) vs A1 FOR UPDATE**: 같은 "대기" UX의 비용 차이. `ThroneServiceForUpdate` 구현체 별도 작성, `innodb_lock_wait_timeout = 3` 한정 적용.
  - A2 NOWAIT / A3 낙관적 락 실험은 19장 "추후 개발 예정"으로 이관 — 각각 별도 구현체 필요해 2주 scope 초과.
- 개선 사이클 마무리, 종합 비교 표 작성 (10.9)

**Day 11 — Phase 2 마무리 + 버퍼**
- Day 8~10에서 밀린 측정 재실행, Baseline/개선 #1~3/A1 비교 표 최종본 작성
- `docs/improvement-{1,2,3}.md`, `docs/improvement-concurrency.md` 초안 완성
- 남는 시간에 Phase 3 준비(Redis Docker compose, Flyway V_phase3_1 작성)
- **Phase 2 완료 태그 (git tag `v2.0-optimized`)**

### Phase 3 — Redis 도입 (Day 12~13)

**Day 12 — Redis 통합 & 핫패스 이전**
- Docker Compose에 Redis 추가
- **Flyway `V_phase3_1__disable_auto_increment.sql`** — `throne_reigns.id`의 AUTO_INCREMENT 비활성화 (C5)
- **`global:reign_seq` 부트스트랩** — `@PostConstruct`에서 `MAX(throne_reigns.id)`로 1회 초기화 (B3)
- Lua 스크립트 작성 (11.2 — `global:reign_seq` INCR + `RPUSH queue:throne_events`까지 Lua 안에서 원자 실행, B3+B5)
- `ThroneService`에 MySQL 구현체 / Redis 구현체 분리 (전략 패턴)
- `queue:throne_events` Consumer — **단일 try-catch + BLPOP** (재시도 큐잉·DLQ는 19장으로 이관)
- **`ConsumerBacklogGate` 단순 버전** — `/finalize`에서 `LLEN == 0` + 1초 polling + 30초 타임아웃 (B6 단순 버전)
- 기능 동등성 e2e 테스트 (Phase 1과 동일 API 응답) + Lua 원자성 시연(앱이 Lua 반환 직후 죽어도 Redis 상태 == 큐 상태)

**Day 13 — 실시간 리더보드 + After 측정**
- `ZINCRBY` 실시간 리더보드
- Redis Pub/Sub로 SSE fan-out 전환
- 1초 배치 리더보드 push
- **k6 시나리오 A/D 동일 조건 재측정**
- 종합 비교 표 완성 (10.9) — MVP / 개선 3종 / Phase 3 가로 비교
- **Phase 3 완료 태그 (git tag `v3.0-redis`)**

### Day 14 — 문서화 & 마무리

- README 최종본 (각 Phase별 섹션)
- 아키텍처 다이어그램 (MVP / Phase 3)
- 개선 사이클 보고서 3종 (`docs/improvement-{1,2,3}.md`)
- 성능 리포트 종합 (`docs/performance-report.md`)
- 로깅 가이드, 메트릭 리스트
- 포스트모템 템플릿

---

## 18. 포폴화 체크리스트

README가 담아야 할 것.

**프로젝트 서사**
- [ ] Phase 1 (MVP) → Phase 2 (측정·개선) → Phase 3 (Redis) 의사결정 흐름
- [ ] **1.4 서사 원칙**: "처음부터 완벽"이 아니라 "측정 후 개선"
- [ ] **Phase 1 찬탈 p99가 높은 것은 직렬화의 구조적 특성 — Phase 3 Redis 도입의 정당화 근거**
- [ ] 각 Phase에서 배운 것 요약

**아키텍처 결정**
- [ ] 왜 MVP 단순 구현으로 시작했나
- [ ] 왜 Caffeine write-back이 MVP에 유일하게 필요했나 (UPDATE 핫스팟)
- [ ] 왜 `ReentrantLock(fair)` 메모리 락인가 — UX 단순화 + DB 커넥션 보호 (대안: DB 락 3종 10.8 실험 결과 인용)
- [ ] 왜 SSE가 기본값인가 (12장 수치 인용)
- [ ] EC2 t3.medium 선택 근거
- [ ] `Known limitation`: `throne_claims`는 이벤트 당일 피크 트래픽에서 빠르게 증가할 수 있으며, 장기 운영 시 파티셔닝 또는 보관 정책이 필요함을 명시

**MVP 코드 하이라이트 (Phase 1)**
- [ ] `ThroneService.claim()` 이벤트별 `ReentrantLock` + `TransactionTemplate` 구조 (`lock → tx → afterCommit → unlock` 순서)
- [ ] `throneMap` / `lockMap` 라이프사이클 3시점 (9.1-bis)
- [ ] `AggregationCache` write-back + `pending.remove` 안전 flush + `@PreDestroy`
- [ ] SSE handshake OAuth2/JWT 검증
- [ ] `LogContextFilter` + MaskingLayout

**개선 사이클 (Phase 2)** ⭐
- [ ] **개선 #1**: 현재 왕 읽기 캐시 — 증상/원인/조치/효과 수치
- [ ] **개선 #2**: 감사 로그 배치 INSERT — before/after INSERT QPS
- [ ] **개선 #3**: SSE 동기 → 비동기 — 믹스 시나리오 p99 변화
- [ ] **동시성 제어 전략 A0/A1 비교 표** (ReentrantLock 기준선 vs FOR UPDATE) — NOWAIT / 낙관적 락 비교는 19장 "추후 개발 예정"
- [ ] SSE 선택 근거 (12장) — WebSocket 정량 비교 실험은 19장 "추후 개발 예정"

**Phase 3 이행**
- [ ] Redis Lua 스크립트 + Consumer
- [ ] 영속성 경로 (Redis 핫패스 + MySQL 이벤트 소싱)
- [ ] k6 동일 시나리오 Phase 3 재측정

**동시성 증명**
- [ ] k6로 200 VU 동시 찬탈 → 1건만 성공 (MVP & Phase 3 모두)
- [ ] MVP: `throne_lock_wait_duration` / `throne_lock_queue_length` 수치로 직렬화 비용 가시화
- [ ] Redis Lua 실행 지표

**로깅 역량**
- [ ] 로그 레벨 규율 문서
- [ ] JSON 로그 샘플 (찬탈 1건 추적)
- [ ] 마스킹 before/after
- [ ] MDC로 requestId 역추적 사례

**관측성**
- [ ] 커스텀 메트릭이 개선 서사와 1:1 연결됨을 표로 증명 (14.2)
- [ ] MVP vs Phase 3 PromQL 수치 비교 표 (`docs/performance-report.md`)

**성능 종합 (10.9 표)** ⭐⭐
- [ ] Baseline (MVP)
- [ ] 개선 #1 적용 후
- [ ] 개선 #2 적용 후
- [ ] 개선 #3 적용 후
- [ ] 동시성 전략 A1(FOR UPDATE) 비교 결과
- [ ] Phase 3 (Redis) After
- [ ] **전체 배율 개선 수치** (MVP → Phase 3)

**튜닝 경험**
- [ ] HikariCP 풀 사이즈 측정 기반 선택 근거
- [ ] `innodb_lock_wait_timeout` 튜닝 (10.8 A1 실험 시 한정 적용)
- [ ] JDBC `rewriteBatchedStatements` 배치 INSERT 효과 (개선 #2)

**운영 경험**
- [ ] GitHub OAuth App 셋업 절차
- [ ] Nginx SSE / WS 설정 차이
- [ ] 배포 스크립트
- [ ] 이벤트 당일 기록 / 포스트모템

---

## 부록 A — 역량 매핑 (채용 관점)

| 채용 공고 키워드 | 이 프로젝트의 증빙 |
|---|---|
| "RDBMS 트랜잭션/락 경험" | 10.8 동시성 전략 비교 실험 (ReentrantLock vs FOR UPDATE 수치 비교), `closeReign` 조건부 UPDATE + row 수 검증 |
| "캐시 전략 수립" | Caffeine write-back + flush 주기 선택 근거 |
| "Redis 경험" | Lua 원자 처리, ZSET, Pub/Sub |
| "동시성 제어" | k6 동시 요청 → 1건만 성공 증명 (양 단계), `ReentrantLock` vs DB 락 수치 비교 |
| "로그 기반 문제 해결" | JSON + MDC + 마스킹 + 규율 문서 |
| "APM/모니터링" | Prometheus + 커스텀 메트릭 + PromQL 기반 before/after 수치 비교 |
| "부하테스트" | k6 다중 시나리오 + baseline/after |
| "성능 최적화" | Phase 1 → Phase 3 수치 비교 |
| "기술 선정 능력" | SSE 선택 근거(12장), 10.8 동시성 전략 비교 |
| "OAuth2/SSO" | GitHub OAuth2 Client 연동 |
| "용량 산정" | EC2 스펙 계산 근거 |
| "실시간 시스템" | SSE + Pub/Sub + 200 동시 연결 |
| "운영 배포" | Docker Compose + Nginx + HTTPS + 공개 서비스 |

---

## 부록 B — MySQL 최적화 체크리스트

v4.2에서는 이 리스트를 **두 그룹으로 분리**한다: MVP에 포함할 것 vs Phase 2 개선 사이클에서 도입할 것. 이 구분 자체가 "측정 후 필요한 것만"이라는 원칙의 증거다.

### B.1. MVP에 포함 (Phase 1 Day 1~7)

기본기에 해당하므로 처음부터 적용. 없으면 MVP가 바로 무너진다.

- [ ] **이벤트당 `ReentrantLock(fair)` + `lockMap` `ConcurrentHashMap` 관리** — 찬탈 순차 처리
- [ ] **`throneMap` `ConcurrentHashMap` + 서버 부팅 시 RUNNING 이벤트 복원** — 9.1-bis 참조
- [ ] **판정 순서 `NOT_RUNNING → ALREADY_OWNER → COOLDOWN`** — 9.1 스텝 5~7 (C2)
- [ ] **`closeReign`의 `WHERE ended_at IS NULL` + row 수 1 검증** — 9.1 스텝 8 (C3)
- [ ] **감사 로그는 락 안에서만** — 락 바깥은 메트릭 힌트 only(`throne_fast_reject_hint_total` Counter), 실제 rejection 없음 (B1, v4.3.5)
- [ ] **`ApiReadinessFilter`** — ApplicationReadyEvent 이전 POST API 503 (I2)
- [ ] **@Transactional(timeout = 3) / TransactionTemplate 동일 타임아웃**
- [ ] **@Transactional(readOnly = true)** — 읽기 트랜잭션 최적화
- [ ] **집계 Caffeine write-back + SYSTEM 필터 4군데** — `event_participants` UPDATE 핫스팟 회피
- [ ] **`event_participants(event_id, total_hold_ms DESC)`** 커버링 인덱스
- [ ] **`current_throne`을 별도 테이블** — events 본체와 행 경합 분리
- [ ] **`idx_cd_expires`** 쿨타임 정리용 인덱스
- [ ] **`innodb_buffer_pool_size = 256M`** — t3.medium 기준
- [ ] **HikariCP `maximumPoolSize = 10`** — 초기값 (공식: `(core_count * 2) + spindle_count`, t3.medium 2 vCPU)
- [ ] **HikariCP `connectionTimeout = 2000ms`**
- [ ] **JDBC `cachePrepStmts=true` / `prepStmtCacheSize=250`**
- [ ] **Logback AsyncAppender** — 파일 I/O를 API 스레드에서 분리
- [ ] **CSRF 토큰 + SameSite=Lax 쿠키** — 13.7 (M9)
- [ ] **IP rate limit (Bucket4j 10 req/s)** — `/api/events/active/claim` (M3, NFR-06)
- [ ] **SSE 다중 탭 허용** — `Map<Long, Set<SseEmitter>>` (M4)

### B.2. Phase 2 개선 사이클에서 도입

MVP에선 일부러 안 넣는다. 측정으로 필요성이 드러나면 추가하고 before/after를 기록.

- [ ] **현재 왕 읽기 캐시 (Caffeine)** — 개선 #1의 조치
- [ ] **감사 로그 배치 INSERT + `rewriteBatchedStatements=true` + Guava RateLimiter 기반 WarnLimiter** — 개선 #2의 조치
- [ ] **SSE 비동기 브로드캐스트 (afterCommit 유지 + ExecutorService + @PreDestroy shutdown)** — 개선 #3의 조치
- [ ] **`innodb_flush_log_at_trx_commit = 2`** — 기본값 1과 수치 비교 실험
- [ ] **HikariCP 풀 사이즈 튜닝** — baseline 후 `hikaricp_pending_connections` 기반으로 8~15 범위 조정
- [ ] **`FOR UPDATE` (A1) 구현** — 10.8 동시성 전략 비교 (`ThroneServiceForUpdate` 별도 구현체)
- [ ] **`innodb_lock_wait_timeout = 3`** — 10.8 A1 실험 시 한정 적용

> `FOR UPDATE NOWAIT` (A2) / 낙관적 락 (A3)은 19장 "추후 개발 예정"으로 이관.

### B.3. Phase 2 사이클 운영 원칙

- 한 번에 **하나의 개선만** 적용. 두 가지를 동시에 바꾸면 어느 쪽의 효과인지 알 수 없다.
- 각 개선은 **별도 커밋 + 별도 측정 문서**. Git log와 `docs/improvement-N.md`가 1:1 대응.
- 예상과 다른 결과가 나오면 그대로 기록. "개선했는데 거의 안 바뀜" 또는 "오히려 나빠짐"도 유효한 포폴 콘텐츠다.

### B.4. 측정 전 체크리스트 (Day 8 baseline 전)

- [ ] B.1 항목 전부 적용되어 있는가
- [ ] 로그 레벨 INFO 이상 (DEBUG는 별도 실험 시)
- [ ] Prometheus scrape 정상, PromQL 쿼리 7종(14.4) 수치 확인
- [ ] k6 스크립트 JWT 환경변수 주입 방식 확인
- [ ] EC2 외부에서 k6 실행 (같은 머신에서 실행하면 결과가 오염됨)

---

## 19. 추후 개발 예정

v4.3.3 → v4.3.7 라운드를 거치며 설계한 운영 수준의 방어 장치 / 확장 실험 중, **1인 2주 포폴 scope에 넣기엔 과하지만 설계 자체는 남겨둘 가치가 있는** 항목들을 여기에 모은다. 각 항목마다 **"왜 지금은 안 하는가"**의 근거를 명시한다 — 이 근거가 "무작정 미구현"과 "판단력 있는 유예"를 가르는 증거다.

면접에서 이 섹션에 있는 항목을 질문받았을 때의 이상적인 답변 구조는:
1. "설계는 되어 있고, 위치는 19장 X번 항목입니다."
2. "이런 조건(규모/트래픽/운영 팀 유무)에서는 구현이 정당화되지만, 이 프로젝트의 scope(200 TPS, 단일 인스턴스, 1인 운영)에서는 관측 확률이 낮아 미구현으로 결정했습니다."
3. "대신 XX 버전을 본문에 두어 기능은 동작하며, 한계는 Known Limitations로 명시했습니다."

---

### 19.1. Consumer 내결함성 고도화 (Phase 3)

#### 19.1.1. DLQ + 재시도 3회 + 지수 backoff + 멱등 UNIQUE (I6)

**현재 본문 상태 (11.3)**: BLPOP → persistToMysql → 실패 시 에러 로그 + 다음 메시지. 재시도 큐잉 없음, DLQ 없음, 멱등 UNIQUE 없음.

**추후 개발 시 추가할 것**:
- `queue:throne_events:dlq` 리스트
- Consumer 인메모리 for-루프 blocking retry (`MAX_RETRY=3`, backoff `50ms / 150ms / 450ms`)
- `ThroneEventConsumer.consumeOne()`에서 `DataAccessException` catch 시 backoff sleep 후 같은 메시지 재시도 (**꼬리 재큐잉은 `prevReignId` 연쇄를 깨므로 금지** — 단일 Consumer + blocking retry가 순서 엄격 보장을 준다)
- 3회 실패 시 `queue:throne_events:dlq`로 이동 + `throne_consumer_dlq_total` Counter 증가 (알람 대상)
- Flyway `V_phase3_2__add_idempotency_keys.sql`: `throne_claims.reign_id BIGINT NULL` + `UNIQUE KEY uk_claims_reign (reign_id)` 추가. Consumer가 동일 메시지를 두 번 처리해도 SUCCESS 찬탈 로그는 UNIQUE로 차단. 실패 기록은 `reign_id=NULL`이라 UNIQUE 영향 없음.
- 신규 메트릭: `throne_consumer_retry_total`, `throne_consumer_dlq_total`

**왜 지금은 안 하는가**:
- **관측 확률**: 1인 프로젝트 + 단일 MySQL 환경에서 Day 12~13 k6 측정 기간(수 시간)에 DB 일시 장애가 발생할 확률은 실질적으로 0에 가깝다. 재시도 로직을 구현해도 **한 번도 안 타는 코드**가 되어 수치/서사 기여가 없다.
- **복잡도 대비 서사 기여**: DLQ 구현은 로직 자체보다 "DLQ 메시지 확인 절차"와 "prevReignId 연쇄 깨짐 시 수동 복구 플레이북"까지 맞물려야 완결된다. 1~2시간에 끝나지 않고 포폴 서사의 주축(Phase 1 → Redis 돌파)을 희석시킨다.
- **대체 증빙**: README `Known Limitations`에 "DB 일시 장애 기간의 메시지는 유실되며 복구는 운영자 수동 검사 대상"을 명시 + 19장 이 섹션을 가리키면, "설계는 완결됐고 scope 판단으로 보류"의 증거가 된다.

#### 19.1.2. finalize 3중 게이트 (R1)

**현재 본문 상태 (15.2)**: `ConsumerBacklogGate`는 `LLEN queue:throne_events == 0`만 1초 간격으로 polling, 30초 타임아웃.

**추후 개발 시 추가할 것**:
- `AtomicInteger inFlightCount` — Consumer가 `leftPop` 직후 증가, `persistToMysql` 완료/실패/DLQ 이동 시 감소
- `AtomicLong dlqBaseline` — finalize 진입 시점의 `throne_consumer_dlq_total` 스냅샷
- `ConsumerBacklogGate.awaitOrThrow`: 500ms 간격으로 **3가지 조건 동시 polling**:
  1. `LLEN queue:throne_events == 0`
  2. `throneEventConsumer.getInFlightCount() == 0`
  3. `current_dlq_total == dlqBaseline` (대기 중 DLQ 이동 발생하면 즉시 409)

**왜 지금은 안 하는가**:
- **조건 #2(in-flight)**는 `leftPop` 직후 `persistToMysql` 실행 중인 **수 ms ~ 수십 ms** 타이밍 창을 잡는 것. 200 TPS + 단일 Consumer에서 이 창에 finalize가 걸릴 확률이 극히 낮다. 걸리더라도 `current_throne`은 이전 상태 그대로라 사용자가 관측할 불일치가 없다.
- **조건 #3(DLQ baseline)**은 19.1.1(DLQ 자체)이 구현되어야 의미를 가진다. DLQ를 안 만들면 조건 #3도 필요 없다.
- **본문의 단순 버전으로 충분**: `LLEN==0 + 1초 wait`만으로 200 TPS 규모에서 99.9% 케이스 커버 + 판단 근거가 명확하다.

#### 19.1.3. Redis Streams + XACK 모델 전환 (Consumer 크래시 내성)

**현재 본문 상태 (11.3)**: Redis List + BLPOP 패턴. Consumer가 `leftPop` 직후 `persistToMysql` 실행 중 크래시하면 메시지 유실.

**추후 개발 시 교체할 것**:
- 큐 자료구조: `List` (`RPUSH`/`BLPOP`) → `Streams` (`XADD`/`XREADGROUP`/`XACK`)
- Consumer 그룹 등록 + Pending Entries List(PEL) 관리
- 크래시 후 재시작 시 `XCLAIM`으로 미처리 메시지 재할당
- Lua 스크립트의 `RPUSH KEYS[5] payload` → `XADD KEYS[5] * data payload`로 변경 (Lua 내부 원자성은 그대로)

**왜 지금은 안 하는가**:
- **구조적 한계 vs 포폴 서사**: Redis List + BLPOP의 크래시 내성 한계는 **구조적 한계**라 "전환"이 정답인데, 이 전환은 Phase 3 "List를 쓸 수밖에 없는 이유"를 애초에 사라지게 만든다. 포폴 서사에서 "List로 시작 → 한계 인지 → Streams가 정공법임을 문서로 명시"가 오히려 학습 증거가 된다.
- **Streams는 Redis 전용 기술 트리** 하나를 추가로 요구한다 (Consumer 그룹, PEL, XCLAIM 재할당 로직). Phase 3 Day 12~13의 8시간 × 2 내에서 Lua + List + Consumer를 안정시키는 것 자체가 빠듯하다.
- **대체 증빙**: README에 "Consumer 크래시 내성은 Streams로 전환하는 것이 정공법"을 명시하고 이 섹션을 가리키는 것만으로 "구조적 한계를 인지하고 있다"는 증거가 된다.

---

### 19.2. Redis 장애 복구 경로

#### 19.2.1. Redis 전체 유실 시 meta/throne Hash 재구성 (R4)

**시나리오**: Redis 컨테이너 재생성, 볼륨 삭제 등으로 Redis가 통째로 유실된 상태에서 앱을 재기동하는 경우.

**추후 개발 시 추가할 것**:
- `@PostConstruct`에서 `SELECT * FROM events WHERE status='RUNNING'` 실행 → RUNNING 이벤트 발견 시:
  1. `current_throne` 조회
  2. `HSET event:{id}:meta status RUNNING cooldownMs <v>`
  3. `HSET event:{id}:throne ownerId <ct.user_id> heldSince <ct.held_since> reignId <ct.reign_id>` 재구성
- cooldown 키들은 유실 허용 — 짧은 TTL이라 최악의 경우 쿨타임 중인 일부 유저가 한 번 쿨타임 없이 찬탈 가능하며, 다음 성공 찬탈로 자연 복구
- `docs/ops-runbook.md`에 "Redis 장애 복구" 절차 문서화

**왜 지금은 안 하는가**:
- **운영 레벨 이벤트**: Redis 통째 유실은 1인 프로젝트에서 **의도적으로 재현할 일이 없다**. k6 측정 기간에 발생하지도 않는다.
- **`@PostConstruct` 부트스트랩만으로도 앱 재기동은 커버**: `global:reign_seq` 재동기화(본문 11.2-bis)가 있어 "Redis 살아있는 상태에서의 앱 재기동"은 이미 안전.
- **대체 증빙**: `docs/ops-runbook.md`에 복구 절차만 문서화. 면접에서 "Redis 유실은 운영 레벨 드문 사건이라 복구 절차 정의를 실제 구현보다 우선했다"고 답변.

#### 19.2.2. `/start` 직전 reign_seq 2차 재동기화 (R2 2차)

**시나리오**: 앱은 살아있고 Redis 컨테이너만 독립적으로 재시작된 경우. `global:reign_seq == nil`이 되어 다음 `INCR`가 1을 반환 → MySQL의 reign_id=1과 PK 충돌.

**추후 개발 시 추가할 것**:
- `/start` 커밋 직전(또는 `afterCommit` 초반)에 11.2-bis의 원자 조건부 `EVAL`을 **한 번 더** 실행:
  ```
  EVAL 'local c=tonumber(redis.call("GET",KEYS[1])) or 0;
        if c < tonumber(ARGV[1]) then redis.call("SET",KEYS[1],ARGV[1]) end;
        return c' 1 global:reign_seq <MAX(throne_reigns.id)>
  ```
- 결과: 앱 기동 1회 + `/start` 1회 = 이중 부트스트랩으로 "Redis 단독 재시작" 시나리오 자동 복구

**왜 지금은 안 하는가**:
- **시나리오 재현 확률**: "앱은 살아있고 Redis만 재시작" 시나리오는 Docker Compose 환경에서 `docker restart redis`를 명시적으로 실행해야 발생한다. 포폴 시연/측정 중 자연스럽게 발생하지 않는다.
- **본문의 1회 부트스트랩으로 재기동 시나리오 커버 충분**: 앱 자체를 재시작하면 `@PostConstruct`가 트리거되므로 "앱 + Redis 동시 재시작"은 커버된다.
- **대체 증빙**: README에 "Redis만 독립 재시작된 시나리오에서 다음 `/start` 직전에 재동기화가 필요함"을 Known Limitation으로 명시.

---

### 19.3. 동시성 전략 A2/A3 비교 실험 (I4)

**현재 본문 상태 (10.8)**: A0 ReentrantLock(기준선) vs A1 FOR UPDATE 비교만 수행.

**추후 개발 시 추가할 실험**:
- **A2 — `FOR UPDATE NOWAIT`**: 락 즉시 획득 실패 시 `409 LOCK_CONFLICT`. `ThroneServiceForUpdateNowait` 구현체. UX 측면에서 "클라이언트 재시도" 패턴과의 상호작용까지 측정.
- **A3 — 낙관적 락**: `current_throne.version` (스키마에 이미 예비 컬럼 존재) 기반 CAS UPDATE 루프. `ThroneServiceOptimistic` 별도 구현체. `ReentrantLock` 제거 + version 불일치 시 재시도. 경합 적을 때 최고, 많을 때 재시도 폭증하는 특성을 수치로 입증.
- 10.9 종합 표에 A2, A3 행 추가. 네 전략을 한 표에서 비교하면 "JVM 락 vs DB 대기 락 vs DB 즉시실패 락 vs 낙관적 재시도"의 trade-off 지도가 완성된다.

**왜 지금은 안 하는가**:
- **구현 비용**: 각각 **별도 ThroneService 구현체**가 필요하다. 특히 A3은 판정 순서(`NOT_RUNNING → ALREADY_OWNER → COOLDOWN`)를 유지하면서 CAS 루프 + 재시도 한계 + 재시도 간 쿨타임 갱신 정합성까지 맞춰야 해 A1/A2보다 2~3배 작업량이다.
- **서사적 충분성**: "MVP에서 ReentrantLock을 고른 이유"를 수치로 정당화하려면 **A1 하나**면 충분하다. 같은 "대기" UX에서 DB 커넥션을 블로킹하는 쪽이 훨씬 비싸다는 것만 보이면 `ReentrantLock` 선택이 정당화된다. Phase 3 Redis Lua의 정당화는 A0 자체의 VU 비례 p99 상승으로 이미 성립된다.
- **대체 증빙**: 10.8에 두 실험을 남긴 설계 근거를 남기고 "scope 판단으로 A2/A3 미수행" 명시.

---

### 19.4. SSE vs WebSocket 정량 비교 실험

**현재 본문 상태 (12장)**: SSE 선택 근거를 **설계상으로만** 제시. k6 수치 비교 없음.

**추후 개발 시 추가할 것**:
- `/ws/throne` 엔드포인트 + handshake 인터셉터(JWT 쿠키 검증) + `WsPublisher` 구현
- 메시지 스키마는 SSE와 1:1 동일 (`throne_changed`, `leaderboard_updated`, `event_status`)
- 동일한 `ThroneChangedEvent`를 SsePublisher / WsPublisher **병렬 발행**
- k6 시나리오:
  - **Scenario-B (SSE 200 연결, 5분)**: `k6/x/sse` 또는 HTTP stream 직접 사용
  - **Scenario-C (WS 200 연결, 5분)**: `k6/ws` 사용
- 측정 지표: 서버 RAM 증가분, Tomcat/Netty thread count, 연결 수립 지연 p99, 메시지 수신 지연 p99, 구현 LoC, Nginx 설정 추가 사항, 네트워크 트래픽 KB/연결/분
- 보고: `docs/sse-vs-ws-report.md`에 수치 표 + 결론

**왜 지금은 안 하는가**:
- **설계상 이미 결정됨**: 12장의 5가지 근거(단방향 요구, 자동 재연결, HTTP 인증 재사용, Nginx 단순 튜닝, 브라우저 호환)로 SSE 선택이 명확하다. 수치 비교로 결론이 뒤집힐 가능성이 낮다.
- **200 VU 규모의 정보 가치**: 두 방식 모두 "동작은 한다" 수준의 결과가 나올 가능성이 높아 **유의미한 차이가 측정되지 않는 실험**이 될 공산이 크다. 수치 차이 없는 비교 표는 포폴 기여가 낮다.
- **대체 증빙**: 면접에서 수치 비교를 요구받으면 "설계상의 적합성 판단으로 SSE를 선택했으며, 정량 비교는 확장 과제로 분류"라고 답변.

---

### 19.5. RUNNING 1개 제약의 DB 레벨 보강 (M12)

**현재 본문 상태 (8.2)**: 앱 레이어에서 `/start` 트랜잭션 시작 시 `SELECT id FROM events WHERE status='RUNNING' FOR UPDATE` 체크.

**추후 개발 시 추가할 것**:
- DDL에 generated column 추가:
  ```sql
  ALTER TABLE events
    ADD COLUMN running_marker TINYINT
      GENERATED ALWAYS AS (CASE WHEN status = 'RUNNING' THEN 1 END) VIRTUAL,
    ADD UNIQUE KEY uk_events_running (running_marker);
  ```
- NULL은 UNIQUE 중복 허용이라 `status='RUNNING'` 행에만 유니크 제약이 적용된다.

**왜 지금은 안 하는가**:
- **MySQL 버전/옵티마이저 의존성**: generated column `VIRTUAL`에 대한 UNIQUE 인덱스 지원 범위가 MySQL 버전에 따라 다르다. 로컬 개발 MySQL과 EC2 MySQL 버전이 달라지면 DDL이 한쪽에서만 실패하는 운영 리스크가 생긴다.
- **앱 레이어 보강으로 동일 효과**: `SELECT ... FOR UPDATE` + 앱 체크는 `idx_events_status`를 활용해 비용도 낮고, 버전 비의존적이며, 오류 메시지(`409 ALREADY_RUNNING`)가 명확하다.
- **대체 증빙**: 앱 레이어 체크가 본문에 명시되어 있으므로 "DB 보강도 설계했으나 버전 의존성으로 앱 레이어 채택"이 판단 근거로 읽힌다.

---

### 19.6. 확장성 — 멀티 인스턴스 + throne_claims 파티셔닝

#### 19.6.1. 멀티 인스턴스 + Redis Pub/Sub fan-out

**현재 본문 상태**: 단일 인스턴스 전제. SSE fan-out은 단일 JVM 메모리의 `ConcurrentHashMap<Long, Set<SseEmitter>>`.

**추후 개발 시 추가할 것**:
- EC2 인스턴스 2대 이상, ALB 스티키 세션(SSE 연결 유지) 또는 Nginx upstream 라운드 로빈
- 각 인스턴스가 **자신이 보유한 SseEmitter만** 로컬 Map에 가지고 있고, 왕좌 변경 이벤트는 Redis Pub/Sub로 전 인스턴스에 전파 → 각 인스턴스가 로컬 SseEmitter에 broadcast
- `ThroneEventConsumer`는 단일 인스턴스로만 돌려야 중복 영속화가 없다 → **분산 락**(`SETNX event:consumer:leader`) + leader election

**왜 지금은 안 하는가**:
- **현재 용량 계산(7.1)이 단일 t3.medium에서 200 TPS를 수용**: 멀티 인스턴스의 실질적 필요가 없다. 수치로 정당화되지 않는 확장은 "그냥 복잡도를 위한 복잡도"로 읽힌다.
- **리더 선출·세션 스티키·Pub/Sub 전파**는 각각 별개의 운영 이슈 트리를 여는 작업이다. 2주 scope에서 Phase 3 자체를 끝내는 것이 먼저.
- **대체 증빙**: Phase 3 단일 인스턴스 설계에 "멀티 인스턴스로 확장할 경우 Pub/Sub + leader election이 필요함"을 README Architecture 섹션에 짧게 명시.

#### 19.6.2. throne_claims 파티셔닝

**현재 본문 상태 (8.3 Known limitation)**: append-only 로그 특성상 이벤트 당일 피크 트래픽에서 빠르게 증가. 장기 운영 시 보관/파티셔닝 필요성만 언급.

**추후 개발 시 추가할 것**:
- `PARTITION BY RANGE (TO_DAYS(requested_at))`로 일 단위 파티션
- 파티션 자동 생성 스케줄러 + N일 경과 파티션 자동 DROP
- 이벤트 아카이빙: ENDED 이벤트의 throne_claims를 별도 아카이브 DB로 이전

**왜 지금은 안 하는가**:
- **문제 규모**: 200명 × 3~4시간 이벤트 × 찬탈 5~10초 간격 = 단일 이벤트 최대 수만~수십만 건. MySQL 단일 테이블로 수백만 행은 인덱스만 있으면 문제없다. 장기(수개월 단위) 운영에서만 이슈가 된다.
- **포폴 scope 밖**: 이벤트 반복 개최 50회 이상 누적돼야 의미 있는 이슈가 된다. 2주 프로젝트의 문제가 아니다.
- **대체 증빙**: 현재 본문 8.3의 "Known limitation" 문구로 충분. 면접에서 "장기 운영 시 파티셔닝 또는 별도 archive DB가 정공법"이라 답변.

---

### 19.7. 요약 표

| 항목 | 본문 위치 | 이관 이유 요약 |
|---|---|---|
| Consumer DLQ + 재시도 + 멱등 UNIQUE | 11.3 단순 버전 → 19.1.1 | 200 TPS + 단일 MySQL에서 관측 확률 0에 가까움 |
| finalize 3중 게이트 (in-flight, DLQ baseline) | 15.2 LLEN==0 단순 버전 → 19.1.2 | 수 ms 타이밍 창 + DLQ 의존 — 본문 단순 버전으로 99.9% 커버 |
| Redis Streams + XACK 전환 | 11.3 List+BLPOP → 19.1.3 | 구조적 한계 인지가 포폴 서사로는 더 가치 있음 |
| Redis 전체 유실 재구성 (R4) | 11.2-bis → 19.2.1 | 운영 레벨 드문 사건, 복구 절차 문서화 우선 |
| `/start` 직전 reign_seq 2차 재동기화 (R2 2차) | 11.2-bis 1회만 → 19.2.2 | "Redis만 독립 재시작" 시나리오 재현 확률 낮음 |
| 동시성 A2 NOWAIT, A3 낙관적 락 | 10.8 A1만 → 19.3 | A1 하나로 ReentrantLock 선택 정당화 충분 |
| SSE vs WebSocket 정량 비교 | 12장 선택 근거만 → 19.4 | 설계상 이미 결정, 200 VU에서 유의미 차이 측정 어려움 |
| RUNNING 1개 DB 레벨 보강 (M12) | 8.2 앱 레이어만 → 19.5 | MySQL 버전 의존성 회피, 앱 레이어로 동일 효과 |
| 멀티 인스턴스 + Redis Pub/Sub fan-out | — → 19.6.1 | 단일 t3.medium로 200 TPS 수용 가능 |
| throne_claims 파티셔닝 | 8.3 Known limitation → 19.6.2 | 장기 운영 이슈, 2주 scope 밖 |

---

### 19.8. README `Known Limitations` 템플릿

위 19.1~19.6 항목은 README에 아래와 같이 명시적으로 기록한다. 이 섹션 자체가 **"과도한 방어를 피하고 실용적 판단을 내린 증거"**로 포폴에서 읽힌다.

```markdown
## Known Limitations

이 프로젝트는 1인 2주 포폴 scope로 진행됐으며, 다음 항목들은 **설계는 완결됐으나 구현은 보류**했다.
상세 근거는 [spec v5.0 §19 "추후 개발 예정"](./docs/spec.md#19-추후-개발-예정) 참조.

- **Consumer 크래시 내성**: Redis List + BLPOP 패턴은 Consumer가 `leftPop` 직후 MySQL 영속화 중
  크래시하면 해당 메시지를 유실한다. 프로덕션에서는 Redis Streams + XACK로 전환하거나
  `BRPOPLPUSH ... processing` 패턴을 도입해야 한다.

- **Consumer 재시도 / DLQ 없음**: Phase 3 MVP Consumer는 1회 시도 + 실패 시 에러 로그만 남긴다.
  DB 일시 장애 기간의 메시지는 유실되며, 운영자가 로그를 보고 수동 복구한다.

- **finalize 게이트 단순 버전**: `LLEN queue:throne_events == 0` + 1초 polling만 확인한다.
  `leftPop` 직후 `persistToMysql` 실행 중인 in-flight 메시지는 관측하지 못하지만,
  200 TPS 규모 + `current_throne` 일관성 유지로 실질 영향 없음.

- **Redis 전체 유실 복구**: Redis 통째 유실 시 `@PostConstruct`에서 meta/throne Hash를
  재구성하는 절차는 `docs/ops-runbook.md`에 문서화했으며 실제 코드는 없다.

- **단일 인스턴스 전제**: SSE fan-out이 JVM 로컬 Map이라 멀티 인스턴스 확장 불가.
  확장 시 Redis Pub/Sub + leader election(분산 락)이 필요하다.

- **`throne_claims` 파티셔닝 없음**: append-only 로그라 장기(수개월) 운영 시 보관 정책 필요.
```
