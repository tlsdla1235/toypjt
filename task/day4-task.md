# Day 4 Task - Throne Claim MVP

## Goal
- Day 4의 목표는 실제 왕좌 찬탈 MVP를 구현하는 것이다.
- RUNNING 이벤트에서 로그인한 사용자가 `/api/events/active/claim`으로 왕좌를 탈취할 수 있어야 한다.
- 찬탈은 이벤트당 공정한 순서로 직렬 처리되어야 하며, 쿨타임과 현재 왕 상태를 정확히 판정해야 한다.
- DB 상태, 메모리 상태, 감사 로그가 한 흐름으로 정합성을 유지해야 한다.

## Scope For Today
- [ ] `POST /api/events/active/claim` API 작성
- [ ] `ThroneService.claim()` 작성
- [ ] 이벤트별 `ReentrantLock(true)` 적용
- [ ] `TransactionTemplate` 기반 찬탈 트랜잭션 작성
- [ ] `throneMap`, `lockMap` 도입
- [ ] 현재 왕 / 본인 여부 / 쿨타임 판정 로직 작성
- [ ] reign 종료 + 신규 reign 생성 + `current_throne` 갱신 작성
- [ ] `cooldowns` upsert 작성
- [ ] `throne_claims` 감사 로그 기록 작성
- [ ] 성공 시 afterCommit 기반 메모리 상태 갱신 작성

## What Should Be Implemented

### 1. Claim entrypoint
- [ ] `POST /api/events/active/claim` 컨트롤러 작성
- [ ] `currentActiveEventId == null` 이면 즉시 `NO_ACTIVE_EVENT`
- [ ] 컨트롤러에서 활성 이벤트 ID를 가져와 `throneService.claim(eventId, userId)` 호출

### 2. Throne in-memory state
- [ ] `ThroneState` immutable record 작성
- [ ] `throneMap: ConcurrentHashMap<Long, ThroneState>` 작성
- [ ] `lockMap: ConcurrentHashMap<Long, ReentrantLock>` 작성
- [ ] Day 3의 이벤트 시작/종료/finalize 흐름과 메모리 상태를 연결

### 3. Claim transaction flow
- [ ] 락 바깥 쿨타임 선체크 추가
- [ ] 단, 선체크는 fast-fail 하지 않고 힌트용으로만 사용
- [ ] `ReentrantLock(true)` 로 이벤트별 락 획득
- [ ] `TransactionTemplate` 내부에서 `doClaim()` 수행
- [ ] 판정 순서를 `NOT_RUNNING -> ALREADY_OWNER -> COOLDOWN -> SUCCESS` 로 유지

### 4. Persistence updates
- [ ] 현재 열린 reign 종료 (`ended_at`, `duration_ms`)
- [ ] 신규 reign 생성
- [ ] `current_throne` 갱신
- [ ] `cooldowns` upsert
- [ ] 감사 로그 저장

### 5. afterCommit updates
- [ ] 성공 시 `throneMap` 갱신을 afterCommit으로 이동
- [ ] 성공 로그도 afterCommit에서 기록
- [ ] 롤백 시 메모리 상태가 바뀌지 않도록 유지

## Required Domain / Infra Pieces

### Entities / repositories likely needed
- [ ] `Cooldown` 엔티티 및 리포지토리
- [ ] `ThroneClaim` 엔티티 및 리포지토리
- [ ] `ThroneReignRepository`에 close/open 작업 메서드 추가
- [ ] `CurrentThroneRepository`에 update 또는 save 전략 정리

### Service support
- [ ] `TransactionTemplate` 빈 준비 또는 주입 방식 결정
- [ ] claim 결과 응답 DTO 작성 (`SUCCESS`, `remainingMs`, `reignId` 등)
- [ ] 예외/에러코드 매핑 정리

## Behavior Rules To Keep
- [ ] RUNNING 이벤트가 없으면 `404 NO_ACTIVE_EVENT`
- [ ] 본인이 현재 왕이면 `409 ALREADY_OWNER`
- [ ] 쿨타임 중이면 `429 COOLDOWN`
- [ ] 락 대기 중 이벤트가 종료되면 `409 NOT_RUNNING`
- [ ] 성공 시 이전 왕의 reign 종료 시간이 기록된다
- [ ] 성공 시 새 reign이 열린다
- [ ] 성공 시 `current_throne`가 새 왕으로 바뀐다
- [ ] 성공 시 새 왕에게 쿨타임이 설정된다

## Verification Checklist For Today

### Build / compile
- [ ] `./gradlew compileJava` 성공

### API / behavior
- [ ] RUNNING 이벤트가 없을 때 `claim`이 `NO_ACTIVE_EVENT`를 반환한다
- [ ] 첫 찬탈 성공 시 `SUCCESS`를 반환한다
- [ ] 첫 찬탈 성공 시 SYSTEM reign이 종료된다
- [ ] 첫 찬탈 성공 시 새로운 reign이 생성된다
- [ ] 첫 찬탈 성공 시 `current_throne.user_id`가 새 유저로 바뀐다
- [ ] 같은 유저가 바로 다시 요청하면 `ALREADY_OWNER`가 반환된다
- [ ] 다른 유저가 연속 요청하면 정상적으로 왕이 교체된다
- [ ] 쿨타임 중인 유저는 `COOLDOWN`을 받는다
- [ ] 이벤트 종료 후 `claim` 시 `NO_ACTIVE_EVENT` 또는 `NOT_RUNNING` 경로가 의도대로 동작한다

### Concurrency / lock
- [ ] 동시에 여러 요청을 보내도 왕좌 변경이 순차 처리된다
- [ ] 2명 이상이 거의 동시에 요청해도 열린 reign이 2개 생기지 않는다
- [ ] `current_throne`와 `throne_reigns` 결과가 서로 모순되지 않는다

### Audit / persistence
- [ ] 성공 요청이 `throne_claims`에 기록된다
- [ ] `COOLDOWN` 실패가 `throne_claims`에 기록된다
- [ ] `ALREADY_OWNER` 실패가 `throne_claims`에 기록된다
- [ ] `NOT_RUNNING` 실패가 `throne_claims`에 기록된다
- [ ] `NO_ACTIVE_EVENT`는 DB 기록 없이 응답만 반환된다

## Human-Owned Responsibility
- [ ] Day 3 상태에서 RUNNING 이벤트를 하나 준비한다
- [ ] 테스트용 일반 사용자 2명 이상을 준비한다
- [ ] 토큰을 직접 발급하거나 로그인 흐름으로 테스트 계정을 확보한다
- [ ] API 호출 순서를 직접 기록한다
- [ ] DB에서 `throne_reigns`, `current_throne`, `cooldowns`, `throne_claims` 변화를 직접 본다
- [ ] 적어도 한 번은 거의 동시에 요청을 보내보며 결과를 비교한다
- [ ] 본인이 `NOT_RUNNING`, `ALREADY_OWNER`, `COOLDOWN`의 차이를 설명할 수 있어야 한다

## Suggested Manual Test Flow
1. RUNNING 이벤트 1개 준비
2. 유저 A로 `claim` 호출
3. 응답이 `SUCCESS`인지 확인
4. DB에서 SYSTEM reign 종료 여부 확인
5. DB에서 `current_throne.user_id = A` 확인
6. 유저 A로 즉시 다시 `claim` 호출
7. `ALREADY_OWNER` 확인
8. 유저 B로 `claim` 호출
9. 왕이 B로 바뀌는지 확인
10. 유저 A가 바로 재요청하면 `COOLDOWN`인지 확인
11. 이벤트 종료 후 `claim` 호출
12. `NO_ACTIVE_EVENT` 또는 `NOT_RUNNING` 경로 확인

## Definition Of Done For Day 4
- [ ] 찬탈 API가 동작한다
- [ ] 현재 왕 판정이 맞다
- [ ] 쿨타임 판정이 맞다
- [ ] reign 종료/생성이 정합하게 기록된다
- [ ] `current_throne`가 정확히 갱신된다
- [ ] 메모리 상태와 DB 상태가 모순되지 않는다
- [ ] 실패 케이스가 명세대로 응답한다
- [ ] 본인이 락, 쿨타임, 판정 순서를 설명할 수 있다

## Notes
- Day 4의 핵심은 "빠른 기능 추가"보다 "정합성을 잃지 않는 최소 찬탈 흐름"이다.
- 이 단계에서는 성능 최적화보다 상태 일관성이 우선이다.
- SSE 비동기화, 감사 로그 배치, 집계 캐시는 Day 4 범위가 아니다.
