package bfoot.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Journalisation minimale horodatée (console), sans dépendance externe. */
public final class Log {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {}

    public static void info(String msg) {
        System.out.println(LocalDateTime.now().format(FMT) + " INFO  " + msg);
    }

    public static void warn(String msg) {
        System.err.println(LocalDateTime.now().format(FMT) + " WARN  " + msg);
    }

    public static void error(String msg, Throwable t) {
        System.err.println(LocalDateTime.now().format(FMT) + " ERROR " + msg
            + (t != null ? " — " + t.getClass().getSimpleName() + ": " + t.getMessage() : ""));
        if (t != null && Env.get("DEBUG") != null) {
            t.printStackTrace();
        }
    }
}
