package com.wehavephones.mobile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for the Battle.net Agent's {@code C:\ProgramData\Battle.net\Agent\product.db}:
 * the protobuf database of every product the client manages. Only the fields
 * below are decoded (schema as in lutris {@code battlenet.py} and the
 * game-scanner crate's {@code product_db.proto}); unknown fields are skipped.
 *
 * <pre>
 * Database         { repeated ProductInstall product_installs = 1; repeated ProductConfig product_configs = 4; }
 * ProductInstall   { string uid = 1; string product_code = 2; UserSettings settings = 3; CachedProductState cached_product_state = 4; }
 * UserSettings     { string install_path = 1; string versionbranch = 10; }
 * CachedProductState { BaseProductState base_product_state = 1; }
 * BaseProductState { bool installed = 1; bool playable = 2; bool update_complete = 3; string current_version_str = 7; }
 * ProductConfig    { string product_code = 1; }
 * </pre>
 *
 * Pure Java (host self-tested); never throws on malformed input, it returns
 * what it could decode.
 */
public final class BattleNetProductDb {
    public static final class Install {
        public String uid = "";
        public String productCode = "";
        public String installPath = "";
        public String branch = "";
        public boolean installed;
        public boolean playable;
        public boolean updateComplete;
        public String version = "";
    }

    public final ArrayList<Install> installs = new ArrayList<>();
    /** Product codes the Agent holds a product config for (installed or not). */
    public final ArrayList<String> configCodes = new ArrayList<>();

    public static BattleNetProductDb read(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            return parse(Files.readAllBytes(file.toPath()));
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static BattleNetProductDb parse(byte[] data) {
        BattleNetProductDb db = new BattleNetProductDb();
        Reader reader = new Reader(data, 0, data.length);
        while (reader.hasMore()) {
            int tag = reader.tag();
            if (tag < 0) break;
            if (field(tag) == 1 && wire(tag) == 2) {
                Reader sub = reader.message();
                if (sub == null) break;
                db.installs.add(parseInstall(sub));
            }
            else if (field(tag) == 4 && wire(tag) == 2) {
                Reader sub = reader.message();
                if (sub == null) break;
                String code = firstString(sub, 1);
                if (!code.isEmpty() && !db.configCodes.contains(code)) db.configCodes.add(code);
            }
            else if (!reader.skip(wire(tag))) {
                break;
            }
        }
        return db;
    }

    public List<Install> gameInstalls() {
        ArrayList<Install> games = new ArrayList<>();
        for (Install install : installs) {
            if (WowFlavor.isGameProductCode(install.productCode)) games.add(install);
        }
        return games;
    }

    public List<String> gameConfigCodes() {
        ArrayList<String> games = new ArrayList<>();
        for (String code : configCodes) {
            if (WowFlavor.isGameProductCode(code)) games.add(code);
        }
        return games;
    }

    /** Playable is an early streaming-install milestone, not a completed download. */
    public boolean isDownloadComplete(String productCode, String uid) {
        for (Install install : installs) {
            boolean matches = productCode != null && !productCode.isEmpty()
                    ? productCode.equalsIgnoreCase(install.productCode)
                    : uid != null && !uid.isEmpty() && uid.equalsIgnoreCase(install.uid);
            if (matches) return install.installed && install.playable && install.updateComplete;
        }
        return false;
    }

    private static Install parseInstall(Reader reader) {
        Install install = new Install();
        while (reader.hasMore()) {
            int tag = reader.tag();
            if (tag < 0) break;
            int field = field(tag);
            if (wire(tag) == 2 && (field == 1 || field == 2)) {
                String value = reader.string();
                if (value == null) break;
                if (field == 1) install.uid = value;
                else install.productCode = value;
            }
            else if (wire(tag) == 2 && field == 3) {
                Reader settings = reader.message();
                if (settings == null) break;
                parseSettings(settings, install);
            }
            else if (wire(tag) == 2 && field == 4) {
                Reader cached = reader.message();
                if (cached == null) break;
                parseCachedState(cached, install);
            }
            else if (!reader.skip(wire(tag))) {
                break;
            }
        }
        return install;
    }

    private static void parseSettings(Reader reader, Install install) {
        while (reader.hasMore()) {
            int tag = reader.tag();
            if (tag < 0) return;
            if (wire(tag) == 2 && (field(tag) == 1 || field(tag) == 10)) {
                String value = reader.string();
                if (value == null) return;
                if (field(tag) == 1) install.installPath = value;
                else install.branch = value;
            }
            else if (!reader.skip(wire(tag))) {
                return;
            }
        }
    }

    private static void parseCachedState(Reader reader, Install install) {
        while (reader.hasMore()) {
            int tag = reader.tag();
            if (tag < 0) return;
            if (field(tag) == 1 && wire(tag) == 2) {
                Reader base = reader.message();
                if (base == null) return;
                parseBaseState(base, install);
            }
            else if (!reader.skip(wire(tag))) {
                return;
            }
        }
    }

    private static void parseBaseState(Reader reader, Install install) {
        while (reader.hasMore()) {
            int tag = reader.tag();
            if (tag < 0) return;
            int field = field(tag);
            if (wire(tag) == 0 && field >= 1 && field <= 3) {
                long value = reader.varint();
                if (value < 0) return;
                if (field == 1) install.installed = value != 0;
                else if (field == 2) install.playable = value != 0;
                else install.updateComplete = value != 0;
            }
            else if (wire(tag) == 2 && field == 7) {
                String value = reader.string();
                if (value == null) return;
                install.version = value;
            }
            else if (!reader.skip(wire(tag))) {
                return;
            }
        }
    }

    private static String firstString(Reader reader, int wanted) {
        while (reader.hasMore()) {
            int tag = reader.tag();
            if (tag < 0) return "";
            if (field(tag) == wanted && wire(tag) == 2) {
                String value = reader.string();
                return value != null ? value : "";
            }
            if (!reader.skip(wire(tag))) return "";
        }
        return "";
    }

    private static int field(int tag) {
        return tag >>> 3;
    }

    private static int wire(int tag) {
        return tag & 7;
    }

    /** Protobuf wire-format cursor over a byte range; every read returns null/-1 past the end. */
    private static final class Reader {
        private final byte[] data;
        private int position;
        private final int end;

        Reader(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        boolean hasMore() {
            return position < end;
        }

        int tag() {
            long value = varint();
            return value < 0 || value > Integer.MAX_VALUE ? -1 : (int)value;
        }

        long varint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position >= end) return -1;
                byte b = data[position++];
                result |= (long)(b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
            }
            return -1;
        }

        Reader message() {
            long length = varint();
            if (length < 0 || length > end - position) return null;
            Reader sub = new Reader(data, position, position + (int)length);
            position += (int)length;
            return sub;
        }

        String string() {
            Reader sub = message();
            return sub == null ? null : new String(data, sub.position, sub.end - sub.position, StandardCharsets.UTF_8);
        }

        boolean skip(int wireType) {
            switch (wireType) {
                case 0: return varint() >= 0;
                case 1: return advance(8);
                case 2: return message() != null;
                case 5: return advance(4);
                default: return false;
            }
        }

        private boolean advance(int count) {
            if (end - position < count) return false;
            position += count;
            return true;
        }
    }
}
