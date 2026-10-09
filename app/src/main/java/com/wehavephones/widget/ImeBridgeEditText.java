package com.wehavephones.widget;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

public class ImeBridgeEditText extends AppCompatEditText {
    public interface Listener {
        void onCommitText(CharSequence text);
        void onDeleteSurroundingText(int beforeLength, int afterLength);
        void onSendKeyEvent(KeyEvent event);
        void onEditorAction(int actionCode);
    }

    private Listener listener;
    private boolean privateInput;

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
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
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

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        final InputConnection base = super.onCreateInputConnection(outAttrs);
        outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_FULLSCREEN;
        outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (privateInput) outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;

        return new InputConnectionWrapper(base, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (listener != null && text != null && text.length() > 0) {
                    listener.onCommitText(text);
                }
                boolean result = super.commitText(text, newCursorPosition);
                Editable editable = getEditableText();
                if (editable != null && editable.length() > 64) {
                    editable.delete(0, editable.length() - 32);
                }
                return result;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                return super.setComposingText(text, newCursorPosition);
            }

            @Override
            public boolean finishComposingText() {
                return super.finishComposingText();
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (listener != null) {
                    listener.onDeleteSurroundingText(beforeLength, afterLength);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (listener != null && event != null) {
                    listener.onSendKeyEvent(event);
                }
                return super.sendKeyEvent(event);
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                if (listener != null) {
                    listener.onEditorAction(actionCode);
                }
                // The listener presses Enter for sign-in text; the default action would hide the keyboard.
                if (privateInput) return true;
                return super.performEditorAction(actionCode);
            }
        };
    }
}
