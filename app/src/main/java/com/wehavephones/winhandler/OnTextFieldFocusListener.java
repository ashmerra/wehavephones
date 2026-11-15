package com.wehavephones.winhandler;

/** The patched win32u reports when a program's focused window starts or stops taking text. */
public interface OnTextFieldFocusListener {
    void onTextFieldFocusChanged(boolean focused, String program);
}
