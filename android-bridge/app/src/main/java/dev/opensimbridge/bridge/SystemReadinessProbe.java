package dev.opensimbridge.bridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

final class SystemReadinessProbe {
    private SystemReadinessProbe() {}

    static String report(Context context) {
        JSONObject o = new JSONObject();
        try {
            ApplicationInfo ai = context.getApplicationInfo();

            o.put("manufacturer", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("device", Build.DEVICE);
            o.put("product", Build.PRODUCT);
            o.put("board", Build.BOARD);
            o.put("hardware", Build.HARDWARE);
            o.put("android_release", Build.VERSION.RELEASE);
            o.put("sdk_int", Build.VERSION.SDK_INT);
            o.put("security_patch", Build.VERSION.SECURITY_PATCH);
            o.put("build_id", Build.ID);
            o.put("display", Build.DISPLAY);
            o.put("fingerprint", Build.FINGERPRINT);
            o.put("bootloader", Build.BOOTLOADER);
            o.put("radio", safeRadioVersion());
            o.put("build_type", Build.TYPE);
            o.put("build_tags", Build.TAGS);
            o.put("debuggable_build",
                    "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE));
            o.put("app_source_dir", ai.sourceDir);
            o.put("app_is_system",
                    (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            o.put("app_is_updated_system",
                    (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);

            JSONObject paths = new JSONObject();
            paths.put("su_system_xbin", new File("/system/xbin/su").exists());
            paths.put("su_system_bin", new File("/system/bin/su").exists());
            paths.put("su_sbin", new File("/sbin/su").exists());
            paths.put("magisk", new File("/sbin/.magisk").exists()
                    || new File("/data/adb/magisk").exists());
            paths.put("system_priv_app_exists", new File("/system/priv-app").exists());
            paths.put("system_permissions_exists", new File("/system/etc/permissions").exists());
            o.put("paths", paths);

            o.put("selinux_enforce_file", readFirstLine("/sys/fs/selinux/enforce"));
            o.put("selinux_status", execOne("getenforce"));

            JSONObject props = new JSONObject();
            String[] wanted = new String[] {
                    "ro.build.version.incremental",
                    "ro.build.version.security_patch",
                    "ro.boot.verifiedbootstate",
                    "ro.boot.flash.locked",
                    "ro.boot.vbmeta.device_state",
                    "ro.boot.warranty_bit",
                    "ro.boot.odin_download",
                    "ro.boot.selinux",
                    "ro.secure",
                    "ro.debuggable",
                    "ro.adb.secure",
                    "ro.build.type",
                    "ro.build.tags",
                    "ro.product.model",
                    "ro.product.device",
                    "ro.bootloader",
                    "ro.baseband",
                    "ro.boot.rpmb_state"
            };
            for (String key : wanted) {
                String value = getProp(key);
                if (value != null && !value.isEmpty()) props.put(key, value);
            }
            o.put("properties", props);

            JSONArray suAttempts = new JSONArray();
            suAttempts.put(execStructured(new String[]{"sh", "-c", "id"}));
            suAttempts.put(execStructured(new String[]{"sh", "-c", "which su"}));
            suAttempts.put(execStructured(new String[]{"sh", "-c", "su -c id"}));
            o.put("shell_checks", suAttempts);

            o.put("capture_audio_output_granted",
                    context.checkCallingOrSelfPermission(
                            "android.permission.CAPTURE_AUDIO_OUTPUT"
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED);
            o.put("modify_audio_routing_granted",
                    context.checkCallingOrSelfPermission(
                            "android.permission.MODIFY_AUDIO_ROUTING"
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED);

            return o.toString();
        } catch (Exception e) {
            try {
                o.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                return o.toString();
            } catch (Exception ignored) {
                return "{\"error\":\"readiness_probe_failed\"}";
            }
        }
    }

    private static String safeRadioVersion() {
        try {
            return Build.getRadioVersion();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readFirstLine(String path) {
        try {
            BufferedReader br = new BufferedReader(new java.io.FileReader(path));
            String line = br.readLine();
            br.close();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String getProp(String key) {
        try {
            Process p = new ProcessBuilder("/system/bin/getprop", key)
                    .redirectErrorStream(true)
                    .start();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream())
            );
            String line = br.readLine();
            p.waitFor();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String execOne(String command) {
        try {
            Process p = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream())
            );
            String line = br.readLine();
            p.waitFor();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject execStructured(String[] command) {
        JSONObject o = new JSONObject();
        try {
            JSONArray cmd = new JSONArray();
            for (String c : command) cmd.put(c);
            o.put("command", cmd);

            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream())
            );

            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null && lines.size() < 20) {
                lines.add(line);
            }

            int exit = p.waitFor();
            o.put("exit_code", exit);

            JSONArray output = new JSONArray();
            for (String s : lines) output.put(s);
            o.put("output", output);
        } catch (Exception e) {
            try {
                o.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return o;
    }
}
