package com.example.ayaneooverlay;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;

/**
 * Root-side key injector launched by `su -c app_process`, like RootAudioBridge.
 * It injects key events into one display (the upper screen, display 0) so the
 * PocketHub keyboard on the lower screen can type into whatever is focused on top,
 * even when no text field has an input connection to the IME.
 *
 * Protocol: one JSON object per line on stdin, one JSON reply per line on stdout.
 *   {"cmd":"ping"}
 *   {"cmd":"key","code":67,"meta":0,"display":0}
 *   {"cmd":"text","text":"hello","display":0}   (only characters the virtual keyboard map can produce)
 *   {"cmd":"focus","display":0}   (give input focus to the top task of that display)
 *   {"cmd":"touch","action":"down|move|up","x":10,"y":20,"display":0}   (finger touch, used for click and drag)
 *   {"cmd":"rightclick","x":10,"y":20,"display":0}   (mouse secondary button)
 *   {"cmd":"scroll","x":10,"y":20,"v":1.0,"h":0.0,"display":0}   (mouse wheel)
 *   {"cmd":"stop"}
 */
public final class RootInputBridge {
    private static final int INJECT_ASYNC = 0;
    private static final int INJECT_WAIT_FOR_FINISH = 2;

    private static Object inputManager;
    private static Method injectMethod;
    private static Method setDisplayIdMethod;
    private static Method motionSetDisplayIdMethod;
    private static Method setActionButtonMethod;
    private static long touchDownTime;
    private static KeyCharacterMap keyMap;

    private RootInputBridge() { }

    public static void main(String[] args) {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter output = new PrintWriter(System.out, true);
        try {
            exemptHiddenApiReflection();
            connectInputManager();
            keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        } catch (Throwable fatal) {
            try {
                output.println(new JSONObject().put("ok", false).put("error",
                        "Root input helper initialization failed: " + fatal.getClass().getSimpleName() + ": " + fatal.getMessage()).toString());
            } catch (Throwable ignored) { }
            return;
        }

        String line;
        while (true) {
            try {
                line = input.readLine();
                if (line == null) break;
            } catch (Throwable ioError) { break; }
            boolean stop = false;
            JSONObject response;
            try {
                JSONObject request = new JSONObject(line);
                String command = request.optString("cmd", "");
                int display = request.optInt("display", 0);
                switch (command) {
                    case "ping":
                        response = new JSONObject().put("ok", true).put("result", "pong");
                        break;
                    case "key": {
                        boolean wait = request.optBoolean("wait", false);
                        boolean ok = injectKey(request.optInt("code", 0), request.optInt("meta", 0), display, wait);
                        response = new JSONObject().put("ok", ok);
                        break;
                    }
                    case "text": {
                        int unmapped = injectText(request.optString("text", ""), display);
                        response = new JSONObject().put("ok", unmapped == 0).put("unmapped", unmapped);
                        break;
                    }
                    case "focus":
                        response = new JSONObject().put("ok", focusDisplay(display));
                        break;
                    case "touch":
                        response = new JSONObject().put("ok", injectTouch(request.optString("action", ""),
                                (float) request.optDouble("x", 0), (float) request.optDouble("y", 0), display));
                        break;
                    case "rightclick":
                        response = new JSONObject().put("ok", injectRightClick(
                                (float) request.optDouble("x", 0), (float) request.optDouble("y", 0), display));
                        break;
                    case "scroll":
                        response = new JSONObject().put("ok", injectScroll(
                                (float) request.optDouble("x", 0), (float) request.optDouble("y", 0),
                                (float) request.optDouble("v", 0), (float) request.optDouble("h", 0), display));
                        break;
                    case "stop":
                        response = new JSONObject().put("ok", true).put("result", "stopping");
                        stop = true;
                        break;
                    default:
                        response = new JSONObject().put("ok", false).put("error", "Unknown command: " + command);
                }
            } catch (Throwable error) {
                try {
                    response = new JSONObject().put("ok", false).put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
                } catch (Throwable ignored) { response = new JSONObject(); }
            }
            output.println(response.toString());
            output.flush();
            if (stop) break;
        }
    }

    private static void exemptHiddenApiReflection() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method exemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            exemptions.setAccessible(true);
            exemptions.invoke(runtime, (Object) new String[] { "L" });
        } catch (Throwable ignored) {
            // A root process may already be allowed to use hidden APIs.
        }
    }

    private static void connectInputManager() throws Exception {
        // Android 13: InputManager.getInstance(); Android 14+: InputManagerGlobal.getInstance().
        Object manager = null;
        try {
            Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
            manager = global.getDeclaredMethod("getInstance").invoke(null);
        } catch (Throwable ignored) { }
        if (manager == null) {
            Class<?> legacy = Class.forName("android.hardware.input.InputManager");
            Method getInstance = legacy.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            manager = getInstance.invoke(null);
        }
        if (manager == null) throw new IllegalStateException("InputManager unavailable");
        inputManager = manager;
        injectMethod = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        injectMethod.setAccessible(true);
        try {
            setDisplayIdMethod = KeyEvent.class.getMethod("setDisplayId", int.class);
            setDisplayIdMethod.setAccessible(true);
        } catch (Throwable ignored) {
            setDisplayIdMethod = null;
        }
        try {
            motionSetDisplayIdMethod = MotionEvent.class.getMethod("setDisplayId", int.class);
            motionSetDisplayIdMethod.setAccessible(true);
        } catch (Throwable ignored) {
            motionSetDisplayIdMethod = null;
        }
        try {
            setActionButtonMethod = MotionEvent.class.getMethod("setActionButton", int.class);
            setActionButtonMethod.setAccessible(true);
        } catch (Throwable ignored) {
            setActionButtonMethod = null;
        }
    }

    private static MotionEvent motion(long downTime, int action, float x, float y, int toolType, int buttonState,
                                      int source, float vScroll, float hScroll) {
        MotionEvent.PointerProperties[] properties = { new MotionEvent.PointerProperties() };
        properties[0].id = 0;
        properties[0].toolType = toolType;
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_SCROLL) ? 0f : 1f;
        coords[0].size = 1f;
        if (vScroll != 0f) coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        if (hScroll != 0f) coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, properties, coords,
                0, buttonState, 1f, 1f, 0, 0, source, 0);
    }

    private static boolean injectMotion(MotionEvent event, int display) throws Exception {
        try {
            if (motionSetDisplayIdMethod != null) motionSetDisplayIdMethod.invoke(event, display);
            Object result = injectMethod.invoke(inputManager, event, INJECT_ASYNC);
            return !(result instanceof Boolean) || (Boolean) result;
        } finally {
            event.recycle();
        }
    }

    /** A finger on the touchscreen: what a left click and a left-button drag become. */
    private static boolean injectTouch(String action, float x, float y, int display) throws Exception {
        int code;
        switch (action) {
            case "down": code = MotionEvent.ACTION_DOWN; touchDownTime = SystemClock.uptimeMillis(); break;
            case "move": code = MotionEvent.ACTION_MOVE; break;
            case "up": code = MotionEvent.ACTION_UP; break;
            default: return false;
        }
        if (touchDownTime == 0) touchDownTime = SystemClock.uptimeMillis();
        boolean ok = injectMotion(motion(touchDownTime, code, x, y, MotionEvent.TOOL_TYPE_FINGER, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0f, 0f), display);
        if (code == MotionEvent.ACTION_UP) touchDownTime = 0;
        return ok;
    }

    /** Mouse secondary button press and release at the cursor (context menus, Chrome right click). */
    private static boolean injectRightClick(float x, float y, int display) throws Exception {
        int source = InputDevice.SOURCE_MOUSE;
        int secondary = MotionEvent.BUTTON_SECONDARY;
        long down = SystemClock.uptimeMillis();
        boolean ok = injectMotion(motion(down, MotionEvent.ACTION_HOVER_MOVE, x, y, MotionEvent.TOOL_TYPE_MOUSE, 0, source, 0f, 0f), display);
        ok &= injectMotion(motion(down, MotionEvent.ACTION_DOWN, x, y, MotionEvent.TOOL_TYPE_MOUSE, secondary, source, 0f, 0f), display);
        MotionEvent press = motion(down, MotionEvent.ACTION_BUTTON_PRESS, x, y, MotionEvent.TOOL_TYPE_MOUSE, secondary, source, 0f, 0f);
        if (setActionButtonMethod != null) setActionButtonMethod.invoke(press, secondary);
        ok &= injectMotion(press, display);
        MotionEvent release = motion(down, MotionEvent.ACTION_BUTTON_RELEASE, x, y, MotionEvent.TOOL_TYPE_MOUSE, 0, source, 0f, 0f);
        if (setActionButtonMethod != null) setActionButtonMethod.invoke(release, secondary);
        ok &= injectMotion(release, display);
        ok &= injectMotion(motion(down, MotionEvent.ACTION_UP, x, y, MotionEvent.TOOL_TYPE_MOUSE, 0, source, 0f, 0f), display);
        return ok;
    }

    /** Mouse wheel at the cursor; positive v scrolls up, positive h scrolls right. */
    private static boolean injectScroll(float x, float y, float v, float h, int display) throws Exception {
        long now = SystemClock.uptimeMillis();
        return injectMotion(motion(now, MotionEvent.ACTION_SCROLL, x, y, MotionEvent.TOOL_TYPE_MOUSE, 0,
                InputDevice.SOURCE_MOUSE, v, h), display);
    }

    private static boolean inject(KeyEvent event, int display, int mode) throws Exception {
        if (setDisplayIdMethod != null) setDisplayIdMethod.invoke(event, display);
        Object result = injectMethod.invoke(inputManager, event, mode);
        return !(result instanceof Boolean) || (Boolean) result;
    }

    private static boolean injectKey(int code, int meta, int display, boolean wait) throws Exception {
        if (code <= 0) return false;
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        boolean ok = inject(down, display, INJECT_ASYNC);
        ok &= inject(up, display, wait ? INJECT_WAIT_FOR_FINISH : INJECT_ASYNC);
        return ok;
    }

    /**
     * Key events only reach a display that owns the input focus. Tapping PocketHub on the
     * lower screen moves focus there, so before typing we hand focus back to the top task
     * of the target display (with per-display focus disabled, only one display has focus).
     */
    private static boolean focusDisplay(int display) throws Exception {
        Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
        Method getService = atmClass.getDeclaredMethod("getService");
        getService.setAccessible(true);
        Object service = getService.invoke(null);
        java.util.List<?> tasks = null;
        try {
            Method onDisplay = service.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class);
            tasks = asList(onDisplay.invoke(service, display));
        } catch (NoSuchMethodException ignored) { }
        if (tasks == null) {
            Method all = service.getClass().getMethod("getAllRootTaskInfos");
            tasks = asList(all.invoke(service));
        }
        Method setFocusedTask = service.getClass().getMethod("setFocusedTask", int.class);
        for (Object task : tasks) {
            Integer taskDisplay = intField(task, "displayId");
            if (taskDisplay != null && taskDisplay != display) continue;
            Boolean visible = boolField(task, "visible");
            if (visible == null) visible = boolField(task, "isVisible");
            if (visible != null && !visible) continue;
            Integer taskId = intField(task, "taskId");
            if (taskId == null || taskId < 0) continue;
            setFocusedTask.invoke(service, taskId);
            return true;
        }
        return false;
    }

    private static java.util.List<?> asList(Object value) throws Exception {
        if (value instanceof java.util.List<?>) return (java.util.List<?>) value;
        if (value == null) return new java.util.ArrayList<>();
        Method getList = value.getClass().getMethod("getList");
        Object list = getList.invoke(value);
        return list instanceof java.util.List<?> ? (java.util.List<?>) list : new java.util.ArrayList<>();
    }

    private static Integer intField(Object target, String name) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), name);
            return field == null ? null : field.getInt(target);
        } catch (Throwable ignored) { return null; }
    }

    private static Boolean boolField(Object target, String name) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), name);
            return field == null ? null : field.getBoolean(target);
        } catch (Throwable ignored) { return null; }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    /** Injects every character the virtual keyboard map can type; returns how many could not be mapped. */
    private static int injectText(String text, int display) throws Exception {
        int unmapped = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            KeyEvent[] events = keyMap.getEvents(new char[] { c });
            if (events == null) { unmapped++; continue; }
            for (KeyEvent original : events) {
                KeyEvent event = new KeyEvent(original.getDownTime(), original.getEventTime(), original.getAction(),
                        original.getKeyCode(), original.getRepeatCount(), original.getMetaState(),
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
                inject(event, display, INJECT_ASYNC);
            }
        }
        return unmapped;
    }
}
