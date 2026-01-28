package com.example.idgendemo.id;

import java.util.concurrent.atomic.AtomicLong;

public class Segment {

    private final long start;
    private final long end;
    private final AtomicLong cursor;

    public Segment(long start, long end) {
        if (end < start) throw new IllegalArgumentException("end < start");
        this.start = start;
        this.end = end;
        this.cursor = new AtomicLong(start);
    }

    public long next() {
        long v = cursor.getAndIncrement();
        if (v > end) return -1;
        return v;
    }

    public long remaining() {
        long c = cursor.get();
        return Math.max(0, end - c + 1);
    }

    public long getStart() { return start; }
    public long getEnd() { return end; }
}
