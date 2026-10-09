package com.wehavephones.xenvironment;

import java.io.File;

public final class ImageFs {
    private final File rootDir;
    public ImageFs(File rootDir) { this.rootDir = rootDir; }
    public File getRootDir() { return rootDir; }
}
