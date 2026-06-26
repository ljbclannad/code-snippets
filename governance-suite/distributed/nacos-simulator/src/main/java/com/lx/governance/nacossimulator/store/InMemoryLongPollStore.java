package com.lx.governance.nacossimulator.store;

import com.lx.governance.nacossimulator.model.PendingConfigPoll;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class InMemoryLongPollStore {

    private final CopyOnWriteArrayList<PendingConfigPoll> pendingPolls = new CopyOnWriteArrayList<>();

    public void add(PendingConfigPoll poll) {
        pendingPolls.add(poll);
    }

    public void remove(PendingConfigPoll poll) {
        pendingPolls.remove(poll);
    }

    public List<PendingConfigPoll> list() {
        return new ArrayList<>(pendingPolls);
    }

    public int size() {
        return pendingPolls.size();
    }
}
