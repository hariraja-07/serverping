package com.serverping.target;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * CRUD over watched targets. The check endpoints live in {@code CheckController}.
 *
 * <p>Paths are plural nouns under a version prefix, and the HTTP verbs carry the operation:
 * POST creates (201 + {@code Location}), GET reads, PUT replaces, DELETE removes (204, no body).
 */
@RestController
@RequestMapping("/api/v1/targets")
public class TargetController {

    private final TargetService targetService;

    public TargetController(TargetService targetService) {
        this.targetService = targetService;
    }

    @PostMapping
    public ResponseEntity<TargetResponse> create(@Valid @RequestBody TargetRequest request) {
        Target created = targetService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/targets/" + created.getId()))
                .body(TargetResponse.from(created));
    }

    @GetMapping
    public List<TargetResponse> list(
            @RequestParam(name = "status", required = false) Status status) {
        return targetService.list(status).stream().map(TargetResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TargetResponse get(@PathVariable long id) {
        return TargetResponse.from(targetService.get(id));
    }

    @PutMapping("/{id}")
    public TargetResponse replace(@PathVariable long id,
                                  @Valid @RequestBody TargetRequest request) {
        return TargetResponse.from(targetService.replace(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        targetService.delete(id);
    }
}
