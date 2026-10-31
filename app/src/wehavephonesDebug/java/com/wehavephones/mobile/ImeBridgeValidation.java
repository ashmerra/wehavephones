package com.wehavephones.mobile;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.TextAttribute;
import android.widget.FrameLayout;

import com.wehavephones.widget.ImeBridgeEditText;

/** Exercises the real Android editor, including hardware and Korean composition.
 * Run only with no live session: adb shell am instrument -w -e display 42
 * com.wehavephones.dev/com.wehavephones.mobile.ImeBridgeValidation */
public final class ImeBridgeValidation extends Instrumentation {
    private int display;
    private int checks;
    private WeHavePhonesActivity activity;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        display = Integer.parseInt(args.getString("display", "0"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent intent = new Intent(getTargetContext(), WeHavePhonesActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display);
            activity = (WeHavePhonesActivity)startActivitySync(intent, options.toBundle());
            runOnMainSync(this::checkEditor);
            result.putString("stream", "PASS: " + checks + " Android IME bridge checks\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            finish(0, result);
        }
    }

    private void checkEditor() {
        ImeBridgeEditText editor = new ImeBridgeEditText(activity);
        FrameLayout host = activity.findViewById(android.R.id.content);
        host.addView(editor, new FrameLayout.LayoutParams(1, 1));
        Sink sink = new Sink();
        editor.setListener(sink);
        editor.requestFocus();
        InputConnection connection = editor.onCreateInputConnection(new EditorInfo());
        try {
            connection.setComposingText("ㄱ", 1);
            connection.setComposingText("가", 1);
            expect("unfinished syllable stays local", "", sink.text.toString());
            connection.finishComposingText();
            expect("finish composition sends syllable", "가", sink.text.toString());
            connection.finishComposingText();
            expect("finish is idempotent", "가", sink.text.toString());
            connection.setComposingText("ㄴ", 1);
            connection.commitText("나", 1);
            expect("commit after compose sends once", "가나", sink.text.toString());
            connection.deleteSurroundingText(1, 0);
            expect("delete reaches guest", "가", sink.text.toString());
            editor.resetText();
            expect("reset preserves guest text", "가", sink.text.toString());
            connection.deleteSurroundingText(1, 0);
            expect("delete works without local history", "", sink.text.toString());

            connection.beginBatchEdit();
            connection.beginBatchEdit();
            connection.setComposingText("한", 1);
            connection.finishComposingText();
            expect("batch holds committed text", "", sink.text.toString());
            connection.endBatchEdit();
            expect("nested batch stays held", "", sink.text.toString());
            connection.endBatchEdit();
            expect("batch sends final text once", "한", sink.text.toString());
            editor.resetText();
            sink.text.setLength(0);

            if (Build.VERSION.SDK_INT >= 33) {
                TextAttribute attributes = new TextAttribute.Builder().build();
                connection.setComposingText("글", 1, attributes);
                expect("modern composing remains local", "", sink.text.toString());
                connection.commitText("글", 1, attributes);
                expect("modern commit reaches guest", "글", sink.text.toString());
                connection.commitText("😀", 1, attributes);
                expect("supplementary Unicode", "글😀", sink.text.toString());
                connection.deleteSurroundingText(2, 0);
                expect("emoji deletion uses one guest key", "글", sink.text.toString());
            }
            editor.resetText();
            sink.text.setLength(0);
            KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents("ab".toCharArray());
            for (KeyEvent event : events) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) editor.onKeyDown(event.getKeyCode(), event);
                else editor.onKeyUp(event.getKeyCode(), event);
            }
            expect("hardware letters forwarded once", "ab", sink.text.toString());
            connection.setComposingText("가", 1);
            editor.onKeyDown(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            editor.onKeyUp(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            expect("hardware Enter flushes final syllable", "ab가", sink.submitted);
            expect("hardware Enter submits once", "1", Integer.toString(sink.submits));
            connection.setComposingText("나", 1);
            connection.performEditorAction(EditorInfo.IME_ACTION_DONE);
            expect("editor action flushes final syllable", "ab가나", sink.submitted);
            expect("editor action submits once", "2", Integer.toString(sink.submits));
            connection.beginBatchEdit();
            connection.setComposingText("한", 1);
            connection.performEditorAction(EditorInfo.IME_ACTION_DONE);
            connection.endBatchEdit();
            expect("submission inside batch flushes first", "ab가나한", sink.submitted);
            expect("batch does not duplicate submission", "3", Integer.toString(sink.submits));
        } finally {
            editor.setListener(null);
            host.removeView(editor);
        }
    }

    private void expect(String name, String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        checks++;
    }

    private static final class Sink implements ImeBridgeEditText.Listener {
        final StringBuilder text = new StringBuilder();
        String submitted;
        int submits;
        @Override public void onCommitText(CharSequence value) { text.append(value); }
        @Override public void onDeleteSurroundingText(int before, int after) {
            for (int i = 0; i < before && text.length() > 0; i++) {
                int start = Character.offsetByCodePoints(text, text.length(), -1);
                text.delete(start, text.length());
            }
        }
        @Override public void onSendKeyEvent(KeyEvent event) {}
        @Override public void onEditorAction(int action) { submitted = text.toString(); submits++; }
    }
}
