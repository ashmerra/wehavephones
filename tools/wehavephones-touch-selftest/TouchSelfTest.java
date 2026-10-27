import com.wehavephones.mobile.MobileTouchInput;
import com.wehavephones.mobile.MobileTouchInput.Key;
import com.wehavephones.mobile.MobileTouchInput.Zone;
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
        int clicks;
        public void key(Key key, boolean down) { if (down) held.add(key); else held.remove(key); }
        public void click(float x, float y) { check(!camera, "UI click releases camera grab first"); clicks++; events.add("click"); }
        public void camera(boolean down) { camera = down; events.add("camera:" + down); }
        public void moveCamera(float dx, float dy) { check(camera, "camera movement holds right button"); }
    }
    public static void main(String[] args) throws Exception {
        Sink sink = new Sink();
        MobileTouchInput input = new MobileTouchInput(sink, 8);
        input.down(4, Zone.STICK, 100, 100);
        input.move(4, 150, 40, 100, 100, 60);
        check(sink.held.contains(Key.FORWARD) && sink.held.contains(Key.RIGHT), "stick moves diagonally");
        input.down(19, Zone.CAMERA, 600, 250);
        input.move(19, 630, 250, 100, 100, 60);
        check(sink.camera, "second finger turns camera while moving");
        input.down(8, Zone.UI, 500, 650);
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
        input.down(2, Zone.CAMERA, 600, 250);
        input.up(2);
        check(sink.clicks == 2, "camera region remains directly tappable");
        input.down(11, Zone.JUMP, 800, 600);
        input.down(21, Zone.JUMP, 800, 600);
        input.up(11);
        check(sink.held.contains(Key.JUMP), "duplicate utility touches do not release another finger");
        input.down(0, Zone.UI, 400, 400);
        input.move(0, 450, 400, 100, 100, 60);
        input.up(0);
        check(sink.clicks == 2, "UI drag never casts an accidental spell");
        input.cancel();
        check(sink.held.isEmpty() && !sink.camera, "cancel releases every key and mouse button");
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
}
