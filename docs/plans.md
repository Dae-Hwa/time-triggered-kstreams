# 목표

Kafka Streams 기반 1분 tick 트리거 라이브러리에서 “정확한 인터벌 의미론(1분 경과 시 반드시 실행)”을 강화한다.

* 기존 requirements.md에 있는 요구(앵커 토픽, wall-clock, alignToMinute, scope 등)를 유지한다.
* 추가 요구: 드리프트 방지(next_due_ms), 검사 주기(checkPeriodMs), catch-up 정책 옵션화, 명확성 강화 컨텍스트(B안).
* 아직 외부 배포 이력이 없으므로 API/모델의 파괴적 변경을 허용한다.

---

# 다음 일감 — requirements.md 기준 미구현 항목 정리

## PR/Commit 6 — storeType 옵션 + persistent store 지원

### 완료 조건

* TickSchedulerConfig에 `storeType: IN_MEMORY | PERSISTENT` 추가
* TickTopologyBuilder가 storeType에 따라 in-memory / RocksDB store를 생성
* TtkProperties에도 storeType 노출
* 관련 테스트 보강(TopologyTestDriver로 store 생성 경로 확인)

## PR/Commit 7 — Serde 주입 포인트 제공(Topology/DSL)

### 완료 조건

* Topology 경로에서 output key/value Serde 및 store Serde를 주입 가능
* DSL 경로에서도 Produced/Serdes 주입 포인트 제공
* 기본값은 기존 동작과 동일하게 유지

## PR/Commit 8 — Testcontainers 통합 테스트(주기 발행 검증)

### 완료 조건

* Kafka 컨테이너 기반 통합 테스트 추가
* 실제 wall-clock 기반 tick이 출력 토픽에 발행됨을 검증

## PR/Commit 9 — TTL 옵션(확장 포인트)

### 완료 조건

* TTL 관련 설정 추가(기본은 비활성)
* TTL 적용/비적용 경로가 명확히 분리됨

## PR/Commit 10 — Exactly-Once V2 옵션(확장 포인트)

### 완료 조건

* EOS V2 관련 설정 추가
* 필요한 프로듀서/스트림즈 설정 문서화

* `skippedCount`를 로그/metrics로 노출할 수 있는 훅(추후 micrometer 연동 포인트) 위치를 TODO로 남긴다.
