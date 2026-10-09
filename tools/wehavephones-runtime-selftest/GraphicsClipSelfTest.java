package com.wehavephones.xserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class GraphicsClipSelfTest {
    private static void expect(GraphicsClip clip, int x, int y, int w, int h, String... expected) {
        List<String> actual = new ArrayList<>();
        clip.forEach(x, y, w, h, 100, 100, (a, b, c, d) -> actual.add(a + "," + b + "," + c + "," + d));
        if (!actual.equals(Arrays.asList(expected))) throw new AssertionError(actual + " != " + Arrays.toString(expected));
    }

    public static void main(String[] args) {
        GraphicsClip clip = new GraphicsClip();
        expect(clip, -5, -2, 10, 9, "0,0,5,7");
        clip.setRectangles(10, 20, new short[]{0, 0, 5, 10, 15, 0, 10, 10});
        expect(clip, 0, 0, 100, 100, "10,20,5,10", "25,20,10,10");
        expect(clip, 12, 25, 16, 10, "12,25,3,5", "25,25,3,5");
        GraphicsClip copy = new GraphicsClip();
        copy.copyFrom(clip);
        copy.setOriginX(0);
        copy.setOriginY(0);
        expect(copy, 0, 0, 100, 100, "0,0,5,10", "15,0,10,10");
        expect(clip, 0, 0, 100, 100, "10,20,5,10", "25,20,10,10");
        clip.setRectangles(0, 0, new short[0]);
        expect(clip, 0, 0, 100, 100);
        clip.clear();
        expect(clip, 90, 95, 20, 20, "90,95,10,5");
        clip.setRectangles(-10, -20, new short[]{0, 0, (short)40000, (short)40000});
        expect(clip, 0, 0, 100, 100, "0,0,100,100");
        clip.clear();
        List<String> missing = new ArrayList<>();
        clip.forEachMissingSource(-2, 0, 10, 20, 10, 10, 100, 100, 100, 100,
                (x, y, w, h) -> missing.add(x + "," + y + "," + w + "," + h));
        if (!missing.equals(Arrays.asList("10,20,2,10"))) throw new AssertionError(missing);
        missing.clear();
        clip.forEachMissingSource(95, 95, 10, 20, 10, 10, 100, 100, 100, 100,
                (x, y, w, h) -> missing.add(x + "," + y + "," + w + "," + h));
        if (!missing.equals(Arrays.asList("10,25,10,5", "15,20,5,5"))) throw new AssertionError(missing);
        System.out.println("GraphicsClipSelfTest PASS");
    }
}
