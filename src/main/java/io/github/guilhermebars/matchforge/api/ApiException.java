package io.github.guilhermebars.matchforge.api;

public class ApiException extends RuntimeException {
    final int status;
    final String code;
    final Object order;
    final boolean replay;
    public ApiException(int status, String code) { this(status, code, null, false); }
    public ApiException(int status, String code, Object order, boolean replay) {
        super(code); this.status = status; this.code = code; this.order = order; this.replay = replay;
    }
}
