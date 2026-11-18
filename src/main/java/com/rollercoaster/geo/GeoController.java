package com.rollercoaster.geo;

import com.rollercoaster.geo.GeoDtos.HitTestRequest;
import com.rollercoaster.geo.GeoDtos.ProjectBatchRequest;
import com.rollercoaster.geo.GeoDtos.ProjectPointRequest;
import com.rollercoaster.geo.GeoDtos.ProjectedPoint;
import com.rollercoaster.geo.GeoDtos.ProjectedTerritory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/geo")
public class GeoController {

    private final GeoService geoService;

    public GeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    @PostMapping(value = "/project-point", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectedPoint> projectPoint(@RequestBody ProjectPointRequest request) {
        double width = request.width() != null ? request.width() : GeoService.DEFAULT_WIDTH;
        double height = request.height() != null ? request.height() : GeoService.DEFAULT_HEIGHT;
        return ResponseEntity.ok(geoService.projectEurope(request.lon(), request.lat(), width, height));
    }

    @PostMapping(value = "/project", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ProjectedTerritory>> projectBatch(@RequestBody ProjectBatchRequest request) {
        double width = request.width() != null ? request.width() : GeoService.DEFAULT_WIDTH;
        double height = request.height() != null ? request.height() : GeoService.DEFAULT_HEIGHT;
        return ResponseEntity.ok(geoService.projectTerritories(request.territories(), width, height));
    }

    @PostMapping(value = "/hit-test", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectedTerritory> hitTest(@RequestBody HitTestRequest request) {
        return ResponseEntity.ok(geoService.hitTerritory(
                request.territories(),
                request.cursorX(),
                request.cursorY()));
    }
}
