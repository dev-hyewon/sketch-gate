package sketch.gate;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class ConcurrentCountMinSketch {
    private final int width;
    private final int depth;

    // 💡 동시성 멀티스레드 환경에서 락(Lock) 없이 원자적 연산을 보장하기 위한 배열 구조
    private final AtomicIntegerArray[] table;

    public ConcurrentCountMinSketch(int width, int depth) {
        this.width = width;
        this.depth = depth;
        this.table = new AtomicIntegerArray[depth];
        for (int i = 0; i < depth; i++) {
            this.table[i] = new AtomicIntegerArray(width);
        }
    }

    /**
     * IP 주소를 받아 4개의 독립된 해시 버킷에 카운트를 원자적으로 1씩 증가시킵니다.
     */
    public void add(String ip) {
        for (int i = 0; i < depth; i++) {
            int bucket = getBucket(ip, i);

            // 💡 incrementAndGet()을 사용하여 CPU 레벨에서 동기화(CAS 연산) 처리
            // 여러 스레드가 동시에 접근해도 절대 카운트 누락이 발생하지 않습니다.
            table[i].incrementAndGet(bucket);
        }
    }

    /**
     * IP 주소의 현재 추정 빈도수(최소값)를 원자적으로 조회합니다.
     */
    public int estimate(String ip) {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < depth; i++) {
            int bucket = getBucket(ip, i);

            // 💡 값을 읽어올 때도 volatile 성격의 고속 atomic 조회를 수행합니다.
            int count = table[i].get(bucket);
            min = Math.min(min, count);
        }
        return min;
    }

    /**
     * 새로운 1초를 맞이하기 위해 매트릭스 내부를 안전하게 0으로 초기화합니다.
     */
    public void clear() {
        for (int i = 0; i < depth; i++) {
            for (int j = 0; j < width; j++) {
                table[i].set(j, 0);
            }
        }
    }

    /**
     * MurmurHash3와 시드값(seed)을 활용해 비트 연산 최적화된 버킷 인덱스를 계산합니다.
     */
    private int getBucket(String ip, int hashIndex) {
        int hash = Hashing.murmur3_32_fixed(hashIndex)
                .hashString(ip, StandardCharsets.UTF_8)
                .asInt();

        return (hash & Integer.MAX_VALUE) & (width - 1);
    }
}