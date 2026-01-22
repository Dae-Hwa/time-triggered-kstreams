# Time-triggered Kafka Streams (tick-handler) 설계 문서 v3 (Requirements 통합 + 명확성 강화)

## 0. 목적과 배경

### 목표

* **입력 메시지가 없어도** 지정한 **인터벌(예: 60초)** 단위로 트리거가 **반드시 실행**되게 한다.
* 시작 초(second) 단위는 틀어져도 허용하되, **“인터벌이 경과하면 최소 1회 실행”** 의미론을 보장한다.
* 지연/누락(catch-up) 처리 방식은 **옵션으로 제공**한다.
* 라이브러리 형태로 재사용/배포 가능하도록 설계한다.

### 기존 문제

* Kafka Streams 윈도우/서프레션은 stream-time(event-time)에 의존하는 경우가 많아 **입력이 없으면 동작이 정지**할 수 있다.
* Kafka/Kafka Streams는 정밀 스케줄러가 아니므로, wall-clock 기반도 **지연/지터**가 발생할 수 있다.
* 따라서 “정각 실행 보장”이 아니라 **논리적 인터벌 의미론**을 유지하는 보정 로직이 필요하다.

---

## 1. 트리거 기준 (Wall-Clock vs Stream-Time)

### 결정: Wall-Clock Time 사용

* Wall-Clock은 시스템 시간을 기준으로 고정 간격 동작하며, 입력 레코드가 없어도 실행 가능
* Stream-Time은 입력 timestamp 진행에 의존하여 입력이 없으면 정지 가능

---

## 2. 분 경계 정렬(alignToMinute)

### 결정: 분 경계 정렬 옵션 제공

* alignToMinute=true: 앱 시작 후 **다음 분 경계(HH:mm:00)** 를 첫 due로 설정하고 이후 interval 단위로 due 전진
* alignToMinute=false: 시작 시점 기준 `now + interval`을 첫 due로 설정

### 주의(의미 명확화)

* “정각에 실제 실행”은 best-effort
* 본 설계는 `fireAtEpochMs`(논리 due)를 분 경계로 고정하여 **논리 시각 의미론**을 유지한다.

---

## 3. 핵심 설계: 논리 due-time 기반 인터벌 보장

### 핵심 아이디어

* store에 `next_due_ms`를 저장한다.
* 스케줄 콜백은 “실행”이 아니라 “검사(check)”에 사용한다.
* 매 검사 시 `wallClockNow >= nextDue`이면 tick을 실행한다.
* 실행 후 nextDue는 wallClockNow로 재설정하지 않고, **논리적으로 전진**한다(드리프트 방지).

### 검사 주기(checkPeriod) 분리

* 실행 주기(intervalMs)와 검사 주기(checkPeriodMs)를 분리한다.
* 권장 기본값: `checkPeriodMs = 1000` (1초)
* 목적: “1분이 지났는데도 실행이 안 됨”을 줄이고, 인터벌 의미론을 강하게 만든다.

---

## 4. 누락(catch-up) 정책 옵션

### CatchUpMode

* `LATEST_ONLY` : 누락이 있어도 **1회만 실행**
* `CATCH_UP_ALL` : 누락된 인터벌 수만큼 **모두 실행**
* `CATCH_UP_BOUNDED(maxCatchUp)` : 최대 N회까지만 실행(기본 예: 60)

### 공통 계산(정의)

* nextDue: store에 저장된 다음 논리 실행 시각
* interval: intervalMs
* missed: `(wallClockNow - nextDue) / interval` (wallClockNow >= nextDue일 때)
* dueCount = missed + 1
* lastDue = nextDue + missed*interval

### 모드별 실행

* LATEST_ONLY

  * fireAt = lastDue (1회 실행)
  * skippedCount = dueCount - 1
  * nextDue = lastDue + interval
* CATCH_UP_ALL

  * fireAt = nextDue, nextDue+interval, ... lastDue (dueCount회)
  * skippedCount = 0
  * nextDue = lastDue + interval
* CATCH_UP_BOUNDED

  * runCount = min(dueCount, maxCatchUp)
  * 최신 기준 실행: lastDue-(runCount-1)*interval ... lastDue
  * skippedCount = dueCount - runCount
  * nextDue = lastDue + interval

---

## 5. 스코프 (파티션별 vs 글로벌 단일)

### 기본: PARTITION (태스크/파티션별 트리거)

* 각 태스크가 독립적으로 tick을 발생

### 옵션: GLOBAL_SINGLETON (전역 단일 트리거)

* 1안(현재 구현 방향): **partition 0만 실행**
* 2안(향후 확장): 글로벌 store에 리더 플래그 + TTL/갱신으로 리더십 유지(장애 시 승계)

  * 초기 버전에서는 1안을 기본으로 두고 2안은 확장 포인트로 남긴다.

---

## 6. 출력 동작 및 “앵커 토픽” 요구사항

### 입력이 없어도 발행하려면?

* Kafka Streams는 최소 하나의 소스 노드가 필요
* 따라서 “빈 앵커 토픽(예: ttk-anchor)”을 소스로 두고, 레코드는 무시하되 wall-clock 기반 스케줄로 주기 발행을 수행한다.

### 출력 토픽

* 기본값: `time-triggered-ticks`
* 설정으로 변경 가능

### Serde

* key/value Serde 설정을 구성 옵션으로 제공(기본값은 라이브러리에서 합리적으로 선택)
* (구현 시) Topology/DSL 경로 모두에서 Serde 주입 포인트 제공

---

## 7. Processor vs Transformer

### Processor API

* Topology를 수동 구성(addSource/addProcessor/addSink)
* ProcessorContext.schedule()로 punctuator 등록
* “입력 무시 + 주기 발행” 체인 구성에 유리

### Transformer (DSL)

* KStream.transform()/transformValues()로 DSL에 삽입
* schedule() 사용 가능
* DSL 파이프라인 결합이 용이

### 결정

* 라이브러리에서 두 가지 어댑터 모두 제공(Processor/Transformer)

---

## 8. State Store 사용 및 TTL

### 요구

* 필요 시 tick 로직에서 store read/write 가능해야 함

### 설계

* KeyValueStore 바인딩 지원(in-memory 또는 persistent(RocksDB))
* TickInvocationContext를 통해 ReadOnly/ReadWrite 접근 제공

### TTL(옵션)

* 글로벌 리더십 유지/캐시 청소/집계 만료 등에서 필요할 수 있음
* 초기 버전은 TTL을 강제하지 않고 옵션/확장 포인트로 제공

---

## 9. API/모델 (명확성 강화: 확정)

### 9.1 TickSchedulerConfig 확장

* intervalMs: Long = 60_000
* alignToMinute: Boolean = true
* checkPeriodMs: Long = 1_000
* catchUpMode: CatchUpMode = LATEST_ONLY
* maxCatchUp: Int = 60
* scope: PARTITION | GLOBAL_SINGLETON
* outputTopic: String = "time-triggered-ticks"
* storeName: String? = null
* (선택) storeType: IN_MEMORY | PERSISTENT

```kotlin
enum class CatchUpMode { LATEST_ONLY, CATCH_UP_ALL, CATCH_UP_BOUNDED }
```

### 9.2 TickInvocationContext (명확성 강화)

* wallClockNowEpochMs: Long        // 스케줄 콜백이 실제로 실행된 시각
* fireAtEpochMs: Long              // 논리적으로 이번 tick이 “발생해야 하는” 시각(due)
* dueCount: Long                   // 이번 체크에서 논리상 처리 대상 tick 개수
* skippedCount: Long               // 실제 실행하지 않은 tick 개수(bounded 또는 latest-only에서 발생 가능)
* catchUpMode: CatchUpMode         // 적용된 정책
* taskId: String
* processorContext: ProcessorContext<*, *>?
* storeAccessor: StoreAccessor?

주의: 기존 nowEpochMs는 제거 또는 deprecated 처리하여 의미 혼동을 피한다.

### 9.3 TickHandler

* `onTick(context: TickInvocationContext): KeyValue<K, V>?`

---

## 10. 구현 변경 포인트(작업 단위)

### 10.1 TickProcessor

* schedule을 intervalMs가 아니라 checkPeriodMs로 등록
* store에서 next_due_ms 읽기/초기화(alignToMinute 반영)
* wallClockNow >= nextDue이면 모드별 실행
* TickInvocationContext를 wallClockNow/fireAt/dueCount/skippedCount로 채워 handler 호출
* 실행 후 next_due_ms 갱신(드리프트 방지)

### 10.2 TickTransformer

* TickProcessor와 동일한 보정 로직 적용
* transform()은 null 반환(입력 무시)

### 10.3 TickTopologyBuilder

* 앵커 토픽/출력 토픽 기본값 유지
* storeType/persistent 옵션 지원(선택)
* scope/global 모드 선택 반영
* 테스트/결정적 재현을 위해 `timeProvider: () -> Long` 옵션을 제공(기본값: `System.currentTimeMillis`)

예시:

```kotlin
val fixedNow = 100_000L
TickTopologyBuilder.addTickProcessor(
  topology = topology,
  schedulerConfig = config,
  tickHandler = handler,
  timeProvider = { fixedNow }
)
```

---

## 11. 정확성/트랜잭션

* 기본: At-Least-Once
* 옵션: Exactly-Once V2는 향후 확장(설정 및 프로듀서 트랜잭션 포함)

---

## 12. 테스트 전략

* 단위 테스트: 알고리즘 중심(wallClockNow/nextDue/interval/catchUpMode)
* 통합 테스트: Testcontainers Kafka로 주기 발행 검증
* TopologyTestDriver: 간격을 5초 등으로 단축하여 반복 검증

---

## 13. 운영/구성 및 NTP

* Wall-Clock 기반은 시스템 시간이 중요하므로 NTP 동기화 권장
* 노드 간 시간 오차가 크면 파티션별 트리거 시점 불일치 가능

---

## 14. 결정 요약

* Wall-Clock 사용
* 분 경계 정렬 옵션
* 파티션별 기본 + 글로벌 단일 옵션
* Processor/Transformer 모두 제공
* store 접근 허용 및 storeType 옵션
* 인터벌 의미론 보강: next_due_ms + checkPeriodMs + catchUpMode
* 기본 At-Least-Once, EOS는 향후 확장
