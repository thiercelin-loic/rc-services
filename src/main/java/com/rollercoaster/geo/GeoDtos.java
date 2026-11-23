package com.rollercoaster.geo;

import java.util.List;

public final class GeoDtos {

    private GeoDtos() {
    }

    public record ProjectedPoint(
            double x,
            double y) {
    }

    public record TerritoryInput(
            String id,
            String nutsCode,
            String name,
            double centerLon,
            double centerLat) {
    }

    public record ProjectedTerritory(
            String id,
            String nutsCode,
            String name,
            double screenX,
            double screenY,
            double radius) {
    }

    public record ProjectPointRequest(
            double lon,
            double lat,
            Double width,
            Double height) {
    }

    public record ProjectBatchRequest(
            List<TerritoryInput> territories,
            Double width,
            Double height) {
        public ProjectBatchRequest {
            territories = territories != null ? List.copyOf(territories) : List.of();
        }
    }

    public record HitTestRequest(
            List<ProjectedTerritory> territories,
            double cursorX,
            double cursorY) {
        public HitTestRequest {
            territories = territories != null ? List.copyOf(territories) : List.of();
        }
    }
}
