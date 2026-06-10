package sketch.gate;

import java.util.concurrent.ConcurrentHashMap;

public class FilterService {
    private final TwinSketchManager sketchManager;
    private final int maxLimit;

    // 차단된 IP들을 고속으로 관리하기 위한 락 프리(Lock-Free) 블랙리스트 맵
    // Key: IP 주소, Value: 차단된 시점의 타임스탬프
    private final ConcurrentHashMap<String, Long> blacklist = new ConcurrentHashMap<>();

    public FilterService(TwinSketchManager sketchManager) {
        this.sketchManager = sketchManager;

        // Phase 1에서 구축한 설정 파일로부터 최대 임계치(기본값 100)를 로드
        this.maxLimit = ConfigManager.getInstance().getInt("packet.max.limit", 100);
        System.out.println("[INFO] FilterService initialized. Max Limit/sec: " + maxLimit);
    }

    /**
     * 유입된 패킷의 IP를 검사하여 통과(true) 또는 차단(false)을 결정합니다.
     */
    public boolean isAllowed(String ip) {
        // 1. 이미 블랙리스트에 등록된 IP인지 고속 조회
        if (blacklist.containsKey(ip)) {
            return false;
        }

        // 2. 현재 1초 동안 이 IP가 보낸 패킷 수 1 증가 및 추정치 조회
        sketchManager.recordPacket(ip);
        int currentCount = sketchManager.getEstimate(ip);

        // 3. 설정된 임계치를 초과했는지 판정
        if (currentCount > maxLimit) {
            // 원자적으로 블랙리스트에 등록 (차단 시점 기록)
            blacklist.putIfAbsent(ip, System.currentTimeMillis());
            System.out.println(
                    "[WARN] ALERT! IP " + ip + " exceeded limit (" + currentCount + "/" + maxLimit + "). Blocked.");
            return false;
        }

        return true; // 임계치 미만이면 안전하게 통과
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
    }
}