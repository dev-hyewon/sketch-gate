package sketch.gate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

public class ResponseTemplateLoader {

    // 클래스패스(resources) 내의 HTML 파일을 읽어 문자열로 반환하는 메서드
    public static String loadHtml(String fileName) {
        try (InputStream is = ResponseTemplateLoader.class.getClassLoader().getResourceAsStream(fileName)) {
            if (is == null) {
                return "<html><body><h1>Error: Template not found</h1></body></html>";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to load HTML template: " + fileName + " -> " + e.getMessage());
            return "<html><body><h1>Internal Server Error</h1></body></html>";
        }
    }
}