package com.finance.query.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Service-level health for the read side.
 *
 * GET /api/upsert/** is routed by the gateway to this service (CQRS), so
 * /api/upsert/health never reaches command-service — without this controller it
 * falls through to a 404/500. /analytics/health mirrors the same check.
 * Kubernetes probes use /actuator/health; this is the public-API equivalent.
 */
@RestController
public class HealthController {

    @GetMapping({"/upsert/health", "/analytics/health"})
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "query-service");
    }
}
