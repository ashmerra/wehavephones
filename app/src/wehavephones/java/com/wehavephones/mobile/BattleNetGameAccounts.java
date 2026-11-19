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
 * {@code GameAccount added to family: family=WoW account=GameAccount(WoW:wow_classic_era region=US entity=... name=WoW1)}.
 * Only the names and regions are kept, never the account entity or email.
 * Pure Java, host-tested.
 */
public final class BattleNetGameAccounts {
    private static final Pattern ACCOUNT = Pattern.compile(
            "GameAccount\\(WoW:([a-z0-9_]+) region=([A-Z]{2}) entity=\\S+ name=([A-Za-z0-9]+)\\)");
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

        @Override
        public boolean equals(Object other) {
            return other instanceof Account && ((Account)other).name.equals(name) && ((Account)other).region.equals(region);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + region.hashCode();
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

    /** The game accounts for {@code uid} from the newest Battle.net logs that list any. */
    public static List<Account> forGame(File logsDir, String uid) {
        File[] logs = logsDir != null ? logsDir.listFiles((dir, name) -> name.startsWith("battle.net-") && name.endsWith(".log")) : null;
        if (logs == null) return new ArrayList<>();
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
            return accounts != null ? accounts : new ArrayList<>();
        }
        return new ArrayList<>();
    }
}
