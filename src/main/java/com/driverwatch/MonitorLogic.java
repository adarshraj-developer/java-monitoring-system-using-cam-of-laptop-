package com.driverwatch;

/** Pure Java alert timing logic. Camera and detector are deliberately separate. */
public final class MonitorLogic {
    public static final long FACE_ALERT_MS = 3_500;
    public static final long EYES_ALERT_MS = 2_500;

    public enum Status {
        NORMAL("NORMAL", "Face and eyes are visible", false),
        NO_FACE("FACE NOT VISIBLE", "Move into the webcam's view", false),
        NO_EYES("EYES NOT VISIBLE", "Adjust lighting or face the camera", false),
        FACE_ALERT("FACE VISIBILITY ALERT", "Driver's face has been missing", true),
        EYE_ALERT("POSSIBLE DROWSINESS", "Eyes not detected for several seconds", true);

        public final String label;
        public final String explanation;
        public final boolean warning;

        Status(String label, String explanation, boolean warning) {
            this.label = label;
            this.explanation = explanation;
            this.warning = warning;
        }
    }

    public record Result(Status status, boolean enteredWarning, long elapsedMillis) {}

    private long missingFaceSince = -1;
    private long missingEyesSince = -1;
    private Status previousStatus = Status.NO_FACE;
    private int alertCount;

    public synchronized Result update(long timestampMillis, boolean faceVisible, boolean eyesVisible) {
        Status status;
        long duration = 0;
        if (!faceVisible) {
            missingEyesSince = -1;
            if (missingFaceSince < 0) missingFaceSince = timestampMillis;
            duration = Math.max(0, timestampMillis - missingFaceSince);
            status = duration >= FACE_ALERT_MS ? Status.FACE_ALERT : Status.NO_FACE;
        } else {
            missingFaceSince = -1;
            if (eyesVisible) {
                missingEyesSince = -1;
                status = Status.NORMAL;
            } else {
                if (missingEyesSince < 0) missingEyesSince = timestampMillis;
                duration = Math.max(0, timestampMillis - missingEyesSince);
                status = duration >= EYES_ALERT_MS ? Status.EYE_ALERT : Status.NO_EYES;
            }
        }
        boolean entered = status.warning && previousStatus != status;
        if (entered) alertCount++;
        previousStatus = status;
        return new Result(status, entered, duration);
    }

    public synchronized int alertCount() { return alertCount; }

    public synchronized void reset() {
        missingFaceSince = -1;
        missingEyesSince = -1;
        previousStatus = Status.NO_FACE;
        alertCount = 0;
    }
}
