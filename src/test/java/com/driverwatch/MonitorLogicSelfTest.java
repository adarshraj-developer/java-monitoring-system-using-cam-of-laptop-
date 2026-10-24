package com.driverwatch;

/** Standalone fast test: no camera, Maven, or OpenCV necessary. */
public final class MonitorLogicSelfTest {
    private static void expected(MonitorLogic.Status wanted, MonitorLogic.Result actual) {
        if (actual.status() != wanted) throw new AssertionError(wanted + " but got " + actual.status());
    }

    public static void main(String[] args) {
        MonitorLogic monitor = new MonitorLogic();
        expected(MonitorLogic.Status.NO_FACE, monitor.update(1000, false, false));
        expected(MonitorLogic.Status.NO_FACE, monitor.update(4499, false, false));
        MonitorLogic.Result missing = monitor.update(4500, false, false);
        expected(MonitorLogic.Status.FACE_ALERT, missing);
        if (!missing.enteredWarning() || monitor.alertCount() != 1) throw new AssertionError("Face alert count");
        if (monitor.update(5000, false, false).enteredWarning()) throw new AssertionError("Duplicate warning");
        expected(MonitorLogic.Status.NORMAL, monitor.update(6000, true, true));
        expected(MonitorLogic.Status.NO_EYES, monitor.update(7000, true, false));
        expected(MonitorLogic.Status.NO_EYES, monitor.update(9499, true, false));
        MonitorLogic.Result eyes = monitor.update(9500, true, false);
        expected(MonitorLogic.Status.EYE_ALERT, eyes);
        if (!eyes.enteredWarning() || monitor.alertCount() != 2) throw new AssertionError("Eye alert count");
        expected(MonitorLogic.Status.NORMAL, monitor.update(10000, true, true));
        monitor.reset();
        if (monitor.alertCount() != 0) throw new AssertionError("Reset didn't clear alert count");
        System.out.println("All driver-monitor logic tests passed.");
    }
}
