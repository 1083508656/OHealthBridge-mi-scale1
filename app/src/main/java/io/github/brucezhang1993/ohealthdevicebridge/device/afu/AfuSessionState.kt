package io.github.brucezhang1993.ohealthdevicebridge.device.afu

enum class AfuSessionState {
    DISCONNECTED,
    CONNECTING,
    DISCOVERING,
    SUBSCRIBING,
    HANDSHAKE,
    IDLE_HISTORY,
    LIVE,
    WAIT_IDLE,
    COMMIT,
    DRAIN_HISTORY,
    FINISHED,
}
