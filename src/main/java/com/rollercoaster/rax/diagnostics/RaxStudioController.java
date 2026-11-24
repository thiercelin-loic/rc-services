package com.rollercoaster.rax.diagnostics;

import com.rollercoaster.rax.compiler.CalculatedColumnService;
import com.rollercoaster.rax.compiler.CalculatedColumnService.CalculatedColumnRequest;
import com.rollercoaster.rax.compiler.CalculatedColumnService.CalculatedColumnResult;
import com.rollercoaster.rax.compiler.CalculatedTableService;
import com.rollercoaster.rax.compiler.CalculatedTableService.CalculatedTableRequest;
import com.rollercoaster.rax.compiler.CalculatedTableService.CalculatedTableResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Thin REST controller exposing RAX Table View calculated column and calculated table endpoints.
 */
@RestController
@RequestMapping("/api/rax")
public class RaxStudioController {

    private final CalculatedColumnService calculatedColumnService;
    private final CalculatedTableService calculatedTableService;

    @Autowired
    public RaxStudioController(
            CalculatedColumnService calculatedColumnService,
            CalculatedTableService calculatedTableService) {
        this.calculatedColumnService = Objects.requireNonNull(
                calculatedColumnService, "calculatedColumnService must not be null");
        this.calculatedTableService = Objects.requireNonNull(
                calculatedTableService, "calculatedTableService must not be null");
    }

    @PostMapping("/calculated-column")
    public ResponseEntity<CalculatedColumnResult> evaluateCalculatedColumn(
            @RequestBody CalculatedColumnRequest request) {
        Objects.requireNonNull(request, "CalculatedColumnRequest body is required.");
        return ResponseEntity.ok(calculatedColumnService.evaluateCalculatedColumn(request));
    }

    @PostMapping("/calculated-table")
    public ResponseEntity<CalculatedTableResult> evaluateCalculatedTable(
            @RequestBody CalculatedTableRequest request) {
        Objects.requireNonNull(request, "CalculatedTableRequest body is required.");
        return ResponseEntity.ok(calculatedTableService.buildCalculatedTableFromFormula(request));
    }
}
