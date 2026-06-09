package sketch.gate;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TwinSketchManager {
    private final ConcurrentCountMinSketch sketchA;
    private final ConcurrentCountMinSketch sketchB;

    // 멀티스레드 환경에서 락(Lock) 없이 안전하게 포인터를 스왑하기 위한 AtomicReference
    private final AtomicReference<ConcurrentCountMinSketch> activeSketch;
    private final ScheduledExecutorService scheduler;

    public TwinSketchManager() {
        ConfigManager config = ConfigManager.getInstance();
        int width = config.getInt("cms.width", 65536);
        int depth = config.getInt("cms.depth", 4);

        // 두 개의 스케치 매트릭스 선할당 (Garbage Collection 부하 방지)
        this.sketchA = new ConcurrentCountMinSketch(width, depth);
        this.sketchB = new ConcurrentCountMinSketch(width, depth);

        // 시작 시점에는 A를 활성 상태로 지정
        this.activeSketch = new AtomicReference<>(sketchA);

        // 1초 주기로 스왑을 실행할 백그라운드 스케줄러 가동
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sketch-swap-scheduler");
            thread.setDaemon(true); // 애플리케이션 종료 시 함께 종료되도록 데몬 설정
            return thread;
        });

        startSlider();
    }

    /**
     * 1초마다 정확하게 스케치 포인터를 스왑하고, 과거 스케치를 비동기로 청소합니다.
     */
    private void startSlider() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                ConcurrentCountMinSketch current = activeSketch.get();
                // 현재 A면 B로, B면 A로 포인터 전환
                ConcurrentCountMinSketch next = (current == sketchA) ? sketchB : sketchA;

                // 원자적(Atomic) 포인터 변경 - 1나노초 이내에 수행되어 패킷 유실을 최소화함
                activeSketch.set(next);

                // [중요] 역할이 바뀐 직전 과거의 스케치를 깨끗하게 청소하여 다음 1초를 준비함 (메모리 재사용)
                current.clear();
                System.out.println("[INFO] Twin-Sketch Swap completed. Past matrix cleared.");
            } catch (Exception e) {
                System.err.println("[ERROR] Error occurred during sketch swapping: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS); // 1초마다 실행
    }

    /**
     * 실시간으로 유입되는 패킷 IP를 현재 활성화된 스케치에 기록합니다.
     */
    public void recordPacket(String ip) {
        activeSketch.get().add(ip);
    }

    /**
     * 특정 IP의 현재 초당 요청 횟수를 조회합니다.
     */
    public int getEstimate(String ip) {
        return activeSketch.get().estimate(ip);
    }
}