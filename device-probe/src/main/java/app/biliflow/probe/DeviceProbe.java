package app.biliflow.probe;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Xml;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.xmlpull.v1.XmlSerializer;
import java.io.File;
import java.io.FileOutputStream;

/** A separate test APK: UI snapshots do not restart the BiliFlow process or wait for video to be idle. */
public final class DeviceProbe extends Instrumentation {
    private Bundle arguments;
    private File directory;
    private UiAutomation automation;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            directory = getContext().getExternalFilesDir(null);
            if (directory == null) throw new IllegalStateException("No probe output directory");
            directory.mkdirs();
            automation = getUiAutomation();
            android.accessibilityservice.AccessibilityServiceInfo service = automation.getServiceInfo();
            service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(service);
            if ("gesture".equals(arguments.getString("op"))) gesture();
            else if ("fade".equals(arguments.getString("op"))) fadeFrames();
            else if ("home".equals(arguments.getString("op"))) fadeFrames();
            else if ("homeswipeframes".equals(arguments.getString("op"))) homeSwipeFrames();
            else if ("tapframes".equals(arguments.getString("op"))) tapFrames();
            else if ("tapfastframes".equals(arguments.getString("op"))) tapFastFrames();
            else if ("drag".equals(arguments.getString("op"))) drag();
            else if ("dragframes".equals(arguments.getString("op"))) dragFrames();
            else if ("dragliveframes".equals(arguments.getString("op"))) dragLiveFrames();
            else if ("press".equals(arguments.getString("op"))) {
                long down = SystemClock.uptimeMillis();
                float x = Integer.parseInt(arguments.getString("x", "360")); float y = Integer.parseInt(arguments.getString("y", "350"));
                event(down, MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(Integer.parseInt(arguments.getString("hold", "150")));
                capture("press-hold.png"); event(down, MotionEvent.ACTION_UP, x, y);
            }
            else {
                AccessibilityNodeInfo root = appRoot();
                if (root == null) { SystemClock.sleep(250); root = appRoot(); }
                if (root == null) throw new IllegalStateException("No active UI");
                try (FileOutputStream stream = new FileOutputStream(new File(directory, "window.xml"))) {
                    XmlSerializer xml = Xml.newSerializer(); xml.setOutput(stream, "UTF-8"); xml.startDocument("UTF-8", true);
                    xml.startTag(null, "hierarchy"); dump(root, xml); xml.endTag(null, "hierarchy"); xml.endDocument();
                }
                if ("true".equals(arguments.getString("screenshot"))) capture("screen.png");
            }
            result.putString("result", "ok"); finish(0, result);
        } catch (Exception error) { result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage()); finish(1, result); }
    }
    private AccessibilityNodeInfo appRoot() {
        AccessibilityNodeInfo active = automation.getRootInActiveWindow();
        if (active != null && "app.biliflow".contentEquals(active.getPackageName() == null ? "" : active.getPackageName())) return active;
        for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && "app.biliflow".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return root;
        }
        return active;
    }
    private void dump(AccessibilityNodeInfo node, XmlSerializer xml) throws Exception {
        xml.startTag(null, "node");
        attribute(xml, "text", node.isPassword() ? "" : node.getText());
        attribute(xml, "content-desc", node.getContentDescription()); attribute(xml, "class", node.getClassName());
        attribute(xml, "resource-id", node.getViewIdResourceName());
        xml.attribute(null, "clickable", Boolean.toString(node.isClickable())); xml.attribute(null, "selected", Boolean.toString(node.isSelected()));
        xml.attribute(null, "checkable", Boolean.toString(node.isCheckable())); xml.attribute(null, "checked", Boolean.toString(node.isChecked()));
        xml.attribute(null, "enabled", Boolean.toString(node.isEnabled())); xml.attribute(null, "visible", Boolean.toString(node.isVisibleToUser()));
        Rect rect = new Rect(); node.getBoundsInScreen(rect); xml.attribute(null, "bounds", rect.flattenToString());
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null && child.isVisibleToUser()) dump(child, xml); }
        xml.endTag(null, "node");
    }
    private void attribute(XmlSerializer xml, String key, CharSequence value) throws Exception {
        String text = value == null ? "" : value.toString();
        xml.attribute(null, key, text.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", ""));
    }
    private void capture(String name) throws Exception {
        Bitmap image = automation.takeScreenshot();
        if (image == null) throw new IllegalStateException("Screenshot unavailable");
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name))) { image.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        image.recycle();
    }
    private void event(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN); automation.injectInputEvent(event, true); event.recycle();
    }
    private void gesture() throws Exception {
        float y = Integer.parseInt(arguments.getString("y", "750")); float end = Integer.parseInt(arguments.getString("x", "300"));
        long down = SystemClock.uptimeMillis(); event(down, MotionEvent.ACTION_DOWN, 1, y);
        for (int i = 1; i <= 20; i++) { event(down, MotionEvent.ACTION_MOVE, 1 + (end - 1) * i / 20f, y); SystemClock.sleep(16); }
        capture("back-progress.png");
        boolean cancel = "true".equals(arguments.getString("cancel"));
        if (cancel) for (int i = 1; i <= 16; i++) { event(down, MotionEvent.ACTION_MOVE, end - (end - 1) * i / 16f, y); SystemClock.sleep(16); }
        event(down, MotionEvent.ACTION_UP, cancel ? 1 : end, y);
        for (int i = 0; i < 12; i++) { capture("back-release-" + i + ".png"); SystemClock.sleep(16); }
    }
    private void drag() throws Exception {
        float x = Integer.parseInt(arguments.getString("x", "360"));
        float from = Integer.parseInt(arguments.getString("from", "500"));
        float to = Integer.parseInt(arguments.getString("to", "300"));
        long down = SystemClock.uptimeMillis();
        capture("drag-before.png"); event(down, MotionEvent.ACTION_DOWN, x, from);
        for (int i = 1; i <= 24; i++) { event(down, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 24f); SystemClock.sleep(16); }
        SystemClock.sleep(80); capture("drag-held.png");
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root != null) try (FileOutputStream stream = new FileOutputStream(new File(directory, "drag-held.xml"))) {
            XmlSerializer xml = Xml.newSerializer(); xml.setOutput(stream, "UTF-8"); xml.startDocument("UTF-8", true); xml.startTag(null, "hierarchy"); dump(root, xml); xml.endTag(null, "hierarchy"); xml.endDocument();
        }
        event(down, MotionEvent.ACTION_UP, x, to);
        for (int i = 0; i < 12; i++) { capture("drag-release-" + i + ".png"); SystemClock.sleep(16); }
    }
    private void tapFrames() throws Exception {
        float x = Integer.parseInt(arguments.getString("x", "360")); float y = Integer.parseInt(arguments.getString("y", "700"));
        long down = SystemClock.uptimeMillis(); capture("entry-before.png");
        event(down, MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(40); event(down, MotionEvent.ACTION_UP, x, y);
        for (int i = 0; i < 18; i++) {
            capture("entry-frame-" + i + ".png");
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) try (FileOutputStream stream = new FileOutputStream(new File(directory, "entry-frame-" + i + ".xml"))) {
                XmlSerializer xml = Xml.newSerializer(); xml.setOutput(stream, "UTF-8"); xml.startDocument("UTF-8", true); xml.startTag(null, "hierarchy");
                AccessibilityNodeInfo flight = findFlight(root); if (flight != null) dump(flight, xml);
                xml.endTag(null, "hierarchy"); xml.endDocument();
            }
            SystemClock.sleep(16);
        }
    }
    private void tapFastFrames() throws Exception {
        float x = Integer.parseInt(arguments.getString("x", "360")); float y = Integer.parseInt(arguments.getString("y", "700"));
        long down = SystemClock.uptimeMillis();
        event(down, MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(40); event(down, MotionEvent.ACTION_UP, x, y);
        java.util.ArrayList<Bitmap> frames = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) { Bitmap frame = automation.takeScreenshot(); if (frame != null) frames.add(frame); SystemClock.sleep(8); }
            for (int i = 0; i < frames.size(); i++) try (FileOutputStream stream = new FileOutputStream(new File(directory, "tap-fast-" + i + ".png"))) { frames.get(i).compress(Bitmap.CompressFormat.PNG, 100, stream); }
        } finally { for (Bitmap frame : frames) frame.recycle(); }
    }
    private void dragLiveFrames() throws Exception {
        final float x = Integer.parseInt(arguments.getString("x", "360"));
        final float from = Integer.parseInt(arguments.getString("from", "500"));
        final float to = Integer.parseInt(arguments.getString("to", "300"));
        Thread gesture = new Thread(() -> {
            SystemClock.sleep(80); long down = SystemClock.uptimeMillis();
            event(down, MotionEvent.ACTION_DOWN, x, from);
            for (int i = 1; i <= 32; i++) { event(down, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 32f); SystemClock.sleep(16); }
            event(down, MotionEvent.ACTION_UP, x, to);
        });
        java.util.ArrayList<Bitmap> frames = new java.util.ArrayList<>();
        gesture.start();
        try {
            for (int i = 0; i < 40; i++) { Bitmap frame = automation.takeScreenshot(); if (frame != null) frames.add(frame); SystemClock.sleep(8); }
            gesture.join();
            for (int i = 0; i < frames.size(); i++) try (FileOutputStream stream = new FileOutputStream(new File(directory, "drag-live-" + i + ".png"))) { frames.get(i).compress(Bitmap.CompressFormat.PNG, 100, stream); }
        } finally { for (Bitmap frame : frames) frame.recycle(); }
    }
    private void dragFrames() throws Exception {
        float x = Integer.parseInt(arguments.getString("x", "360"));
        float from = Integer.parseInt(arguments.getString("from", "500"));
        float to = Integer.parseInt(arguments.getString("to", "300"));
        long down = SystemClock.uptimeMillis();
        event(down, MotionEvent.ACTION_DOWN, x, from);
        for (int i = 1; i <= 24; i++) { event(down, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 24f); SystemClock.sleep(16); }
        event(down, MotionEvent.ACTION_UP, x, to);
        java.util.ArrayList<Bitmap> frames = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) { Bitmap frame = automation.takeScreenshot(); if (frame == null) throw new IllegalStateException("Screenshot unavailable"); frames.add(frame); SystemClock.sleep(8); }
            for (int i = 0; i < frames.size(); i++) try (FileOutputStream stream = new FileOutputStream(new File(directory, "drag-fast-" + i + ".png"))) { frames.get(i).compress(Bitmap.CompressFormat.PNG, 100, stream); }
        } finally { for (Bitmap frame : frames) frame.recycle(); }
    }
    private void homeSwipeFrames() throws Exception {
        final int width = Integer.parseInt(arguments.getString("width", "720"));
        final int height = Integer.parseInt(arguments.getString("height", "1600"));
        Thread gesture = new Thread(() -> {
            try {
                SystemClock.sleep(100);
                long down = SystemClock.uptimeMillis();
                event(down, MotionEvent.ACTION_DOWN, width / 2f, height - 4f);
                for (int i = 1; i <= 16; i++) {
                    event(down, MotionEvent.ACTION_MOVE, width / 2f, height - 4f - i * 25f);
                    SystemClock.sleep(12);
                }
                event(down, MotionEvent.ACTION_UP, width / 2f, height - 404f);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        gesture.start();
        java.util.ArrayList<Bitmap> frames = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 50; i++) {
                Bitmap frame = automation.takeScreenshot();
                if (frame == null) throw new IllegalStateException("Screenshot unavailable");
                frames.add(frame); SystemClock.sleep(8);
            }
            gesture.join();
            for (int i = 0; i < frames.size(); i++) try (FileOutputStream stream = new FileOutputStream(new File(directory, "homeswipe-" + i + ".png"))) {
                frames.get(i).compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
        } finally { for (Bitmap frame : frames) frame.recycle(); }
    }
    /** Sample visual fades without querying accessibility (which can stall Compose). */
    private void fadeFrames() throws Exception {
        float x = Integer.parseInt(arguments.getString("x", "25")); float y = Integer.parseInt(arguments.getString("y", "350"));
        capture("fade-before.png");
        if ("home".equals(arguments.getString("op"))) {
            automation.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HOME), true);
            automation.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HOME), true);
        } else {
            long down = SystemClock.uptimeMillis(); event(down, MotionEvent.ACTION_DOWN, x, y);
            SystemClock.sleep(30); event(down, MotionEvent.ACTION_UP, x, y);
        }
        java.util.ArrayList<Bitmap> frames = new java.util.ArrayList<>();
        try {
            // PNG compression between samples misses short fades; collect first, encode later.
            for (int i = 0; i < 18; i++) {
                Bitmap frame = automation.takeScreenshot();
                if (frame == null) throw new IllegalStateException("Screenshot unavailable");
                frames.add(frame); SystemClock.sleep(8);
            }
            for (int i = 0; i < frames.size(); i++) try (FileOutputStream stream = new FileOutputStream(new File(directory, "fade-frame-" + i + ".png"))) {
                frames.get(i).compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
        } finally { for (Bitmap frame : frames) frame.recycle(); }
    }
    private AccessibilityNodeInfo findFlight(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName(); if (id != null && id.startsWith("cover_flight_")) return node;
        for (int i = node.getChildCount() - 1; i >= 0; i--) { AccessibilityNodeInfo child = node.getChild(i); if (child != null && child.isVisibleToUser()) { AccessibilityNodeInfo found = findFlight(child); if (found != null) return found; } }
        return null;
    }
}
