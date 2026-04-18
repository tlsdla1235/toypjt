# SSAFY 왕좌전 (Throne War) — 최종 서비스 정의서 v4.3.1

> **v4.3 → v4.3.1 변경 요약 (착수 전 마무리 정리)**
>
> - 이벤트 시작 시 `current_throne`을 공석으로 두지 않기 위해, seed된 `SYSTEM` 사용자로 초기 reign을 생성하는 정책을 명시
> - 일정 리스크를 반영해 Phase 2의 락 전략 비교와 WebSocket 비교를 `optional` 실험으로 하향
> - `throne_claims`의 고볼륨 가능성을 README의 `Known limitation`으로 명시하도록 체크리스트 보강
>
> **v4.2 → v4.3 변경 요약 (정합성 보강)**
>
> - `throne_claims.result` 정의를 실제 로직/메트릭과 맞춤 (`LOCK_CONFLICT` 추가, `NO_ACTIVE_EVENT` 명시)
> - 찬탈 실패 로그는 `REQUIRES_NEW`, 성공 로그와 SSE 발행은 `afterCommit`으로 정리하여 외부 관측 정합성 보강
> - `/api/events/active/claim`의 무활성 이벤트 응답을 `404 NO_ACTIVE_EVENT`로 통일하고 `410 Gone` 문구 제거
> - 쿨타임 설명을 실제 스키마(`PRIMARY KEY (event_id, user_id)` 기반 upsert)와 일치시킴
> - SSE/WS 인증 예시를 운영 기준 `HttpOnly` 쿠키 중심으로 통일
>
> **v4.1 → v4.2 변경 요약 (MVP 정리)**
>
> v4.1은 Phase 1에 너무 많은 기능을 넣어 1인 2주 프로젝트로는 버거웠다. v4.2는 **Phase 1을 "최소 작동 버전"으로 축소**하고, 제거한 요소들을 **Phase 2의 측정-개선 사이클**로 재배치한다.
>
> - **Phase 1 MVP에서 제거 → Phase 2 개선 콘텐츠로 이동**:
>   - 현재 왕 읽기 캐시 (개선 #1)
>   - 감사 로그 배치 INSERT (개선 #2)
>   - SSE 동기 → 비동기 브로드캐스트 (개선 #3)
>   - WebSocket 구현 (Phase 2 비교 실험 단계)
> - **Phase 1에 남는 것 (MVP 핵심 2종)**:
>   - `FOR UPDATE NOWAIT` — 대기 큐 폭주 방지
>   - Caffeine write-back (집계용) — UPDATE 핫스팟 회피
> - **Phase 2 확장**: baseline 측정 → 개선 3회 → 재측정 → SSE vs WS → 종합 비교
> - **타임라인 재조정**: Phase 1 (Day 1~7) → Phase 2 (Day 8~11) → Phase 3 (Day 12~13) → 문서화 (Day 14)
> - **포폴 서사 강화**: "처음부터 완벽"이 아니라 **"측정하며 하나씩 개선"**이 더 실무적이고 이야기가 많다

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
12. [SSE / WebSocket 비교 실험](#12-sse--websocket-비교-실험)
13. [로깅 전략](#13-로깅-전략)
14. [메트릭 & 관측성](#14-메트릭--관측성)
15. [시상 & 결과 집계](#15-시상--결과-집계)
16. [도메인 서비스 & API 명세](#16-도메인-서비스--api-명세)
17. [개발 일정 (2주)](#17-개발-일정-2주)
18. [포폴화 체크리스트](#18-포폴화-체크리스트)

---

## 1. 프로젝트 목적 & 학습 서사

### 1.1. 왜 이 프로젝트인가

**이 프로젝트는 "빠른 MVP → 정량적 병목 측정 → 기술 도입으로 개선" 이라는 실무 의사결정 사이클을 1인 프로젝트 규모로 재현한다.** 단순히 기술 스택을 나열하는 포폴이 아니라, **"왜 이 기술을 썼나"에 수치로 답할 수 있는 포폴**을 만드는 것이 목표다.

### 1.2. 학습 포인트 (채용 관점)

| # | 학습 목표 | 포폴 증빙 |
|---|---|---|
| 1 | **DB 레이어에서의 동시성 제어** — 트랜잭션, 잠금 전략 | MySQL 단계의 `SELECT ... FOR UPDATE` 구현 + K6 검증 |
| 2 | **캐시 도입 설계** — write-back / write-through / read-through | Caffeine으로 집계 캐시 + 주기적 flush 구현 |
| 3 | **실시간 푸시 구현** — SSE vs WebSocket 비교 | 두 방식 모두 구현 후 k6로 정량 비교 |
| 4 | **구조화 로깅 & MDC** — 트레이싱 가능한 애플리케이션 | JSON 로그 + requestId/userId 추적 |
| 5 | **부하테스트 기반 병목 분석** | k6 baseline → 개선 → 재측정의 before/after 수치 |
| 6 | **기술 선택의 근거 정량화** | "Redis 도입 전 MySQL 한계 수치 → Redis 도입 후 개선 수치" |
| 7 | **OAuth2 인증 연동** | GitHub OAuth2 + SSE handshake 보안 |

### 1.3. 3단계 서사 구조

```
   Phase 1 (Day 1~7)            Phase 2 (Day 8~11)                 Phase 3 (Day 12~13)
 ┌──────────────────────┐    ┌──────────────────────────────┐    ┌─────────────────────┐
 │ MVP 구축             │ →  │ 측정 → 개선 → 재측정 반복    │ →  │ Redis 도입          │
 │ - 동작하는 최소 버전 │    │ - baseline                   │    │ - Lua 원자 처리     │
 │ - NOWAIT + 집계캐시  │    │ - 개선 #1 읽기 캐시          │    │ - 실시간 리더보드   │
 │ - SSE만 (WS는 Phase2)│    │ - 개선 #2 감사로그 배치      │    │ - After 수치 비교   │
 │                      │    │ - 개선 #3 SSE 비동기         │    │                     │
 │                      │    │ - WebSocket 추가 + 비교      │    │                     │
 └──────────────────────┘    └──────────────────────────────┘    └─────────────────────┘
```

### 1.4. 서사의 핵심 가설 (⭐ 포폴 차별점)

이 프로젝트는 "성공한 프로젝트"를 만드는 게 아니다. **"MySQL 기반 MVP는 어디서 무너지는가를 수치로 드러내고, 단계적 개선으로 어디까지 끌어올릴 수 있는지, 그리고 그 한계를 Redis가 어떻게 돌파하는지"**를 보여주는 것이 목표다.

- **Phase 1 MVP는 의도적으로 "최적화 전의 단순 구현"이다.** 여기엔 구현의 함정과 한계가 많이 숨어 있다.
- **Phase 2는 그 함정을 하나씩 노출하고 수치 기반으로 수정**한다. 개선 하나당 "증상 → 원인 → 조치 → 효과"를 문서화한다.
- **Phase 3은 MySQL로 끌어올릴 수 있는 상한을 넘어서기 위해** Redis를 도입하고, 동일 시나리오에서 목표를 달성한다.

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
| 왕좌 찬탈 (MVP) | 1 | MySQL `FOR UPDATE NOWAIT` 단순 트랜잭션 |
| 집계 write-back | 1 | Caffeine으로 `event_participants` UPDATE 핫스팟 회피 |
| 5분 배치 리더보드 | 1 | 스냅샷 테이블 |
| SSE 실시간 푸시 | 1 | OAuth2 인증된 세션만 handshake (커밋 후 동기 발행) |
| 3대 시상 | 1 | 최장 보유 / 최다 탈취 / 마지막 왕 |
| 구조화 로깅 | 1 | JSON + MDC + 마스킹 |
| 기본 메트릭 | 1 | Spring Actuator + Prometheus + Grafana |
| k6 baseline 측정 | 2 | MVP의 한계 수치 확보 |
| 개선 #1 현재 왕 읽기 캐시 | 2 | Caffeine 2초 TTL |
| 개선 #2 감사 로그 배치 INSERT | 2 | 500건/500ms 큐 + `rewriteBatchedStatements` |
| 개선 #3 SSE 비동기 브로드캐스트 | 2 | 별도 ExecutorService |
| WebSocket 대안 구현 | 2 | SSE와 동일 기능, 비교 실험용 |
| Redis Lua 원자 처리 | 3 | 찬탈 핫패스 이전 |
| Redis ZSET 실시간 리더보드 | 3 | 5분 배치 대체 |
| Redis Pub/Sub SSE fan-out | 3 | 멀티 인스턴스 대비 |

### 2.2. 명시적 제외

- 유저 프로필 / 팔로잉 / 채팅 등 소셜 기능
- 동시 복수 이벤트 (1 RUNNING per time)
- 모바일 앱, PWA
- 이메일 알림, SMS
- Python/R 기반 오프라인 분석 — Java로 대체

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
- **FR-01-5**: 재접속 시 JWT로 자동 인증, 만료 시 OAuth2 재인증.
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

- **FR-03-1**: RUNNING 이벤트 없음 → `404 NO_ACTIVE_EVENT`.
- **FR-03-2**: 본인이 현재 왕 → `409 ALREADY_OWNER`.
- **FR-03-3**: 쿨타임 중 → `429 COOLDOWN` + 남은 ms.
- **FR-03-4**: 다른 요청이 락 선점 → `409 LOCK_CONFLICT` (Phase 1 NOWAIT 즉시 실패). 클라이언트는 재시도 여부를 직접 결정.
- **FR-03-5**: 성공 시 3초 쿨타임 시작, 이전 왕의 보유 시간 확정.
- **FR-03-6**: 찬탈 시도(성공/실패)는 `throne_claims` 테이블에 기록. (Phase 1은 매 요청 INSERT, Phase 2 개선 #2에서 배치로 전환.)
- **FR-03-7**: (Phase 1) MySQL `FOR UPDATE NOWAIT` 단순 트랜잭션. (Phase 3) Redis Lua로 대체.

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

### FR-06. WebSocket (Phase 2 비교 구현)

Phase 1에서는 SSE만 제공하며, **Phase 2 비교 실험 단계에서 WebSocket을 추가로 구현**해 두 방식을 정량 비교한다.

- **FR-06-1**: `/ws/throne` 엔드포인트로 SSE와 동일한 정보 전송.
- **FR-06-2**: STOMP 또는 순수 WebSocket (구현 시 결정).
- **FR-06-3**: handshake 인터셉터에서 JWT 검증. 운영 기준은 SSE와 동일하게 **HttpOnly 쿠키 기반**을 사용한다.
- **FR-06-4**: Phase 2 비교 시나리오 이후 운영 기본값은 수치 기반 결정 (SSE 유지가 기본 가정).

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

- **Phase 1**: MySQL `SELECT ... FOR UPDATE`로 `current_throne` 행 락 + 트랜잭션. 쿨타임은 `cooldowns`의 `PRIMARY KEY (event_id, user_id)` 기반 upsert로 관리하고, `expires_at` 인덱스로 만료 정리를 보조한다.
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
| 찬탈 API p99 | 수백 ms 예상 | 개선 효과 누적 | < 100ms 달성 |
| 찬탈 성공 TPS | ~30~50 TPS | 일부 개선 | 200 TPS |
| 리더보드 p99 | < 300ms | < 200ms (읽기 캐시) | < 50ms |
| 동시 SSE 연결 | 200 제한적 | 200 안정 (비동기화) | 300 안정 |

> MVP는 "동작하는 최소"를 목표로 한다. 성능 SLO 달성은 Phase 2의 단계적 개선 + Phase 3의 Redis 도입으로 이룬다. 각 단계의 수치를 10.9 종합 비교 표에 기록한다.

**MVP에서 드러날 것으로 예상되는 병목** (Phase 2에서 검증 + 개선)
- `current_throne` 단일 행 락 직렬화 → InnoDB row lock wait
- 감사 로그 매 요청 INSERT → INSERT QPS 급증
- SSE 동기 브로드캐스트 → 연결 수 증가 시 찬탈 p99 악화
- 읽기 경로의 DB 히트 → 쓰기 경합에 물려 p99 불안정

### NFR-03. 영속성

- **Phase 1 & 3 공통**: MySQL이 Source of Truth. 모든 찬탈 이벤트는 `throne_claims` 테이블에 append-only.
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
- Prometheus가 scrape, Grafana로 시각화.
- **v3 대비 제거**: node-exporter, AlertManager, Discord 웹훅 (필요 시 추후 추가).
- 대시보드는 성능 비교가 목적 → 핵심 지표 1개 화면에 몰아넣기.

### NFR-06. 부정 방지

- IP 기반 rate limit 10 req/s (Bucket4j 또는 Spring filter).
- 1 GitHub 계정 = 1 참가자.
- 관리자 수동 블록 (`users.blocked = true`).

---

## 6. 확정 기술 스택

| 구분 | 기술 | 비고 |
|------|------|------|
| Language | Java 17 | |
| Framework | Spring Boot 3.x | MVC + SseEmitter + WebSocket |
| Security | Spring Security + OAuth2 Client + JWT | GitHub provider |
| DB | MySQL 8.x | Source of truth |
| ORM | Spring Data JPA + QueryDSL (선택) | |
| 로컬 캐시 | Caffeine | Phase 1 write-back |
| 분산 캐시/원자 연산 | Redis 7 (Lettuce) | **Phase 3에서만 도입** |
| 실시간 | SseEmitter + WebSocket (둘 다) | 비교 실험 |
| 로깅 | Logback + logstash-logback-encoder | JSON |
| 기본 메트릭 | Micrometer + Prometheus + Grafana | 최소 대시보드 |
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

> 로깅과 기본 메트릭은 "취업시장에서 요구하는 수준"이므로 유지. 단 대시보드 Row 개수와 커스텀 메트릭 종류는 실측에 꼭 필요한 것만.

---

## 7. 인프라 & 배포

### 7.1. 트래픽 추정 (200명 기준)

| 항목 | 계산 | 결과 |
|---|---|---|
| 지속 TPS | 200명 × (1/3.5s) | ≈ 57 TPS |
| 피크 TPS | 시작 순간 200명 동시 클릭 | 200 TPS |
| 네트워크 | 찬탈 + SSE 합산 | ~6 Mbps |
| DB QPS (Phase 1) | 찬탈 1건당 SELECT + UPDATE + INSERT ≈ 3 쿼리 | 600 QPS peak |

### 7.2. 메모리/CPU 산정 (t3.medium 기준)

- Spring Boot JVM: 1.3GB (heap 1GB + non-heap 300MB)
- MySQL: 500MB (innodb_buffer_pool 256MB)
- Redis (Phase 3): 100MB
- Prometheus + Grafana: 700MB
- Nginx: 50MB
- OS: 300MB
- **합계: ~2.9GB / 4GB**, **2 vCPU 적정**

### 7.3. 인스턴스 선택

| 등급 | 인스턴스 | 비용 | 판단 |
|---|---|---|---|
| **권장** | **t3.medium (2 vCPU/4GB)** | $1/day | 메모리 여유로 관측 스택 포함 가능 |
| 최소 | t3.small | $0.5/day | 부하 시 OOM 위험, 추천 안 함 |

> 인스턴스 사양은 v3과 동일 유지. **MySQL이 추가되었지만 Redis 단계에서 선택 도입이므로 피크 시 두 저장소를 모두 띄우지는 않음** (혹은 Phase 3에서도 MySQL 유지하므로 메모리 계산이 바뀌는 점은 k6 측정 시 반영).

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
│  └──────────────┬───────────────────────┘   │
│                 │                           │
│  ┌──────────────▼───────────────────────┐   │
│  │ MySQL 8 (:3306)                      │   │
│  │  (Phase 3에서 Redis 추가)            │   │
│  └──────────────────────────────────────┘   │
│                                             │
│  ┌──────────────────────────────────────┐   │
│  │ Prometheus (:9090)                   │   │
│  │ Grafana (:3000, anonymous)           │   │
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
- 3000 (Grafana): 공개 read-only
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

-- 현재 왕 (이벤트당 1행) — 동시성 핫스팟
CREATE TABLE current_throne (
  event_id    BIGINT PRIMARY KEY,
  reign_id    BIGINT NOT NULL,
  user_id     BIGINT NOT NULL,
  held_since  TIMESTAMP(3) NOT NULL,
  version     BIGINT NOT NULL DEFAULT 0,  -- 낙관적 락 용 (백업)
  FOREIGN KEY (event_id) REFERENCES events(id),
  FOREIGN KEY (reign_id) REFERENCES throne_reigns(id),
  FOREIGN KEY (user_id)  REFERENCES users(id)
) ENGINE=InnoDB;

> **초기 왕 정책**: `current_throne.reign_id`, `user_id`가 `NOT NULL`이므로, 이벤트 시작 시 seed된 `SYSTEM` 사용자(일반 양수 PK 사용)로 초기 reign을 1건 생성하고 `current_throne` 1행을 채운다. 이후 첫 성공 찬탈이 이 reign을 종료하고 실제 참가자 reign으로 전환한다.

-- 찬탈 시도 로그 (성공/실패 모두, append-only)
CREATE TABLE throne_claims (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id     BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  result       ENUM('SUCCESS','COOLDOWN','ALREADY_OWNER','LOCK_CONFLICT','NO_ACTIVE_EVENT','NOT_RUNNING') NOT NULL,
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

> **운영상의 알려진 한계**: `throne_claims`는 append-only 로그이므로 이벤트 당일 피크 트래픽에서 빠르게 증가할 수 있다. 본 프로젝트는 Phase 2의 배치 INSERT와 Phase 3의 Redis 핫패스 전환 전후 비교를 우선 목표로 하며, 장기 운영 시에는 파티셔닝 또는 보관 정책을 추가한다.
| `cooldowns(expires_at)` | 만료된 쿨타임 일괄 삭제 (스케줄러) |

---

## 9. Phase 1 — MVP 설계

**목표**: 동작하는 최소 버전. 과한 최적화 없이, 단순하고 이해하기 쉬운 코드로.

### 9.1. 찬탈 로직 (MVP 버전)

Phase 1은 **하나의 트랜잭션에 필수 로직만** 넣는다. 3단 분리나 비동기 훅 같은 구조는 Phase 2에서 측정 후 필요한 것만 도입한다.

```java
@Transactional(timeout = 3)
public ClaimResult claim(long eventId, long userId) {
    Instant now = Instant.now();

    // 1. 쿨타임 체크 (PK 단건 조회, 락 없음)
    Cooldown cd = cooldownRepo.findActive(eventId, userId, now);
    if (cd != null) {
        claimAudit.recordFailure(eventId, userId, COOLDOWN, now);   // REQUIRES_NEW
        throw new CooldownException(cd.remainingMs(now));
    }

    // 2. 현재 왕 락 — FOR UPDATE NOWAIT (대기 큐 방지)
    CurrentThrone throne;
    try {
        throne = currentThroneRepo.findByEventIdForUpdateNoWait(eventId)
            .orElseThrow(NoActiveEventException::new);
    } catch (PessimisticLockingFailureException e) {
        claimAudit.recordFailure(eventId, userId, LOCK_CONFLICT, now);  // REQUIRES_NEW
        throw new RetryableConflictException();
    }

    // 3. 이벤트 상태 + 소유자 검증
    Event event = eventRepo.findById(eventId).orElseThrow();
    if (event.getStatus() != RUNNING) {
        claimAudit.recordFailure(eventId, userId, NOT_RUNNING, now);    // REQUIRES_NEW
        throw new NotRunningException();
    }
    if (throne.getUserId() == userId) {
        claimAudit.recordFailure(eventId, userId, ALREADY_OWNER, now);  // REQUIRES_NEW
        throw new AlreadyOwnerException();
    }

    // 4. reign 종료 + 시작
    long prevUserId = throne.getUserId();
    long heldMs = Duration.between(throne.getHeldSince(), now).toMillis();
    reignRepo.closeReign(throne.getReignId(), now, heldMs);
    long newReignId = reignRepo.openReign(eventId, userId, now);

    // 5. current_throne 갱신
    throne.update(newReignId, userId, now);

    // 6. 쿨타임 upsert
    cooldownRepo.upsert(eventId, userId, now.plusMillis(event.getCooldownMs()));

    // 7. 집계는 Caffeine write-back — 동기 UPDATE는 절대 피함 (9.2)
    aggregationCache.addReignEnd(eventId, prevUserId, heldMs);
    aggregationCache.addClaimSuccess(eventId, userId);

    // 8. 외부 관측(감사 로그/SSE)은 afterCommit에서 수행
    ThroneChangedEvent evt = new ThroneChangedEvent(eventId, userId, newReignId, now);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {
            claimAudit.recordSuccess(eventId, userId, now);             // Phase 1: 개별 INSERT
            ssePublisher.publishThroneChanged(evt);                     // Phase 1: 동기 루프
        }
    });

    return ClaimResult.success(newReignId);
}
```

> 실패 로그는 `REQUIRES_NEW`로 즉시 남기고, 성공 로그와 SSE 발행은 `afterCommit`으로 미룬다. 이렇게 하면 "실패는 롤백과 무관하게 남기고, 성공은 실제 커밋 이후에만 외부로 보인다"는 최소 정합성을 확보할 수 있다.

**MVP의 의도적 단순화**:
- ❌ 락 밖 선체크 분리 없음 — 쿨타임 체크 1줄만 분리 (간단함)
- ❌ 감사 로그 배치 큐 없음 — 성공/실패 모두 개별 INSERT
- ❌ 읽기 캐시 없음
- ❌ SSE 비동기 브로드캐스트 없음
- ✅ 외부 관측은 `afterCommit`으로 미뤄 커밋 실패와의 정합성은 보장

**MVP가 지키는 핵심 3가지**:
- ✅ **`FOR UPDATE NOWAIT`** — 기본 FOR UPDATE의 대기 큐 폭주를 한 줄로 방지
- ✅ **집계 Caffeine write-back** — `event_participants` UPDATE 핫스팟만은 처음부터 회피 (9.2)
- ✅ **성공 로그/SSE는 `afterCommit`** — 롤백된 트랜잭션이 외부에 성공으로 보이지 않게 방지

### 9.2. Caffeine Write-Back 집계 (MVP 필수 유지)

Phase 1에서 **유일하게 처음부터 적용하는 "최적화"**. 집계 UPDATE를 write-back 없이 매 찬탈마다 하면 `event_participants`가 락 경합의 또 다른 핫스팟이 되어 찬탈 API 전체가 느려진다.

```java
// 키: (eventId, userId), 값: AggregationDelta
private final ConcurrentHashMap<Key, AggregationDelta> pending = new ConcurrentHashMap<>();

public void addReignEnd(long eventId, long userId, long durationMs) {
    pending.merge(new Key(eventId, userId),
                  AggregationDelta.ofHold(durationMs),
                  AggregationDelta::merge);
}

public void addClaimSuccess(long eventId, long userId) {
    pending.merge(new Key(eventId, userId),
                  AggregationDelta.ofClaim(),
                  AggregationDelta::merge);
}

@Scheduled(fixedDelay = 10_000)
public void flush() {
    Map<Key, AggregationDelta> drained = new HashMap<>();
    for (Key k : pending.keySet()) {
        AggregationDelta d = pending.remove(k);   // 원자 추출
        if (d != null) drained.put(k, d);
    }
    if (drained.isEmpty()) return;
    participantRepo.applyDeltaBatch(drained);
}

@PreDestroy
public void finalFlush() { flush(); }
```

**트레이드오프 (README 기재)**:
- ✅ `event_participants` UPDATE QPS를 1/N로 축소
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
    Instant capturedAt = Instant.now();
    snapshotRepo.saveAll(toSnapshotRows(rows, running.getId(), capturedAt));

    ssePublisher.publishLeaderboardSnapshot(running.getId(), capturedAt);
}
```

### 9.5. SSE 퍼블리셔 (MVP — 동기)

단일 인스턴스 전제. `SseEmitter` 리스트를 in-memory로 관리하고, **`afterCommit` 이후 같은 요청 스레드에서 동기로 발행**한다. Phase 2에서 이 동기 발행이 문제를 일으키는지 측정한다.

```java
@Component
public class SsePublisher {
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long userId) {
        SseEmitter em = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitters.put(userId, em);
        em.onCompletion(() -> emitters.remove(userId));
        em.onTimeout(() -> emitters.remove(userId));
        return em;
    }

    public void publishThroneChanged(long eventId, long newOwnerId, long reignId, Instant at) {
        // Phase 1: 동기 루프 (Phase 2 개선 #3에서 ExecutorService로 비동기화)
        emitters.forEach((uid, em) -> safeSend(em, "throne", payload(...)));
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
                                                                         [WebSocket 추가 + 비교]
                                                                                     ↓
                                                                              [종합 비교 표]
```

각 개선은 **독립 커밋 + 독립 측정 + 독립 문서화**. Phase 3 진입 전에 "MySQL + JVM 레벨에서 할 수 있는 개선은 다 해봤다"가 입증돼야 한다.

### 10.2. k6 시나리오 매트릭스

| 시나리오 | 목표 | VU | 기간 |
|---|---|---|---|
| A. 찬탈 스파이크 | `current_throne` 경합 측정 | 300 | 3분 |
| B. SSE 동시 연결 | SSE 서버 스레드/메모리 | 200 | 5분 |
| C. WebSocket 동시 연결 (WS 구현 후) | WS 서버 스레드/메모리 | 200 | 5분 |
| D. 믹스 (찬탈 + SSE) | 실제 이벤트 날 재현 | 200 + 100 | 5분 |
| E. 리더보드 조회 | 읽기 부하 | 100 | 2분 |

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
    'http_reqs{status:409}': [],   // NOWAIT 충돌 관찰용
  }
};

export default function () {
  const token = __ENV.JWT;
  const res = http.post(
    `${__ENV.BASE}/api/events/active/claim`,
    null,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  check(res, { 'valid response': (r) => [200,409,429].includes(r.status) });
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
| SSE/WS | 동시 연결 수, publish latency |

### 10.5. Baseline 측정 (Day 8)

MVP 상태 그대로 시나리오 A~E + D 실행. 스크린샷과 수치를 `docs/baseline-YYYYMMDD.md`에 기록.

**예측하는 주요 증상** (실측 전 가설):
- 찬탈 p99가 SLO 100ms를 크게 상회 (아마 수백 ms)
- NOWAIT 409 응답이 피크에서 상당 비율
- SSE 연결 수가 올라갈수록 찬탈 p99가 함께 상승 (동기 발행 영향)
- `throne_claims` INSERT가 초당 수백 건 발생

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

@Scheduled(fixedDelay = 500)
public void drainAndInsert() {
    List<ClaimLogEntry> batch = new ArrayList<>(500);
    queue.drainTo(batch, 500);
    if (!batch.isEmpty()) claimRepo.batchInsert(batch);
}
@PreDestroy public void drainAll() { drainAndInsert(); }
```

**찬탈 로직 변경**: `claimRepo.insert(...)` 호출을 `queue.offer(new ClaimLogEntry(...))`로 교체.

**측정 & 문서화**: 시나리오 A 재실행. 찬탈 API p99, MySQL `Com_insert` QPS, 큐 사이즈 추이.

#### 개선 #3 — SSE 커밋 후 동기 → 비동기 브로드캐스트

**증상**: 시나리오 D(믹스)에서 찬탈 API p99가 시나리오 A 단독 대비 유의미하게 상승. SSE 연결 수에 비례.
**원인**: Phase 1은 `afterCommit`을 사용하더라도, 같은 요청 스레드에서 **SSE 연결 수만큼 동기 루프**를 돈다. 200 연결이면 200번 I/O가 요청 tail latency를 늘린다.
**조치**:
1. `afterCommit` 구조는 유지한다 (정합성 유지).
2. 별도 ExecutorService로 송신을 오프로딩한다.

```java
private final ExecutorService sseExecutor = Executors.newFixedThreadPool(4);

public void publishThroneChangedAsync(ThroneChangedEvent evt) {
    sseExecutor.submit(() -> emitters.forEach((uid, em) -> safeSend(em, "throne", evt)));
}

// ThroneService 변경: 기존 afterCommit 콜백의 본문만 async 호출로 변경
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() { ssePublisher.publishThroneChangedAsync(evt); }
});
```

**측정 & 문서화**: 시나리오 D 재실행. 찬탈 p99 변화 + SSE 수신 지연 변화.

### 10.7. WebSocket 추가 & 비교 실험

개선 3회를 마친 후, WebSocket 구현을 추가한다. 이 시점에는 MySQL 기반 코드가 안정화돼 있어 부담이 적다.

#### 구현
- `/ws/throne` 엔드포인트, handshake 인터셉터로 JWT 검증. 운영 브라우저 클라이언트는 SSE와 동일하게 쿠키 기반.
- 메시지 스키마는 SSE와 1:1 동일.
- 동일한 `ThroneChangedEvent`를 `WsPublisher`에도 전달 (SsePublisher와 병렬).

#### 비교 시나리오
- 시나리오 B(SSE 200 연결) vs 시나리오 C(WS 200 연결) 동일 조건.
- 서버 RAM, 스레드 수, 메시지 수신 p99, 구현 LoC를 12장 보고 템플릿에 기입.

### 10.8. 보조 실험: 락 전략 비교

개선 사이클이 끝난 상태에서, **락 전략만 바꿔가며** 한 번 더 측정 → MySQL 내 최선 조합 기록.

| 실험 | 전략 | 기대 결과 |
|---|---|---|
| A1 | `FOR UPDATE` (대기) | p99 최악, 성공률 최고 |
| A2 | `FOR UPDATE NOWAIT` (현재) | p99 안정, 409 발생 |
| A3 | 낙관적 락 (version 컬럼) | p99 중간, 재시도 로직 필요 |

이 표가 "MySQL에서 이 이상은 어렵다"는 정당화의 마지막 조각이 된다.

### 10.9. Phase 2 종합 비교 표 (포폴용)

| 단계 | 찬탈 p99 | 성공 TPS | 읽기 p99 | 비고 |
|---|---|---|---|---|
| Baseline (MVP) | ???ms | ??? | ???ms | 측정 기준선 |
| 개선 #1 (읽기 캐시) | ???ms | ??? | ???ms | 읽기 p99 주목 |
| 개선 #2 (감사로그 배치) | ???ms | ??? | ???ms | INSERT 감소 효과 |
| 개선 #3 (SSE 비동기) | ???ms | ??? | ???ms | 믹스 시 p99 안정 |
| 락 전략 A1 | ???ms | ??? | - | 대기 전략 |
| 락 전략 A3 | ???ms | ??? | - | 낙관적 락 |
| **Phase 1 최선** | **???ms** | **???** | **???ms** | MySQL 한계 |
| **Phase 3 (Redis)** | **???ms** | **???** | **???ms** | **배율 개선** |

이 표 하나가 이력서 "성능 최적화 경험"의 증빙이 된다.

---

## 11. Phase 3 — Redis 도입 & 개선

### 11.1. 역할 재정의

| 관심사 | Phase 1 | Phase 3 |
|---|---|---|
| 현재 왕 저장소 | `current_throne` (MySQL) | `event:{id}:throne` (Redis Hash) |
| 쿨타임 | `cooldowns` 테이블 | `event:{id}:cooldown:{uid}` (TTL) |
| 리더보드 | `event_participants` + 5분 배치 스냅샷 | `event:{id}:lb` (ZSET, 실시간) |
| 찬탈 원자성 | MySQL `SELECT FOR UPDATE` | Redis Lua |
| 영속 이벤트 로그 | `throne_claims`, `throne_reigns` | **여전히 MySQL** |
| SSE fan-out | in-memory `ConcurrentHashMap` | Redis Pub/Sub |

**포인트**: Redis는 "핫 패스 + 원자성"을 가져가고, MySQL은 "최종 진실 + 쿼리 가능한 이력"을 맡는다.

### 11.2. 찬탈 Lua (v3에서 가져옴)

```lua
-- KEYS: [throne, cooldown, meta]
-- ARGV: [userId, nowMs, cooldownMs]

local status = redis.call('HGET', KEYS[3], 'status')
if status ~= 'RUNNING' then
  return {0, 'NOT_RUNNING', 0}
end

local cdExpiresAt = redis.call('GET', KEYS[2])
if cdExpiresAt and tonumber(cdExpiresAt) > tonumber(ARGV[2]) then
  return {0, 'COOLDOWN', tonumber(cdExpiresAt) - tonumber(ARGV[2])}
end

local currentOwner = redis.call('HGET', KEYS[1], 'ownerId')
if currentOwner == ARGV[1] then
  return {0, 'ALREADY_OWNER', 0}
end

-- 성공: 왕좌 갱신 + 쿨타임 세팅
local prevHeldSince = redis.call('HGET', KEYS[1], 'heldSince')
redis.call('HSET', KEYS[1], 'ownerId', ARGV[1], 'heldSince', ARGV[2])
redis.call('SET', KEYS[2], tonumber(ARGV[2]) + tonumber(ARGV[3]),
           'PX', tonumber(ARGV[3]))

return {1, 'SUCCESS', prevHeldSince or 0}
```

### 11.3. 영속성 — 이벤트 큐 Consumer

Redis에서 성공한 찬탈을 MySQL로 전달:

```
[앱] Lua 성공 → LPUSH queue:throne_events (eventId, userId, prevOwnerId, prevHeldSince, now)
              ↓
[Consumer] BLPOP → MySQL 트랜잭션으로 INSERT throne_reigns, throne_claims, UPDATE event_participants
```

**k6 재측정**: 동일 시나리오 A 재실행 → 목표 p99 < 80ms 달성 검증.

### 11.4. 실시간 리더보드

- Redis `ZINCRBY event:{id}:lb <addMs> <userId>`
- 조회는 `ZREVRANGE ... WITHSCORES`
- SSE로 1초 배치 브로드캐스트
- `leaderboard_snapshot` 배치는 Phase 3에서 폐기 or 과거 이벤트 조회용으로만 유지

### 11.5. Grafana에 before/after 나란히

- Phase 1 수치는 이미 수집 완료 → Grafana에서 "Phase 1 baseline" variable로 범위 고정
- Phase 3 수치는 "After" variable로 → 동일 대시보드에서 비교 가능

---

## 12. SSE / WebSocket 비교 실험

### 12.1. 실험 목적

"왜 SSE를 선택했는가?"를 **수치로** 답하기 위한 실험. 구현 난이도, 서버 리소스, 연결당 메모리, 지연 시간을 비교.

### 12.2. 두 구현의 동등성 보장

- 동일 이벤트 정보 전송 (throne_changed, leaderboard_updated, event_status)
- 동일 인증 정책 (운영 기준 HttpOnly 쿠키, 부하테스트는 Cookie 헤더 사용)
- 동일 메시지 직렬화 (JSON)
- 차이는 "전송 메커니즘"만

### 12.3. 비교 시나리오 (k6)

#### Scenario-SSE
```javascript
// 200 VU가 SSE 연결 5분간 유지
// 서버가 주기적으로 publish → 수신 지연 측정
import sse from 'k6/x/sse';   // 공식 확장 or HTTP stream 직접
```

#### Scenario-WS
```javascript
import ws from 'k6/ws';
export default function () {
  const url = `${__ENV.WS}/ws/throne`;
  ws.connect(url, {
    headers: { Cookie: `access_token=${__ENV.JWT}` }
  }, (socket) => {
    socket.on('message', (msg) => { /* 수신 시각 기록 */ });
    socket.setTimeout(() => socket.close(), 300_000);
  });
}
```

### 12.4. 측정 지표

| 지표 | SSE | WebSocket |
|---|---|---|
| 서버 RAM (200 연결) | MB | MB |
| Tomcat/Netty thread | count | count |
| 연결 수립 지연 | p99 ms | p99 ms |
| 메시지 수신 지연 | p99 ms | p99 ms |
| 코드 복잡도 | LoC / 디펜던시 | LoC / 디펜던시 |
| Nginx 설정 추가 사항 | proxy_buffering off 등 | Upgrade 헤더 등 |
| 네트워크 트래픽 | KB/연결/분 | KB/연결/분 |

### 12.5. 보고 템플릿

```
## SSE vs WebSocket 비교 결과

### 조건
- 200 VU, 5분, Phase 3 기준 (Redis Pub/Sub 공유)
- 이벤트 발생: 평균 1.5/s (실측 피크 근처)

### 결과
| 지표 | SSE | WebSocket |
|---|---|---|
| 서버 RAM 증가분 | 42 MB | 58 MB |
| 메시지 수신 p99 | 12 ms | 9 ms |
| 구현 LoC (서버) | 180 | 260 |
| Nginx 변경 | 1줄 | 2줄 |

### 결론
- 단방향 push 시나리오에선 SSE가 구현 단순성 대비 성능 차이 미미
- 양방향 + 바이너리가 필요했다면 WS 선택
- 이 프로젝트에선 SSE 운영 기본값 유지
```

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

### 13.2. MDC 주입 (LogContextFilter)

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LogContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        String reqId = Optional.ofNullable(req.getHeader("X-Request-Id"))
            .orElse("req-" + UUID.randomUUID().toString().substring(0, 8));
        MDC.put("requestId", reqId);
        res.setHeader("X-Request-Id", reqId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}

// Spring Security 이후 userId 주입하는 별도 필터
```

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

---

## 14. 메트릭 & 관측성

### 14.1. 수집 방식

- Spring Actuator `/actuator/prometheus` → Prometheus scrape (5초 주기)
- Grafana가 Prometheus 쿼리

### 14.2. 커스텀 메트릭 (도입 시점별)

**Phase 1 MVP부터 수집**

| 메트릭 | 타입 | 용도 |
|---|---|---|
| `throne_claim_total{result}` | Counter | 찬탈 결과별 건수 (SUCCESS/COOLDOWN/ALREADY_OWNER/LOCK_CONFLICT/NO_ACTIVE_EVENT/NOT_RUNNING) |
| `throne_claim_duration` | Histogram | 찬탈 API 레이턴시 |
| `throne_lock_conflict_total` | Counter | NOWAIT 충돌 횟수 |
| `throne_current_owner_changes_total` | Counter | 왕좌 교체 횟수 |
| `caffeine_flush_duration` | Histogram | 집계 캐시 flush 지연 |
| `caffeine_pending_keys` | Gauge | 집계 캐시 대기 키 수 |
| `sse_active_connections` | Gauge | 현재 SSE 연결 수 |
| `leaderboard_snapshot_duration` | Histogram | 5분 배치 소요 시간 |

**Phase 2 개선 시 추가**

| 메트릭 | 타입 | 추가 시점 |
|---|---|---|
| `caffeine_current_throne_hit_ratio` | Gauge | 개선 #1 (읽기 캐시 도입) |
| `claim_log_queue_size` | Gauge | 개선 #2 (감사 로그 배치) |
| `sse_publish_duration` | Histogram | 개선 #3 (SSE 비동기 전환 검증) |
| `ws_active_connections` | Gauge | WebSocket 추가 시 |

이 구분은 **각 메트릭이 왜 존재하는지가 개선 서사와 1:1로 연결**된다는 포폴 포인트이기도 하다.

### 14.3. 기본 제공 메트릭 활용

별도 설정 없이 Actuator 기본 노출을 대시보드에 올리면 되는 것들:
- `http_server_requests_seconds_*` → API p50/p95/p99
- `jvm_memory_*`, `jvm_gc_*`, `jvm_threads_*`
- `tomcat_threads_busy`, `tomcat_threads_config_max`
- `hikaricp_connections_*`

### 14.4. Grafana 대시보드 (3 Row)

**Row 1 — API**
- 찬탈 TPS (성공/쿨타임/이미보유)
- 찬탈 p50/p95/p99 (Phase 1 vs Phase 3 비교 패널)
- 5xx rate

**Row 2 — 내부**
- Caffeine pending keys
- flush duration
- leaderboard snapshot duration
- SSE / WS 연결 수

**Row 3 — 리소스**
- JVM heap, GC pause
- Tomcat busy threads
- HikariCP active/idle/pending
- MySQL QPS (mysqld_exporter 생략 시 Actuator의 `spring.data.repository`로 대체)

설정: `auth.anonymous.enabled=true`, role=Viewer, 편집 불가.

---

## 15. 시상 & 결과 집계

### 15.1. 시상 정의 (변경)

| 상 | 정의 | 계산 기준 |
|---|---|---|
| 👑 **군림상 (Longest Reign)** | 단일 reign 최장 보유자 | `MAX(throne_reigns.duration_ms)` per user, then global MAX |
| ⚔️ **찬탈왕 (Usurper)** | 찬탈 성공 총 횟수 최다 | `event_participants.claim_success_cnt` DESC |
| 🌅 **최후의 왕 (Last King)** | 이벤트 종료 시 왕좌 보유자 | `throne_reigns WHERE event_id = ? AND ended_at IS NULL` (강제 종료 시점의 현재 왕) 또는 `started_at` 기준 가장 최근 reign |

### 15.2. finalize 처리

```java
@Transactional
public EventSummary finalize(long eventId) {
    // 1. 진행 중 reign이 있으면 ended_at = ended_at of event 로 마감
    reignRepo.closeOpenReign(eventId, event.getEndedAt());
    
    // 2. Caffeine flush (아직 안 들어간 집계 반영)
    aggregationCache.forceFlush();
    
    // 3. 시상 계산
    var longest = reignRepo.findLongestReign(eventId);
    var usurper = participantRepo.findMostClaims(eventId);
    var lastKing = reignRepo.findLastReign(eventId);
    
    awardRepo.save(longest, usurper, lastKing);
    
    // 4. 통계 요약 생성 → summary JSON 반환용
    return buildSummary(eventId);
}
```

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
EventService          CRUD + 상태 전환 + 스케줄러
ThroneService         찬탈 핵심 (MVP: 단일 트랜잭션)
CooldownService       쿨타임 관리 + 만료 정리
LeaderboardService    5분 배치 스냅샷 + 조회
AwardService          finalize 시 시상 계산
AggregationCache      Caffeine write-back (집계 증분)
SsePublisher          SSE fan-out (MVP: 동기)

# Phase 2 개선에서 추가 또는 전환
CurrentThroneCache    (개선 #1) 현재 왕 읽기 캐시
ClaimLogQueue         (개선 #2) 감사 로그 배치 INSERT 큐
SsePublisher (async)  (개선 #3) ExecutorService 기반 비동기 전환
WsPublisher           WebSocket 추가 시 SSE와 병렬 publish

# Phase 3에서 추가
ThroneLuaExecutor     Redis Lua 원자 연산
ThroneEventConsumer   queue:throne_events → MySQL 이벤트 소싱
RedisLeaderboard      ZSET 실시간 리더보드
RedisPubSubBridge     SSE/WS fan-out을 Pub/Sub으로 전환
```

### 16.2. API 명세 (요약)

| Method | URI | Auth | 설명 |
|---|---|---|---|
| GET  | `/login/oauth2/authorization/github` | - | OAuth2 시작 |
| GET  | `/login/oauth2/code/github`           | - | OAuth2 콜백 |
| POST | `/api/auth/refresh`                   | JWT | 토큰 갱신 |
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
| WS   | `/ws/throne`                          | JWT (handshake) | WebSocket |

---

## 17. 개발 일정 (2주)

총 14일 기준. Phase별 데드라인을 두되 필요에 따라 축소/확장 가능.

### Phase 1 — MVP 구축 (Day 1~7)

**Day 1 — 프로젝트 셋업**
- Gradle, Spring Boot 3 초기화
- Docker Compose: MySQL, Prometheus, Grafana
- Flyway 마이그레이션 v1 (users, events, throne_reigns, current_throne, throne_claims, cooldowns, leaderboard_snapshot, event_awards)
- `LogContextFilter` + Logback JSON 설정

**Day 2 — 인증 & 유저**
- Spring Security + OAuth2 Client (GitHub)
- JWT 발급/검증 (jjwt)
- `users` upsert, 관리자 role 시드
- `/api/me`, `/api/auth/refresh`

**Day 3 — 이벤트 라이프사이클**
- `EventService` + `@Scheduled` 자동 종료
- 관리자 API 4종 (생성/시작/종료/finalize)
- 이벤트 상태 전이 테스트
- 이벤트 시작 시 seed된 `SYSTEM` 사용자로 초기 reign + `current_throne` 1행 생성

**Day 4 — 찬탈 MVP**
- `ThroneService.claim()` 단일 트랜잭션 구현 (9.1)
- `FOR UPDATE NOWAIT` + `@Transactional(timeout = 3)`
- 감사 로그는 배치 없이 개별 INSERT (실패는 REQUIRES_NEW, 성공은 afterCommit)
- 쿨타임 upsert

**Day 5 — 집계 & 리더보드**
- `AggregationCache` write-back (`pending.remove` 기반 안전 flush) (9.2)
- `@PreDestroy` 최종 flush
- `cooldowns` 1분 주기 만료 정리 (9.3)
- 5분 배치 `leaderboard_snapshot` (9.4)

**Day 6 — SSE (동기) + finalize & 시상**
- `SseEmitter` handshake 시 JWT 검증
- 찬탈 성공 시 동기 브로드캐스트 (9.5)
- 리더보드 스냅샷 완료 시 SSE 알림
- `AwardService` — 3종 시상 계산 (군림/찬탈왕/최후의 왕)
- `/api/events/{id}/summary` 구현

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
- Grafana 대시보드 구성, Anonymous 공개
- **k6 baseline 측정** — 시나리오 A~E 실행
- `docs/baseline-YYYYMMDD.md` 작성

**Day 9 — 개선 #1, #2**
- 개선 #1: 현재 왕 읽기 캐시 추가 → 시나리오 E 재측정 (10.6)
- 개선 #2: 감사 로그 배치 INSERT로 전환 → 시나리오 A 재측정 (10.6)
- 각 개선의 before/after 수치 기록

**Day 10 — 개선 #3 + 락 전략 비교 (⭐ optional 실험 포함)**
- 개선 #3: SSE 비동기 브로드캐스트 (기존 `afterCommit` 유지) → 시나리오 D 재측정 (10.6)
- 보조 실험: 락 전략 3종 비교 (10.8) — FOR UPDATE / NOWAIT / 낙관적 락
  - ⭐ 일정이 밀리면 Day 14 이후 확장 실험으로 이관 가능
- 개선 사이클 마무리, 종합 비교 표 작성

**Day 11 — WebSocket 추가 & 비교 (⭐ optional)**
- `/ws/throne` 구현, 동일한 `ThroneChangedEvent` 발행
- 시나리오 B(SSE) vs C(WS) 동일 조건 측정
- 12장 보고 템플릿 작성: RAM, 스레드, 지연, LoC 비교
- ⭐ 일정이 밀리면 SSE만 운영 기본값으로 확정하고, WS 비교는 Day 14 이후 선택 과제로 이관 가능
- **Phase 2 완료 태그 (git tag `v2.0-optimized`)**

### Phase 3 — Redis 도입 (Day 12~13)

**Day 12 — Redis 통합 & 핫패스 이전**
- Docker Compose에 Redis 추가
- Lua 스크립트 작성 (11.2)
- `ThroneService`에 MySQL 구현체 / Redis 구현체 분리 (전략 패턴)
- `queue:throne_events` Consumer (BLPOP → MySQL `throne_reigns`/`throne_claims` INSERT)
- 기능 동등성 e2e 테스트

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
- [ ] 각 Phase에서 배운 것 요약

**아키텍처 결정**
- [ ] 왜 MVP 단순 구현으로 시작했나
- [ ] 왜 Caffeine write-back이 MVP에 유일하게 필요했나 (UPDATE 핫스팟)
- [ ] 왜 `FOR UPDATE NOWAIT`인가 (대안: 기본 FOR UPDATE, 낙관적 락 비교)
- [ ] 왜 SSE가 기본값인가 (12장 수치 인용)
- [ ] EC2 t3.medium 선택 근거
- [ ] `Known limitation`: `throne_claims`는 이벤트 당일 피크 트래픽에서 빠르게 증가할 수 있으며, 장기 운영 시 파티셔닝 또는 보관 정책이 필요함을 명시

**MVP 코드 하이라이트 (Phase 1)**
- [ ] `ThroneService.claim()` 단일 트랜잭션 버전
- [ ] `AggregationCache` write-back + `pending.remove` 안전 flush + `@PreDestroy`
- [ ] SSE handshake OAuth2/JWT 검증
- [ ] `LogContextFilter` + MaskingLayout

**개선 사이클 (Phase 2)** ⭐
- [ ] **개선 #1**: 현재 왕 읽기 캐시 — 증상/원인/조치/효과 수치
- [ ] **개선 #2**: 감사 로그 배치 INSERT — before/after INSERT QPS
- [ ] **개선 #3**: SSE 동기 → 비동기 — 믹스 시나리오 p99 변화
- [ ] **락 전략 3종 비교 표** (FOR UPDATE / NOWAIT / 낙관적 락)
- [ ] **SSE vs WebSocket 비교 표** (RAM / 스레드 / 지연 / LoC)

**Phase 3 이행**
- [ ] Redis Lua 스크립트 + Consumer
- [ ] 영속성 경로 (Redis 핫패스 + MySQL 이벤트 소싱)
- [ ] k6 동일 시나리오 Phase 3 재측정

**동시성 증명**
- [ ] k6로 200 VU 동시 찬탈 → 1건만 성공 (MVP & Phase 3 모두)
- [ ] MySQL `SHOW ENGINE INNODB STATUS` 스샷 + row lock wait 지표
- [ ] Redis Lua 실행 지표

**로깅 역량**
- [ ] 로그 레벨 규율 문서
- [ ] JSON 로그 샘플 (찬탈 1건 추적)
- [ ] 마스킹 before/after
- [ ] MDC로 requestId 역추적 사례

**관측성**
- [ ] Grafana 공개 URL
- [ ] 커스텀 메트릭이 개선 서사와 1:1 연결됨을 표로 증명 (14.2)
- [ ] MVP vs Phase 3 대시보드 비교 스샷

**성능 종합 (10.9 표)** ⭐⭐
- [ ] Baseline (MVP)
- [ ] 개선 #1 적용 후
- [ ] 개선 #2 적용 후
- [ ] 개선 #3 적용 후
- [ ] 락 전략 실험 결과
- [ ] Phase 3 (Redis) After
- [ ] **전체 배율 개선 수치** (MVP → Phase 3)

**튜닝 경험**
- [ ] HikariCP 풀 사이즈 측정 기반 선택 근거
- [ ] `innodb_lock_wait_timeout` 등 my.cnf 튜닝
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
| "RDBMS 트랜잭션/락 경험" | MySQL `SELECT FOR UPDATE` + 낙관/비관 락 비교 |
| "캐시 전략 수립" | Caffeine write-back + flush 주기 선택 근거 |
| "Redis 경험" | Lua 원자 처리, ZSET, Pub/Sub |
| "동시성 제어" | k6 동시 요청 → 1건만 성공 증명 (양 단계) |
| "로그 기반 문제 해결" | JSON + MDC + 마스킹 + 규율 문서 |
| "APM/모니터링" | Prometheus + Grafana + 커스텀 메트릭 |
| "부하테스트" | k6 다중 시나리오 + baseline/after |
| "성능 최적화" | Phase 1 → Phase 3 수치 비교 |
| "기술 선정 능력" | SSE vs WebSocket 비교 실험 |
| "OAuth2/SSO" | GitHub OAuth2 Client 연동 |
| "용량 산정" | EC2 스펙 계산 근거 |
| "실시간 시스템" | SSE/WS + Pub/Sub + 300 동시 연결 |
| "운영 배포" | Docker Compose + Nginx + HTTPS + 공개 서비스 |

---

## 부록 B — MySQL 최적화 체크리스트

v4.2에서는 이 리스트를 **두 그룹으로 분리**한다: MVP에 포함할 것 vs Phase 2 개선 사이클에서 도입할 것. 이 구분 자체가 "측정 후 필요한 것만"이라는 원칙의 증거다.

### B.1. MVP에 포함 (Phase 1 Day 1~7)

기본기에 해당하므로 처음부터 적용. 없으면 MVP가 바로 무너진다.

- [ ] **`FOR UPDATE NOWAIT`** — 대기 큐 폭주 방지 (MVP 핵심 2 중 하나)
- [ ] **`@Transactional(timeout = 3)`** — 트랜잭션 상한 명시
- [ ] **`@Transactional(readOnly = true)`** — 읽기 트랜잭션 최적화
- [ ] **집계 Caffeine write-back** — `event_participants` UPDATE 핫스팟 회피 (MVP 핵심 2 중 하나)
- [ ] **`event_participants(event_id, total_hold_ms DESC)`** 커버링 인덱스
- [ ] **`current_throne`을 별도 테이블** — events 본체와 행 경합 분리
- [ ] **`idx_cd_expires`** 쿨타임 정리용 인덱스
- [ ] **`innodb_lock_wait_timeout = 3`** — NOWAIT 병행 안전망
- [ ] **`innodb_buffer_pool_size = 256M`** — t3.medium 기준
- [ ] **HikariCP `maximumPoolSize = 10`** — 초기값 (공식: `(core_count * 2) + spindle_count`, t3.medium 2 vCPU)
- [ ] **HikariCP `connectionTimeout = 2000ms`**
- [ ] **JDBC `cachePrepStmts=true` / `prepStmtCacheSize=250`**
- [ ] **Logback AsyncAppender** — 파일 I/O를 API 스레드에서 분리

### B.2. Phase 2 개선 사이클에서 도입

MVP에선 일부러 안 넣는다. 측정으로 필요성이 드러나면 추가하고 before/after를 기록.

- [ ] **현재 왕 읽기 캐시 (Caffeine)** — 개선 #1의 조치
- [ ] **감사 로그 배치 INSERT + `rewriteBatchedStatements=true`** — 개선 #2의 조치
- [ ] **SSE 비동기 브로드캐스트 (afterCommit 유지 + ExecutorService)** — 개선 #3의 조치
- [ ] **`innodb_flush_log_at_trx_commit = 2`** — 기본값 1과 수치 비교 실험
- [ ] **HikariCP 풀 사이즈 튜닝** — baseline 후 `hikaricp_pending_connections` 기반으로 8~15 범위 조정
- [ ] **낙관적 락 구현 (version 컬럼)** — 락 전략 A3 실험용

### B.3. Phase 2 사이클 운영 원칙

- 한 번에 **하나의 개선만** 적용. 두 가지를 동시에 바꾸면 어느 쪽의 효과인지 알 수 없다.
- 각 개선은 **별도 커밋 + 별도 측정 문서**. Git log와 `docs/improvement-N.md`가 1:1 대응.
- 예상과 다른 결과가 나오면 그대로 기록. "개선했는데 거의 안 바뀜" 또는 "오히려 나빠짐"도 유효한 포폴 콘텐츠다.

### B.4. 측정 전 체크리스트 (Day 8 baseline 전)

- [ ] B.1 항목 전부 적용되어 있는가
- [ ] 로그 레벨 INFO 이상 (DEBUG는 별도 실험 시)
- [ ] Grafana 대시보드 Baseline 스냅샷 캡처 세팅
- [ ] k6 스크립트 JWT 환경변수 주입 방식 확인
- [ ] EC2 외부에서 k6 실행 (같은 머신에서 실행하면 결과가 오염됨)
