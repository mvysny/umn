package com.gitlab.mvysny.umn.core.fulltext;

import java.lang.reflect.Field;

public class Constants {
    public static final String OS_ARCH = System.getProperty("os.arch");
    /**
     * True iff running on a 64bit JVM
     */
    public static final boolean JRE_IS_64BIT;
    public static final int NUM_BYTES_CHAR = 2;

    static {
        boolean is64Bit = false;
        try {
            final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            final Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            final Object unsafe = unsafeField.get(null);
            final int addressSize = ((Number) unsafeClass.getMethod("addressSize")
                    .invoke(unsafe)).intValue();
            //System.out.println("Address size: " + addressSize);
            is64Bit = addressSize >= 8;
        } catch (Exception e) {
            final String x = System.getProperty("sun.arch.data.model");
            if (x != null) {
                is64Bit = x.indexOf("64") != -1;
            } else {
                if (OS_ARCH != null && OS_ARCH.indexOf("64") != -1) {
                    is64Bit = true;
                } else {
                    is64Bit = false;
                }
            }
        }
        JRE_IS_64BIT = is64Bit;
    }
}
