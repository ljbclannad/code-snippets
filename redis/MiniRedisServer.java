package redis;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MiniRedis:
 * - 单线程 Reactor：Selector.select() ~= epoll_wait()
 * - RESP 解析：仅支持 *array of $bulk strings（redis-cli 的常见请求格式）
 * - 命令：PING/ECHO/GET/SET/DEL/EXISTS/INCR/TTL/EXPIRE/INFO/QUIT
 * - 过期：lazy delete + active expire cycle（每 100ms 扫一小段）
 */
public class MiniRedisServer {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6380; // 避免占用真实 Redis 6379
        new MiniRedisServer().start(port);
    }

    // ===== Redis-like data model =====
    static final class Entry {
        byte[] value;
        long expireAtMs; // -1 means no expire

        Entry(byte[] v, long exp) {
            this.value = v;
            this.expireAtMs = exp;
        }
    }

    // 单线程里直接 HashMap 就行（真实 Redis 是 dict + SDS + 更多技巧）
    private final HashMap<String, Entry> db = new HashMap<>(1 << 16);

    // active expire cycle：限制每次扫描量，避免阻塞事件循环
    private long lastExpireRunMs = 0;

    // ===== Reactor =====
    private void start(int port) throws Exception {
        Selector selector = Selector.open();

        ServerSocketChannel server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress("0.0.0.0", port));
        server.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("MiniRedis started on port " + port);
        System.out.println("Try: redis-cli -p " + port);

        while (true) {
            // 事件循环：select() 类似 epoll_wait
            selector.select(50);

            // 过期清理（active expire cycle）
            long now = System.currentTimeMillis();
            if (now - lastExpireRunMs >= 100) {
                activeExpireCycle(now, 32); // 每 100ms 扫 32 个 key（演示用）
                lastExpireRunMs = now;
            }

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                try {
                    if (key.isAcceptable())
                        handleAccept(selector, key);
                    else {
                        if (key.isReadable())
                            handleRead(key);
                        if (key.isWritable())
                            handleWrite(key);
                    }
                } catch (IOException e) {
                    closeKey(key);
                }
            }
        }
    }

    private void handleAccept(Selector selector, SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel ch = server.accept();
        if (ch == null)
            return;

        ch.configureBlocking(false);
        ch.socket().setTcpNoDelay(true);

        Conn conn = new Conn();
        ch.register(selector, SelectionKey.OP_READ, conn);
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        Conn conn = (Conn) key.attachment();

        int n = ch.read(conn.readBuf);
        if (n == -1) {
            closeKey(key);
            return;
        }
        if (n == 0)
            return;

        conn.readBuf.flip();
        conn.in.append(conn.readBuf);
        conn.readBuf.clear();

        // 一次 read 可能带来多个完整命令：循环解析
        while (true) {
            List<byte[]> cmd = conn.parser.tryParseCommand(conn.in);
            if (cmd == null)
                break;

            byte[] resp = dispatch(cmd);
            conn.outQ.add(ByteBuffer.wrap(resp));
        }

        if (!conn.outQ.isEmpty()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        Conn conn = (Conn) key.attachment();

        while (!conn.outQ.isEmpty()) {
            ByteBuffer buf = conn.outQ.peek();
            ch.write(buf);
            if (buf.hasRemaining())
                break; // 发送缓冲区满了，下次继续
            conn.outQ.poll();
        }

        if (conn.outQ.isEmpty()) {
            // 关键点：没东西写就取消 OP_WRITE，避免空转（busy loop）
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            if (conn.shouldClose)
                closeKey(key);
        }
    }

    private void closeKey(SelectionKey key) {
        try {
            key.cancel();
            key.channel().close();
        } catch (IOException ignored) {
        }
    }

    // ===== Command dispatch =====
    private byte[] dispatch(List<byte[]> cmdParts) {
        // Redis 命令不区分大小写
        String cmd = upperAscii(cmdParts.get(0));
        long now = System.currentTimeMillis();

        try {
            switch (cmd) {
                case "PING":
                    if (cmdParts.size() == 1)
                        return Resp.simple("PONG");
                    return Resp.bulk(cmdParts.get(1));

                case "ECHO":
                    requireArgs(cmdParts, 2);
                    return Resp.bulk(cmdParts.get(1));

                case "QUIT":
                    // 返回 OK 后关闭连接（像 redis-cli 那样）
                    // 这里通过 Conn.shouldClose 实现，dispatch 本身无连接上下文
                    // 简化：返回 OK，实际关闭在上层（见 dispatchWithConn 版本）
                    return Resp.simple("OK");

                case "GET": {
                    requireArgs(cmdParts, 2);
                    String key = utf8(cmdParts.get(1));
                    Entry e = getIfNotExpired(key, now);
                    if (e == null)
                        return Resp.nilBulk();
                    return Resp.bulk(e.value);
                }

                case "SET": {
                    requireArgs(cmdParts, 3);
                    String key = utf8(cmdParts.get(1));
                    byte[] val = cmdParts.get(2);

                    // 解析可选参数：EX sec / PX ms / NX / XX
                    long expireAt = -1;
                    boolean nx = false, xx = false;

                    for (int i = 3; i < cmdParts.size(); i++) {
                        String opt = upperAscii(cmdParts.get(i));
                        if ("EX".equals(opt)) {
                            i = i + 1;
                            if (i >= cmdParts.size())
                                return Resp.error("ERR syntax error");
                            long sec = parseLong(utf8(cmdParts.get(i)));
                            expireAt = now + sec * 1000L;
                        } else if ("PX".equals(opt)) {
                            i = i + 1;
                            if (i >= cmdParts.size())
                                return Resp.error("ERR syntax error");
                            long ms = parseLong(utf8(cmdParts.get(i)));
                            expireAt = now + ms;
                        } else if ("NX".equals(opt))
                            nx = true;
                        else if ("XX".equals(opt))
                            xx = true;
                        else
                            return Resp.error("ERR syntax error");
                    }

                    Entry existed = getIfNotExpired(key, now);
                    if (nx && existed != null)
                        return Resp.nilBulk(); // Redis 实际返回 nil bulk（(nil)）
                    if (xx && existed == null)
                        return Resp.nilBulk();

                    db.put(key, new Entry(val, expireAt));
                    return Resp.simple("OK");
                }

                case "DEL": {
                    requireArgs(cmdParts, 2);
                    int removed = 0;
                    for (int i = 1; i < cmdParts.size(); i++) {
                        String k = utf8(cmdParts.get(i));
                        Entry e = getIfNotExpired(k, now);
                        if (e != null) {
                            db.remove(k);
                            removed++;
                        } else {
                            // 若已过期，getIfNotExpired 已经懒删除了
                            if (db.remove(k) != null)
                                removed++;
                        }
                    }
                    return Resp.integer(removed);
                }

                case "EXISTS": {
                    requireArgs(cmdParts, 2);
                    int cnt = 0;
                    for (int i = 1; i < cmdParts.size(); i++) {
                        String k = utf8(cmdParts.get(i));
                        if (getIfNotExpired(k, now) != null)
                            cnt++;
                    }
                    return Resp.integer(cnt);
                }

                case "INCR": {
                    requireArgs(cmdParts, 2);
                    String key = utf8(cmdParts.get(1));
                    Entry e = getIfNotExpired(key, now);
                    long v = 0;
                    long exp = -1;

                    if (e != null) {
                        exp = e.expireAtMs;
                        v = parseLong(utf8(e.value));
                    }
                    v++;
                    db.put(key, new Entry(Long.toString(v).getBytes(StandardCharsets.UTF_8), exp));
                    return Resp.integer(v);
                }

                case "TTL": {
                    requireArgs(cmdParts, 2);
                    String key = utf8(cmdParts.get(1));
                    Entry e = getIfNotExpired(key, now);
                    if (e == null)
                        return Resp.integer(-2); // key 不存在
                    if (e.expireAtMs < 0)
                        return Resp.integer(-1); // 永不过期
                    long ttlSec = Math.max(0, (e.expireAtMs - now) / 1000L);
                    return Resp.integer(ttlSec);
                }

                case "EXPIRE": {
                    requireArgs(cmdParts, 3);
                    String key = utf8(cmdParts.get(1));
                    long sec = parseLong(utf8(cmdParts.get(2)));
                    Entry e = getIfNotExpired(key, now);
                    if (e == null)
                        return Resp.integer(0);
                    e.expireAtMs = now + sec * 1000L;
                    return Resp.integer(1);
                }

                case "INFO": {
                    // 简化版 info
                    StringBuilder sb = new StringBuilder();
                    sb.append("# Server\n");
                    sb.append("mini_redis:1\n");
                    sb.append("# Keyspace\n");
                    sb.append("db0:keys=").append(countAliveKeys(now)).append("\n");
                    sb.append("# Memory\n");
                    sb.append("note=java_demo_no_real_used_memory\n");
                    return Resp.bulk(sb.toString().getBytes(StandardCharsets.UTF_8));
                }

                default:
                    return Resp.error("ERR unknown command '" + cmd.toLowerCase(Locale.ROOT) + "'");
            }
        } catch (NumberFormatException nfe) {
            return Resp.error("ERR value is not an integer or out of range");
        } catch (IllegalArgumentException iae) {
            return Resp.error("ERR " + iae.getMessage());
        }
    }

    private int countAliveKeys(long now) {
        int cnt = 0;
        for (Map.Entry<String, Entry> me : db.entrySet()) {
            Entry e = me.getValue();
            if (e.expireAtMs < 0 || e.expireAtMs > now)
                cnt++;
        }
        return cnt;
    }

    private Entry getIfNotExpired(String key, long now) {
        Entry e = db.get(key);
        if (e == null)
            return null;
        if (e.expireAtMs >= 0 && e.expireAtMs <= now) {
            // lazy expiration：访问时发现过期就删除
            db.remove(key);
            return null;
        }
        return e;
    }

    private void activeExpireCycle(long now, int maxScan) {
        // Redis 的 active expire cycle 更复杂（采样 + 动态调整）
        // 这里做个限量扫描：每次最多扫 maxScan 个
        if (db.isEmpty())
            return;
        int scanned = 0;

        Iterator<Map.Entry<String, Entry>> it = db.entrySet().iterator();
        while (it.hasNext() && scanned < maxScan) {
            Map.Entry<String, Entry> me = it.next();
            Entry e = me.getValue();
            if (e.expireAtMs >= 0 && e.expireAtMs <= now) {
                it.remove();
            }
            scanned++;
        }
    }

    private static void requireArgs(List<byte[]> parts, int n) {
        if (parts.size() < n)
            throw new IllegalArgumentException("wrong number of arguments");
    }

    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static long parseLong(String s) {
        // Redis 对整数解析更严格；这里用 Java 自带
        return Long.parseLong(s.trim());
    }

    private static String upperAscii(byte[] b) {
        // 避免 Locale 影响；命令一般是 ASCII
        char[] cs = new char[b.length];
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xff;
            if (c >= 'a' && c <= 'z')
                c = c - 32;
            cs[i] = (char) c;
        }
        return new String(cs);
    }

    // ===== Connection state =====
    static final class Conn {
        final ByteBuffer readBuf = ByteBuffer.allocate(16 * 1024);
        final ByteQueue in = new ByteQueue(16 * 1024);
        final RespParser parser = new RespParser();
        final ArrayDeque<ByteBuffer> outQ = new ArrayDeque<>();
        boolean shouldClose = false;
    }

    // ===== RESP encoder =====
    static final class Resp {
        static byte[] simple(String s) {
            return ("+" + s + "\r\n").getBytes(StandardCharsets.UTF_8);
        }

        static byte[] error(String s) {
            return ("-" + s + "\r\n").getBytes(StandardCharsets.UTF_8);
        }

        static byte[] integer(long v) {
            return (":" + v + "\r\n").getBytes(StandardCharsets.UTF_8);
        }

        static byte[] nilBulk() {
            return "$-1\r\n".getBytes(StandardCharsets.UTF_8);
        }

        static byte[] bulk(byte[] data) {
            byte[] head = ("$" + data.length + "\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] tail = "\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[head.length + data.length + tail.length];
            System.arraycopy(head, 0, out, 0, head.length);
            System.arraycopy(data, 0, out, head.length, data.length);
            System.arraycopy(tail, 0, out, head.length + data.length, tail.length);
            return out;
        }
    }

    // ===== RESP parser: only supports *array of $bulk strings =====
    static final class RespParser {
        enum State {
            INIT, ARRAY_LEN, BULK_START, BULK_LEN, BULK_DATA
        }

        State st = State.INIT;

        int arrayLen = 0;
        int bulkLen = 0;
        ArrayList<byte[]> parts;

        List<byte[]> tryParseCommand(ByteQueue q) {
            while (true) {
                switch (st) {
                    case INIT: {
                        Byte b = q.peek();
                        if (b == null)
                            return null;
                        if (b != (byte) '*')
                            return errorAndReset("protocol error: expected '*'");
                        q.readByte(); // consume '*'
                        st = State.ARRAY_LEN;
                        break;
                    }
                    case ARRAY_LEN: {
                        byte[] line = q.readLine();
                        if (line == null)
                            return null;
                        arrayLen = parseIntAscii(line);
                        if (arrayLen < 0)
                            return errorAndReset("protocol error: invalid multibulk length");
                        parts = new ArrayList<>(arrayLen);
                        st = State.BULK_START;
                        break;
                    }
                    case BULK_START: {
                        if (parts.size() == arrayLen) {
                            // complete command
                            List<byte[]> out = parts;
                            parts = null;
                            st = State.INIT;
                            return out;
                        }
                        Byte b = q.peek();
                        if (b == null)
                            return null;
                        if (b != (byte) '$')
                            return errorAndReset("protocol error: expected '$'");
                        q.readByte();
                        st = State.BULK_LEN;
                        break;
                    }
                    case BULK_LEN: {
                        byte[] line = q.readLine();
                        if (line == null)
                            return null;
                        bulkLen = parseIntAscii(line);
                        if (bulkLen < -1)
                            return errorAndReset("protocol error: invalid bulk length");
                        if (bulkLen == -1) {
                            // nil bulk => treat as empty
                            parts.add(null);
                            st = State.BULK_START;
                        } else {
                            st = State.BULK_DATA;
                        }
                        break;
                    }
                    case BULK_DATA: {
                        if (q.size() < bulkLen + 2)
                            return null;
                        byte[] data = q.readBytes(bulkLen);
                        // expect \r\n
                        byte r = q.readByte();
                        byte n = q.readByte();
                        if (r != '\r' || n != '\n')
                            return errorAndReset("protocol error: expected CRLF after bulk data");
                        parts.add(data);
                        st = State.BULK_START;
                        break;
                    }
                    default:
                        return errorAndReset("protocol error");
                }
            }
        }

        private List<byte[]> errorAndReset(String msg) {
            // 简化：遇到协议错误就 reset；真实 Redis 会返回 -ERR 并断开/丢弃等策略
            st = State.INIT;
            parts = null;
            arrayLen = 0;
            bulkLen = 0;
            // 返回一个“伪命令”，让上层回 ERR（也可以直接抛异常）
            return Arrays.asList("ECHO".getBytes(StandardCharsets.UTF_8),
                    ("-ERR " + msg).getBytes(StandardCharsets.UTF_8));
        }

        private static int parseIntAscii(byte[] line) {
            int i = 0;
            boolean neg = false;
            if (line.length == 0)
                throw new NumberFormatException();
            if (line[0] == '-') {
                neg = true;
                i = 1;
            }
            int v = 0;
            for (; i < line.length; i++) {
                int c = line[i] & 0xff;
                if (c < '0' || c > '9')
                    throw new NumberFormatException();
                v = v * 10 + (c - '0');
            }
            return neg ? -v : v;
        }
    }

    // ===== Byte queue for incremental parsing =====
    static final class ByteQueue {
        byte[] buf;
        int r, w; // read, write index

        ByteQueue(int cap) {
            buf = new byte[cap];
        }

        int size() {
            return w - r;
        }

        void append(ByteBuffer bb) {
            int n = bb.remaining();
            ensureCapacity(size() + n);
            bb.get(buf, w, n);
            w += n;
        }

        void ensureCapacity(int need) {
            if (need <= buf.length)
                return;
            int newCap = buf.length;
            while (newCap < need)
                newCap *= 2;
            byte[] nb = new byte[newCap];
            int sz = size();
            System.arraycopy(buf, r, nb, 0, sz);
            buf = nb;
            r = 0;
            w = sz;
        }

        Byte peek() {
            if (r >= w)
                return null;
            return buf[r];
        }

        byte readByte() {
            return buf[r++];
        }

        byte[] readBytes(int n) {
            byte[] out = new byte[n];
            System.arraycopy(buf, r, out, 0, n);
            r += n;
            compactIfNeeded();
            return out;
        }

        byte[] readLine() {
            // find \r\n
            for (int i = r; i + 1 < w; i++) {
                if (buf[i] == '\r' && buf[i + 1] == '\n') {
                    int len = i - r;
                    byte[] line = new byte[len];
                    System.arraycopy(buf, r, line, 0, len);
                    r = i + 2; // skip CRLF
                    compactIfNeeded();
                    return line;
                }
            }
            return null;
        }

        void compactIfNeeded() {
            // 避免数组无限增长：当读指针走到一定程度就搬移
            if (r > 0 && (r > buf.length / 2 || r > 1 << 20)) {
                int sz = size();
                System.arraycopy(buf, r, buf, 0, sz);
                r = 0;
                w = sz;
            }
        }
    }
}