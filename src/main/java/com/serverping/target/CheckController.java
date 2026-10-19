package com.serverping.target;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand checks. {@code GET} reads return targets; these mutate observed state, so they are
 * {@code POST}s even though they trigger work rather than create a resource.
 *
 * <p>Endpoints 1–5 of the v1 API live in {@link TargetController}; check history
 * ({@code GET /{id}/checks}) will land here alongside this one.
 */
@RestController
@RequestMapping("/api/v1/targets")
public class CheckController {

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
}
