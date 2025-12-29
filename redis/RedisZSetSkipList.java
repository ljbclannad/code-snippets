package redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Redis-like ZSET SkipList (Java)
 *
 * 贴近 Redis 的点：
 * - Node 结构：forward + span（用于按 rank 快速定位） + backward
 * - SkipList 结构：header/tail/level/length
 * - 插入：update[] + rank[] 的 span 切分逻辑
 * - 删除：逐层断链 + span 拼回 + level 收缩
 * - range：
 * - byScore: 先 firstInRange() 再沿 level0 顺扫
 * - byRank : getElementByRank() 用 span 跳跃定位
 */
public class RedisZSetSkipList {

    /** Redis 里默认 32 */
    public static final int ZSKIPLIST_MAXLEVEL = 32;
    /** Redis 里默认 0.25（层高概率） */
    public static final double ZSKIPLIST_P = 0.25;

    /** 范围查询：对应 Redis 的 zrangespec */
    public static final class RangeSpec {
        public final double min;
        public final double max;
        public final boolean minExclusive; // true: (min, ...], false: [min, ...]
        public final boolean maxExclusive; // true: [..., max), false: [..., max]

        public RangeSpec(double min, double max, boolean minExclusive, boolean maxExclusive) {
            this.min = min;
            this.max = max;
            this.minExclusive = minExclusive;
            this.maxExclusive = maxExclusive;
        }
    }

    /** 跳表层（Redis: zskiplistLevel） */
    private static final class Level {
        Node forward; // 本层 forward 指针
        long span; // 本层跨度：跳过多少个 level0 节点（按 rank 的关键）
    }

    /** 跳表节点（Redis: zskiplistNode） */
    public static final class Node {
        final String ele; // Redis 用 sds，这里用 String
        final double score;

        Node backward; // 只维护 level0 的 backward（用于逆序遍历）
        final Level[] level; // 柔性数组：节点拥有的层数 = level.length

        Node(int levels, double score, String ele) {
            this.ele = ele;
            this.score = score;
            this.level = new Level[levels];
            for (int i = 0; i < levels; i++) {
                this.level[i] = new Level();
            }
        }

        int levels() {
            return level.length;
        }
    }

    /** 跳表本体（Redis: zskiplist） */
    public static final class SkipList {
        final Node header; // 虚拟头结点：固定 MAXLEVEL
        Node tail;
        long length; // 节点数量
        int level; // 当前最高层数（1..MAXLEVEL）

        private final Random rnd = new Random();

        public SkipList() {
            this.level = 1;
            this.length = 0;
            this.header = new Node(ZSKIPLIST_MAXLEVEL, 0, "");
            this.tail = null;

            // header 每层 forward/span 初始化
            for (int i = 0; i < ZSKIPLIST_MAXLEVEL; i++) {
                header.level[i].forward = null;
                header.level[i].span = 0;
            }
            header.backward = null;
        }

        // -------------------------
        // 比较规则：先 score，后 ele（字典序）
        // -------------------------
        private static int compare(double score1, String ele1, double score2, String ele2) {
            if (score1 < score2)
                return -1;
            if (score1 > score2)
                return 1;
            // score 相同，用 member 字典序保证“全序关系”
            return ele1.compareTo(ele2);
        }

        // -------------------------
        // 随机层高：P=1/4
        // -------------------------
        private int randomLevel() {
            int lvl = 1;
            while (lvl < ZSKIPLIST_MAXLEVEL && rnd.nextDouble() < ZSKIPLIST_P) {
                lvl++;
            }
            return lvl;
        }

        // =========================================================
        // 1) INSERT: zslInsert (ZADD 最核心)
        // =========================================================
        public Node insert(double score, String ele) {
            // update[i]：第 i 层上需要修改 forward 的“前驱节点”
            Node[] update = new Node[ZSKIPLIST_MAXLEVEL];
            // rank[i]：到达 update[i] 时，已经跨过了多少个 level0 节点（用于 span 计算）
            long[] rank = new long[ZSKIPLIST_MAXLEVEL];

            Node x = header;

            /**
             * 从最高层往下找：
             * update[i] = 本层最后一个 < (score,ele) 的节点
             * rank[i] = 到 update[i] 为止在 level0 上跨过的节点数量
             */
            for (int i = level - 1; i >= 0; i--) {
                rank[i] = (i == level - 1) ? 0 : rank[i + 1];

                while (x.level[i].forward != null &&
                        compare(x.level[i].forward.score, x.level[i].forward.ele, score, ele) < 0) {
                    rank[i] += x.level[i].span; // span 累加：本层一次跳过多少 level0 节点
                    x = x.level[i].forward;
                }
                update[i] = x;
            }

            int newLevel = randomLevel();

            /**
             * 如果新节点层数超过当前跳表最高层：
             * - 需要把新高出来的层补齐 update/rank
             * - 这些层的前驱默认都是 header
             * - header 在这些层的 span 初始应为 length（因为 header->forward 将指向新节点）
             */
            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    rank[i] = 0;
                    update[i] = header;
                    update[i].level[i].span = length;
                }
                level = newLevel;
            }

            Node newNode = new Node(newLevel, score, ele);

            /**
             * 逐层插入 + span 切分
             *
             * 核心公式（和 Redis 一样）：
             * newNode.span = update.span - (rank[0] - rank[i])
             * update.span = (rank[0] - rank[i]) + 1
             *
             * 直觉：
             * - rank[0] 是新节点在 level0 上的“全局位置”
             * - rank[i] 是 update[i] 在 level0 上的“全局位置”
             * - rank[0]-rank[i] 就是 update[i] 到 newNode 的 level0 距离
             */
            for (int i = 0; i < newLevel; i++) {
                newNode.level[i].forward = update[i].level[i].forward;
                update[i].level[i].forward = newNode;

                long updateSpan = update[i].level[i].span;
                long dist = rank[0] - rank[i];

                newNode.level[i].span = updateSpan - dist;
                update[i].level[i].span = dist + 1;
            }

            /**
             * 对于比 newLevel 更高但 <= 当前 level 的层：
             * 这些层没插入索引节点，但 level0 多了一个节点，所以路径 span 需要 +1
             */
            for (int i = newLevel; i < level; i++) {
                update[i].level[i].span += 1;
            }

            // 维护 backward / tail（只在 level0）
            newNode.backward = (update[0] == header) ? null : update[0];
            if (newNode.level[0].forward != null) {
                newNode.level[0].forward.backward = newNode;
            } else {
                tail = newNode;
            }

            length++;
            return newNode;
        }

        // =========================================================
        // 2) DELETE: zslDelete / zslDeleteNode (ZREM、ZADD 改分数都会用到)
        // =========================================================
        public boolean delete(double score, String ele) {
            Node[] update = new Node[ZSKIPLIST_MAXLEVEL];
            Node x = header;

            // 找每层前驱 update[i]
            for (int i = level - 1; i >= 0; i--) {
                while (x.level[i].forward != null &&
                        compare(x.level[i].forward.score, x.level[i].forward.ele, score, ele) < 0) {
                    x = x.level[i].forward;
                }
                update[i] = x;
            }

            // 候选节点在 level0 forward
            x = x.level[0].forward;

            // 必须精确命中 (score, ele)
            if (x != null && x.score == score && x.ele.equals(ele)) {
                deleteNode(x, update);
                return true;
            }
            return false;
        }

        private void deleteNode(Node x, Node[] update) {
            /**
             * 逐层处理：
             * - 若 update[i].forward == x，说明该层确实连着 x：
             * update.span = update.span + x.span - 1 （拼回跨度）
             * update.forward = x.forward
             * - 否则该层没直接连 x，但 level0 少了一个节点，span 也要 -1
             */
            for (int i = 0; i < level; i++) {
                if (update[i].level[i].forward == x) {
                    update[i].level[i].span += x.level[i].span - 1;
                    update[i].level[i].forward = x.level[i].forward;
                } else {
                    update[i].level[i].span -= 1;
                }
            }

            // backward / tail
            if (x.level[0].forward != null) {
                x.level[0].forward.backward = x.backward;
            } else {
                tail = x.backward;
            }

            // 收缩最高层：如果 header 在最高层没 forward 了，就降 level
            while (level > 1 && header.level[level - 1].forward == null) {
                level--;
            }

            length--;
        }

        // =========================================================
        // 3) RANGE by SCORE: firstInRange + 顺扫（ZRANGEBYSCORE）
        // =========================================================
        public Node firstInRange(RangeSpec range) {
            if (!isInRange(range))
                return null;

            Node x = header;

            // 从顶层往下找：最后一个 < min 的节点
            for (int i = level - 1; i >= 0; i--) {
                while (x.level[i].forward != null && !valueGteMin(x.level[i].forward.score, range)) {
                    x = x.level[i].forward;
                }
            }

            x = x.level[0].forward;
            if (x == null)
                return null;
            if (!valueLteMax(x.score, range))
                return null;
            return x;
        }

        public List<Node> rangeByScore(RangeSpec range, int limit) {
            List<Node> res = new ArrayList<>();
            Node x = firstInRange(range);
            while (x != null && valueLteMax(x.score, range)) {
                res.add(x);
                if (limit > 0 && res.size() >= limit)
                    break;
                x = x.level[0].forward; // 沿 level0 顺序遍历
            }
            return res;
        }

        private boolean isInRange(RangeSpec range) {
            if (range.min > range.max)
                return false;
            if (range.min == range.max && (range.minExclusive || range.maxExclusive))
                return false;
            if (tail == null)
                return false;

            // tail 都 < min，则不可能有
            if (!valueGteMin(tail.score, range))
                return false;

            Node first = header.level[0].forward;
            if (first == null)
                return false;

            // first 都 > max，则不可能有
            if (!valueLteMax(first.score, range))
                return false;

            return true;
        }

        private boolean valueGteMin(double v, RangeSpec range) {
            return range.minExclusive ? (v > range.min) : (v >= range.min);
        }

        private boolean valueLteMax(double v, RangeSpec range) {
            return range.maxExclusive ? (v < range.max) : (v <= range.max);
        }

        // =========================================================
        // 4) RANGE by RANK: span 的高光（ZRANGE start stop）
        // =========================================================

        /**
         * 取第 rank 个元素（rank 从 1 开始）
         * 利用 span 在高层做跳跃，时间复杂度 O(logN)
         */
        public Node getElementByRank(long rank) {
            if (rank <= 0 || rank > length)
                return null;

            long traversed = 0;
            Node x = header;

            for (int i = level - 1; i >= 0; i--) {
                while (x.level[i].forward != null && (traversed + x.level[i].span) <= rank) {
                    traversed += x.level[i].span;
                    x = x.level[i].forward;
                }
                if (traversed == rank) {
                    return x;
                }
            }
            return null;
        }

        /**
         * 模拟 ZRANGE start stop（start/stop 是 0-based）
         */
        public List<Node> rangeByRank(long start, long stop) {
            List<Node> res = new ArrayList<>();
            if (start < 0)
                start = 0;
            if (stop < 0)
                return res;
            if (start >= length)
                return res;
            if (stop >= length)
                stop = length - 1;

            long rank = start + 1; // 转成 1-based
            Node x = getElementByRank(rank);

            for (long idx = start; x != null && idx <= stop; idx++) {
                res.add(x);
                x = x.level[0].forward;
            }
            return res;
        }

        // =========================================================
        // 5) 辅助：按 (score,ele) 查找（用于调试/演示）
        // =========================================================
        public Node find(double score, String ele) {
            Node x = header;
            for (int i = level - 1; i >= 0; i--) {
                while (x.level[i].forward != null &&
                        compare(x.level[i].forward.score, x.level[i].forward.ele, score, ele) < 0) {
                    x = x.level[i].forward;
                }
            }
            x = x.level[0].forward;
            if (x != null && x.score == score && x.ele.equals(ele))
                return x;
            return null;
        }
    }

    // =========================
    // Demo：可直接跑，观察行为
    // =========================
    public static void main(String[] args) {
        SkipList zsl = new SkipList();

        zsl.insert(10, "alice");
        zsl.insert(5, "bob");
        zsl.insert(10, "carol"); // score 相同按 ele 字典序

        System.out.println("== Range by rank [0..2] ==");
        for (Node n : zsl.rangeByRank(0, 2)) {
            System.out.printf("(%f, %s)%n", n.score, n.ele);
        }

        System.out.println("\n== Range by score [5..10] inclusive ==");
        RangeSpec r = new RangeSpec(5, 10, false, false);
        for (Node n : zsl.rangeByScore(r, 0)) {
            System.out.printf("(%f, %s)%n", n.score, n.ele);
        }

        System.out.println("\n== Delete (10, alice) ==");
        zsl.delete(10, "alice");
        for (Node n : zsl.rangeByRank(0, 10)) {
            System.out.printf("(%f, %s)%n", n.score, n.ele);
        }
    }
}