package com.rollercoaster.writeback;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/writeback")
public class WritebackController {

    private final WritebackService writebackService;

    public WritebackController(WritebackService writebackService) {
        this.writebackService = writebackService;
    }

    @PostMapping("/submit")
    public ResponseEntity<WritebackDtos.WritebackStagingDto> submit(
            @RequestBody WritebackService.WritebackSubmissionRequest request) {
        WritebackStagingEntity created = writebackService.submitWriteback(request);
        return ResponseEntity.ok(WritebackDtos.WritebackStagingDto.from(created));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<WritebackDtos.WritebackStagingDto> approve(
            @PathVariable UUID id,
            @RequestParam(required = false) String compositeKey) {
        WritebackStagingEntity approved = writebackService.approveAndMerge(id, compositeKey);
        return ResponseEntity.ok(WritebackDtos.WritebackStagingDto.from(approved));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<WritebackDtos.WritebackStagingDto>> listPending(
            @RequestParam(defaultValue = "default-tenant") String tenantId) {
        List<WritebackDtos.WritebackStagingDto> pendingDtos = writebackService.listPending(tenantId).stream()
                .map(WritebackDtos.WritebackStagingDto::from)
                .toList();
        return ResponseEntity.ok(pendingDtos);
    }

}
