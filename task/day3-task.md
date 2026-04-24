# Day 3 Task - Event Lifecycle

## Goal
- Day 3의 목표는 이벤트 라이프사이클을 구현하는 것이다.
- 관리자가 이벤트를 생성하고 시작/종료/finalize 할 수 있어야 한다.
- 서버는 현재 RUNNING 이벤트를 메모리에 들고 있어야 하며, 재시작 후에도 복원할 수 있어야 한다.
- 시간이 지난 RUNNING 이벤트는 스케줄러가 자동으로 ENDED 처리해야 한다.

## Scope For Today
- [x] `Event` 도메인 기본 구조 작성
- [x] 이벤트 상태 전이 구현: `DRAFT -> RUNNING -> ENDED -> FINALIZED`
- [x] `EventRepository` 작성
- [x] 관리자 이벤트 API 작성
- [x] 현재 활성 이벤트 조회 API 작성
- [x] `AtomicReference<Long>` 기반 `currentActiveEventId` 캐시 작성
- [x] 서버 시작 시 RUNNING 이벤트 복원 로직 작성
- [x] `@Scheduled` 기반 만료 이벤트 자동 종료 로직 작성
- [x] 이벤트 시작 시 SYSTEM 사용자로 초기 reign / current_throne 생성
- [x] 스케줄링 활성화 (`@EnableScheduling`)

## What Was Implemented

### 1. Event domain
- [x] `EventStatus` enum 작성
- [x] `Event` 엔티티 작성
- [x] `created_by`, `deleted_at` 등 스키마 기준 필드 반영
- [x] `finalize_()` 메서드 유지
- [x] 상태 전이 검증 로직 추가

### 2. Repository
- [x] `EventRepository extends JpaRepository<Event, Long>`
- [x] RUNNING 이벤트 단건 조회
- [x] 만료된 RUNNING 이벤트 목록 조회
- [x] RUNNING 이벤트 잠금 조회 (`PESSIMISTIC_WRITE`)

### 3. Service
- [x] 이벤트 생성
- [x] 이벤트 시작
- [x] 이벤트 종료
- [x] 이벤트 finalize
- [x] 활성 이벤트 조회
- [x] 서버 시작 시 RUNNING 이벤트 복원
- [x] 만료 이벤트 자동 종료 처리

### 4. Controller
- [x] `POST /api/admin/events`
- [x] `POST /api/admin/events/{eventId}/start`
- [x] `POST /api/admin/events/{eventId}/end`
- [x] `POST /api/admin/events/{eventId}/finalize`
- [x] `GET /api/events/active`

### 5. Minimal throne bootstrap for Day 3
- [x] `ThroneReign` 최소 엔티티 작성
- [x] `CurrentThrone` 최소 엔티티 작성
- [x] `/start` 시 SYSTEM 유저 기반 초기 왕좌 상태 생성

## Verification Checklist For Today

### Build / compile
- [x] 프로젝트가 빌드 또는 컴파일 단계에서 깨지지 않는다
- [x] `./gradlew compileJava` 실행 후 성공을 직접 확인했다

### API / behavior
- [x] 관리자 계정으로 이벤트 생성이 된다
- [x] 생성 직후 상태가 `DRAFT`이다
- [x] `start` 호출 시 상태가 `RUNNING`으로 바뀐다
- [x] `start` 호출 시 `started_at`, `ends_at`이 채워진다
- [x] 동시에 두 번째 이벤트를 `RUNNING`으로 만들 수 없다
- [x] `start` 호출 시 `current_throne`에 SYSTEM 사용자 기준 초기 행이 생성된다
- [x] `start` 호출 후 `GET /api/events/active`가 현재 이벤트를 반환한다
- [x] `end` 호출 시 상태가 `ENDED`로 바뀐다
- [x] `finalize` 호출 시 상태가 `FINALIZED`로 바뀐다

### Scheduler / recovery
- [x] 서버 시작 시 DB에 RUNNING 이벤트가 있으면 활성 이벤트 캐시가 복원된다
- [x] `ends_at <= now` 인 RUNNING 이벤트가 스케줄러에 의해 ENDED 처리된다
- [x] 스케줄러 실행 후 활성 이벤트 캐시가 적절히 비워진다

### Data integrity
- [x] 잘못된 상태 전이 시 `INVALID_EVENT_STATUS`가 반환된다
- [x] 존재하지 않는 이벤트 ID 요청 시 `EVENT_NOT_FOUND`가 반환된다
- [ ] SYSTEM 사용자(`id = 1`)가 없으면 시작 로직이 실패한다

## Next Task Note
- Day 4 이후 작업은 이 문서에 계속 누적하지 않는다.
- 다음 날 작업은 `task/day4-task.md`, `task/day5-task.md` 같은 식으로 분리한다.
- 이 문서에는 Day 3 완료 기준과 Day 3 검증 결과만 남긴다.

## Human-Owned Responsibility
- [x] Docker / MySQL을 올리고 실제 DB 연동 상태를 확인한다
- [x] SYSTEM 사용자 seed가 정상 반영되었는지 확인한다
- [x] 관리자 계정으로 API를 직접 호출해 상태 전이 흐름을 검증한다
- [x] Postman, Swagger, HTTP client 중 하나로 요청/응답을 기록한다
- [x] DB 테이블(`events`, `throne_reigns`, `current_throne`) 변화를 직접 확인한다
- [x] 실패 케이스도 직접 재현해본다
- [x] 오늘 구현 범위를 기준으로 "진짜 Day 3 종료" 여부를 체크한다

## Suggested Manual Test Flow
1. Docker로 MySQL 실행
2. 애플리케이션 실행
3. 관리자 로그인 또는 관리자 토큰 준비
4. 이벤트 생성
5. 생성된 이벤트 시작
6. DB에서 `events.status = RUNNING` 확인
7. DB에서 `current_throne.user_id = 1` 확인
8. `GET /api/events/active` 호출
9. 이벤트 종료
10. finalize 호출
11. 필요하면 `ends_at`을 지난 데이터로 스케줄러 동작 확인
12. 서버 재시작 후 RUNNING 이벤트 복원 확인

## Definition Of Done For Day 3
- [x] 코드가 빌드된다
- [x] 이벤트 생성/시작/종료/finalize 흐름이 실제로 동작한다
- [x] 활성 이벤트 조회가 동작한다
- [x] 서버 재시작 복원이 동작한다
- [x] 만료 이벤트 자동 종료가 동작한다
- [x] DB 상태가 명세와 어긋나지 않는다
- [x] 본인이 흐름을 설명할 수 있다

## Notes
- Day 3의 핵심은 "이벤트 상태 머신"과 "운영 중인 이벤트를 관리하는 최소 기반"이다.
- 아직 찬탈 본게임 로직이 없더라도 Day 3는 끝날 수 있다.
- 이 문서는 구현 완료 체크리스트이자, 직접 검증할 작업 지시서 역할을 한다.
- 2026-04-24 검증 결과:
  - `./gradlew compileJava` 성공
  - 관리자 JWT로 `create -> start -> active 조회 -> end -> finalize` 실제 검증
  - 앱 재시작 후 RUNNING 이벤트 복원 검증
  - `ends_at`을 과거로 조정한 뒤 스케줄러 자동 종료 검증
  - 존재하지 않는 이벤트 ID로 `EVENT_NOT_FOUND` 검증
