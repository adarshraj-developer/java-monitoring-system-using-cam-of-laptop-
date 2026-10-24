package com.driverwatch;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;

/** Haar-cascade visibility detector. NOT a validated eyelid-closure or gaze detector. */
final class FaceDetector implements AutoCloseable {
    record Observation(Rectangle face, List<Rectangle> eyes) {
        boolean faceVisible() { return face != null; }
        boolean eyesVisible() { return !eyes.isEmpty(); }
    }

    private final CascadeClassifier faceDetector;
    private final CascadeClassifier eyeDetector;

    FaceDetector() throws Exception {
        faceDetector = new CascadeClassifier(
                ModelStore.ensure("haarcascade_frontalface_default.xml").toAbsolutePath().toString());
        eyeDetector = new CascadeClassifier(
                ModelStore.ensure("haarcascade_eye_tree_eyeglasses.xml").toAbsolutePath().toString());
        if (faceDetector.empty() || eyeDetector.empty()) {
            throw new IllegalStateException("OpenCV could not read one of the face/eye models.");
        }
    }

    Observation analyze(Mat bgrFrame) {
        try (Mat gray = new Mat(); RectVector detectedFaces = new RectVector()) {
            cvtColor(bgrFrame, gray, COLOR_BGR2GRAY);
            equalizeHist(gray, gray);
            faceDetector.detectMultiScale(gray, detectedFaces, 1.13, 5, 0,
                    new Size(85, 85), new Size());

            if (detectedFaces.size() == 0) return new Observation(null, List.of());

            // Use the largest face; don't treat random people in the background as the driver.
            Rect largest = detectedFaces.get(0);
            for (long i = 1; i < detectedFaces.size(); i++) {
                Rect candidate = detectedFaces.get(i);
                if (candidate.width() * candidate.height() > largest.width() * largest.height()) {
                    largest = candidate;
                }
            }
            Rectangle face = new Rectangle(largest.x(), largest.y(),
                    largest.width(), largest.height());

            // Eyes are searched only in the upper part of the detected face.
            int eyeHeight = Math.max(1, (int) (face.height * 0.58));
            List<Rectangle> eyes = new ArrayList<>();
            try (Mat eyeRoi = new Mat(gray,
                    new Rect(face.x, face.y, face.width, eyeHeight));
                 RectVector detectedEyes = new RectVector()) {
                eyeDetector.detectMultiScale(eyeRoi, detectedEyes, 1.1, 5, 0,
                        new Size(14, 14), new Size());
                for (long i = 0; i < Math.min(2, detectedEyes.size()); i++) {
                    Rect eye = detectedEyes.get(i);
                    eyes.add(new Rectangle(face.x + eye.x(), face.y + eye.y(),
                            eye.width(), eye.height()));
                }
            }
            return new Observation(face, eyes);
        }
    }

    @Override public void close() {
        faceDetector.close();
        eyeDetector.close();
    }
}
