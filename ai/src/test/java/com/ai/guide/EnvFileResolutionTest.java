package com.ai.guide;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EnvFileResolutionTest {
    @TempDir Path root;
    @Test void localFileTakesPrecedenceOverParent() throws Exception {
        var cwd = Files.createDirectory(root.resolve("app"));
        Files.createFile(root.resolve(".env"));
        var local = Files.createFile(cwd.resolve(".env"));
        assertEquals(local, ScenicGuideApplication.resolveEnvFile(cwd));
    }
    @Test void parentIsSupportedButAbsenceNeverUsesAnotherWorkspace() throws Exception {
        var cwd = Files.createDirectory(root.resolve("app"));
        assertEquals(cwd.resolve(".env"), ScenicGuideApplication.resolveEnvFile(cwd));
        assertFalse(Files.exists(ScenicGuideApplication.resolveEnvFile(cwd)));
        var parent = Files.createFile(root.resolve(".env"));
        assertEquals(parent, ScenicGuideApplication.resolveEnvFile(cwd));
    }
}
