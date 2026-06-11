package sketch.gate.util;

import java.io.InputStream;
import java.util.Properties;

public class ConfigManager {
    private static final ConfigManager INSTANCE = new ConfigManager();
    private final Properties properties = new Properties();

    private ConfigManager() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("sketch_gate.properties")) {
            if (input == null) {
                System.err.println("[WARN] sketch_gate.properties 파일을 찾을 수 없습니다. 기본값을 사용합니다.");
                return;
            }
            properties.load(input);
            System.out.println("[INFO] 환경설정 로드 완료: sketch_gate.properties");
        } catch (Exception e) {
            System.err.println("[ERROR] 환경설정 로드 중 오류 발생: " + e.getMessage());
        }
    }

    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        return value != null ? Integer.parseInt(value.trim()) : defaultValue;
    }
}