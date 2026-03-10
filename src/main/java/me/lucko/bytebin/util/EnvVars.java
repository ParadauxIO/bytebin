package me.lucko.bytebin.util;

public final class EnvVars {
    private EnvVars() {}

    /**
     * Uses environment variables to configure some internal system properties
     * that control how the app functions.
     */
    public static void read() {
        setSystemProperty("BYTEBIN_LOG4J_CONFIG", "log4j.configurationFile"); // log4j
        setSystemProperty("BYTEBIN_TMP_DIR", "application.tmpdir"); // jooby
    }

    private static void setSystemProperty(String envVar, String sysProp) {
        String value = System.getenv(envVar);
        if (value != null) {
            System.setProperty(sysProp, value);
        }
    }

}
