import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TokenBucket {
    private final int capacity; // 令牌桶容量
    private final int refillRate; // 令牌生成速率（每秒生成多少个）
    private int tokens; // 当前令牌数
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public TokenBucket(int capacity, int refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity; // 初始时令牌桶满

        // 定时补充令牌
        scheduler.scheduleAtFixedRate(this::addTokens, 0, 1, TimeUnit.SECONDS);
    }

    private synchronized void addTokens() {
        tokens = Math.min(capacity, tokens + refillRate); // 不能超过桶的最大容量
    }

    public synchronized boolean allowRequest() {
        if (tokens > 0) {
            tokens--; // 取一个令牌
            return true; // 允许请求
        }
        return false; // 拒绝请求（限流）
    }

    public void stop() {
        scheduler.shutdown();
    }

    public static void main(String[] args) throws InterruptedException {
        TokenBucket bucket = new TokenBucket(5, 2); // 令牌桶容量 5，每秒生成 2 个令牌

        for (int i = 0; i < 10; i++) {
            if (bucket.allowRequest()) {
                System.out.println("请求 " + (i + 1) + " 通过 ✅");
            } else {
                System.out.println("请求 " + (i + 1) + " 被限流 ❌");
            }
            Thread.sleep(300); // 每 300ms 发送一个请求
        }

        bucket.stop();
    }
}