package io.github.messageguard.ops.api;

import io.github.messageguard.core.model.ConsumeStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/consume-records")
public class ConsumeRecordController {

    @GetMapping
    public List<ConsumeRecordView> list() {
        return List.of(new ConsumeRecordView("demo-consumer", "demo-group", "order-1", ConsumeStatus.SUCCESS, 0));
    }
}
