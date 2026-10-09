package com.wehavephones.mobile.addons.provider;

public class ProviderException extends Exception {
    public enum Kind {AUTH, RATE_LIMIT, NOT_FOUND, NETWORK, PARSE, REMOVED, UNSUPPORTED, CANCELLED, OTHER}

    public final Kind kind;

    public ProviderException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ProviderException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public boolean isAuth() {
        return kind == Kind.AUTH;
    }
}
