#!/usr/bin/env python3
"""Execute the production renderer trim methods with a host drawable tree.

Only the GL queue, textures and X server dependencies are replaced. Extracting
the real methods avoids loading Android or native graphics libraries on a host.
"""

from pathlib import Path
import re
import subprocess
import sys


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


root = Path(__file__).resolve().parents[2]
work = Path(sys.argv[3])
work.mkdir(parents=True, exist_ok=True)
source = (root / "app/src/main/java/com/wehavephones/renderer/GLRenderer.java").read_text()
counter = re.search(r"private long completedMemoryTrims;", source).group()
implementation = "\n".join((
    counter,
    method(source, "public void trimMemory()"),
    method(source, "private void releaseUnmappedTextures("),
))

harness = r'''
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

class BuildConfig {
    static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("trim.debug", "true"));
}
class Log {
    static final List<String> lines = new ArrayList<>();
    static int i(String tag, String text) {
        if (XLock.held != 0) throw new AssertionError("completion logged before unlocking");
        lines.add(tag + " " + text);
        return 0;
    }
}
class Texture {
    boolean allocated = true;
    int destroys;
    boolean isAllocated() { return allocated; }
    void destroy() {
        if (XLock.held != 1) throw new AssertionError("texture destroyed without X lock");
        destroys++;
        allocated = false;
    }
}
class GPUImage extends Texture {
    final ByteBuffer backing = ByteBuffer.allocateDirect(16);
    @Override void destroy() {
        throw new AssertionError("hardware backing must survive an unmapped-window trim");
    }
}
class Drawable {
    final Texture texture;
    final ByteBuffer data;
    Drawable(Texture texture) {
        this.texture = texture;
        data = texture instanceof GPUImage ? ((GPUImage)texture).backing : ByteBuffer.allocateDirect(16);
    }
    Texture getTexture() { return texture; }
}
class Attributes {
    final boolean mapped;
    Attributes(boolean mapped) { this.mapped = mapped; }
    boolean isMapped() { return mapped; }
}
class Window {
    final Attributes attributes;
    final Drawable content;
    final List<Window> children = new ArrayList<>();
    Window(boolean mapped, Drawable content) {
        attributes = new Attributes(mapped);
        this.content = content;
    }
    Drawable getContent() { return content; }
    List<Window> getChildren() { return children; }
}
class XLock implements AutoCloseable {
    static int held;
    XLock() { held++; }
    @Override public void close() { held--; }
}
class WindowManager { Window rootWindow; }
class XServer {
    enum Lockable { WINDOW_MANAGER, DRAWABLE_MANAGER }
    final WindowManager windowManager = new WindowManager();
    XLock lock(Lockable... ignored) { return new XLock(); }
}
class View {
    final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    int renders;
    void queueEvent(Runnable task) { pending.add(task); }
    void requestRender() { renders++; }
}
class EffectComposer {
    int releases;
    void releaseBuffers() { releases++; }
}
class Renderer {
    final View xServerView = new View();
    final XServer xServer = new XServer();
    final EffectComposer effectComposer = new EffectComposer();
    // PRODUCTION_METHODS
}
public class RendererTrimSelfTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Renderer renderer = new Renderer();
        Window root = new Window(true, null);
        renderer.xServer.windowManager.rootWindow = root;
        Texture shown = new Texture();
        Texture hidden = new Texture();
        Texture nested = new Texture();
        Texture empty = new Texture();
        empty.allocated = false;
        GPUImage hardware = new GPUImage();
        Drawable hardwareDrawable = new Drawable(hardware);
        ByteBuffer originalData = hardwareDrawable.data;
        originalData.putInt(0, 0x12345678);
        Window hiddenWindow = new Window(false, new Drawable(hidden));
        hiddenWindow.children.add(new Window(false, new Drawable(nested)));
        root.children.add(new Window(true, new Drawable(shown)));
        root.children.add(hiddenWindow);
        root.children.add(new Window(false, hardwareDrawable));
        root.children.add(new Window(false, new Drawable(empty)));
        root.children.add(new Window(false, null));
        root.children.add(new Window(false, new Drawable(null)));

        renderer.trimMemory();
        check(hidden.destroys == 0 && renderer.effectComposer.releases == 0,
                "trim must wait for the GL queue");
        check(Log.lines.isEmpty(), "completion must wait for actual GL work");
        renderer.xServerView.pending.remove().run();
        check(hidden.destroys == 1 && nested.destroys == 1, "hidden ordinary textures released recursively");
        check(shown.destroys == 0 && shown.allocated, "mapped texture preserved");
        check(empty.destroys == 0, "unallocated texture is not counted as released");
        check(hardware.allocated && hardwareDrawable.data == originalData
                && originalData == hardware.backing && originalData.getInt(0) == 0x12345678,
                "hardware-backed drawable retains its original readable data");
        check(renderer.xServerView.renders == 1 && renderer.effectComposer.releases == 1,
                "effect buffers released and redraw requested");
        if (BuildConfig.DEBUG) {
            check(Log.lines.size() == 1 && Log.lines.get(0).contains("completed=1 releasedTextures=2 retainedHardwareImages=1 elapsedUs="),
                    "one completion log reports actual released and retained texture counts");
        } else check(Log.lines.isEmpty(), "release builds do not emit diagnostic trim logs");

        renderer.trimMemory();
        renderer.xServerView.pending.remove().run();
        check(hidden.destroys == 1 && nested.destroys == 1, "repeated trim does not destroy empty textures");
        check(originalData.getInt(0) == 0x12345678, "hardware backing also survives repeated trim");
        if (BuildConfig.DEBUG) check(Log.lines.size() == 2
                && Log.lines.get(1).contains("completed=2 releasedTextures=0 retainedHardwareImages=1"),
                "completion counter advances while actual release count reaches zero");
        else check(Log.lines.isEmpty(), "repeated release trim remains silent");

        hardwareDrawable.data.putInt(0, 0x76543210);
        check(hardware.backing.getInt(0) == 0x76543210, "retained hardware data stays writable");
        System.out.println("RendererTrimSelfTest PASS (debug=" + BuildConfig.DEBUG + ")");
    }
}
'''
java_source = work / "RendererTrimSelfTest.java"
java_source.write_text(harness.replace("// PRODUCTION_METHODS", implementation))
subprocess.run([sys.argv[1], "-encoding", "UTF-8", "-d", str(work), str(java_source)], check=True)
for debug in ("true", "false"):
    subprocess.run([sys.argv[2], "-Dtrim.debug=" + debug, "-cp", str(work), "RendererTrimSelfTest"], check=True)
