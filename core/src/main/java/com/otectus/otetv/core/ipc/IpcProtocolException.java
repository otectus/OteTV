package com.otectus.otetv.core.ipc;

import java.io.IOException;

/** A malformed, oversized or out-of-direction IPC message. The peer is treated as unhealthy and terminated. */
public final class IpcProtocolException extends IOException {
    public IpcProtocolException(String message) {
        super(message);
    }
}
