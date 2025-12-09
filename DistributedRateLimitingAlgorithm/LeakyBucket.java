import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class LeakyBucket {

    private final int capacity;
    private final double leakRate;
    private final Deque<String> bucket = new ArrayDeque<>();
    private long lastLeakTimeMillis;
    private final Lock lock = new ReentrantLock();

    public LeakyBucket(int capacity, double leakRate) {
        this.capacity = capacity;
        this.leakRate = leakRate;
        this.lastLeakTimeMillis = System.currentTimeMillis();
    }

    /**
     * 以固定速率处理请求
     */
    private void leak() {
        lock.lock();
        try {
            long currentTimeMillis = System.currentTimeMillis();
            long elapsedMillis = currentTimeMillis - lastLeakTimeMillis;
            if (elapsedMillis <= 0) {
                return;
            }
            double elapsedSeconds = elapsedMillis / 1000.0;
            int leaks = (int) (elapsedSeconds * leakRate); // 计算应处理的请求数量

            for (int i = 0; i < leaks; i++) {
                if (!bucket.isEmpty()) {
                    bucket.pollFirst(); // 处理一个请求
                } else {
                    break;
                }
            }

            if (leaks > 0) {
                lastLeakTimeMillis = currentTimeMillis;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 添加请求
     * 
     * @param request 请求内容
     * @return true=请求成功,false=被限流
     */
    public boolean addRequest(String request) {
        lock.lock();
        try {
            // 先处理已有请求
            leak();
            if (bucket.size() < capacity) {
                bucket.addLast(request);
                return true; // 请求成功
            } else {
                return false; // 请求被限流
            }
        } finally {
            lock.unlock();
        }
    }

    // 测试
    public static void main(String[] args) throws InterruptedException {
        // 5个请求容量，每秒处理2个请求
        LeakyBucket bucket = new LeakyBucket(5, 2.0);

        for (int i = 0; i < 10; i++) {
            String req = "请求 " + (i + 1);
            if (bucket.addRequest(req)) {
                System.out.println(req + " 通过 ✅");
            } else {
                System.out.println(req + " 被限流 ❌");
            }
            // 每0.3秒发送一个请求
            Thread.sleep(300);
        }
    }
}
