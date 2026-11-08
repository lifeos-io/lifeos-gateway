package com.lifeos.gateway.error;

import com.lifeos.base.error.ErrorCode;

/** Errors only the gateway produces. Cross-cutting ones come from {@code CommonErrorCode}. */
public enum GatewayErrorCode implements ErrorCode {

    /** The service did not answer within the route's response timeout. */
    GATEWAY_UPSTREAM_TIMEOUT(504, "Upstream service timed out");

    private final int status;
    private final String title;

    GatewayErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
