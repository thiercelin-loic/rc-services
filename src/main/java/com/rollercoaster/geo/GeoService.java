package com.rollercoaster.geo;

import com.rollercoaster.geo.GeoDtos.ProjectedPoint;
import com.rollercoaster.geo.GeoDtos.ProjectedTerritory;
import com.rollercoaster.geo.GeoDtos.TerritoryInput;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class GeoService {

    public static final double DEG_TO_RAD = Math.PI / 180.0;
    public static final double LON0_DEG = 10.0;
    public static final double LAT0_DEG = 50.0;
    public static final double PHI1_DEG = 43.0;
    public static final double PHI2_DEG = 62.0;
    public static final double DEFAULT_WIDTH = 520.0;
    public static final double DEFAULT_HEIGHT = 300.0;
    public static final double SCALE_FACTOR = 1.65;
    public static final double OFFSET_X = 0.52;
    public static final double OFFSET_Y = 0.58;
    public static final double HIT_PADDING = 10.0;
    public static final double DEFAULT_RADIUS = 24.0;

    public ProjectedPoint projectEurope(double lon, double lat, double width, double height) {
        double effectiveWidth = width > 0 ? width : DEFAULT_WIDTH;
        double effectiveHeight = height > 0 ? height : DEFAULT_HEIGHT;

        double lon0 = LON0_DEG * DEG_TO_RAD;
        double lat0 = LAT0_DEG * DEG_TO_RAD;
        double phi1 = PHI1_DEG * DEG_TO_RAD;
        double phi2 = PHI2_DEG * DEG_TO_RAD;

        double n = 0.5 * (Math.sin(phi1) + Math.sin(phi2));
        double c = Math.cos(phi1) * Math.cos(phi1) + 2.0 * n * Math.sin(phi1);
        double rho0 = Math.sqrt(c - 2.0 * n * Math.sin(lat0)) / n;

        double lam = lon * DEG_TO_RAD;
        double phi = lat * DEG_TO_RAD;
        double theta = n * (lam - lon0);
        double rho = Math.sqrt(Math.max(0.01, c - 2.0 * n * Math.sin(phi))) / n;

        double rawX = rho * Math.sin(theta);
        double rawY = rho0 - rho * Math.cos(theta);

        double scale = Math.min(effectiveWidth, effectiveHeight) * SCALE_FACTOR;
        double screenX = roundOneDecimal(effectiveWidth * OFFSET_X + rawX * scale);
        double screenY = roundOneDecimal(effectiveHeight * OFFSET_Y - rawY * scale);

        return new ProjectedPoint(screenX, screenY);
    }

    public List<ProjectedTerritory> projectTerritories(
            List<TerritoryInput> territories,
            double width,
            double height) {
        if (territories == null || territories.isEmpty()) {
            return List.of();
        }
        List<ProjectedTerritory> projected = new ArrayList<>(territories.size());
        for (TerritoryInput territory : territories) {
            ProjectedPoint coords = projectEurope(territory.centerLon(), territory.centerLat(), width, height);
            projected.add(new ProjectedTerritory(
                    territory.id(),
                    territory.nutsCode(),
                    territory.name(),
                    coords.x(),
                    coords.y(),
                    DEFAULT_RADIUS));
        }
        return projected;
    }

    public ProjectedTerritory hitTerritory(
            List<ProjectedTerritory> territories,
            double cursorX,
            double cursorY) {
        if (territories == null || territories.isEmpty()) {
            return null;
        }
        for (ProjectedTerritory territory : territories) {
            double dx = cursorX - territory.screenX();
            double dy = cursorY - territory.screenY();
            if (Math.sqrt(dx * dx + dy * dy) <= territory.radius() + HIT_PADDING) {
                return territory;
            }
        }
        return null;
    }

    private static double roundOneDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
