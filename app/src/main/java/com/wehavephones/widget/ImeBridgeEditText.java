package com.wehavephones.widget;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.TextAttribute;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import java.util.function.BooleanSupplier;

public class ImeBridgeEditText extends AppCompatEditText {
    public interface Listener {
        void onCommitText(CharSequence text);
        void onDeleteSurroundingText(int beforeLength, int afterLength);
        void onSendKeyEvent(KeyEvent event);
        void onEditorAction(int actionCode);
    }

    private Listener listener;
    private boolean privateInput;
    private int editing;
    private int batchDepth;
    private String forwardedText = "";

    public ImeBridgeEditText(@NonNull Context context) {
        super(context);
        init();
    }

    public ImeBridgeEditText(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ImeBridgeEditText(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { forwardCommittedText(); }
        });
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** Reset host history without deleting text from the guest. */
    public void resetText() {
        editing++;
        try {
            if (getText() != null) getText().clear();
            forwardedText = "";
        } finally {
            editing--;
        }
    }

    /** Sign-in text: no suggestions, no autocorrect, and nothing learned by the keyboard. */
    public void setPrivateInput(boolean privateInput) {
        this.privateInput = privateInput;
        setInputType(privateInput
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        setImeOptions(privateInput ? EditorInfo.IME_ACTION_GO : EditorInfo.IME_NULL);
    }

    public boolean isPrivateInput() {
        return privateInput;
    }

    private boolean edit(BooleanSupplier action) {
        editing++;
        try {
            return action.getAsBoolean();
        } finally {
            editing--;
            forwardCommittedText();
        }
    }

    private void forwardCommittedText() {
        forwardCommittedText(false);
    }

    private void forwardCommittedText(boolean submitting) {
        if (editing != 0 || (!submitting && batchDepth != 0) || listener == null) return;
        Editable text = getText();
        if (text == null) return;
        int composingStart = BaseInputConnection.getComposingSpanStart(text);
        String committed = text.subSequence(0, composingStart >= 0 ? composingStart : text.length()).toString();
        int common = 0;
        while (common < forwardedText.length() && common < committed.length()
                && forwardedText.codePointAt(common) == committed.codePointAt(common)) {
            common += Character.charCount(committed.codePointAt(common));
        }
        int deleted = forwardedText.codePointCount(common, forwardedText.length());
        String inserted = committed.substring(common);
        forwardedText = committed;
        if (deleted > 0) listener.onDeleteSurroundingText(deleted, 0);
        if (!inserted.isEmpty()) listener.onCommitText(inserted);
    }

    private void finishComposition() {
        Editable text = getText();
        if (text != null) BaseInputConnection.removeComposingSpans(text);
        // An IME may send its editor action before ending the surrounding batch.
        forwardCommittedText(true);
    }

    private static boolean isGuestNavigation(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_TAB
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_MOVE_HOME || keyCode == KeyEvent.KEYCODE_MOVE_END
                || keyCode == KeyEvent.KEYCODE_FORWARD_DEL;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (listener != null) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                finishComposition();
                listener.onEditorAction(EditorInfo.IME_ACTION_DONE);
                resetText();
                return true;
            }
            if (isGuestNavigation(keyCode)) {
                finishComposition();
                resetText();
                listener.onSendKeyEvent(event);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL && getSelectionStart() == 0 && getSelectionEnd() == 0) {
                listener.onDeleteSurroundingText(1, 0);
                return true;
            }
        }
        // Hardware text edits bypass InputConnection.commitText. Observe the real EditText too.
        return edit(() -> super.onKeyDown(keyCode, event));
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (listener != null) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_DEL) return true;
            if (isGuestNavigation(keyCode)) {
                listener.onSendKeyEvent(event);
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        final InputConnection base = super.onCreateInputConnection(outAttrs);
        if (base == null) return null;
        outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (privateInput) outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;

        return new InputConnectionWrapper(base, true) {
            @Override public boolean beginBatchEdit() {
                batchDepth++;
                return super.beginBatchEdit();
            }

            @Override public boolean endBatchEdit() {
                boolean result = super.endBatchEdit();
                batchDepth = Math.max(0, batchDepth - 1);
                forwardCommittedText();
                return result;
            }

            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                return edit(() -> super.commitText(text, newCursorPosition));
            }

            @Override public boolean commitText(@NonNull CharSequence text, int newCursorPosition,
                    @Nullable TextAttribute textAttribute) {
                return edit(() -> super.commitText(text, newCursorPosition, textAttribute));
            }

            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                return edit(() -> super.setComposingText(text, newCursorPosition));
            }

            @Override public boolean setComposingText(@NonNull CharSequence text, int newCursorPosition,
                    @Nullable TextAttribute textAttribute) {
                return edit(() -> super.setComposingText(text, newCursorPosition, textAttribute));
            }

            @Override public boolean finishComposingText() {
                return edit(() -> super.finishComposingText());
            }

            @Override public boolean setComposingRegion(int start, int end) {
                return edit(() -> super.setComposingRegion(start, end));
            }

            @Override public boolean setComposingRegion(int start, int end, @Nullable TextAttribute attributes) {
                return edit(() -> super.setComposingRegion(start, end, attributes));
            }

            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                int available = Math.max(0, getSelectionStart());
                boolean result = edit(() -> super.deleteSurroundingText(beforeLength, afterLength));
                if (listener != null && beforeLength > available) {
                    listener.onDeleteSurroundingText(beforeLength - available, 0);
                }
                return result;
            }

            @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                Editable text = getText();
                int available = text == null ? 0 : Character.codePointCount(text, 0, Math.max(0, getSelectionStart()));
                boolean result = edit(() -> super.deleteSurroundingTextInCodePoints(beforeLength, afterLength));
                if (listener != null && beforeLength > available) {
                    listener.onDeleteSurroundingText(beforeLength - available, 0);
                }
                return result;
            }

            @Override public boolean performEditorAction(int actionCode) {
                if (listener == null) return super.performEditorAction(actionCode);
                finishComposition();
                listener.onEditorAction(actionCode);
                resetText();
                return true;
            }
        };
    }
}
