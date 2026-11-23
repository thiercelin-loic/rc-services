package com.rollercoaster.serialization;

import com.rollercoaster.serialization.ProjectDtos.ParsedProjectResponse;
import com.rollercoaster.serialization.ProjectDtos.ProjectArchiveReceipt;
import com.rollercoaster.serialization.ProjectDtos.ProjectExportRequest;
import com.rollercoaster.serialization.ProjectDtos.ProjectImportRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectSerializationService serializationService;

    public ProjectController(ProjectSerializationService serializationService) {
        this.serializationService = serializationService;
    }

    @PostMapping(
            value = "/export",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectArchiveReceipt> exportProject(@RequestBody ProjectExportRequest request) {
        return ResponseEntity.ok(serializationService.exportProject(request));
    }

    @PostMapping(
            value = "/import",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ParsedProjectResponse> importProject(@RequestBody ProjectImportRequest request) {
        return ResponseEntity.ok(serializationService.importProject(request));
    }

    @ExceptionHandler({CorruptedPackageException.class, IncompatibleSchemaException.class})
    public ResponseEntity<Map<String, String>> handleArchiveError(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Invalid .rcp project archive"));
    }
}
