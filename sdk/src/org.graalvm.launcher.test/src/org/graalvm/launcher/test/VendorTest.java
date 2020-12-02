package org.graalvm.launcher.test;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VendorTest {

    @Test
    public void testVendorProperties() {
        String vmVendor = System.getProperty("java.vm.vendor");
        String javaVendor = System.getProperty("java.vendor");
        String vendorUrl = System.getProperty("java.vendor.url");
        String bugreportUrl = System.getProperty("java.vendor.url.bug");

        assertEquals("BellSoft", vmVendor);
        assertEquals("BellSoft", javaVendor);
        assertEquals("https://bell-sw.com/", vendorUrl);
        assertEquals("https://bell-sw.com/support", bugreportUrl);
    }
}
