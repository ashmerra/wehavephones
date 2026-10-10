package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class AssetManager {
    private final File root;

    public AssetManager(File root) { this.root = root; }

    public InputStream open(String path) throws IOException {
        File file = new File(root, path);
        if (!file.isFile()) throw new IOException("no asset " + path);
        return new FileInputStream(file);
    }
}
