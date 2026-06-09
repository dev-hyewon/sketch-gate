package sketch.gate;

public class App {
    public static void main(String[] args) {
        System.out.println("[INFO] Sketch-Gate System - Initializing...");

        ConfigManager config = ConfigManager.getInstance();
        int port = config.getInt("server.port", 8080);
        int maxLimit = config.getInt("packet.max.limit", 100);
        int cmsWidth = config.getInt("cms.width", 65536);

        System.out.println("------------------------------------------");
        System.out.println("[CONFIG] Monitor Server Port   : " + port);
        System.out.println("[CONFIG] CMS Matrix Width      : " + cmsWidth);
        System.out.println("[CONFIG] Packet Max Limit/sec  : " + maxLimit);
        System.out.println("------------------------------------------");
        System.out.println("[SUCCESS] Phase 1 Base Architecture Verified.");
    }
}