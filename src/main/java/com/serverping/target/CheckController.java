package com.serverping.target;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Check-related endpoints. Reads live in {@code TargetController} except this one, which needs
 * the target id as its prefix.
 *
 * <p>History ({@code GET /{id}/checks}) and the on-demand probe ({@code POST /{id}/check})
 * share a path but differ in effect: one reads rows already recorded, the other dials out and
 * writes a new one — hence GET versus POST.
 */
@RestController
@RequestMapping("/api/v1/targets")
public class CheckController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final TargetService targetService;

    public CheckController(TargetService targetService) {
        this.targetService = targetService;
    }

    /**
     * Probe the target now and return the result. Synchronous on purpose — the whole point of
     * calling this endpoint is to get a latency number back rather than a 202 and a poll loop.
     * The probe is capped at {@code app.check-timeout-ms}, so the worst case is bounded.
     */
    @PostMapping("/{id}/check")
    public ResponseEntity<CheckResponse> checkNow(@PathVariable long id) {
        return ResponseEntity.ok(targetService.checkNow(id));
    }

    /**
     * Recent probes for one target, newest first.
     *
     * <p>{@code limit} is clamped rather than rejected: an out-of-range page size is a client
     * mistake, not something worth failing an otherwise valid read over.
     */
    @GetMapping("/{id}/checks")
    public List<CheckResponse> history(@PathVariable long id,
                                       @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT)
                                       int limit) {
        return targetService.history(id, Math.min(Math.max(limit, 1), MAX_LIMIT));
    }
}
