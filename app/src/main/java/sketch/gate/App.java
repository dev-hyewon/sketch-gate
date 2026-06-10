package sketch.gate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("[INFO] Sketch-Gate System - Launching Thread Safety Test...");

        ConfigManager config = ConfigManager.getInstance();
        int width = config.getInt("cms.width", 65536);
        int depth = config.getInt("cms.depth", 4);

        ConcurrentCountMinSketch testSketch = new ConcurrentCountMinSketch(width, depth);

        int threadCount = 32;
        int packetsPerThread = 500;
        int totalExpectedPackets = threadCount * packetsPerThread; // 총 16,000개

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        String attackerIp = "185.220.101.5";

        System.out.println("[SIMUL] Starting Multi-Threaded Pure Stress Test...");
        System.out.println("[SIMUL] Total threads: " + threadCount + " | Packets per thread: " + packetsPerThread);
        System.out.println("--------------------------------------------------");

        long startTime = System.currentTimeMillis();

        // 32개 스레드가 원자적 매트릭스 한 곳에 미친 듯이 동시 난사 유도
        for (int i = 0; i < threadCount; i++) {
            // 💡 submit 대신 명확하게 execute(Runnable)를 사용하여 타입 모호성 해결
            executor.execute(() -> {
                try {
                    for (int p = 0; p < packetsPerThread; p++) {
                        testSketch.add(attackerIp);
                    }
                } finally { // 💡 오타였던 package-private 구문 제거
                    latch.countDown();
                }
            });
        }

        latch.await();
        long duration = System.currentTimeMillis() - startTime;

        int finalEstimate = testSketch.estimate(attackerIp);

        System.out.println("--------------------------------------------------");
        System.out.println("[RESULT] Stress Test Duration      : " + duration + " ms");
        System.out.println("[RESULT] Total Simulated Packets   : " + totalExpectedPackets);
        System.out.println("[RESULT] Core Matrix Count Estimate: " + finalEstimate);
        System.out.println("--------------------------------------------------");

        if (finalEstimate == totalExpectedPackets) {
            System.out.println("[SUCCESS] Thread Safety Verification Passed. No count loss detected!");
        } else {
            System.out.println("[FAIL] Race Condition Detected. Count loss: " + (totalExpectedPackets - finalEstimate));
        }

        executor.shutdown();
    }
}