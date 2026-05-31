package com.health.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HomeControllerTest {

    private final HomeController controller = new HomeController();

    @Test
    void shouldRedirectRootToSwaggerUi() {
        assertEquals("redirect:/swagger-ui.html", controller.index());
    }
}
