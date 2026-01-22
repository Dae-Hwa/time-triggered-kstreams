# 목표

Kafka Streams 기반 1분 tick 트리거 라이브러리에서 “정확한 인터벌 의미론(1분 경과 시 반드시 실행)”을 강화한다.

* 기존 requirements.md에 있는 요구(앵커 토픽, wall-clock, alignToMinute, scope 등)를 유지한다.
* 추가 요구: 드리프트 방지(next_due_ms), 검사 주기(checkPeriodMs), catch-up 정책 옵션화, 명확성 강화 컨텍스트(B안).
* 아직 외부 배포 이력이 없으므로 API/모델의 파괴적 변경을 허용한다.

---

# PR/Commit 1 — API/모델 변경(파괴적 변경 허용)

## 완료 조건

* TickInvocationContext가 “wallClockNow vs fireAt”를 분리한 B안으로 완전히 교체된다.
* TickSchedulerConfig에 checkPeriodMs/catchUpMode/maxCatchUp가 추가된다.
* 기존 nowEpochMs 등 의미가 혼동되는 필드는 제거(또는 완전 교체)한다.
* 컴파일이 깨지더라도 OK(다음 커밋에서 수습)지만, 최소한 변경 방향이 명확해야 한다.

## 변경 지시

1. enum 추가

* `CatchUpMode { LATEST_ONLY, CATCH_UP_ALL, CATCH_UP_BOUNDED }`

2. TickSchedulerConfig 확장

* 필드 추가:

  * `checkPeriodMs: Long = 1000`
  * `catchUpMode: CatchUpMode = LATEST_ONLY`
  * `maxCatchUp: Int = 60`
* 기존 필드 유지:

  * intervalMs, alignToMinute, scope, outputTopic, storeName(있는 경우)
* (선택) storeType: IN_MEMORY | PERSISTENT 는 이번 PR에서는 보류 가능

3. TickInvocationContext 재설계(B안 확정)

* 새 필드(최소):

  * `wallClockNowEpochMs: Long`
  * `fireAtEpochMs: Long`
  * `dueCount: Long`
  * `skippedCount: Long`
  * `catchUpMode: CatchUpMode`
  * `taskId: String`
  * `processorContext: ProcessorContext<*, *>?`
  * `storeAccessor: StoreAccessor?`
* 기존 nowEpochMs 같은 애매한 필드는 제거한다.

4. TickHandler 시그니처는 유지

* `onTick(context: TickInvocationContext): KeyValue<K, V>?`

---

# PR/Commit 2 — 순수 알고리즘 모듈(TickComputation) + 단위 테스트

## 완료 조건

* “now/nextDue/interval/catchUpMode”로부터:

  * 실행 fireAt 목록
  * dueCount
  * skippedCount
  * newNextDue
    를 계산하는 순수 함수가 존재한다.
* 이 함수에 대한 단위 테스트가 존재한다(Kotest or JUnit 중 repo 스타일 따라).
* 테스트는 최소 6케이스 이상 포함한다(모드별, 경계값 포함).

## 구현 지시

1. data class 정의

* `TickPlan(
    val fires: List<Long>,
    val dueCount: Long,
    val skippedCount: Long,
    val newNextDueEpochMs: Long
  )`

2. 순수 함수

* `fun computeTickPlan(
    wallClockNowEpochMs: Long,
    nextDueEpochMs: Long,
    intervalMs: Long,
    catchUpMode: CatchUpMode,
    maxCatchUp: Int
  ): TickPlan`
* 규칙:

  * wallClockNow < nextDue -> fires empty, dueCount=0, skipped=0, newNextDue=nextDue
  * wallClockNow >= nextDue:

    * missed = (wallClockNow - nextDue) / interval
    * dueCount = missed + 1
    * lastDue = nextDue + missed*interval
    * LATEST_ONLY:

      * fires=[lastDue]
      * skipped=dueCount-1
      * newNextDue=lastDue+interval
    * CATCH_UP_ALL:

      * fires=[nextDue .. lastDue step interval]
      * skipped=0
      * newNextDue=lastDue+interval
    * CATCH_UP_BOUNDED:

      * runCount=min(dueCount, maxCatchUp)
      * fires= 최신 기준(lastDue-(runCount-1)*interval .. lastDue)
      * skipped=dueCount-runCount
      * newNextDue=lastDue+interval

3. 단위 테스트 케이스(예시)

* now < nextDue
* now == nextDue
* now = nextDue + 2.5*interval (모드별 LATEST/ALL/BOUNDED)
* BOUNDED에서 maxCatchUp=1
* intervalMs=60_000, alignToMinute 여부와 무관한 순수 계산 검증

---

# PR/Commit 3 — TickProcessor에 checkPeriod + next_due_ms + catch-up 적용

## 완료 조건

* TickProcessor가 intervalMs가 아니라 checkPeriodMs로 schedule 한다.
* store에 `next_due_ms`를 읽고/초기화하고/갱신한다.
* computeTickPlan 결과에 따라 handler를 호출한다.
* TickInvocationContext를 B안 필드로 채운다.
* 입력 레코드는 계속 무시한다.

## 구현 지시

1. schedule 변경

* `context.schedule(Duration.ofMillis(config.checkPeriodMs), PunctuationType.WALL_CLOCK_TIME, ...)`

2. store key

* store에 `next_due_ms` 저장/조회
* 초기화 로직:

  * alignToMinute=true면 “다음 분 경계”를 nextDue로 설정
  * 아니면 now + intervalMs

3. 매 punctuate 시

* wallClockNow = timestamp(혹은 System.currentTimeMillis() 사용 여부는 현재 구현 스타일에 맞춤)
* nextDue = store.get(next_due_ms)
* plan = computeTickPlan(wallClockNow, nextDue, intervalMs, catchUpMode, maxCatchUp)
* for each fireAt in plan.fires:

  * context = TickInvocationContext(
    wallClockNowEpochMs=wallClockNow,
    fireAtEpochMs=fireAt,
    dueCount=plan.dueCount,
    skippedCount=plan.skippedCount,
    catchUpMode=config.catchUpMode,
    taskId=..., processorContext=..., storeAccessor=...
    )
  * handler.onTick(context) 반환이 null 아니면 sink로 emit
* store.put(next_due_ms, plan.newNextDueEpochMs) (fires가 비어도 newNextDue가 nextDue면 유지)

4. GLOBAL_SINGLETON scope

* 현재 구현이 partition 0만 실행하는 방식이면 그대로 유지
* (향후 확장) 리더 선출/TTL은 이번 PR에서 구현하지 않음

---

# PR/Commit 4 — TickTransformer에도 동일 로직 적용

## 완료 조건

* TickTransformer가 TickProcessor와 동일한 시간 보정 로직을 갖는다.
* DSL 파이프라인에서 입력 무시 + 주기 실행이 동일하게 동작한다.

---

# PR/Commit 5 — 문서 업데이트(requirements.md -> v3 반영)

## 완료 조건

* docs/requirements.md에 다음 항목이 명확히 포함된다:

  * “정각 실행은 best-effort, 논리 due는 fireAtEpochMs로 유지”
  * checkPeriodMs 개념(검사 주기)
  * next_due_ms 기반 드리프트 방지
  * catchUpMode 옵션 설명(LATEST_ONLY/ALL/BOUNDED)
  * TickInvocationContext B안 필드(최소 스펙)
  * 앵커 토픽 필요성 유지

---

# 보너스(가능하면)

* `skippedCount`를 로그/metrics로 노출할 수 있는 훅(추후 micrometer 연동 포인트) 위치를 TODO로 남긴다.
