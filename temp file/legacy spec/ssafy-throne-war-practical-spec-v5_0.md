# SSAFY 왕좌전 (Throne War) — 실속형 프로젝트 명세서 v5.0

> 목적: **1회성 배포가 가능한 완성형 포트폴리오**를 만든다.  
> 핵심 학습 목표는 **구조화 로깅**, **Prometheus/Actuator 기반 관측성**, **SSE**, **비동기 처리**이며,  
> 복구·재해대응·대규모 운영 내결함성은 이번 버전의 필수 범위에서 제외한다.

---

## 1. 이 문서의 기준

이 문서는 기존 최종 명세서 v4.3.7의 구조와 판단을 바탕으로, **이번 배포에서 실제로 구현할 내용**과 **추후에 해도 되는 내용**을 분리한 실행용 명세서다.

이번 버전의 원칙은 아래와 같다.

1. **동작하는 서비스**를 먼저 만든다.
2. **로깅 / 메트릭 / SSE / 비동기 처리 경험**이 드러나는 기능을 우선한다.
3. **복구, Redis 유실 대응, Consumer 내결함성, 엄밀한 finalize 게이트**는 후순위로 둔다.
4. 포트폴리오의 핵심 산출물은 **서비스 시연 + before/after 수치 + 기술 선택 근거**다.

---

## 2. 프로젝트 목표

### 2.1. 한 줄 정의

관리자가 이벤트를 열면, GitHub으로 로그인한 사용자가 하나의 왕좌를 실시간으로 뺏고 지키는 경쟁 서비스.

### 2.2. 이번 프로젝트에서 반드시 얻어갈 경험

- **구조화 로깅**
  - JSON 로그
  - MDC 기반 `requestId`, `userId`, `eventId` 추적
  - 민감정보 마스킹
- **관측성**
  - Spring Boot Actuator
  - Micrometer
  - Prometheus 수집 및 PromQL 조회
  - before/after 성능 비교 표 작성
- **실시간 통신**
  - SSE 연결 및 브로드캐스트
  - 인증된 SSE handshake 처리
- **비동기 처리**
  - SSE 비동기 브로드캐스트
  - 감사 로그 비동기 배치 적재
  - Caffeine write-back flush
- **동시성 제어 경험**
  - Phase 1: JVM `ReentrantLock`
  - 선택: Phase 3 Redis Lua

### 2.3. 포트폴리오 메시지

이 프로젝트는 “기술을 많이 붙인 프로젝트”가 아니라,

**“단순한 MVP를 먼저 만들고, 병목을 수치로 확인한 뒤, 필요한 부분만 개선한 프로젝트”**

로 보이도록 설계한다.

---

## 3. 이번 배포의 핵심 범위

이번 배포는 **Phase 1 전체 + Phase 2 핵심 + 선택적 Phase 3 T1**을 목표로 한다.

### 3.1. 이번에 반드시 구현할 것

#### A. 서비스 동작에 필요한 핵심 기능

- GitHub OAuth2 로그인
- 관리자 이벤트 생성 / 시작 / 종료 / finalize
- 왕좌 찬탈 API
- 3초 쿨타임
- 현재 왕 조회
- 리더보드 조회
- 시상 3종 계산
- 최소 프론트 페이지
  - 로그인
  - 현재 왕
  - 찬탈 버튼
  - 리더보드
  - 이벤트 결과

#### B. 이번 프로젝트의 학습 포인트와 직접 연결되는 기능

- **구조화 로깅**
  - JSON 로그 출력
  - MDC 필드 주입
  - 요청/사용자/이벤트 추적
- **관측성**
  - `/actuator/health`
  - `/actuator/prometheus`
  - Prometheus scrape
  - 핵심 커스텀 메트릭
- **SSE**
  - 현재 왕 변경 브로드캐스트
  - 이벤트 상태 변경 브로드캐스트
  - 인증된 SSE handshake
- **비동기 처리**
  - SSE 비동기 발행
  - 감사 로그 배치 insert
  - 집계 캐시 flush

#### C. 배포에 필요한 기본 인프라

- Docker Compose
- Nginx reverse proxy
- MySQL
- Spring Boot
- Prometheus
- EC2 또는 동등한 단일 인스턴스 배포

---

## 4. 이번 배포의 제외 범위

이번 버전에서는 아래를 **지금 당장 구현하지 않아도 된다**.

### 4.1. 운영 복구 / 재해 대응

- Redis 전체 유실 복구
- 앱 재시작 시 Redis state 재구성 자동화
- Consumer 크래시 내성
- Redis Streams + ack 모델
- DLQ 고도화
- 엄밀한 finalize backlog 게이트

### 4.2. 고급 비교 실험

- WebSocket 구현 및 SSE와의 정량 비교
- 다양한 DB 락 전략 실험 (`FOR UPDATE`, `NOWAIT`, optimistic lock)
- 멀티 인스턴스 fan-out

### 4.3. 장기 운영 기능

- 관리자 운영 도구 고도화
- 사용자 차단/제재 고도화
- 로그 보관 정책 고도화
- `throne_claims` 파티셔닝
- 장애 알림 체계
- 모바일/PWA

---

## 5. 아키텍처 개요

### 5.1. 이번 배포 기준 아키텍처

```text
Client (React)
   ↓
Nginx
   ↓
Spring Boot
  ├─ OAuth2 / JWT
  ├─ Claim Service (ReentrantLock)
  ├─ SSE Publisher
  ├─ Async Log Writer
  ├─ Aggregation Cache (Caffeine)
  └─ Actuator / Micrometer
   ↓
MySQL

Prometheus
   ↖ /actuator/prometheus scrape
```

### 5.2. 핵심 설계 포인트

- **쓰기 핫패스는 단순하게 유지**한다.
- **동시성은 우선 JVM 락으로 해결**한다.
- **읽기/로그/SSE는 개선 포인트로 분리**한다.
- **관측성과 로그를 처음부터 넣어** 성능 변화의 원인을 설명할 수 있게 한다.

---

## 6. 기능 명세

## 6.1. 인증

- GitHub OAuth2 로그인 제공
- 최초 로그인 시 사용자 생성
- 사용자 role은 `USER`, `ADMIN`, `SYSTEM`
- 관리자 API는 `ADMIN`만 접근 가능
- 브라우저 기준 인증은 HttpOnly 쿠키 기반

## 6.2. 이벤트 라이프사이클

상태는 다음과 같다.

```text
DRAFT -> RUNNING -> ENDED -> FINALIZED
```

- 관리자는 이벤트를 생성한다.
- 관리자는 이벤트를 시작한다.
- RUNNING 이벤트는 하나만 허용한다.
- 관리자는 이벤트를 종료할 수 있다.
- 종료된 이벤트는 finalize 후 조회 전용이 된다.

## 6.3. 왕좌 찬탈

### 처리 규칙

- RUNNING 이벤트가 없으면 `404 NO_ACTIVE_EVENT`
- 현재 왕이 본인이면 `409 ALREADY_OWNER`
- 쿨타임이면 `429 COOLDOWN`
- 락 대기 중 이벤트가 종료되면 `409 NOT_RUNNING`
- 성공 시 왕좌 교체 + 쿨타임 시작

### Phase 1 처리 방식

- 이벤트별 `ReentrantLock(fair)` 사용
- 요청은 순차 처리
- 락 안에서 DB 반영 수행
- `current_throne` + `throne_reigns` + `cooldowns` 반영

### 감사 로그 정책

- `throne_claims`는 **best-effort** 기록
- `NO_ACTIVE_EVENT`는 DB에 저장하지 않고 메트릭만 기록
- 추후 배치 insert로 개선

## 6.4. 리더보드

- Top 20 조회
- 본인 순위 조회
- FINALIZED 이벤트의 과거 결과 조회
- 이번 버전에서는 **5분 스냅샷**으로 충분

## 6.5. 시상

- 최장 군림상
- 최다 찬탈상
- 최후의 왕

`SYSTEM` 사용자는 리더보드와 시상에서 제외한다.

## 6.6. SSE

이번 버전의 실시간 기능은 **SSE만 구현**한다.

### SSE로 제공할 이벤트

- 왕좌 교체
- 현재 왕 초기 상태
- 이벤트 시작/종료/finalize
- 리더보드 갱신 알림

### 인증

- SSE handshake 시 인증 확인
- 미인증은 `401`
- 브라우저는 쿠키 기반

---

## 7. 비기능 요구사항

## 7.1. 로깅

### 반드시 구현

- JSON 로그
- `requestId`, `userId`, `eventId`, `action` MDC 주입
- 예외 로그 표준화
- 민감정보 마스킹

### 로그 목적

- 특정 요청이 어떤 사용자 요청이었는지 추적
- 찬탈 성공/실패 흐름 관찰
- SSE 발행 지점 추적
- 배치 flush 및 비동기 처리 흐름 확인

## 7.2. 관측성

### 반드시 구현

- Spring Boot Actuator
- Micrometer
- Prometheus scrape
- PromQL로 수치 확인

### 최소 메트릭

- `throne_claim_total{result=...}`
- `throne_claim_latency`
- `throne_lock_wait_duration`
- `throne_lock_queue_length`
- `sse_emit_total`
- `sse_emit_failure_total`
- `claim_log_drop_total`
- `aggregation_flush_total`
- `aggregation_flush_duration`

### 이번 프로젝트의 핵심 관측 포인트

- 락 대기 시간이 얼마나 늘어나는가
- SSE 동기/비동기 전환 후 응답 p99가 어떻게 달라지는가
- 감사 로그 배치 적용 후 DB 부담이 줄어드는가

## 7.3. 비동기 처리

이번 프로젝트에서 “비동기 처리 경험”을 보여주는 핵심은 아래 세 가지다.

### A. SSE 비동기 브로드캐스트

- `afterCommit` 직후 직접 전송하지 않고 executor에 위임
- 연결 수 증가가 응답 tail latency를 과도하게 늘리지 않도록 한다

### B. 감사 로그 배치 insert

- queue 기반 적재
- 일정 건수 또는 주기마다 batch insert
- queue overflow 시 drop + metric 증가

### C. Caffeine write-back flush

- `event_participants` 직접 UPDATE를 핫패스에서 제거
- 캐시에 집계 후 주기적 flush

---

## 8. 데이터 모델

이번 버전에서 필요한 핵심 테이블은 아래로 제한한다.

- `users`
- `events`
- `current_throne`
- `throne_reigns`
- `cooldowns`
- `event_participants`
- `throne_claims`
- `leaderboard_snapshot`
- `event_awards`

### 이번 버전에서 중요한 설계 원칙

- `current_throne`은 현재 왕 복원의 source of truth
- `throne_reigns`는 왕좌 이력
- `event_participants`는 집계 결과 테이블
- `throne_claims`는 감사/분석용 append log
- `SYSTEM` seed 사용자를 두어 이벤트 시작 시 공석 상태를 피한다

---

## 9. 구현 단계

## 9.1. Step 1 — MVP 완성

### 목표

서비스가 실제로 동작해야 한다.

### 구현 항목

- GitHub OAuth2 로그인
- 이벤트 CRUD 중 생성/시작/종료/finalize
- `ReentrantLock` 기반 찬탈
- 쿨타임 처리
- `current_throne`, `throne_reigns`, `cooldowns` 영속화
- SSE 연결 및 왕좌 변경 알림
- 리더보드 조회
- 시상 계산
- React 최소 화면

### 완료 기준

- 여러 사용자가 로그인 가능
- 찬탈 버튼이 동작함
- 현재 왕이 실시간으로 바뀜
- 이벤트 종료 후 결과 조회 가능

## 9.2. Step 2 — 관측성과 로깅 완성

### 목표

서비스가 아니라 **서비스를 설명할 수 있는 상태**가 된다.

### 구현 항목

- JSON 로그
- MDC 필터
- 민감정보 마스킹
- Actuator
- Prometheus 연동
- 커스텀 메트릭 추가
- PromQL 확인 스크립트 정리

### 완료 기준

- 요청 하나를 requestId로 끝까지 추적 가능
- Prometheus에서 찬탈 결과/락 대기/SSE 관련 수치를 볼 수 있음

## 9.3. Step 3 — 비동기 처리 개선

### 목표

이번 프로젝트에서 배우고 싶은 비동기 처리 경험을 실제로 넣는다.

### 구현 항목

- SSE 비동기 브로드캐스트
- 감사 로그 배치 insert
- Caffeine write-back flush

### 완료 기준

- before/after 성능 차이를 설명 가능
- 왜 비동기화가 필요한지 로그/메트릭으로 설명 가능

## 9.4. Step 4 — 성능 측정

### 목표

수치가 남아야 포트폴리오가 된다.

### 구현 항목

- k6 baseline 측정
- 개선 후 재측정
- before/after 표 작성

### 비교 항목 예시

- 찬탈 API p95 / p99
- lock wait
- DB 질문 수 증가율
- SSE 연결 수 증가 시 tail latency
- 감사 로그 insert 경로 차이

## 9.5. Step 5 — 선택적 Redis Phase

시간과 체력이 남을 경우에만 진행한다.

### 이 단계에서 해도 좋은 최소 구현

- Redis Lua 원자 찬탈 스크립트
- 기본 Consumer MySQL 영속화
- k6 재측정

### 이 단계의 목적

- 복구를 완성하는 것이 아니라,
- **MySQL 기반 핫패스의 한계를 Redis가 어떻게 줄이는지 보여주는 것**

---

## 10. 이번 버전의 완료 조건

아래가 되면 이번 프로젝트는 충분히 성공이다.

### 필수 완료 조건

- 서비스 실제 배포
- 로그인 / 이벤트 시작 / 찬탈 / SSE / 종료 / 결과 조회 가능
- JSON 로그 + MDC 동작
- Actuator + Prometheus 연동
- SSE 비동기 브로드캐스트 적용
- 감사 로그 배치 insert 적용
- Caffeine write-back 적용
- k6 before/after 측정 표 존재

### 선택 완료 조건

- Redis Lua 도입 후 재측정

---

## 11. 추후 과제

이번에 안 해도 되지만, 추후 확장 포인트로 문서화해둘 항목이다.

### 11.1. 운영 복구 / 내결함성

- Redis 전체 유실 복구
- 앱 재기동 시 Redis state 복원 자동화
- Consumer retry / DLQ 고도화
- Redis Streams 전환
- finalize backlog 엄밀 게이트

### 11.2. 확장성

- 멀티 인스턴스
- Redis Pub/Sub fan-out
- WebSocket 비교
- 더 정교한 rate limit

### 11.3. 운영 관리

- 관리자 대시보드
- 장애 알림
- 로그 보관 전략
- 테이블 파티셔닝

---

## 12. Known Limitations

이번 버전은 1회성 배포와 포트폴리오 목적에 맞춘다. 따라서 아래는 의도적으로 남긴 한계다.

- Redis 유실 복구 자동화는 구현하지 않는다.
- Consumer 크래시 내성은 이번 버전의 필수 목표가 아니다.
- finalize 정합성 게이트는 단순 버전만 사용하거나, 필요 시 수동 검증으로 대체한다.
- WebSocket은 구현하지 않아도 된다.
- 장기 운영을 위한 로그/데이터 보관 정책은 생략한다.

이 한계들은 설계 미비가 아니라 **이번 버전의 범위 통제 결과**로 본다.

---

## 13. 최종 판단

이번 프로젝트는 아래 순서로 보이도록 완성한다.

1. **동작하는 실시간 서비스**
2. **로그와 메트릭이 보이는 서비스**
3. **비동기 처리로 개선된 서비스**
4. **수치로 설명 가능한 서비스**
5. 시간이 남으면 **Redis로 한 단계 더 개선한 서비스**

즉, 이번 버전의 핵심은

**“복구까지 완벽한 서비스”가 아니라, “실시간성·관측성·비동기 처리 경험이 선명하게 드러나는 서비스”**다.
