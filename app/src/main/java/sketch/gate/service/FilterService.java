package sketch.gate.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.time.Duration;

import sketch.gate.core.TwinSketchManager;
import sketch.gate.util.ConfigManager;

public class FilterService {

    // 차단 사유를 명확히 구별하기 위한 내부 Enum 추가
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

    // 자정 정기 청소를 위한 단일 스레드 스케줄러
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

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
     * [관리용] 시스템 종료 시 안전하게 스케줄러 스레드를 풀 풀링 해제하기 위한 훅 (선택)
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    /**
     * [관리용] 현재 차단된 총 IP 개수를 반환합니다.
     */
    public int getBlacklistCount() {
        return blacklist.size();
    }

    /**
     * [관리용] 테스트 및 오탐 해제를 위해 특정 IP를 블랙리스트에서 해제합니다.
     */
    public void unblock(String ip) {
        blacklist.remove(ip);
        dailyCounts.remove(ip);
    }
}