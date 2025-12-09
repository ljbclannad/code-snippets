public class SlidingWindowCounter {
    // 窗口最大请求数
    private final int maxRequests;
    // 窗口大小(秒)
    private final double windowSizeSeconds;
    // 将窗口分成多少个小格子
    private final int numBuckets;
    // 每个小窗口的请求数
    private final int[] buckets;
    // 每个小窗口的时间长度(毫秒)
    private final double bucketDurationMillis;
    // 上次检查时间(毫秒)
    private long lastCheckedMillis;

    public SlidingWindowCounter(int maxRequests, double windowSizeSeconds, int numBuckets) {
        this.maxRequests = maxRequests;
        this.windowSizeSeconds = windowSizeSeconds;
        this.numBuckets = numBuckets;
        this.buckets = new int[numBuckets];
        this.bucketDurationMillis = (this.windowSizeSeconds * 1000.0) / numBuckets;
        this.lastCheckedMillis = System.currentTimeMillis();
    }

    public SlidingWindowCounter(int maxRequests, double windowSizeSeconds) {
        this(maxRequests, windowSizeSeconds, 10);
    }

    public synchronized boolean allowRequest() {
        long currentTimeMillis = System.currentTimeMillis();
        long elapsedMillis = currentTimeMillis - lastCheckedMillis;

        // 计算需要滑动多少个小窗口
        int numSlides = (int) (elapsedMillis / bucketDurationMillis);

        if (numSlides > 0) {
            // 为了避免超出数组长度，这里最多滑动 numBuckets 个
            int slides = Math.min(numSlides, numBuckets);

            // 滑动窗口: 左移 slides 个 bucket，右侧补 0
            // 等价于 Python: self.buckets = self.buckets[num_slides:] + [0] * num_slides
            // 但我们保持数组长度不变
            System.arraycopy(buckets, slides, buckets, 0, numBuckets - slides);
            for (int i = numBuckets - slides; i < numBuckets; i++) {
                buckets[i] = 0;
            }

            lastCheckedMillis = currentTimeMillis;
        }

        // 计算总请求数
        int totalRequests = 0;
        for (int count : buckets) {
            totalRequests += count;
        }

        if (totalRequests < maxRequests) {
            // 在当前 bucket 添加请求(最新 bucket 在数组末尾)
            buckets[numBuckets - 1]++;
            return true; // 允许请求
        } else {
            return false; // 被限流
        }
    }

    // 测试
    public static void main(String[] args) throws InterruptedException {
        // 1秒最多5个请求
        SlidingWindowCounter limiter = new SlidingWindowCounter(5, 1.0);

        for (int i = 0; i < 10; i++) {
            if (limiter.allowRequest()) {
                System.out.println("请求 " + (i + 1) + " 通过 ✅");
            } else {
                System.out.println("请求 " + (i + 1) + " 被限流 ❌");
            }
            // 每0.2秒发送一个请求
            Thread.sleep(200);
        }
    }
}