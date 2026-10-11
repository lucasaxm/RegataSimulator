package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.models.AreaCorner;
import com.boatarde.regatasimulator.models.TemplateArea;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class MediaValidation {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    public static final long MAX_PIXELS = 40_000_000;
    public static final int MAX_AREAS = 128;

    private MediaValidation() { }

    public record Dimensions(int width, int height) { }

    public static String extension(String name) throws IOException {
        String extension = name == null ? "" : FileUtils.getFileExtension(name).toLowerCase(Locale.ROOT);
        if (!Set.of(".jpg", ".jpeg", ".png").contains(extension)) {
            throw new IOException("Unsupported image extension");
        }
        return extension;
    }

    public static Dimensions image(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0 || Files.size(file) > MAX_BYTES) {
            throw new IOException("Image file exceeds size limits or is absent");
        }
        try (var input = new FileImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Invalid image bytes");
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!Set.of("png", "jpeg", "jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new IOException("Unsupported encoded image format");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 10_000 || height > 10_000
                    || (long) width * height > MAX_PIXELS) {
                    throw new IOException("Image pixel dimensions exceed limits");
                }
                var decoded = reader.read(0);
                if (decoded == null) {
                    throw new IOException("Image cannot be decoded");
                }
                decoded.flush();
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    public static void geometry(List<TemplateArea> areas, Dimensions dimensions) throws IOException {
        if (areas == null || areas.isEmpty() || areas.size() > MAX_AREAS) {
            throw new IOException("Template must have between 1 and 128 areas");
        }
        Set<Integer> slots = areas.stream().map(TemplateArea::getSource).collect(Collectors.toSet());
        for (int slot = 1; slot <= slots.size(); slot++) {
            if (!slots.contains(slot)) {
                throw new IOException("Source slots must be positive and contiguous");
            }
        }
        for (int i = 0; i < areas.size(); i++) {
            TemplateArea area = areas.get(i);
            if (area.getIndex() != i + 1) {
                throw new IOException("Area indices must be ordered, unique and contiguous");
            }
            AreaCorner[] corners = {area.getTopLeft(), area.getTopRight(), area.getBottomRight(), area.getBottomLeft()};
            validateCorners(corners);
            if (dimensions != null && !overlapsCanvas(corners, dimensions)) {
                throw new IOException("Template area must overlap the image with positive area");
            }
        }
    }

    private static void validateCorners(AreaCorner[] corners) throws IOException {
        for (AreaCorner corner : corners) {
            if (corner == null || Math.abs((long) corner.getX()) > 10_000 || Math.abs((long) corner.getY()) > 10_000) {
                throw new IOException("Template corner is absent or exceeds coordinate limits");
            }
        }
        long direction = 0;
        for (int j = 0; j < corners.length; j++) {
            AreaCorner a = corners[j];
            AreaCorner b = corners[(j + 1) % corners.length];
            AreaCorner c = corners[(j + 2) % corners.length];
            long cross = ((long) b.getX() - a.getX()) * (c.getY() - b.getY())
                - ((long) b.getY() - a.getY()) * (c.getX() - b.getX());
            if (cross == 0 || (direction != 0 && Long.signum(cross) != direction)) {
                throw new IOException("Template area must be a nondegenerate convex quadrilateral");
            }
            direction = Long.signum(cross);
        }
    }

    private static boolean overlapsCanvas(AreaCorner[] corners, Dimensions dimensions) {
        if (dimensions.width() <= 0 || dimensions.height() <= 0) return false;
        AreaCorner[] canvas = {new AreaCorner(0, 0), new AreaCorner(dimensions.width(), 0),
            new AreaCorner(dimensions.width(), dimensions.height()), new AreaCorner(0, dimensions.height())};
        if (separated(corners, canvas, 1, 0) || separated(corners, canvas, 0, 1)) return false;
        // Separating-axis theorem for two convex polygons; touching alone is not visible area.
        for (int i = 0; i < corners.length; i++) {
            AreaCorner a = corners[i];
            AreaCorner b = corners[(i + 1) % corners.length];
            if (separated(corners, canvas, (long) a.getY() - b.getY(), (long) b.getX() - a.getX())) return false;
        }
        return true;
    }

    private static boolean separated(AreaCorner[] polygon, AreaCorner[] canvas, long x, long y) {
        long polygonMin = Long.MAX_VALUE;
        long polygonMax = Long.MIN_VALUE;
        long canvasMin = Long.MAX_VALUE;
        long canvasMax = Long.MIN_VALUE;
        for (AreaCorner corner : polygon) {
            long projection = x * corner.getX() + y * corner.getY();
            polygonMin = Math.min(polygonMin, projection);
            polygonMax = Math.max(polygonMax, projection);
        }
        for (AreaCorner corner : canvas) {
            long projection = x * corner.getX() + y * corner.getY();
            canvasMin = Math.min(canvasMin, projection);
            canvasMax = Math.max(canvasMax, projection);
        }
        return polygonMax <= canvasMin || canvasMax <= polygonMin;
    }
}