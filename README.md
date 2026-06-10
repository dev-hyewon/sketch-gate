# Twin-Sketch Gate 🛡️
**Twin-Sketch** Gate는 Java와 고성능 네트워크 엔진인 Netty를 기반으로 구현된 초경량·초고속 DDoS 방어 및 레이트 리미터(Rate Limiter)입니다.

메모리를 기가바이트(GB) 단위로 낭비하는 기존의 고정 배열 방식 대신, Count-Min Sketch 알고리즘과 짝/홀수 초 더블 버퍼링(포인터 스왑) 기법을 결합하여 단 수십 MB의 메모리만으로 대규모 패킷 공격을 실시간으로 필터링합니다.

<br/>

## 💡 핵심 아키텍처 (Architecture)
본 시스템은 Netty의 멀티스레드 이벤트 루프가 패킷을 수신하며, 메모리상에 독립된 2개의 CMS(Count-Min Sketch) 매트릭스를 두고 1분 주기로 역할을 교대(Swap)합니다.

```text
[ 짝수 분 (Even Min) ]                 [ 홀수 분 (Odd Min) ]
┌───────────────────┐                 ┌───────────────────┐
│   Matrix A (활성)  │ ── 1분 경과 ──> │   Matrix A (대기)  │ ──> 제로 가비지 초기화
│  → 멀티스레드 카운트│                 │   (memset / 0 리셋)│
└───────────────────┘                 └───────────────────┘
▲                                     ▲
┌───────────────────┐                 ┌───────────────────┐
│   Matrix B (대기)  │ ── 1분 경과 ──> │   Matrix B (활성)  │
│   (초기화 완료 상태)│                 │  → 멀티스레드 카운트│
└───────────────────┘                 └───────────────────┘
```

1. Netty 기반 제로-카피 & 풀링: 패킷 수신 시 JVM의 GC(가비지 컬렉션) 부하를 없애기 위해 PooledByteBufAllocator를 사용, 메모리를 새로 할당하지 않고 재사용합니다.

2. O(1) 초고속 만료 (Time-slot Swapping): 개별 IP마다 타이머를 돌리지 않고, 1분 주기의 백그라운드 타이머가 AtomicReference를 이용해 활성 매트릭스 포인터를 순간적으로 스왑합니다.

3. 포화 잠금 (Saturation Lock): 각 카운터는 byte(1바이트) 단위를 사용하여 설정된 임계치(MAX_LIMIT)에 도달하면 더 이상 증가하지 않고 락(Lock)이 걸리며, 이후 해당 IP의 패킷은 즉시 차단(DROP)됩니다.

<br/>

## ✨ 주요 특징 (Key Features)
- 초경량 메모리 구동: 전 세계 모든 IPv4 대역을 커버하면서도 단 32MB~64MB 내외의 RAM만 점유합니다.
- 멀티코어 최적화: Netty의 비동기 NIO 스레드 모델을 활용하여 멀티코어 CPU 자원을 극한으로 활용합니다.
- 스레드 안전(Thread-Safe): 다중 스레드가 동시에 카운터를 올리는 환경에서도 원자성(Atomicity)을 보장하도록 설계되었습니다.

<br/>

## 🚀 시작하기 (Quick Start)
### 요구 사항
- Java 21.0.1 (jdk 21 이상)
- Gradle 8.12 (gradle 8.5 이상)

### 빌드 및 실행 (Gradle 기준)
- 빌드 진행
```bash
./gradlew shadowJar
```

- 애플리케이션 실행 (기본 8080 포트 패킷 리스너 가동)
```bash
java -jar build/libs/sketch-gate.jar
```

<br/>

## 📝 프로젝트 구현 로드맵 (TO-DO)

### Phase 1: 개발 환경 세팅 & 기본 골격
- [ ] Gradle 기반 Java 21 프로젝트 프로젝트 초기화

- [ ] 고성능 네트워크 성능 확보를 위한 io.netty:netty-all 의존성 추가

- [ ] sketch_gate.properties 환경설정 파일 구조 설계 (포트, 임계치, CMS 크기 정의)

### Phase 2: 핵심 알고리즘 및 자료구조 구현
- [ ] CountMinSketchMatrix 클래스 구현 (행렬 구조 및 MurmurHash3 기반 멀티 해싱 로직)

- [ ] 멀티스레드 동시 접근을 위한 카운터 원자성(Atomic) 보장 및 오버플로우 방지 로직 검증

- [ ] 짝/홀수 초 전환을 제어할 AtomicReference 기반 매트릭스 스왑 매니저 구현

### Phase 3: Netty 파이프라인 및 메모리 최적화
- [ ] PooledByteBufAllocator를 적용한 패킷 수신 핸들러 작성 (제로 가비지 달성)

- [ ] 입수된 패킷의 바이트 데이터에서 고속으로 IPv4 추출하는 파싱 레이어 구현

- [ ] 파싱된 IP를 CMS 매트릭스에 대조하여 PASS / DROP을 결정하는 인터셉터 핸들러 구현

### Phase 4: 테스트 및 성능 고도화
- [ ] 가상으로 초당 10만 건 이상의 IP를 인젝션하는 DDoS 공격 시뮬레이션 테스트 코드 작성

- [ ] 타임슬롯이 교대되는 경계 시점(Race Condition)에서 패킷 누수나 오탐이 없는지 동시성 테스트

- [ ] 대용량 트래픽 상황에서 GC(Garbage Collection) 로그를 모니터링하여 Stop-The-World 현상 제로화 검증