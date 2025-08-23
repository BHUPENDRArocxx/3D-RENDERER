import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class DemoViewer {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("3D Demo Viewer");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            Container pane = frame.getContentPane();
            pane.setLayout(new BorderLayout());

            // Slider to control horizontal rotation (heading / yaw)
            JSlider headingSlider = new JSlider(0, 360, 180);
            headingSlider.setMajorTickSpacing(90);
            headingSlider.setPaintTicks(true);
            headingSlider.setPaintLabels(true);
            pane.add(headingSlider, BorderLayout.SOUTH);

            // Slider to control vertical rotation (pitch)
            JSlider pitchSlider = new JSlider(SwingConstants.VERTICAL, -90, 90, 0);
            pitchSlider.setMajorTickSpacing(45);
            pitchSlider.setPaintTicks(true);
            pitchSlider.setPaintLabels(true);
            pane.add(pitchSlider, BorderLayout.EAST);

            // Render panel
            JPanel renderPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g;

                    int W = getWidth();
                    int H = getHeight();

                    // Background
                    g2.setColor(Color.BLACK);
                    g2.fillRect(0, 0, W, H);

                    // Define 3D object (a tetrahedron)
                    List<Triangle> tris = new ArrayList<>();
                    tris.add(new Triangle(new Vertex(100, 100, 100),
                                          new Vertex(-100, -100, 100),
                                          new Vertex(-100, 100, -100),
                                          Color.WHITE));
                    tris.add(new Triangle(new Vertex(100, 100, 100),
                                          new Vertex(-100, -100, 100),
                                          new Vertex(100, -100, -100),
                                          Color.RED));
                    tris.add(new Triangle(new Vertex(-100, 100, -100),
                                          new Vertex(100, -100, -100),
                                          new Vertex(100, 100, 100),
                                          Color.GREEN));
                    tris.add(new Triangle(new Vertex(-100, 100, -100),
                                          new Vertex(100, -100, -100),
                                          new Vertex(-100, -100, 100),
                                          Color.BLUE));

                    // --- Rotation from sliders ---
                    double heading = Math.toRadians(headingSlider.getValue());
                    double pitch = Math.toRadians(pitchSlider.getValue());

                    Matrix3 headingTransform = new Matrix3(new double[]{
                            Math.cos(heading), 0, Math.sin(heading),
                            0, 1, 0,
                            -Math.sin(heading), 0, Math.cos(heading)
                    });

                    Matrix3 pitchTransform = new Matrix3(new double[]{
                            1, 0, 0,
                            0, Math.cos(pitch), -Math.sin(pitch),
                            0, Math.sin(pitch), Math.cos(pitch)
                    });

                    Matrix3 transform = headingTransform.multiply(pitchTransform);

                    // Offscreen buffer for software rasterization
                    BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
                    double[] zBuffer = new double[W * H];
                    for (int i = 0; i < zBuffer.length; i++) {
                        zBuffer[i] = Double.NEGATIVE_INFINITY;
                    }

                    // Light direction (camera space), normalized
                    Vertex lightDir = normalize(new Vertex(0, 0, 1));

                    // Perspective parameters
                    double cameraZOffset = 400.0; // push object in front of camera
                    // (bigger = less perspective distortion)

                    // Rasterize each triangle
                    for (Triangle t : tris) {
                        // Transform to camera space
                        Vertex tv1 = transform.transform(t.v1);
                        Vertex tv2 = transform.transform(t.v2);
                        Vertex tv3 = transform.transform(t.v3);

                        // Compute face normal in camera space
                        Vertex ab = new Vertex(tv2.x - tv1.x, tv2.y - tv1.y, tv2.z - tv1.z);
                        Vertex ac = new Vertex(tv3.x - tv1.x, tv3.y - tv1.y, tv3.z - tv1.z);
                        Vertex norm = cross(ab, ac);
                        norm = normalize(norm);

                        // Simple Lambert: intensity = max(0, dot(normal, lightDir))
                        double intensity = Math.max(0, dot(norm, lightDir));

                        // Optional backface culling (uncomment if you want)
                        // if (dot(norm, new Vertex(0, 0, 1)) <= 0) continue;

                        // Perspective projection to screen space
                        Vertex pv1 = projectToScreen(tv1, cameraZOffset, W, H);
                        Vertex pv2 = projectToScreen(tv2, cameraZOffset, W, H);
                        Vertex pv3 = projectToScreen(tv3, cameraZOffset, W, H);

                        // Bounding box
                        int minX = (int) Math.max(0, Math.ceil(Math.min(pv1.x, Math.min(pv2.x, pv3.x))));
                        int maxX = (int) Math.min(W - 1, Math.floor(Math.max(pv1.x, Math.max(pv2.x, pv3.x))));
                        int minY = (int) Math.max(0, Math.ceil(Math.min(pv1.y, Math.min(pv2.y, pv3.y))));
                        int maxY = (int) Math.min(H - 1, Math.floor(Math.max(pv1.y, Math.max(pv2.y, pv3.y))));

                        // Triangle area (for barycentric)
                        double triArea = edgeFunction(pv1, pv2, pv3);

                        if (triArea == 0) continue; // degenerate

                        // Shade color
                        Color shade = getShade(t.color, intensity);

                        // Rasterize with barycentric coords + z-buffer
                        for (int y = minY; y <= maxY; y++) {
                            for (int x = minX; x <= maxX; x++) {
                                Vertex p = new Vertex(x + 0.5, y + 0.5, 0);

                                double w1 = edgeFunction(pv2, pv3, p) / triArea;
                                double w2 = edgeFunction(pv3, pv1, p) / triArea;
                                double w3 = edgeFunction(pv1, pv2, p) / triArea;

                                if (w1 >= 0 && w2 >= 0 && w3 >= 0) {
                                    // Interpolate depth using camera-space z
                                    double depth = w1 * tv1.z + w2 * tv2.z + w3 * tv3.z;

                                    int zIndex = y * W + x;
                                    if (zBuffer[zIndex] < depth) {
                                        img.setRGB(x, y, shade.getRGB());
                                        zBuffer[zIndex] = depth;
                                    }
                                }
                            }
                        }
                    }

                    // Blit image to screen
                    g2.drawImage(img, 0, 0, null);
                }
            };

            pane.add(renderPanel, BorderLayout.CENTER);

            // Repaint when sliders move
            headingSlider.addChangeListener(e -> renderPanel.repaint());
            pitchSlider.addChangeListener(e -> renderPanel.repaint());

            frame.setSize(700, 700);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    // ---------- Math / helpers ----------

    static Vertex projectToScreen(Vertex v, double camZ, int W, int H) {
        // Simple perspective projection with a z-offset
        double z = v.z + camZ;
        double f = camZ / z; // focal ratio
        double sx = v.x * f + W / 2.0;
        double sy = -v.y * f + H / 2.0; // flip Y for screen coords
        return new Vertex(sx, sy, z);   // store z' if needed
    }

    static double edgeFunction(Vertex a, Vertex b, Vertex c) {
        // 2D cross product (area * 2)
        return (c.x - a.x) * (b.y - a.y) - (c.y - a.y) * (b.x - a.x);
    }

    static Vertex cross(Vertex a, Vertex b) {
        return new Vertex(
                a.y * b.z - a.z * b.y,
                a.z * b.x - a.x * b.z,
                a.x * b.y - a.y * b.x
        );
    }

    static double dot(Vertex a, Vertex b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }

    static Vertex normalize(Vertex v) {
        double len = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
        if (len == 0) return new Vertex(0, 0, 0);
        return new Vertex(v.x / len, v.y / len, v.z / len);
    }

    // Basic linear shade (keep this single version to avoid duplicate methods)
    static Color getShade(Color color, double shade) {
        shade = Math.max(0, Math.min(1, shade));
        int r = (int) (color.getRed() * shade);
        int g = (int) (color.getGreen() * shade);
        int b = (int) (color.getBlue() * shade);
        return new Color(r, g, b);
    }
}

// ---------- Data classes ----------

class Vertex {
    double x, y, z;
    Vertex(double x, double y, double z) {
        this.x = x; this.y = y; this.z = z;
    }
}

class Triangle {
    Vertex v1, v2, v3;
    Color color;
    Triangle(Vertex v1, Vertex v2, Vertex v3, Color color) {
        this.v1 = v1; this.v2 = v2; this.v3 = v3;
        this.color = color;
    }
}

class Matrix3 {
    double[] values; // row-major [0..8]
    Matrix3(double[] values) {
        if (values.length != 9) throw new IllegalArgumentException("Matrix3 needs 9 values");
        this.values = values;
    }
    Matrix3 multiply(Matrix3 other) {
        double[] r = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                double sum = 0;
                for (int i = 0; i < 3; i++) {
                    sum += this.values[row * 3 + i] * other.values[i * 3 + col];
                }
                r[row * 3 + col] = sum;
            }
        }
        return new Matrix3(r);
    }
    Vertex transform(Vertex in) {
        return new Vertex(
                in.x * values[0] + in.y * values[3] + in.z * values[6],
                in.x * values[1] + in.y * values[4] + in.z * values[7],
                in.x * values[2] + in.y * values[5] + in.z * values[8]
        );
    }
}
