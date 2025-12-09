import java.util.logging.Logger;

/**
 * 分布式限流器-计数器算法
 */
public class RateLimiter {

    private static final Logger logger = Logger.getLogger(RateLimiter.class.getName());

    /**
     * 请求上限
     */
    private final int limit;

    /**
     * 时间窗口，单位为ms
     */
    private final long timeWindow;

    private int counter;

    private long startTimeMillis;

    public RateLimiter(int limit, long timeWindow) {
        this.limit = limit;
        this.timeWindow = timeWindow;
        this.counter = 0;
        this.startTimeMillis = System.currentTimeMillis();
        logger.info("startTimeMillis: " + this.startTimeMillis);
    }

    public boolean isAllowed() {
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - this.startTimeMillis > this.timeWindow) {
            counter = 0;
            startTimeMillis = currentTimeMillis;
        }

        if (counter < limit) {
            counter++;
            return true;
        }

        return false;
    }

    public static void main(String[] args) throws InterruptedException {
        RateLimiter rateLimiter = new RateLimiter(10, 1000);
        for (int i = 1; i <= 12; i++) {
            if (i == 11) {
                Thread.sleep(1000);
            }
            System.out.println("isAllowed: " + i + " " + rateLimiter.isAllowed());
        }
    }
}
