package com.ticketing.gateway.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/actuator/circuit-breakers")
@RequiredArgsConstructor
public class CircuitBreakerStatusController {

    private final CircuitBreakerManager cbManager;

    @GetMapping
    public Map<String, String> getStates() {
        return cbManager.getAllStates().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().name()
                ));
    }
}
