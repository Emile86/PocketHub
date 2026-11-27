package com.example.ayaneooverlay;

import android.content.ComponentName;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Root-side bridge launched by `su -c app_process`. It deliberately uses a tiny
 * newline-delimited JSON protocol over stdin/stdout, so the app does not depend on
 * Shizuku or another installed service manager.
 *
 * This is experimental: Android/OEM builds can restrict hidden audio and task APIs.
 */
public final class RootAudioBridge {
    private static Object audioService;

    private RootAudioBridge() { }

    public static void main(String[] args) {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter output = new PrintWriter(System.out, true);
        try {
            exemptHiddenApiReflection();
            // Do not bootstrap ActivityThread/systemMain here: app_process is launched
            // as root, not as Android system_server, so ActivityThread can remain null.
            // Talk to the system audio Binder service directly instead.
            audioService = connectAudioService();
            if (audioService == null) throw new IllegalStateException("Audio service Binder unavailable");
        } catch (Throwable fatal) {
            try {
                output.println(new JSONObject().put("ok", false).put("error",
                        "Root helper initialization failed: " + fatal.getClass().getSimpleName() + ": " + String.valueOf(fatal.getMessage())).toString());
                output.flush();
            } catch (Throwable ignored) { }
            try { System.err.println("RootAudioBridge fatal during init: " + fatal); } catch (Throwable ignored) { }
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
                switch (command) {
                    case "ping":
                        response = new JSONObject().put("ok", true).put("result", "pong");
                        break;
                    case "list":
                        response = listActivePlayers();
                        break;
                    case "set":
                        response = setVolume(request.optInt("uid", -1), (float) request.optDouble("volume", 1.0));
                        break;
                    case "setPlayer":
                        response = setPlayerVolume(request.optInt("uid", -1), request.optInt("playerId", -1),
                                (float) request.optDouble("volume", 1.0));
                        break;
                    case "stop":
                        response = new JSONObject().put("ok", true).put("result", "stopping");
                        stop = true;
                        break;
                    default:
                        response = new JSONObject().put("ok", false).put("error", "Unknown command");
                        break;
                }
            } catch (Throwable error) {
                try {
                    response = new JSONObject().put("ok", false)
                            .put("error", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
                } catch (Throwable ignored) {
                    response = new JSONObject();
                }
            }
            output.println(response.toString());
            output.flush();
            if (stop) break;
        }
    }

    private static Object connectAudioService() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = findMethod(serviceManager, "getService", String.class);
        if (getService == null) throw new NoSuchMethodException("ServiceManager.getService unavailable");
        getService.setAccessible(true);
        Object binder = getService.invoke(null, "audio");
        if (binder == null) throw new IllegalStateException("Audio service Binder returned null");

        Class<?> iBinderClass = Class.forName("android.os.IBinder");
        Class<?> stubClass = Class.forName("android.media.IAudioService$Stub");
        Method asInterface = findMethod(stubClass, "asInterface", iBinderClass);
        if (asInterface == null) throw new NoSuchMethodException("IAudioService.Stub.asInterface unavailable");
        asInterface.setAccessible(true);
        Object service = asInterface.invoke(null, binder);
        if (service == null) throw new IllegalStateException("IAudioService proxy unavailable");

        Method active = findMethod(service.getClass(), "getActivePlaybackConfigurations");
        if (active == null) {
            Class<?> iface = Class.forName("android.media.IAudioService");
            active = findMethod(iface, "getActivePlaybackConfigurations");
        }
        if (active == null) throw new NoSuchMethodException("IAudioService.getActivePlaybackConfigurations unavailable");
        active.setAccessible(true);
        return service;
    }

    private static void exemptHiddenApiReflection() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method exemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            exemptions.setAccessible(true);
            exemptions.invoke(runtime, (Object) new String[] {
                    "Landroid/media/", "Landroid/os/", "Landroid/app/ActivityThread", "Landroid/app/ActivityTaskManager",
                    "Landroid/app/TaskInfo", "Landroid/app/ActivityManager$",
                    "Landroid/content/pm/ParceledListSlice", "Ldalvik/system/VMRuntime"
            });
        } catch (Throwable ignored) {
            // Root/system process may already have hidden-API access. Individual calls report errors.
        }
    }

    private static JSONObject listActivePlayers() throws Exception {
        List<?> configs = activeConfigurations();
        Set<Integer> uids = new LinkedHashSet<>();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        JSONArray players = new JSONArray();
        for (Object config : configs) {
            Integer uid = reflectedInt(config, "getClientUid");
            if (uid == null || uid < 10000) continue;
            uids.add(uid);
            counts.put(uid, counts.containsKey(uid) ? counts.get(uid) + 1 : 1);
            Integer playerId = reflectedInt(config, "getPlayerInterfaceId");
            if (playerId != null) players.put(new JSONObject().put("uid", uid).put("playerId", playerId));
        }
        JSONArray uidArray = new JSONArray();
        for (Integer uid : uids) uidArray.put(uid);
        JSONObject countObject = new JSONObject();
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            countObject.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        JSONArray displays = new JSONArray();
        for (Map.Entry<String, Set<Integer>> entry : activeTaskDisplays().entrySet()) {
            for (Integer displayId : entry.getValue()) {
                displays.put(new JSONObject().put("package", entry.getKey()).put("displayId", displayId));
            }
        }
        return new JSONObject().put("ok", true).put("uids", uidArray)
                .put("playerCounts", countObject).put("players", players).put("packageDisplays", displays);
    }

    private static JSONObject setPlayerVolume(int uid, int playerId, float volume) throws Exception {
        if (uid < 10000 || playerId < 0) return new JSONObject().put("ok", false).put("error", "Invalid app player");
        if (Float.isNaN(volume) || Float.isInfinite(volume)) volume = 1.0f;
        volume = Math.max(0.0f, Math.min(1.0f, volume));
        for (Object config : activeConfigurations()) {
            Integer clientUid = reflectedInt(config, "getClientUid");
            Integer currentPlayerId = reflectedInt(config, "getPlayerInterfaceId");
            if (clientUid == null || clientUid != uid || currentPlayerId == null || currentPlayerId != playerId) continue;
            try {
                Method getProxy = findMethod(config.getClass(), "getPlayerProxy");
                if (getProxy == null) break;
                getProxy.setAccessible(true);
                Object proxy = getProxy.invoke(config);
                if (proxy == null) break;
                Method setter = findMethod(proxy.getClass(), "setVolume", float.class);
                if (setter == null) break;
                setter.setAccessible(true);
                setter.invoke(proxy, volume);
                return new JSONObject().put("ok", true).put("changed", true);
            } catch (Throwable ignored) { }
            break;
        }
        return new JSONObject().put("ok", true).put("changed", false);
    }

    private static JSONObject setVolume(int uid, float volume) throws Exception {
        if (uid < 10000) return new JSONObject().put("ok", false).put("error", "Invalid app UID");
        if (Float.isNaN(volume) || Float.isInfinite(volume)) volume = 1.0f;
        volume = Math.max(0.0f, Math.min(1.0f, volume));
        int changed = 0;
        for (Object config : activeConfigurations()) {
            Integer clientUid = reflectedInt(config, "getClientUid");
            if (clientUid == null || clientUid != uid) continue;
            try {
                Method getProxy = findMethod(config.getClass(), "getPlayerProxy");
                if (getProxy == null) continue;
                getProxy.setAccessible(true);
                Object proxy = getProxy.invoke(config);
                if (proxy == null) continue;
                Method setter = findMethod(proxy.getClass(), "setVolume", float.class);
                if (setter == null) continue;
                setter.setAccessible(true);
                setter.invoke(proxy, volume);
                changed++;
            } catch (Throwable ignored) {
                // Players can disappear between enumeration and the set request.
            }
        }
        return new JSONObject().put("ok", true).put("uid", uid)
                .put("changed", changed > 0).put("count", changed);
    }

    private static List<?> activeConfigurations() throws Exception {
        if (audioService == null) throw new IllegalStateException("Audio service not initialized");
        Method method = findMethod(audioService.getClass(), "getActivePlaybackConfigurations");
        if (method == null) {
            Class<?> iface = Class.forName("android.media.IAudioService");
            method = findMethod(iface, "getActivePlaybackConfigurations");
        }
        if (method == null) throw new NoSuchMethodException("IAudioService.getActivePlaybackConfigurations is unavailable on this firmware");
        method.setAccessible(true);
        Object value = method.invoke(audioService);
        if (value instanceof List<?>) return (List<?>) value;
        if (value instanceof Collection<?>) return new ArrayList<>((Collection<?>) value);
        return new ArrayList<>();
    }

    private static Map<String, Set<Integer>> activeTaskDisplays() {
        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Method getService = atmClass.getDeclaredMethod("getService");
            getService.setAccessible(true);
            Object service = getService.invoke(null);
            Method rootsMethod = findMethod(service.getClass(), "getAllRootTaskInfos");
            if (rootsMethod == null) rootsMethod = findMethod(service.getClass(), "getAllRootTaskInfosOnDisplay", int.class);
            Object roots;
            if (rootsMethod == null) throw new NoSuchMethodException("Task list API unavailable");
            rootsMethod.setAccessible(true);
            List<Object> tasks = new ArrayList<>();
            if (rootsMethod.getParameterTypes().length == 0) {
                roots = rootsMethod.invoke(service);
                tasks.addAll(asList(roots));
            } else {
                // OEM builds occasionally expose only the per-display task query.
                for (int displayId = 0; displayId < 8; displayId++) {
                    try { tasks.addAll(asList(rootsMethod.invoke(service, displayId))); }
                    catch (Throwable ignoredDisplay) { }
                }
            }
            for (Object task : tasks) {
                Boolean visible = reflectedBoolean(task, "isVisible");
                if (visible == null) visible = reflectedBoolean(task, "getIsVisible");
                if (visible != null && !visible) continue;
                Integer display = reflectedInt(task, "displayId");
                if (display == null) display = fieldInt(task, "displayId");
                if (display == null) continue;
                addTaskComponent(result, task, "topActivity", display);
                addTaskComponent(result, task, "baseActivity", display);
            }
        } catch (Throwable ignored) {
            // Fall through to the dumpsys parser for OEM builds with restricted hidden task APIs.
        }
        if (result.isEmpty()) parseActivityDumpsys(result);
        return result;
    }

    private static List<?> asList(Object result) throws Exception {
        if (result instanceof List<?>) return (List<?>) result;
        if (result == null) return new ArrayList<>();
        Method getList = findMethod(result.getClass(), "getList");
        if (getList != null) {
            getList.setAccessible(true);
            Object value = getList.invoke(result);
            if (value instanceof List<?>) return (List<?>) value;
        }
        return new ArrayList<>();
    }

    private static void addTaskComponent(Map<String, Set<Integer>> result, Object task, String field, int displayId) {
        Object component = readMember(task, field);
        if (!(component instanceof ComponentName)) return;
        String packageName = ((ComponentName) component).getPackageName();
        if (packageName == null || packageName.isEmpty()) return;
        Set<Integer> ids = result.get(packageName);
        if (ids == null) {
            ids = new LinkedHashSet<>();
            result.put(packageName, ids);
        }
        ids.add(displayId);
    }

    private static void parseActivityDumpsys(Map<String, Set<Integer>> result) {
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", "dumpsys activity activities").redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            int display = -1;
            boolean inTask = false;
            long deadline = System.currentTimeMillis() + 2400L;
            while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
                java.util.regex.Matcher displayHeading = java.util.regex.Pattern.compile("Display\\s+#(\\d+)").matcher(line);
                if (displayHeading.find()) {
                    display = Integer.parseInt(displayHeading.group(1));
                    inTask = false;
                    continue;
                }
                if (line.contains("Task{") || line.trim().startsWith("RootTask")) {
                    inTask = true;
                    int candidate = parseDisplayId(line);
                    if (candidate >= 0) display = candidate;
                } else if (inTask && line.contains("displayId=")) {
                    int candidate = parseDisplayId(line);
                    if (candidate >= 0) display = candidate;
                }
                if (inTask && display >= 0 && line.contains("ActivityRecord")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bu[0-9]+\\s+([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+").matcher(line);
                    if (m.find()) {
                        String packageName = m.group(1);
                        Set<Integer> ids = result.get(packageName);
                        if (ids == null) { ids = new LinkedHashSet<>(); result.put(packageName, ids); }
                        ids.add(display);
                    }
                }
            }
            reader.close();
            process.destroy();
        } catch (Throwable ignored) { }
    }

    private static int parseDisplayId(String line) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("displayId=(\\d+)").matcher(line);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static Integer reflectedInt(Object target, String methodName) {
        try {
            Method method = findMethod(target.getClass(), methodName);
            if (method == null) return null;
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value instanceof Number ? ((Number) value).intValue() : null;
        } catch (Throwable ignored) { return null; }
    }

    private static Boolean reflectedBoolean(Object target, String methodName) {
        try {
            Method method = findMethod(target.getClass(), methodName);
            if (method == null) return null;
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable ignored) { return null; }
    }

    private static Integer fieldInt(Object target, String fieldName) {
        Object value = readMember(target, fieldName);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static Object readMember(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) { type = type.getSuperclass(); }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredMethod(name, parameterTypes); }
            catch (NoSuchMethodException ignored) { cursor = cursor.getSuperclass(); }
            catch (Throwable ignored) { return null; }
        }
        return null;
    }
}
