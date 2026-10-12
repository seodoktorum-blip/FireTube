package com.uykutube.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/* Kalıcı günlük: logcat tamponu döndüğünde bile gece olan biten
   sonra incelenebilsin. files/uyku.log (256 KB üstünde döndürülür). */
final class AppLog {
    private static File logFile = null;
    private static long logSize = 0;

    private AppLog() {
    }

    static synchronized void write(Context ctx, String m) {
        try {
            File dir = ctx.getApplicationContext().getFilesDir();
            if (logFile == null) {
                logFile = new File(dir, "uyku.log");
                logSize = logFile.exists() ? logFile.length() : 0;
            }
            if (logSize > 256 * 1024) {
                File old = new File(dir, "uyku.old.log");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                logFile.renameTo(old);
                logFile = new File(dir, "uyku.log");
                logSize = 0;
            }
            String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new java.util.Date()) + " " + m + "\n";
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fo = new FileOutputStream(logFile, true)) {
                fo.write(bytes);
            }
            logSize += bytes.length;
        } catch (Exception ignored) {
        }
    }
}
