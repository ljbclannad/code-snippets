package com.lx.governance.nacossimulator.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

class MutableClock extends Clock {

    private Instant current;
    private final ZoneId zoneId;

    MutableClock(Instant current, ZoneId zoneId) {
        this.current = current;
        this.zoneId = zoneId;
    }

    void advanceSeconds(long seconds) {
        current = current.plusSeconds(seconds);
    }

    void advanceMillis(long millis) {
        current = current.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return zoneId;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(current, zone);
    }

    @Override
    public Instant instant() {
        return current;
    }
}
