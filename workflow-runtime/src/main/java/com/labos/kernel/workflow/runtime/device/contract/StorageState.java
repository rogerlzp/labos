package com.labos.kernel.workflow.runtime.device.contract;

/** Truthful local Sidecar WAL storage condition reported in {@code HELLO_ACK}. */
public enum StorageState {
    PERSISTED,
    FRESH_STORAGE,
    CORRUPTED
}
