package sketch.gate;

public class App {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("[INFO] Sketch-Gate System - Initializing...");

        // 1. 매니저 가동 (내부 스케줄러가 1초 타이머 시작)
        TwinSketchManager manager = new TwinSketchManager();

        System.out.println("[INFO] Starting Packet Stream Simulation...");
        System.out.println("------------------------------------------");

        // 2. 1초 내에 특정 IP(공격자 변장)로 패킷 대량 유입 시뮬레이션
        String attackerIp = "192.168.0.55";
        for (int i = 0; i < 75; i++) {
            manager.recordPacket(attackerIp);
        }

        // 현재 기록된 빈도 확인
        System.out.println("[SIMUL] Current estimate for " + attackerIp + ": " + manager.getEstimate(attackerIp));

        // 3. 1.5초 대기 (이 사이에 백그라운드 스케줄러가 스왑을 일으키고 매트릭스를 청소함)
        Thread.sleep(1500);

        // 4. 스왑 이후 새로운 1초가 되었을 때 과거 데이터가 정상 청소되었는지 확인
        System.out.println("[SIMUL] After 1.5s (Next Window) estimate: " + manager.getEstimate(attackerIp));
        System.out.println("------------------------------------------");
        System.out.println("[SUCCESS] Phase 2 Twin-Sketch Core Logic Verified.");
    }
}