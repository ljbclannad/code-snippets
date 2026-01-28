package com.example.idgendemo;

import com.example.idgendemo.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IdgenDemoApplicationTests {

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void generateIds() {
        for (int i = 0; i < 10; i++) {
            long id = idGenerator.nextId("1001");
            System.out.println(id);
        }
    }
}
