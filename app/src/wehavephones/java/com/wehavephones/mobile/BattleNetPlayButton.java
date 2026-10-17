package com.wehavephones.mobile;

/**
 * Battle.net's own Play button state on a game's page, classified from the
 * button's pixels. The page paints Play in the game's accent colour and the
 * settings gear beside it always keeps that accent colour, so the two fills
 * are compared instead of absolute colours: a dialog dimming the page dims
 * both equally, while a disabled Play (the game is pre-installed or not
 * released yet, so clicking it does nothing) is the accent at about half
 * brightness. Measured on the Galaxy S23 on 2026-10-17, where the accent
 * itself differs by page and by how far the page has painted (blue
 * {@code 0x0074E0} and orange {@code 0xE07400} were both seen): enabled Play
 * matches the gear, the Forever Stress Test pre-install's disabled Play was
 * {@code 0x003F7A} and {@code 0x7A3F00} against those gears, a ratio of
 * about 0.55 either way.
 *
 * Pure Java (host self-tested).
 */
public final class BattleNetPlayButton {
    public enum State {
        /** The button's pixels were not readable (page not rendered, no gear button). */
        UNKNOWN,
        /** The Play fill matches the accent colour: clicking Play starts the game. */
        ENABLED,
        /** The Play fill is the accent dimmed: Play is greyed out and does nothing. */
        DISABLED
    }

    /** Below this the gear fill is not a painted button (page blank or not rendered). */
    static final int MIN_ACCENT = 90;
    /** Play fill at or below this fraction of the gear fill is the disabled style. */
    static final double DISABLED_RATIO = 0.72;

    private BattleNetPlayButton() {}

    /**
     * @param playFill average fill colour of the Play button ({@code 0xRRGGBB}, -1 unreadable)
     * @param gearFill average fill colour of the settings gear beside Play ({@code 0xRRGGBB}, -1 unreadable)
     */
    public static State classify(int playFill, int gearFill) {
        if (playFill < 0 || gearFill < 0) return State.UNKNOWN;
        int accent = brightness(gearFill);
        if (accent < MIN_ACCENT) return State.UNKNOWN;
        return brightness(playFill) <= accent * DISABLED_RATIO ? State.DISABLED : State.ENABLED;
    }

    static int brightness(int rgb) {
        return Math.max(Math.max((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF), rgb & 0xFF);
    }

    /** {@code 0xRRGGBB} for logs and the launch record; -1 becomes "none". */
    static String hex(int rgb) {
        return rgb < 0 ? "none" : String.format(java.util.Locale.ROOT, "#%06x", rgb);
    }
}
