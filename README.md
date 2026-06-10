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

## 📖 사용 및 테스트 가이드
* 실전 HTTP DDoS 공격 시뮬레이션 및 차단 검증 방법은 [실전 DDoS 방어 테스트 가이드](docs/DDoS-Test-Guide.md) 문서를 참고하세요.

<br/>

## 📝 프로젝트 구현 로드맵 (TO-DO)

### Phase 1: 개발 환경 세팅 & 기본 골격
- [x] Gradle 기반 Java 프로젝트 초기화 및 환경 표준화 완료
- [x] 고성능 비동기 네트워크 처리를 위한 io.netty:netty-all 엔진 의존성 주입 완료
- [x] sketch_gate.properties 환경설정 인프라 설계 완료 (포트, 임계치, CMS 매트릭스 크기 전역 관리)

### Phase 2: 핵심 알고리즘 및 자료구조 구현
- [x] ConcurrentCountMinSketch 구현 (MurmurHash3 멀티 해싱 및 충돌 최소화 행렬 구조 설계)
- [x] 멀티스레드 대량 동시 접근 환경 유지를 위한 AtomicIntegerArray 기반의 락 프리(Lock-Free) 원자성 확보
- [x] Twin-Sketch 스왑 매니저(TwinSketchManager) 아키텍처 구현 및 백그라운드 타이머 결합 완료

### Phase 3: Netty 파이프라인 및 아키텍처 고도화
- [x] Netty 내장 HTTP 프로토콜 스택을 활용한 고성능 비동기 네트워크 부트스트랩 인프라 구축
- [x] 원격 클라이언트 커넥션에서 고속으로 IP를 추출하는 최전방 인바운드 핸들러(ThrottlingHandler) 구현
- [x] 추출된 IP를 실시간으로 판정하고 고속 블랙리스트 맵과 연동하여 PASS / DROP을 결정하는 인터셉터 레이어 완성
- [x] 관심사 분리(SoC)를 위해 하드코딩된 웹 응답을 외장화하고 templates/ 폴더 구조로 정적 자원 격리 완료

### Phase 4: 실전 테스트 및 가혹 조건 검증
- [x] 독립형 멀티스레드 스트레스 테스트 환경 구축을 통한 16,000개 패킷 무결성(누수 0%) 검증 완료
- [x] 가상 curl 루프 스크립트 폭격을 이용한 실전 HTTP DDoS 차단 및 최초 1회 스마트 경고 로그 메커니즘 검증 완료
- [x] [최종 고도화] 순간 변동성 오탐 방지 및 실무 표준 준수를 위해 '초당 체계'에서 '분당 주기를 가지는 슬라이딩 윈도우 체계'로 전면 최적화 완료
