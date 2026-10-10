package android.os;

/** Settable stand-in for the Build fields the device rules read. */
public class Build {
    public static String MANUFACTURER = "samsung";
    public static String BRAND = "samsung";
    public static String MODEL = "SM-S916U1";
    public static String DEVICE = "dm2q";
    public static String FINGERPRINT = "samsung/test/fingerprint";
    public static String SOC_MODEL = "SM8550";
    public static String SOC_MANUFACTURER = "QTI";

    public static class VERSION {
        public static String RELEASE = "16";
        public static int SDK_INT = 36;
    }
}
