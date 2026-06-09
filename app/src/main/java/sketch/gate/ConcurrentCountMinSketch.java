package sketch.gate;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;

public class ConcurrentCountMinSketch {
    private final int width;
    private final int depth;
    private final int[][] table;

    public ConcurrentCountMinSketch(int width, int depth) {
        this.width = width;
        this.depth = depth;
        this.table = new int[depth][width];
    }

    /**
     * IP 주소를 받아 4개의 독립된 해시 버킷에 카운트를 1씩 증가시킵니다.
     */
    public void add(String ip) {
        for (int i = 0; i < depth; i++) {
            int bucket = getBucket(ip, i);
            // 동시성 환경에서 안전하게 값을 올리기 위해 원자적 연산 필요 (Phase 3 고도화 예정)
            table[i][bucket]++;
        }
    }

    /**
     * IP 주소의 현재 추정 빈도수(최소값)를 반환합니다.
     */
    public int estimate(String ip) {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < depth; i++) {
            int bucket = getBucket(ip, i);
            min = Math.min(min, table[i][bucket]);
        }
        return min;
    }

    /**
     * 새로운 1초를 맞이하기 위해 매트릭스 배열을 0으로 초기화합니다. (메모리 재사용)
     */
    public void clear() {
        for (int i = 0; i < depth; i++) {
            java.util.Arrays.fill(table[i], 0);
        }
    }

    /**
     * MurmurHash3와 시드값(seed)을 활용해 비트 연산 최적화된 버킷 인덱스를 계산합니다.
     */
    private int getBucket(String ip, int hashIndex) {
        // 각 depth마다 서로 다른 시드값을 주어 독립적인 해시 함수 효과를 냅니다.
        int hash = Hashing.murmur3_32_fixed(hashIndex)
                .hashString(ip, StandardCharsets.UTF_8)
                .asInt();

        // 음수 해시 방지 및 2^n 승 비트 연산 최적화 적용 (hash % width 대신 hash & (width - 1))
        return (hash & Integer.MAX_VALUE) & (width - 1);
    }
}