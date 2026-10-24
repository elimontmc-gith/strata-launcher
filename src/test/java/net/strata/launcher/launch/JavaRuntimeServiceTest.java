package net.strata.launcher.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavaRuntimeServiceTest {
    private final JavaRuntimeService service = new JavaRuntimeService();

    @Test
    void mapsMinecraftReleaseFamiliesToTheirMinimumJavaVersions() throws Exception {
        assertEquals(8, service.requiredMajorVersion("1.16.5"));
        assertEquals(16, service.requiredMajorVersion("1.17"));
        assertEquals(17, service.requiredMajorVersion("1.20.4"));
        assertEquals(21, service.requiredMajorVersion("1.20.5"));
        assertEquals(21, service.requiredMajorVersion("1.21.4"));
    }
}
