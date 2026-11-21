package com.wehavephones.mobile;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The WoW game accounts (WoW1, WoW2, ...) Battle.net lists for each game and
 * region, read from its own log:
 * {@code GameAccount added to family: family=WoW account=GameAccount(WoW:wow_classic_era region=US entity=... name=WoW1)},
 * and the one its Play panel has selected:
 * {@code New selection for product group WoW: account=GameAccount(WoW:retail region=US entity=... name=WoW1)}.
 * Only the names and regions are kept, never the account entity or email.
 * Pure Java, host-tested.
 */
public final class BattleNetGameAccounts {
    private static final Pattern ACCOUNT = Pattern.compile(
            "GameAccount\\(WoW:([a-z0-9_]+) region=([A-Z]{2}) entity=\\S+ name=([A-Za-z0-9]+)\\)");
    private static final Pattern SELECTION = Pattern.compile("New selection for product group WoW: account=" + ACCOUNT.pattern());
    /** The newest log may stop before accounts load, so read a few. */
    private static final int LOGS = 4;

    private BattleNetGameAccounts() {}

    public static final class Account {
        public final String name;
        public final String region;

        Account(String name, String region) {
            this.name = name;
            this.region = region;
        }

        /** The saved form of the choice ("US/WoW2"); see {@link #fromId}. */
        public String id() {
            return region + "/" + name;
        }

        public static Account fromId(String id) {
            int slash = id == null ? -1 : id.indexOf('/');
            if (slash <= 0 || slash == id.length() - 1) return null;
            return new Account(id.substring(slash + 1), id.substring(0, slash));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Account && ((Account)other).name.equals(name) && ((Account)other).region.equals(region);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + region.hashCode();
        }
    }

    /** What the app read for one game: its accounts in Battle.net's order and Battle.net's selection. */
    public static final class Snapshot {
        public final List<Account> accounts;
        /** Battle.net's current selection, or null when the log has none. */
        public final Account selected;

        Snapshot(List<Account> accounts, Account selected) {
            this.accounts = accounts;
            this.selected = selected;
        }
    }

    /** Battle.net calls Retail "retail"; every other game by its uid. */
    static String key(String uid) {
        String value = uid == null ? "" : uid.toLowerCase(Locale.ROOT);
        return "wow".equals(value) ? "retail" : value;
    }

    /** Accounts per Battle.net key ("retail", "wow_classic_era", ...), in log order. */
    public static Map<String, List<Account>> parse(String logText) {
        Map<String, List<Account>> out = new LinkedHashMap<>();
        if (logText == null) return out;
        Matcher matcher = ACCOUNT.matcher(logText);
        while (matcher.find()) {
            List<Account> accounts = out.get(matcher.group(1));
            if (accounts == null) out.put(matcher.group(1), accounts = new ArrayList<>());
            Account account = new Account(matcher.group(3), matcher.group(2));
            if (!accounts.contains(account)) accounts.add(account);
        }
        return out;
    }

    /** The account Battle.net selected last for {@code key} in {@code logText}, or null. */
    public static Account selection(String logText, String key) {
        Account selected = null;
        if (logText == null) return null;
        Matcher matcher = SELECTION.matcher(logText);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) selected = new Account(matcher.group(3), matcher.group(2));
        }
        return selected;
    }

    /** One log line's selection as {key, account}, or null when the line is not one. */
    static Object[] selectionLine(String line) {
        if (line == null || !line.contains("New selection for product group WoW")) return null;
        Matcher matcher = SELECTION.matcher(line);
        return matcher.find() ? new Object[] {matcher.group(1), new Account(matcher.group(3), matcher.group(2))} : null;
    }

    /**
     * Sorts like Battle.net's account dropdown (by name, numbers by value), so
     * an account's index is its row there.
     */
    public static List<Account> sorted(List<Account> accounts) {
        List<Account> out = new ArrayList<>(accounts);
        out.sort((a, b) -> compareNames(a.name, b.name));
        return out;
    }

    static int compareNames(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i);
            char y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int iEnd = i;
                while (iEnd < a.length() && Character.isDigit(a.charAt(iEnd))) iEnd++;
                int jEnd = j;
                while (jEnd < b.length() && Character.isDigit(b.charAt(jEnd))) jEnd++;
                int order = new java.math.BigInteger(a.substring(i, iEnd)).compareTo(new java.math.BigInteger(b.substring(j, jEnd)));
                if (order != 0) return order;
                i = iEnd;
                j = jEnd;
                continue;
            }
            int order = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
            if (order != 0) return order;
            i++;
            j++;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /**
     * Rows between the selected account and {@code target} in Battle.net's
     * account dropdown (negative: up), or null when either is not one of
     * {@code accounts} or equal names leave their rows unknown.
     */
    public static Integer steps(List<Account> accounts, Account current, Account target) {
        List<Account> rows = sorted(accounts);
        int from = rows.indexOf(current);
        int to = rows.indexOf(target);
        if (from < 0 || to < 0) return null;
        for (int i = 0; i < rows.size(); i++) {
            // Equal names sort in no fixed order, so the row cannot be known.
            if (i != to && compareNames(rows.get(i).name, target.name) == 0) return null;
            if (i != from && compareNames(rows.get(i).name, current.name) == 0) return null;
        }
        return to - from;
    }

    /** The game accounts for {@code uid} from the newest Battle.net logs that list any. */
    public static List<Account> forGame(File logsDir, String uid) {
        return read(logsDir, uid).accounts;
    }

    /** Accounts (Battle.net's order) and selection for {@code uid} from the newest logs that list any. */
    public static Snapshot read(File logsDir, String uid) {
        File[] logs = logsDir != null ? logsDir.listFiles((dir, name) -> name.startsWith("battle.net-") && name.endsWith(".log")) : null;
        if (logs == null) return new Snapshot(new ArrayList<>(), null);
        java.util.Arrays.sort(logs, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = 0; i < logs.length && i < LOGS; i++) {
            if (logs[i].length() > 16L * 1024 * 1024) continue;
            String text;
            try {
                text = new String(java.nio.file.Files.readAllBytes(logs[i].toPath()), java.nio.charset.StandardCharsets.UTF_8);
            }
            catch (java.io.IOException e) {
                continue;
            }
            Map<String, List<Account>> all = parse(text);
            if (all.isEmpty()) continue;
            List<Account> accounts = all.get(key(uid));
            return new Snapshot(accounts != null ? sorted(accounts) : new ArrayList<>(), selection(text, key(uid)));
        }
        return new Snapshot(new ArrayList<>(), null);
    }
}
