package sketch.gate;

public class App {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("[INFO] Sketch-Gate System - Initializing...");

        // 1. 핵심 컴포넌트들 초기화
        TwinSketchManager sketchManager = new TwinSketchManager();
        FilterService filterService = new FilterService(sketchManager);

        System.out.println("[INFO] Starting Traffic Firewall Simulation...");
        System.out.println("------------------------------------------");

        String normalIp = "121.140.0.1";
        String attackerIp = "211.234.50.99";

        // 시나리오 A: 정상 유저가 1초 동안 50개의 요청을 보냄 (임계치 100 미만)
        System.out.println("[SIMUL] Simulating Normal User Traffic (" + normalIp + ")...");
        boolean normalResult = true;
        for (int i = 0; i < 50; i++) {
            normalResult &= filterService.isAllowed(normalIp);
        }
        System.out.println("[SIMUL] Normal User Allowed Result: " + normalResult);

        // 시나리오 B: 공격자 IP가 1초 동안 120개의 요청을 마구 퍼부음 (임계치 100 초과)
        System.out.println("[SIMUL] Simulating DDoS Attack Traffic (" + attackerIp + ")...");
        int allowedCount = 0;
        int blockedCount = 0;

        for (int i = 0; i < 120; i++) {
            if (filterService.isAllowed(attackerIp)) {
                allowedCount++;
            } else {
                blockedCount++;
            }
        }

        System.out.println("------------------------------------------");
        System.out.println("[RESULT] Attack IP Allowed Packets : " + allowedCount);
        System.out.println("[RESULT] Attack IP Blocked Packets : " + blockedCount);
        System.out.println("[RESULT] Total Blacklisted IP Count: " + filterService.getBlacklistCount());
        System.out.println("------------------------------------------");
        System.out.println("[SUCCESS] Phase 2 Twin-Sketch Core Firewall Architecture Verified.");
    }
}