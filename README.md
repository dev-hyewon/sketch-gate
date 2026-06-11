# Twin-Sketch Gate 🛡️

**Twin-Sketch Gate**는 Java와 고성능 네트워크 엔진인 Netty를 기반으로 구현된 초경량·초고속 DDoS 방어 및 멀티 레이어 레이트 리미터(Multi-tier Rate Limiter)입니다.

메모리를 기가바이트(GB) 단위로 낭비하는 기존의 고정 배열 방식 대신, Count-Min Sketch 알고리즘과 짝/홀수 분 더블 버퍼링(포인터 스왑) 기법을 결합하여 단 수십 MB의 메모리만으로 대규모 패킷 공격을 실시간으로 필터링합니다.

<br/>

## 💡 핵심 아키텍처 (Architecture)
본 시스템은 Netty의 멀티스레드 이벤트 루프가 패킷을 수신하며, 메모리상에 독립된 2개의 CMS(Count-Min Sketch) 매트릭스를 두고 1분 주기로 역할을 교대(Swap)합니다. 또한 단기 분당 폭격(RPM)과 장기 자원 잠식(RPD)을 동시에 방어하는 다중 계층 유량 제어 아키텍처를 가집니다.

```text
[ 유입 요청 (Client Request) ]
              │
              ▼
    ┌───────────────────┐
    │  0차: 블랙리스트   │ ──(상주 IP)──> [ 403 Forbidden ] (즉시 차단)
    └───────────────────┘
              │ (미등록 IP)
              ▼
    ┌───────────────────┐
    │   1차: RPD 필터   │ ──(일일 한도 초과)──> [ 403 Quota Exceeded ]
    └───────────────────┘
              │ (한도 내 인입)
              ▼
    ┌───────────────────┐
    │   2차: RPM 필터   │ ──(분당 임계치 초과)──> [ 블랙리스트 등록 ] ──> [ 403 DDoS Detected ]
    └───────────────────┘
              │
              ▼
    [ 200 OK / 정상 통과 ]
```

#### 1. 분당 제어 (RPM) & 포인터 스왑 (Time-slot Swapping)
개별 IP마다 타이머를 돌리지 않고, 1분 주기의 백그라운드 타이머가 AtomicReference를 이용해 활성 매트릭스 포인터를 순간적으로 스왑합니다. 대기 상태가 된 매트릭스는 대형 가비지 컬렉션(GC) 부하 없이 메모리 내부에서 제로 가비지 형태로 리셋(memset)됩니다.

#### 2. 일일 제어 (RPD) & 자정 자동 청소
분당 임계치를 우회하여 장기적으로 자원을 잠식하는 봇 및 크롤러를 제어하기 위해 일일 누적 카운트 매트릭스를 운용합니다. 메모리 오버헤드를 최소화하기 위해 active 상태의 IP만 ConcurrentHashMap으로 관리하며, 매일 자정(00:00:00) 백그라운드 스케줄러 스레드가 시스템 중단 없이 이 매트릭스를 자동으로 세척(clear)합니다.

#### 3. 병목 제로 비동기 파일 영속성 (Zero-Blocking Persistence)
서버 재부팅 시 기존 차단 명단이 휘발되는 취약점을 해결하기 위해 data/blacklist.txt 기반의 파일 영속성을 지원합니다. 디도스 폭격 시 디스크 I/O가 Netty 워커 스레드를 붙잡지 않도록, LinkedBlockingQueue 기반의 비동기 대기열과 독립된 단일 백그라운드 스레드 엔진(AsyncFileWriter)을 구축하여 넌블로킹(Non-blocking) 파일 쓰기를 실현했습니다.

#### 4. 엄격한 참조 카운팅 (Zero-Leak Memory Management)
Netty의 오프힙(Off-heap) 메모리 풀링 아키텍처(PooledByteBufAllocator)의 효율을 극대화하기 위해, 차단 분기점 진입 시 인입된 요청 객체를 명시적으로 해제(ReferenceCountUtil.release(msg))합니다. 이를 통해 가혹한 트래픽 폭격 하에서도 1MB의 메모리 누수도 허용하지 않는 완벽한 풋프린트를 유지합니다.

<br/>

## ✨ 주요 특징 (Key Features)
- **초경량 메모리 구동**: 전 세계 모든 IPv4 대역을 커버하면서도 단 32MB~64MB 내외의 RAM만 점유합니다.
- **클라이언트별 하이브리드 뷰 분기**: 동일한 403 차단이더라도 요청 툴체인(Curl/Wget 등 터미널 vs 일반 브라우저)을 판별하여 텍스트 스트림 혹은 정적 HTML 템플릿(templates/)을 다이내믹하게 스왑하여 제공합니다.
- **내탄성(Fault Tolerance) 설계**: 최초 배포 및 런타임 환경에서 저장소 폴더가 누락되어도 시스템이 스스로 Files.createDirectories()를 통해 환경을 재건하고 안전하게 기동합니다.

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

### Phase 5: 다중 계층 유량 제한 및 데이터 영속성 레이어 확보
- [x] 단기 폭격(RPM) 외에 장기 누적 요청을 제어하기 위한 일일 제한(RPD) 시스템 매트릭스 구축
- [x] 차단 사유(RPM, RPD)에 따른 터미널 응답 메시지 세분화 및 전용 quota_exceeded.html 브라우저 뷰 연동 완료
- [x] 매일 자정 RPD 매트릭스를 안전하게 초기화하는 정기 백그라운드 스케줄러 시동 완료
- [x] 서버 재부팅 시 차단 명단 휘발을 방지하기 위한 data/ 폴더 기반 파일 영속성(blacklist.txt) 도입 완료
- [x] 극단적 폭격 상황에서의 병목 방지를 위해 LinkedBlockingQueue 기반 비동기 넌블로킹 파일 쓰기 엔진 안착 완료
- [x] 최전방 차단 분기점 이탈 시 발생하던 Netty ByteBuf 소유권 누락 문제를 ReferenceCountUtil.release(msg) 도입으로 완벽히 박멸 (메모리 누수 0%)
- [ ] 화이트리스트(Whitelist) 선제 레이어 및 패스트 트랙(Fast-Track) 구축