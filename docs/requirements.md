## Time-Triggered Kafka Streams - Requirements & Decisions

### 목표
- Kafka Streams Processor/Transformer API를 이용해 Punctuator 기반 1분 트리거 구현
- 입력 유무와 관계없이 주기적으로 출력 레코드 발행 가능
- 라이브러리 형태로 재사용/배포 가능하도록 설계

### 트리거 기준 (Wall-Clock vs Stream-Time)
- 결정: Wall-Clock Time 사용
- 설명: Wall-Clock은 시스템 실시간을 기준으로 고정 간격으로 동작. 입력 레코드가 없어도 1분마다 트리거됨. Stream-Time은 입력 레코드의 타임스탬프 진행에 의존하여 입력이 없으면 정지할 수 있음.

### 분 경계 정렬
- 결정: 분 경계(예: HH:mm:00) 정렬 지원. 애플리케이션 시작 후 다음 분 경계에 첫 발사, 이후 매분 정각 발사.
- 구현 포인트: 첫 스케줄링 시 현재 시간을 기준으로 다음 분 경계까지 대기 후 고정 간격 스케줄.

### 스코프 (파티션별 vs 글로벌 단일)
- 기본: 파티션/태스크별 트리거 동작 (각 태스크가 독립적으로 매분 실행)
- 옵션: 글로벌 단일 트리거 모드 제공(향후 확장). 구현 아이디어:
  - 파티션 0만 수행하도록 조건 설정하거나
  - 글로벌 StateStore에 리더 선출 플래그를 저장하고 TTL/갱신으로 리더십 유지

### 출력 동작
- 라이브러리에서 사용자 정의 콜백(함수형 인터페이스)으로 트리거 시 실행할 로직 주입 가능하게 설계
- 입력이 없어도 발행: Kafka Streams 특성상 최소 하나의 소스 노드가 필요. 빈 앵커 토픽(또는 글로벌 스토어)로 태스크를 생성한 뒤 Wall-Clock Punctuator가 주기 발행 수행
- 임시 출력 토픽(예: `time-triggered-ticks`)을 기본값으로 제공하되 설정으로 변경 가능

### Processor vs Transformer 차이 설명 및 선택
- Processor API:
  - Topology를 수동으로 구성(`Topology.addSource/Processor/Sink`)
  - `ProcessorContext.schedule()`로 Punctuator 등록
  - 레코드 없이도 동작하는 순수 프로세서 체인을 만들기 쉬움
- Transformer (DSL):
  - `KStream.transform()`/`transformValues()`로 DSL 안에 주입
  - `ProcessorContext`에 접근 가능하여 `schedule()` 사용 가능
  - DSL 파이프라인에 쉽게 결합 가능
- 결정: 라이브러리에서 두 가지 어댑터를 모두 제공
  - DSL 사용자: `Transformer` 구현체 제공
  - 저수준 사용자: `Processor` 구현체 제공

### State Store 사용 및 TTL 설명
- 요구: 필요 시 스토어에서 읽기/쓰기 가능해야 함
- 설계:
  - 옵션으로 `KeyValueStore`(RocksDB 또는 In-Memory) 바인딩 지원
  - 트리거 콜백에서 `ReadOnlyKeyValueStore`/`KeyValueStore` 접근 가능 API 제공
- TTL(왜 필요한가?):
  - 주기적 집계나 리더십(글로벌 모드) 유지에 만료 개념이 필요할 수 있음
  - 예: 글로벌 리더 플래그에 TTL을 두어 장애 시 자동 승계, 또는 오래된 캐시/집계 데이터 청소
  - 초기 버전은 TTL 강제하지 않고 옵션으로 제공

### 정확성/트랜잭션
- 기본: At-Least-Once
- 옵션: Exactly-Once V2로 향후 확장 가능(프로듀서 트랜잭션 및 EOS 설정 추가)

### 테스트 전략
- 단위 테스트: `TopologyTestDriver` 사용, 테스트 프로파일에서 간격 5초 등으로 단축
- 통합 테스트: Testcontainers Kafka 사용, 주기 발행 검증
- 프레임워크: JUnit + Kotest assertions 조합 사용

### 운영/구성
- 토픽: 기본값 `time-triggered-ticks` (key: string, value: json/string), 설정으로 변경 가능
- 부트스트랩 서버: 테스트는 Testcontainers, 로컬/운영은 설정 주입
- Docker Compose: 카프카/주키퍼(또는 KRaft 모드) 추가 예정

### NTP 동기화 설명
- NTP(Network Time Protocol)는 서버 시간을 공인 시간원본에 맞추는 프로토콜
- Wall-Clock 기반 스케줄은 시스템 시간이 정확해야 분 경계 정렬 정확도가 높음
- 클러스터 노드 간 시간이 크게 어긋나면 파티션별 트리거 시점 불일치 가능성 증가 → NTP로 시간 동기화 권장

### 구성 옵션(초안)
- intervalMs: 기본 60_000ms
- alignToMinute: true/false (기본 true)
- scope: partition | global-singleton (기본 partition)
- outputTopic: 기본 `time-triggered-ticks`
- serde: key/value Serde 설정
- store: optional store name/type

### 공개 API(초안)
- 라이브러리 컴포넌트
  - `TickProcessor` / `TickTransformer`
  - `TickSchedulerConfig(intervalMs, alignToMinute, scope, ...)`
  - `TickHandler`(콜백 인터페이스): `onTick(context, storeAccessor)`

### 결정 요약
- Wall-Clock, 분 경계 정렬 지원, 파티션별 기본 + 글로벌 옵션, Processor/Transformer 모두 제공, 필요 시 스토어 접근 허용, 기본 At-Least-Once, 테스트는 짧은 간격으로 수행.


