import java.util.ArrayDeque;
import java.util.Deque;

public class SlidingTimeWindowTimeStamp {

    // 窗口最大请求数
    private final int maxRequests;
    // 窗口大小(ms)
    private final long windowSizeSeconds;
    // 存储请求时间戳(毫秒)
    private final Deque<Long> requestTimestamps = new ArrayDeque<>();

    public SlidingTimeWindowTimeStamp(int maxRequests, long windowSizeSeconds) {
        this.maxRequests = maxRequests;
        this.windowSizeSeconds = windowSizeSeconds;
    }

    public boolean isAllowed() {
        long currentTimeMillis = System.currentTimeMillis();

        // 移除过期的请求
        while (!requestTimestamps.isEmpty()
                && currentTimeMillis - requestTimestamps.peek() > windowSizeSeconds) {
            requestTimestamps.poll();
        }

        // 如果请求数小于最大请求数，则允许请求
        if (requestTimestamps.size() < maxRequests) {
            System.out
                    .println("requestTimestamps.size() < maxRequests: " + requestTimestamps.size() + " " + maxRequests);
            requestTimestamps.offer(currentTimeMillis);
            return true;
        }
        return false;

    }

    public static void main(String[] args) throws InterruptedException {
        SlidingTimeWindowTimeStamp slidingTimeWindowTimeStamp = new SlidingTimeWindowTimeStamp(
                10, 1000);

        for (int i = 1; i <= 12; i++) {
            if (i == 12) {
                // 模拟清空窗口
                Thread.sleep(1000);
            }
            System.out.println("isAllowed: " + i + " " + slidingTimeWindowTimeStamp.isAllowed());
        }
    }
}
