package sketch.gate.service;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.Duration;

import sketch.gate.core.TwinSketchManager;
import sketch.gate.util.ConfigManager;

public class FilterService {

    public enum FilterResult {
        ALLOWED, // 정상 통과 (200 OK)
        DENIED_MINUTELY, // RPM(분당) 임계치 초과 차단 (403)
        DENIED_DAILY // RPD(일일) 임계치 초과 차단 (403)
    }

    private final TwinSketchManager sketchManager;
    private final int maxLimit; // RPM (분당 제한)
    private final int dailyLimit; // RPD (일일 제한)

    // 1차 필터: 일일 요청 카운트를 누적 관리할 고속 락 프리 맵 (대안 B)
    // Key: IP 주소, Value: 오늘 하루 누적 요청 횟수
    private final ConcurrentHashMap<String, Integer> dailyCounts = new ConcurrentHashMap<>();

    // 2차 필터: RPM 임계치를 넘어 완전히 영구 블랙리스트로 격리된 공격 IP 관리 맵 (기존 유지)
    private final ConcurrentHashMap<String, Long> blacklist = new ConcurrentHashMap<>();

    // [병목 방지 핵심] 비동기 파일 쓰기 작업을 적재할 고속 메모리 큐 (스레드 안전)
    private final LinkedBlockingQueue<String> fileWriteQueue = new LinkedBlockingQueue<>();

    // 자정 정기 청소를 위한 단일 스레드 스케줄러
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 파일 쓰기를 전담하여 Netty 스레드를 보호할 독립 단일 스레드 풀
    private final ExecutorService fileWriterExecutor = Executors.newSingleThreadExecutor();

    // 블랙리스트를 저장할 텍스트 파일 경로 선언
    private final Path blacklistFilePath = Paths.get("data", "blacklist.txt");

    public FilterService(TwinSketchManager sketchManager) {
        this.sketchManager = sketchManager;

        // 설정 파일로부터 분당/일일 임계치 로드
        this.maxLimit = ConfigManager.getInstance().getInt("packet.max.limit", 100);
        this.dailyLimit = ConfigManager.getInstance().getInt("packet.daily.limit", 5000);

        System.out.println("[INFO] FilterService initialized.");
        System.out.println("[INFO] Max Limit per Minute (RPM): " + maxLimit);
        System.out.println("[INFO] Max Limit per Day (RPD): " + dailyLimit);

        // 매일 자정(00:00:00)에 dailyCounts 맵을 완전히 비워주는 스케줄러 시동
        startDailyResetScheduler();

        // 1. 서버 시작 시 기존에 저장된 블랙리스트 파일이 있다면 복구 로드
        loadBlacklistFromFile();

        // 2. 비동기 파일 쓰기 백그라운드 루프 엔진 기동
        startAsyncFileWriter();
    }

    /**
     * 유입된 패킷의 IP를 다중 계층으로 검사하여 통과 또는 사유별 차단을 결정합니다.
     * 
     * @param ip {String} - IP 주소
     * @return {FilterResult} - {DENIED_DAILY, DENIED_MINUTELY, ALLOWED}
     */
    public FilterResult checkResult(String ip) {
        // 기존 분당 난사로 완전히 찍혀 블랙리스트에 등록된 녀석은 바로 칼차단 (RPM)
        if (blacklist.containsKey(ip)) {
            return FilterResult.DENIED_MINUTELY;
        }

        // RPD 일일 요청 제한 선제 체크
        int currentDailyCount = dailyCounts.getOrDefault(ip, 0);
        if (currentDailyCount >= dailyLimit) {
            return FilterResult.DENIED_DAILY;
        }

        // 기존 RPM 분당 난사 폭격 체크
        sketchManager.recordPacket(ip);
        int currentMinutelyCount = sketchManager.getEstimate(ip);

        if (currentMinutelyCount > maxLimit) {
            // 원자적으로 블랙리스트에 등록 (차단 시점 기록)
            if (blacklist.putIfAbsent(ip, System.currentTimeMillis()) == null) {
                // 공격자가 블랙리스트에 등록되는 최초 1번만 경고 로그가 출력됨
                System.out.println("--------------------------------------------------");
                System.out.println("[WARN] ALERT! 분당 임계치 초과 공격 IP 즉시 차단: " + ip);
                System.out.println("[WARN] 현재 분당 인입량 추정치: " + currentMinutelyCount + " (제한: " + maxLimit + ")");
                System.out.println("--------------------------------------------------");

                // [병목 방지] 파일에 직접 쓰지 않고 비동기 대기열 큐에 IP를 던집니다 (무정체 넌블로킹)
                fileWriteQueue.offer(ip);
            }
            return FilterResult.DENIED_MINUTELY;
        }

        // 모든 필터를 안전하게 통과한 경우 일일 요청 수 1 증가 (원자적 연산 보장)
        dailyCounts.merge(ip, 1, (a, b) -> a + b);
        if (dailyCounts.get(ip) >= dailyLimit) {
            System.out.println("--------------------------------------------------");
            System.out.println("[WARN] ALERT! 일일 할당량 초과: " + ip);
            System.out.println("--------------------------------------------------");
        }
        return FilterResult.ALLOWED;
    }

    /**
     * 백그라운드에서 큐를 상시 감시하며 파일에 이어쓰기(Append)를 수행하는 독립 엔진
     */
    private void startAsyncFileWriter() {
        fileWriterExecutor.submit(() -> {
            Thread.currentThread().setName("SketchGate-AsyncFileWriter");
            try (BufferedWriter writer = Files.newBufferedWriter(blacklistFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                // shutdown()이 호출되었더라도 큐에 잔여 작업이 남아있다면 루프를 유지함
                while (!Thread.currentThread().isInterrupted()) {

                    // poll()을 사용해 1초 동안 큐를 대기합니다.
                    // 데이터가 오면 즉시 반환하고, 1초 동안 안 오면 null을 반환합니다.
                    String ipToWrite = fileWriteQueue.poll(1, TimeUnit.SECONDS);

                    if (ipToWrite != null) {
                        writer.write(ipToWrite);
                        writer.newLine();
                        writer.flush(); // 디스크 즉시 반영
                    } else {
                        // 1초 동안 데이터가 안 왔는데, 만약 외부에서 shutdown()이 호출된 상태라면?
                        // 대기열도 비었고 종료 명령도 받았으니, 미련 없이 루프를 탈출(break)합니다.
                        if (fileWriterExecutor.isShutdown() && fileWriteQueue.isEmpty()) {
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                // poll() 대기 중에 shutdownNow() 등으로 인터럽트가 걸리면 이쪽으로 들어옵니다.
                System.out.println("[INFO] Async FileWriter 스레드가 인터럽트 신호를 받아 안전하게 종료되었습니다.");
            } catch (IOException e) {
                System.err.println("[ERROR] 블랙리스트 파일 쓰기 중 오류 발생: " + e.getMessage());
            } finally {
                System.out.println("[INFO] Async FileWriter 스레드가 파일 자원을 해제하고 최종 종료되었습니다.");
            }
        });
    }

    /**
     * 서버 부팅 시 파일 시스템에서 차단 IP 명단을 읽어 메모리 맵을 부활시킵니다.
     */
    private void loadBlacklistFromFile() {
        try {
            if (blacklistFilePath.getParent() != null) {
                Files.createDirectories(blacklistFilePath.getParent());
            }
        } catch (IOException e) {
            System.err.println("[ERROR] 데이터 저장소 폴더 생성 실패: " + e.getMessage());
        }

        System.out.println("[INFO] 발견된 블랙리스트 파일 로드 중... (" + blacklistFilePath.toAbsolutePath() + ")");
        int loadedCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(blacklistFilePath)) {
            String ip;
            long currentTimestamp = System.currentTimeMillis();
            while ((ip = reader.readLine()) != null) {
                ip = ip.trim();
                if (!ip.isEmpty()) {
                    // 기존 차단 시점 정보가 없으므로 로드된 현재 시점 타임스탬프로 메모리 복구
                    blacklist.put(ip, currentTimestamp);
                    loadedCount++;
                }
            }
            System.out.println("[INFO] 기존 블랙리스트 IP " + loadedCount + "개가 성공적으로 복구되어 방어벽이 재가동되었습니다.");
        } catch (IOException e) {
            System.err.println("[ERROR] 블랙리스트 파일 로딩 실패: " + e.getMessage());
        }
    }

    /**
     * 다음 날 자정(00:00:00)까지 남은 시간을 계산하여 매일 정기적으로 RPD 맵을 클리어합니다.
     */
    private void startDailyResetScheduler() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        long initialDelay = Duration.between(now, nextMidnight).toSeconds();

        // 첫 자정까지 대기 후, 이후부터는 24시간(1일) 간격으로 정기 실행
        scheduler.scheduleAtFixedRate(() -> {
            dailyCounts.clear();
            System.out.println("--------------------------------------------------");
            System.out.println("[INFO] SYSTEM NOTIFICATION: 자정 주기가 도래하여 일일 요청 카운트(RPD) 매트릭스가 성공적으로 초기화되었습니다.");
            System.out.println("--------------------------------------------------");
        }, initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    /**
     * [관리용] 시스템 종료 시 호출되며, 큐에 남아있는 모든 차단 IP를 파일에 안전하게 기록한 후 스레드를 종료합니다.
     */
    public void shutdown() {
        // 1. 일일 카운트 리셋용 정기 스케줄러 즉시 중지
        scheduler.shutdown();

        System.out.println("[INFO] SketchGate 비동기 파일 저장소의 Graceful Shutdown을 시작합니다...");

        // 2. 파일 쓰기 엔진에게 더 이상 새로운 작업(submit)을 받지 않음을 선언
        fileWriterExecutor.shutdown();

        try {
            // 3. 큐에 남아있는 잔여 데이터가 있는지 확인하고 최종 반영 대기
            int remainingTasks = fileWriteQueue.size();
            if (remainingTasks > 0) {
                System.out.println("[INFO] 종료 전 처리 중: 대기열에 남은 " + remainingTasks + "개의 IP를 파일에 안전하게 플러시합니다.");
            }

            // 4. 최대 5초 동안 큐의 작업이 모두 파일로 기록되어 스레드 풀이 자연 종료되기를 기다림
            if (!fileWriterExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.out.println("[WARN] 지정된 대기 시간(5초) 내에 파일 쓰기가 완료되지 않아 강제 종료를 수행합니다.");
                fileWriterExecutor.shutdownNow();
            } else {
                System.out.println("[SUCCESS] 모든 잔여 차단 명단이 파일(`data/blacklist.txt`)에 유실 없이 저장되었습니다.");
            }
        } catch (InterruptedException e) {
            System.err.println("[ERROR] Graceful Shutdown 중 인터럽트가 발생했습니다.");
            fileWriterExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * [관리용] 현재 차단된 총 IP 개수를 반환합니다.
     */
    public int getBlacklistCount() {
        return blacklist.size();
    }

    /**
     * 💡 관리자가 수동으로 차단 해제 시, 메모리에서 지우고 파일 전체를 새로고침(Rewrite) 큐에 위임
     */
    public void unblock(String ip) {
        if (blacklist.remove(ip) == null)
            return;

        dailyCounts.remove(ip);

        // 해제 시에는 한 줄만 지우기 까다로우므로 전체 맵 상태를 파일에 새로 덮어쓰도록 비동기 실행
        fileWriterExecutor.submit(() -> {
            try (BufferedWriter writer = Files.newBufferedWriter(blacklistFilePath,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                for (String remainingIp : blacklist.keySet()) {
                    writer.write(remainingIp);
                    writer.newLine();
                }
                writer.flush();
                System.out.println("[INFO] 파일 저장소에서 IP 해제 반영 완료: " + ip);
            } catch (IOException e) {
                System.err.println("[ERROR] 블랙리스트 파일 언블록 반영 실패: " + e.getMessage());
            }
        });
    }
}