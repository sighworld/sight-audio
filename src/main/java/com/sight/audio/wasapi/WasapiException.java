package com.sight.audio.wasapi;

/** WASAPI/COM 调用失败。{@link #hr} 是原始 HRESULT，方便对照文档排查。 */
public final class WasapiException extends Exception {

    public final int hr;

    public WasapiException(String what, int hr) {
        super(what + " 失败：0x" + String.format("%08X", hr) + "（" + Com.describe(hr) + "）");
        this.hr = hr;
    }
}
