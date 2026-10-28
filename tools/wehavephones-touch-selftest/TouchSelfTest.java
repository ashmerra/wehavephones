import com.wehavephones.mobile.MobileTouchInput;
import com.wehavephones.mobile.MobileTouchInput.Key;
import com.wehavephones.mobile.MobileTouchInput.Zone;
import com.wehavephones.mobile.MobileTouchLayout;
import com.wehavephones.mobile.addons.required.MobileTouchSetup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TouchSelfTest {
    private static int checks;
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
    private static final class Sink implements MobileTouchInput.Sink {
        final Set<Key> held = new HashSet<>();
        final List<String> events = new ArrayList<>();
        boolean camera;
        boolean dragging;
        int contexts, scrolls, hovers;
        int clicks;
        public void key(Key key, boolean down) {
            if (key == Key.CORPSE && down) check(!camera, "corpse targeting releases the camera grab");
            if (down) held.add(key); else held.remove(key);
            events.add(key + ":" + down);
        }
        public void click(float x, float y) { check(!camera, "UI click releases camera grab first"); clicks++; events.add("click"); }
        public void camera(boolean down) { camera = down; events.add("camera:" + down); }
        public void moveCamera(float dx, float dy) { check(camera, "camera movement holds right button"); }
        public void contextClick(float x, float y) { check(!camera, "hold releases camera before context click"); contexts++; }
        public void drag(float x, float y, boolean down) { check(!camera, "UI drag releases camera grab"); dragging = down; }
        public void hover(float x, float y) { hovers++; }
        public void scroll(float x, float y, int clicks) { scrolls += clicks; }
    }
    public static void main(String[] args) throws Exception {
        check(MobileTouchInput.isCameraArea(370, 690, 1000, 1000), "compact camera strip reaches the lower middle");
        check(MobileTouchInput.isCameraArea(500, 400, 1000, 1000), "compact camera starts above the actions");
        check(MobileTouchInput.isCameraArea(800, 400, 1000, 1000), "compact camera reaches across the upper world view");
        check(!MobileTouchInput.isCameraArea(800, 660, 1000, 1000), "enlarged right actions stay directly tappable");
        check(!MobileTouchInput.isCameraArea(500, 800, 1000, 1000), "middle actions and paging stay directly tappable");
        check(!MobileTouchInput.isCameraArea(250, 600, 1000, 1000), "camera never covers the joystick");
        for (float aspect : new float[]{1, 6f / 5, 5f / 4, 4f / 3, 3f / 2, 16f / 10, 16f / 9, 2, 19.5f / 9, 21f / 9, 32f / 9}) {
            float height = 1080, width = height * aspect, layoutWidth = Math.min(width * .80f, height * 16 / 9);
            float left = (width - layoutWidth) / 2;
            check(MobileTouchInput.isCameraArea(left + layoutWidth * (aspect < 1.6f ? .34f : .5f), height * .69f, width, height),
                    "wider games preserve camera swipes in the lower middle");
            check(MobileTouchInput.isCameraArea(left + layoutWidth * .8f, height * (aspect < 1.6f ? .4f : .59f), width, height),
                    "wider games preserve camera swipes above the right actions");
            check(!MobileTouchInput.isCameraArea(left + layoutWidth * .67f, height * .66f, width, height),
                    "centered right actions stay directly tappable in wider games");
            check(!MobileTouchInput.isCameraArea(left + layoutWidth * .5f, height * .8f, width, height),
                    "centered middle actions stay directly tappable in wider games");
        }
        Sink sink = new Sink();
        MobileTouchInput input = new MobileTouchInput(sink, 8);
        input.down(4, Zone.STICK, 100, 100, 0);
        input.move(4, 150, 40, 100, 100, 60);
        check(sink.held.contains(Key.FORWARD) && sink.held.contains(Key.RIGHT), "stick moves diagonally");
        input.down(19, Zone.CAMERA, 600, 250, 0);
        input.move(19, 630, 250, 100, 100, 60);
        check(sink.camera, "second finger turns camera while moving");
        input.down(8, Zone.UI, 500, 650, 0);
        input.up(8);
        check(sink.clicks == 1 && sink.held.contains(Key.FORWARD), "third finger taps spell while moving");
        input.move(19, 630, 250, 100, 100, 60);
        check(!sink.camera, "stationary camera finger does not re-arm when another finger moves");
        input.move(19, 645, 250, 100, 100, 60);
        check(sink.camera, "camera re-arms after spell tap");
        input.up(4);
        check(sink.held.isEmpty() && sink.camera, "lifting stick releases movement only");
        input.up(19);
        check(!sink.camera && sink.clicks == 1, "camera drag does not click on release");
        input.down(2, Zone.CAMERA, 600, 250, 0);
        input.up(2);
        check(sink.clicks == 2, "camera region remains directly tappable");
        input.down(11, Zone.JUMP, 800, 600, 0);
        input.down(21, Zone.JUMP, 800, 600, 0);
        input.up(11);
        check(sink.held.contains(Key.JUMP), "duplicate utility touches do not release another finger");
        input.down(0, Zone.UI, 400, 400, 0);
        input.move(0, 450, 400, 100, 100, 60);
        input.up(0);
        check(sink.clicks == 2, "UI drag never casts an accidental spell");
        input.cancel();
        check(sink.held.isEmpty() && !sink.camera, "cancel releases every key and mouse button");
        input.down(25, Zone.UI, 400, 400, 100);
        input.advanceTime(649);
        check(sink.contexts == 0, "short hold does not edit or right-click");
        input.advanceTime(650);
        input.advanceTime(900);
        input.up(25);
        check(sink.contexts == 1 && sink.clicks == 2, "long hold right-clicks once and never casts on release");
        input.down(2, Zone.CAMERA, 600, 250, 1000);
        input.down(3, Zone.CAMERA, 700, 250, 1000);
        input.move(3, 800, 250, 100, 100, 60);
        check(sink.scrolls > 0 && !sink.camera, "two camera fingers pinch to zoom without a right drag");
        input.up(3);
        input.up(2);
        check(sink.clicks == 2, "lifting pinch fingers never clicks the game");
        input.setUiMode(true);
        input.down(8, Zone.CAMERA, 600, 250, 2000);
        input.move(8, 650, 250, 100, 100, 60);
        check(sink.dragging && !sink.camera, "window mode drags UI under the camera area");
        input.down(9, Zone.UI, 500, 600, 2100);
        input.up(9);
        check(sink.clicks == 2, "another finger cannot click through a held UI drag");
        input.cancel();
        check(!sink.dragging, "focus cancellation releases a UI drag");
        int beforeScroll = sink.scrolls;
        input.down(1, Zone.UI, 600, 200, 3000);
        input.down(2, Zone.UI, 700, 200, 3000);
        input.updateMultiPosition(1, 600, 300);
        input.updateMultiPosition(2, 700, 300);
        input.move(1, 600, 300, 100, 100, 60);
        input.move(2, 700, 300, 100, 100, 60);
        check(sink.scrolls != beforeScroll && !sink.dragging, "two-finger movement scrolls windows without dragging items");
        input.setUiMode(false);
        sink.events.clear();
        input.down(30, Zone.TARGET, 900, 600, 4000);
        check(!sink.events.contains("TARGET:true"), "Target waits for tap or hold instead of selecting an enemy immediately");
        input.up(30);
        check(sink.events.contains("TARGET:true") && sink.held.isEmpty(), "short Target tap cycles once and releases its key");
        sink.events.clear();
        input.down(19, Zone.CAMERA, 600, 250, 5000);
        input.move(19, 640, 250, 100, 100, 60);
        input.down(30, Zone.TARGET, 900, 600, 5000);
        input.advanceTime(5549);
        check(!sink.events.contains("CORPSE:true"), "corpse selection waits for the hold threshold");
        input.advanceTime(5550);
        input.advanceTime(5800);
        input.up(30);
        input.up(19);
        check(sink.events.stream().filter("CORPSE:true"::equals).count() == 1, "holding Target selects a corpse once");
        check(!sink.events.contains("TARGET:true") && sink.held.isEmpty(), "a corpse hold never cycles a living enemy on press or release");
        sink.events.clear();
        input.down(30, Zone.TARGET, 900, 600, 6000);
        input.cancel();
        input.up(30);
        check(!sink.events.contains("TARGET:true") && !sink.events.contains("CORPSE:true"), "cancelling a pending Target press selects nothing");
        for (Zone zone : new Zone[]{Zone.MORE, Zone.MODE, Zone.KEYBOARD, Zone.INSPECT}) {
            sink.events.clear();
            input.down(32, zone, 400, 40, 7000);
            input.cancel();
            check(sink.events.isEmpty(), "focus loss and rotation cannot open " + zone);
            input.down(32, zone, 400, 40, 7000);
            input.up(32);
            check(sink.events.size() == 2, zone + " runs once on release");
        }
        sink.events.clear();
        input.down(32, Zone.TARGET, 900, 600, 7000);
        input.move(32, 400, 600, 100, 100, 60);
        input.advanceTime(8000);
        input.up(32);
        check(sink.events.isEmpty(), "sliding off Target cancels both tap and corpse hold");
        int beforeClick = sink.clicks, beforeContext = sink.contexts, beforeHover = sink.hovers;
        input.down(32, Zone.STICK, 100, 100, 7000);
        input.down(33, Zone.STICK, 100, 100, 7000);
        input.advanceTime(8000);
        input.up(33);
        input.up(32);
        check(sink.clicks == beforeClick && sink.contexts == beforeContext, "a duplicate stick touch never interacts with the world");
        input.setInspectMode(true);
        input.down(32, Zone.CAMERA, 700, 400, 7000);
        input.move(32, 750, 400, 100, 100, 60);
        input.advanceTime(8000);
        input.up(32);
        check(input.isUiMode() && input.isInspectMode() && sink.hovers > beforeHover, "Inspect follows the finger over window content");
        check(sink.clicks == beforeClick && sink.contexts == beforeContext && !sink.dragging && !sink.camera,
                "inspection cannot cast, equip, use, right-click or drag");
        input.setUiMode(false);
        check(!input.isInspectMode(), "Play exits inspection");
        checkLayouts();
        if (args.length > 0) checkAddonGeometry(Path.of(args[0]));
        Path root = Files.createTempDirectory("wehavephones-touch-fixture");
        try {
            byte[] script = "-- fixture\n".getBytes(StandardCharsets.UTF_8);
            MobileTouchSetup.prepare(root.toFile(), false, 11509, script);
            check(!Files.exists(root.resolve("Interface")), "default off leaves install untouched");
            Path addons = root.resolve("Interface/AddOns");
            Files.createDirectories(addons.resolve("ConsolePort"));
            Files.createDirectories(addons.resolve("ConsolePort_Bar"));
            Files.createDirectories(addons.resolve("ConsolePort_Cursor.disabled"));
            Files.createDirectories(addons.resolve("WeHavePhonesControllerSetup"));
            Files.createDirectories(addons.resolve("UserAddon"));
            MobileTouchSetup.prepare(root.toFile(), true, 11509, script);
            check(Files.isDirectory(addons.resolve("ConsolePort.disabled")) && !Files.exists(addons.resolve("ConsolePort")), "touch disables core");
            check(Files.isDirectory(addons.resolve("ConsolePort_Bar.disabled")), "touch disables suite");
            check(Files.isDirectory(addons.resolve("WeHavePhonesControllerSetup.disabled")), "touch disables controller setup");
            check(Files.isDirectory(addons.resolve("UserAddon")), "unrelated addons preserved");
            Path mode = addons.resolve("WeHavePhonesTouch/Mode.lua");
            check(Files.readString(mode).contains("true"), "generated addon enabled");
            MobileTouchSetup.prepare(root.toFile(), true, 11509, script);
            MobileTouchSetup.prepare(root.toFile(), false, 11509, script);
            check(Files.isDirectory(addons.resolve("ConsolePort")) && Files.isDirectory(addons.resolve("ConsolePort_Bar")), "opt-out restores owned folders");
            check(Files.isDirectory(addons.resolve("ConsolePort_Cursor.disabled")), "user-disabled module remains disabled");
            check(Files.readString(mode).contains("false"), "addon stays to restore each character's settings");
            MobileTouchSetup.prepare(root.toFile(), false, 11509, script);
            check(!Files.exists(root.resolve("WTF/.wehavephones-touch-consoleport")), "repeated opt-out is safe");
            Files.createDirectories(addons.resolve("ConsolePort.disabled"));
            boolean refused = false;
            try { MobileTouchSetup.prepare(root.toFile(), true, 11509, script); }
            catch (java.io.IOException expected) { refused = true; }
            check(refused && Files.isDirectory(addons.resolve("ConsolePort")), "conflicts never overwrite an existing folder");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        System.out.println("TouchSelfTest PASS (" + checks + " checks)");
    }

    private static void checkLayouts() {
        for (int height : new int[]{480, 600, 720, 1080, 1440, 2160}) {
            for (float aspect : new float[]{1, 6f / 5, 5f / 4, 4f / 3, 3f / 2, 16f / 10, 16f / 9, 2, 19.5f / 9, 21f / 9, 32f / 9}) {
                for (float density : new float[]{1, 1.5f, 2.75f, 4}) {
                    float width = height * aspect, inset = height * .035f;
                    for (boolean windows : new boolean[]{false, true}) {
                        MobileTouchLayout layout = new MobileTouchLayout(width, height, density, inset, 0, inset, inset, windows);
                        int count = 0;
                        for (int i = 0; i < layout.buttons.length; i++) {
                            MobileTouchLayout.Bounds a = layout.buttons[i];
                            if (a == null) continue;
                            count++;
                            check(a.left >= inset && a.right <= width - inset + .01f && a.top >= 0 && a.bottom <= height - inset,
                                    "every control stays inside cutout and navigation safe bounds");
                            check(a.contains((a.left+a.right)/2, (a.top+a.bottom)/2), "visible controls own their hit targets");
                            for (int j = i + 1; j < layout.buttons.length; j++) {
                                MobileTouchLayout.Bounds b = layout.buttons[j];
                                if (b != null) check(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top,
                                        "native controls never overlap one another");
                            }
                        }
                        check(count == (windows ? 5 : 8), "window mode removes the movement and combat controls");
                        check(layout.stickX - layout.radius * 1.2f >= inset && layout.stickY + layout.radius * 1.2f < height - inset,
                                "the entire stick hit region stays reachable");
                        for (float edge : new float[]{-1000, .5f, 1000}) {
                            MobileTouchLayout.Bounds handle = MobileTouchLayout.handle(width, height, density,
                                    width * edge, height * edge, inset, 0, inset, inset);
                            check(handle.left >= inset && handle.right <= width - inset + .01f
                                            && handle.top >= 0 && handle.bottom <= height - inset + .01f,
                                    "the movable Controls handle cannot be lost beyond safe screen edges");
                        }
                    }
                }
            }
        }
        MobileTouchLayout.Bounds viewport = MobileTouchLayout.viewport(2340, 1080, 1280, 720, false);
        check(Math.abs(viewport.left - 210) < .01f && viewport.top == 0 && viewport.width() == 1920,
                "16:9 guest letterboxing matches the renderer on the S23");
        check(!viewport.contains(100, 500) && viewport.contains(1000, 500), "letterbox taps cannot reach guest windows");
        viewport = MobileTouchLayout.viewport(1600, 1200, 1920, 1080, false);
        check(Math.abs(viewport.top - 150) < .01f && viewport.left == 0, "4:3 hosts letterbox the vertical axis correctly");
        viewport = MobileTouchLayout.viewport(1600, 1200, 1920, 1080, true);
        check(viewport.width() == 1600 && viewport.height() == 1200, "stretched guests use independent axis scaling");
    }

    private static void checkAddonGeometry(Path path) throws Exception {
        for (String line : Files.readAllLines(path)) {
            String[] fields = line.split("\t");
            float rootWidth = Float.parseFloat(fields[0]), rootHeight = Float.parseFloat(fields[1]);
            float x = Float.parseFloat(fields[2]), y = Float.parseFloat(fields[3]);
            float w = Float.parseFloat(fields[4]), h = Float.parseFloat(fields[5]);
            for (float density : new float[]{1, 1.5f, 2.75f, 4}) {
                for (boolean letterboxed : new boolean[]{false, true}) {
                    float height = 1080, width = letterboxed ? 2340 : height * rootWidth / rootHeight;
                    float inset = height * .035f;
                    MobileTouchLayout nativeLayout = new MobileTouchLayout(width, height, density, inset, inset, inset, inset, false);
                    MobileTouchLayout.Bounds viewport = MobileTouchLayout.viewport(width, height, rootWidth, rootHeight, !letterboxed);
                    float left = viewport.left + x / rootWidth * viewport.width();
                    float right = left + w / rootWidth * viewport.width();
                    float top = viewport.bottom - (y + h) / rootHeight * viewport.height();
                    float bottom = top + h / rootHeight * viewport.height();
                    for (MobileTouchLayout.Bounds button : nativeLayout.buttons) {
                        if (button != null) check(right <= button.left + .1f || button.right <= left + .1f
                                        || bottom <= button.top + .1f || button.bottom <= top + .1f,
                                "actual Lua " + fields[6] + " geometry stays clear of native control hit regions");
                    }
                    float closestX = Math.max(left, Math.min(right, nativeLayout.stickX));
                    float closestY = Math.max(top, Math.min(bottom, nativeLayout.stickY));
                    check(Math.hypot(closestX - nativeLayout.stickX, closestY - nativeLayout.stickY) >= nativeLayout.radius * 1.2f - .1f,
                            "actual Lua " + fields[6] + " geometry stays clear of the entire stick hit region");
                    for (float fx : new float[]{.01f, .5f, .99f}) for (float fy : new float[]{.01f, .5f, .99f}) {
                        float sampleX = (left + (right-left)*fx - viewport.left) / viewport.width() * rootWidth;
                        float sampleY = (top + (bottom-top)*fy - viewport.top) / viewport.height() * rootHeight;
                        check(!MobileTouchInput.isCameraArea(sampleX, sampleY, rootWidth, rootHeight),
                                "actual Lua " + fields[6] + " geometry never becomes a camera drag");
                    }
                }
            }
        }
    }
}
