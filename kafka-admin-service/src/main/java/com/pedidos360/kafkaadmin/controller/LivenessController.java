package com.pedidos360.kafkaadmin.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Process liveness for the private ALB target group. No dependency probing here. */
@RestController
public class LivenessController {
    @GetMapping("/internal/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }
}
