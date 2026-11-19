package org.traccar.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NoCacheEntryFilterTest {

    @Test
    public void testEntryFiles() {
        assertTrue(NoCacheEntryFilter.isEntry("/"));
        assertTrue(NoCacheEntryFilter.isEntry("/index.html"));
        assertTrue(NoCacheEntryFilter.isEntry("/sw.js"));
        assertTrue(NoCacheEntryFilter.isEntry("/push-sw.js"));
        assertTrue(NoCacheEntryFilter.isEntry("/manifest.webmanifest"));
        assertTrue(NoCacheEntryFilter.isEntry("/workbox-2fbc6a65.js"));
    }

    @Test
    public void testVersionedAssetsKeepCaching() {
        assertFalse(NoCacheEntryFilter.isEntry("/assets/index-BIAzfhKP.js"));
        assertFalse(NoCacheEntryFilter.isEntry("/api/server"));
        assertFalse(NoCacheEntryFilter.isEntry("/pwa-192x192.png"));
    }

}
