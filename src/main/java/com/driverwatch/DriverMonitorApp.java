package com.driverwatch;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.bytedeco.opencv.global.opencv_core.flip;

/** Webcam-based driver visibility demo. Everything is processed on the laptop. */
public final class DriverMonitorApp extends JFrame {
    private static final Color PAGE = new Color(246, 248, 250);
    private static final Color TEXT = new Color(20, 30, 43);
    private static final Color MUTED = new Color(104, 116, 133);
    private static final Color GREEN = new Color(13, 152, 95);
    private static final Color RED = new Color(211, 59, 59);
    private static final Color AMBER = new Color(188, 120, 16);

    private final CameraPanel cameraPanel = new CameraPanel();
    private final JLabel statusLabel = label("CAMERA OFF", 19, TEXT, Font.BOLD);
    private final JLabel detailLabel = label("Click Start Camera to begin", 12, MUTED, Font.PLAIN);
    private final JLabel faceLabel = valueLabel();
    private final JLabel eyesLabel = valueLabel();
    private final JLabel durationLabel = valueLabel();
    private final JLabel countLabel = valueLabel();
    private final JTextArea events = new JTextArea();
    private final JButton startButton = button("Start Camera", GREEN, Color.WHITE);
    private final JButton stopButton = button("Stop Camera", new Color(227, 232, 238), TEXT);
    private final JCheckBox soundBox = new JCheckBox("Enable sound alerts", true);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<UiPacket> latest = new AtomicReference<>();
    private final MonitorLogic logic = new MonitorLogic();
    private volatile Thread worker;
    private volatile boolean cameraReady;
    private volatile boolean soundEnabled = true;
    private static int configuredCameraIndex = 0;
    private Timer uiTimer;

    private record UiPacket(BufferedImage image, FaceDetector.Observation observation,
                            MonitorLogic.Result result, int alertCount, long runTimeMs) {}

    public static void main(String[] args) {
        if (args.length > 0) {
            try {
                configuredCameraIndex = Math.max(0, Integer.parseInt(args[0]));
            } catch (NumberFormatException invalid) {
                System.err.println("Camera index must be a non-negative integer. Using webcam 0.");
            }
        }
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* default look and feel is also fine */ }
            new DriverMonitorApp().setVisible(true);
        });
    }

    private DriverMonitorApp() {
        super("DriverWatch | Java Driver Monitoring");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(1190, 765);
        setMinimumSize(new Dimension(920, 650));
        setLocationRelativeTo(null);
        getContentPane().setBackground(PAGE);
        setLayout(new BorderLayout(0, 18));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(22, 28, 0, 28));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(label("DRIVERWATCH", 26, TEXT, Font.BOLD));
        heading.add(Box.createVerticalStrut(4));
        heading.add(label("Live driver visibility monitoring  ·  Java + OpenCV", 13, MUTED, Font.PLAIN));
        header.add(heading, BorderLayout.WEST);
        JLabel privacy = label("LOCAL CAMERA PROCESSING  •  NO VIDEO UPLOAD", 11, GREEN, Font.BOLD);
        header.add(privacy, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout(19, 0));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(0, 28, 22, 28));

        JPanel left = new JPanel(new BorderLayout(0, 15));
        left.setOpaque(false);
        RoundedPanel videoCard = new RoundedPanel(Color.WHITE);
        videoCard.setLayout(new BorderLayout(0, 13));
        videoCard.setBorder(new EmptyBorder(14, 14, 14, 14));
        JLabel cameraTitle = label("Camera preview", 16, TEXT, Font.BOLD);
        videoCard.add(cameraTitle, BorderLayout.NORTH);
        videoCard.add(cameraPanel, BorderLayout.CENTER);
        JPanel cameraFooter = new JPanel(new BorderLayout());
        cameraFooter.setOpaque(false);
        cameraFooter.add(label("Face box = green   |   Eye boxes = blue", 11, MUTED, Font.PLAIN), BorderLayout.WEST);
        cameraFooter.add(label("WEBCAM " + configuredCameraIndex, 11, MUTED, Font.BOLD), BorderLayout.EAST);
        videoCard.add(cameraFooter, BorderLayout.SOUTH);
        left.add(videoCard, BorderLayout.CENTER);

        RoundedPanel controls = new RoundedPanel(Color.WHITE);
        controls.setLayout(new FlowLayout(FlowLayout.LEFT, 12, 11));
        controls.add(startButton);
        controls.add(stopButton);
        controls.add(Box.createHorizontalStrut(5));
        soundBox.setBackground(Color.WHITE);
        soundBox.setForeground(TEXT);
        soundBox.setFont(new Font("SansSerif", Font.PLAIN, 12));
        controls.add(soundBox);
        soundBox.addActionListener(e -> soundEnabled = soundBox.isSelected());
        stopButton.setEnabled(false);
        startButton.addActionListener(e -> startMonitoring());
        stopButton.addActionListener(e -> stopMonitoring());
        left.add(controls, BorderLayout.SOUTH);
        content.add(left, BorderLayout.CENTER);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setPreferredSize(new Dimension(325, 550));

        RoundedPanel statusCard = new RoundedPanel(Color.WHITE);
        statusCard.setLayout(new BoxLayout(statusCard, BoxLayout.Y_AXIS));
        statusCard.setBorder(new EmptyBorder(20, 19, 18, 19));
        JLabel statusTitle = label("DRIVER CONDITION", 11, MUTED, Font.BOLD);
        statusTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        right.add(statusCard);
        statusCard.add(statusTitle);
        statusCard.add(Box.createVerticalStrut(14));
        statusCard.add(statusLabel);
        statusCard.add(Box.createVerticalStrut(7));
        statusCard.add(detailLabel);
        statusCard.add(Box.createVerticalStrut(10));
        right.add(Box.createVerticalStrut(14));

        RoundedPanel infoCard = new RoundedPanel(Color.WHITE);
        infoCard.setLayout(new BoxLayout(infoCard, BoxLayout.Y_AXIS));
        infoCard.setBorder(new EmptyBorder(17, 19, 17, 19));
        infoCard.add(infoRow("Face", faceLabel));
        infoCard.add(Box.createVerticalStrut(15));
        infoCard.add(infoRow("Eye visibility", eyesLabel));
        infoCard.add(Box.createVerticalStrut(15));
        infoCard.add(infoRow("Session time", durationLabel));
        infoCard.add(Box.createVerticalStrut(15));
        infoCard.add(infoRow("Alerts triggered", countLabel));
        right.add(infoCard);
        right.add(Box.createVerticalStrut(14));

        RoundedPanel eventCard = new RoundedPanel(Color.WHITE);
        eventCard.setLayout(new BorderLayout(0, 10));
        eventCard.setBorder(new EmptyBorder(16, 17, 14, 17));
        eventCard.add(label("EVENT LOG", 11, MUTED, Font.BOLD), BorderLayout.NORTH);
        events.setEditable(false);
        events.setLineWrap(true);
        events.setWrapStyleWord(true);
        events.setText("No alerts yet.\n");
        events.setFont(new Font("SansSerif", Font.PLAIN, 12));
        events.setForeground(MUTED);
        events.setBackground(Color.WHITE);
        JScrollPane scroll = new JScrollPane(events);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        eventCard.add(scroll, BorderLayout.CENTER);
        right.add(eventCard);
        right.add(Box.createVerticalGlue());
        content.add(right, BorderLayout.EAST);
        add(content, BorderLayout.CENTER);

        faceLabel.setText("—");
        eyesLabel.setText("—");
        durationLabel.setText("00:00");
        countLabel.setText("0");

        // Consume at most the latest result so the Swing event queue never floods with video frames.
        uiTimer = new Timer(80, e -> applyLatest());
        uiTimer.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                running.set(false);
                uiTimer.stop();
            }
        });
    }

    private void startMonitoring() {
        if (worker != null && worker.isAlive()) return;
        logic.reset();
        latest.set(null);
        events.setText("No alerts yet.\n");
        cameraPanel.setFrame(null, false);
        cameraReady = false;
        running.set(true);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        setStatus("INITIALIZING", "Loading OpenCV models and webcam...", MUTED);
        faceLabel.setText("—");
        eyesLabel.setText("—");
        durationLabel.setText("00:00");
        countLabel.setText("0");
        worker = new Thread(this::captureLoop, "DriverWatch-Camera");
        worker.setDaemon(true);
        worker.start();
    }

    private void stopMonitoring() {
        running.set(false);
        stopButton.setEnabled(false);
        setStatus("STOPPING", "Releasing webcam...", MUTED);
    }

    private void captureLoop() {
        long start = System.nanoTime() / 1_000_000;
        long lastBeep = -10_000;
        int noFrames = 0;
        int cameraIndex = configuredCameraIndex;
        try (FaceDetector detector = new FaceDetector();
             OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(cameraIndex);
             OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
             Java2DFrameConverter imageConverter = new Java2DFrameConverter()) {
            if (!running.get()) return;
            grabber.setImageWidth(640);
            grabber.setImageHeight(480);
            grabber.setFrameRate(20);
            grabber.start();
            cameraReady = true;

            while (running.get()) {
                Frame frame = grabber.grabImage();
                if (frame == null) {
                    if (++noFrames > 40) throw new IllegalStateException("Camera stopped providing video frames.");
                    Thread.sleep(50);
                    continue;
                }
                noFrames = 0;
                Mat mat = matConverter.convert(frame);
                if (mat == null || mat.empty()) continue;
                flip(mat, mat, 1); // selfie-style mirror; all detections use these mirrored coordinates.

                FaceDetector.Observation observation = detector.analyze(mat);
                long now = System.nanoTime() / 1_000_000;
                MonitorLogic.Result result = logic.update(now,
                        observation.faceVisible(), observation.eyesVisible());
                if (result.enteredWarning()) {
                    SwingUtilities.invokeLater(() -> appendEvent(result.status().label));
                }
                if (result.status().warning && soundEnabled && now - lastBeep >= 3_000) {
                    Toolkit.getDefaultToolkit().beep();
                    lastBeep = now;
                }

                BufferedImage current = imageConverter.convert(frame);
                if (current == null) continue;
                BufferedImage safe = Java2DFrameConverter.cloneBufferedImage(current);
                overlay(safe, observation, result.status());
                latest.set(new UiPacket(safe, observation, result, logic.alertCount(), now - start));
            }
        } catch (Exception ex) {
            if (running.get()) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    setStatus("CAMERA ERROR", message, RED);
                    JOptionPane.showMessageDialog(this,
                            "Could not start monitoring:\n\n" + message
                            + "\n\nCheck camera permissions, camera index, and internet access for first-run models.",
                            "DriverWatch error", JOptionPane.ERROR_MESSAGE);
                });
            }
        } finally {
            running.set(false);
            cameraReady = false;
            latest.set(null);
            SwingUtilities.invokeLater(() -> {
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                cameraPanel.setFrame(null, false);
                if (!statusLabel.getText().equals("CAMERA ERROR")) {
                    setStatus("CAMERA OFF", "Monitoring stopped", MUTED);
                }
            });
        }
    }

    private void applyLatest() {
        UiPacket packet = latest.getAndSet(null);
        if (packet == null || !running.get()) return;
        cameraPanel.setFrame(packet.image(), cameraReady);
        MonitorLogic.Status status = packet.result().status();
        Color color = status.warning ? RED : status == MonitorLogic.Status.NORMAL ? GREEN : AMBER;
        setStatus(status.label, status.explanation, color);
        faceLabel.setText(packet.observation().faceVisible() ? "Detected" : "Not visible");
        eyesLabel.setText(!packet.observation().faceVisible() ? "—" :
                packet.observation().eyesVisible() ? "Detected" : "Not detected");
        long seconds = packet.runTimeMs() / 1_000;
        durationLabel.setText(String.format("%02d:%02d", seconds / 60, seconds % 60));
        countLabel.setText(Integer.toString(packet.alertCount()));
    }

    private void setStatus(String headline, String subheading, Color color) {
        statusLabel.setText(headline);
        statusLabel.setForeground(color);
        detailLabel.setText("<html><div style='width:250px'>" + escape(subheading) + "</div></html>");
    }

    private static String escape(String input) {
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void appendEvent(String description) {
        if (events.getText().startsWith("No alerts")) events.setText("");
        events.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + "  ·  " + description + "\n");
        events.setCaretPosition(events.getDocument().getLength());
    }

    private static void overlay(BufferedImage image, FaceDetector.Observation info, MonitorLogic.Status status) {
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(3f));
            if (info.face() != null) {
                g.setColor(status.warning ? RED : GREEN);
                Rectangle r = info.face();
                g.drawRoundRect(r.x, r.y, r.width, r.height, 16, 16);
                g.setColor(new Color(49, 159, 235));
                for (Rectangle eye : info.eyes()) g.drawRoundRect(eye.x, eye.y, eye.width, eye.height, 7, 7);
            }
        } finally { g.dispose(); }
    }

    private static JLabel label(String text, int size, Color color, int weight) {
        JLabel result = new JLabel(text);
        result.setForeground(color);
        result.setFont(new Font("SansSerif", weight, size));
        return result;
    }

    private static JLabel valueLabel() { return label("—", 12, TEXT, Font.BOLD); }

    private static JPanel infoRow(String title, JLabel value) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 23));
        row.add(label(title, 12, MUTED, Font.PLAIN), BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private static JButton button(String text, Color bg, Color fg) {
        JButton b = new JButton(text);
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setBackground(bg);
        b.setForeground(fg);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(11, 18, 11, 18));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static final class RoundedPanel extends JPanel {
        private final Color background;
        RoundedPanel(Color background) { this.background = background; setOpaque(false); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(background);
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 20, 20));
            g.dispose();
            super.paintComponent(g0);
        }
    }

    private static final class CameraPanel extends JPanel {
        private BufferedImage frame;
        private boolean live;
        CameraPanel() {
            setOpaque(true);
            setBackground(new Color(18, 27, 39));
            setPreferredSize(new Dimension(640, 470));
        }
        void setFrame(BufferedImage image, boolean isLive) {
            frame = image;
            live = isLive;
            repaint();
        }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (frame == null) {
                g.setFont(new Font("SansSerif", Font.BOLD, 22));
                g.setColor(new Color(222, 231, 241));
                String title = "CAMERA PREVIEW";
                int x = (getWidth() - g.getFontMetrics().stringWidth(title)) / 2;
                g.drawString(title, x, getHeight() / 2 - 8);
                g.setColor(new Color(145, 159, 179));
                g.setFont(new Font("SansSerif", Font.PLAIN, 12));
                String hint = "Start monitoring to activate your webcam";
                g.drawString(hint, (getWidth() - g.getFontMetrics().stringWidth(hint)) / 2,
                        getHeight() / 2 + 22);
            } else {
                double scale = Math.min((double) getWidth() / frame.getWidth(),
                        (double) getHeight() / frame.getHeight());
                int width = (int) (frame.getWidth() * scale);
                int height = (int) (frame.getHeight() * scale);
                int x = (getWidth() - width) / 2;
                int y = (getHeight() - height) / 2;
                g.drawImage(frame, x, y, width, height, null);
                if (live) {
                    g.setColor(new Color(22, 174, 107));
                    g.fillRoundRect(13, 13, 69, 26, 9, 9);
                    g.setFont(new Font("SansSerif", Font.BOLD, 12));
                    g.setColor(Color.WHITE);
                    g.drawString("● LIVE", 24, 31);
                }
            }
            g.dispose();
        }
    }
}
