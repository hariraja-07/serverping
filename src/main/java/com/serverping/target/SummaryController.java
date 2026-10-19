package com.serverping.target;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint 8: fleet-wide totals. Deliberately its own controller — it is not addressed by a
 * target id, so hanging it off {@code TargetController} would mean a second {@code @RequestMapping}
 * base path on a class whose name implies a single resource.
 */
@RestController
@RequestMapping("/api/v1")
public class SummaryController {

    private final TargetService targetService;

    public SummaryController(TargetService targetService) {
        this.targetService = targetService;
    }

    @GetMapping("/summary")
    public SummaryResponse summary() {
        return targetService.summary();
    }
}
