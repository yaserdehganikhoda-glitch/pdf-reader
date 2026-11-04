package com.pdfvoice.phon;

import java.nio.charset.StandardCharsets;

/**
 * آوانگار بیرونی برای sherpa-onnx ≥ 2.0 (که espeak-ng را از خودش حذف کرده).
 * زیر پوشش: piper-phonemize + espeak-ng (کتابخانهٔ بومی libpiper_phonemize_core.so) از طریق پل libpiperphon_jni.so.
 * خروجی همان چیزی است که sherpa-onnx 2.x به‌عنوان phonemeCodepoints می‌گیرد: برای هر جمله، آرایهٔ کدپوینت‌های یونیکدِ آواها.
 * espeak رشتهٔ سراسری دارد و چندنخی امن نیست؛ پس همهٔ فراخوانی‌ها synchronized هستند.
 */
public final class Phonemizer {
  private static boolean loaded = false;
  private static String loadError = null;
  private static String dataDir = null;

  private Phonemizer() {}

  public static synchronized boolean load() {
    if (loaded) return true;
    try { System.loadLibrary("piperphon_jni"); loaded = true; loadError = null; }
    catch (Throwable t) { loadError = String.valueOf(t); }
    return loaded;
  }

  public static synchronized String loadError() { return loadError; }

  /** espeak-ng-data را معرفی می‌کند. با همان مسیر دوباره صدا زدن بی‌اثر است. */
  public static synchronized void init(String dir) {
    if (!load()) throw new IllegalStateException("کتابخانهٔ آوانگار (espeak) بارگذاری نشد: " + loadError);
    if (dir.equals(dataDir)) return;
    int rc = nativeInit((dir + "\0").getBytes(StandardCharsets.UTF_8));
    if (rc <= 0) throw new IllegalStateException("راه‌اندازی espeak ناموفق بود (کد " + rc + ") — مسیر داده: " + dir);
    dataDir = dir;
  }

  /** @return برای هر جمله آرایهٔ کدپوینت‌های آواها (جمله‌های خالی هم ممکن است برگردند؛ فراخواننده حذفشان کند) */
  public static synchronized int[][] phonemize(String text, String voice) {
    if (dataDir == null) throw new IllegalStateException("آوانگار espeak هنوز راه‌اندازی نشده");
    int[][] r = nativePhonemize((text + "\0").getBytes(StandardCharsets.UTF_8), (voice + "\0").getBytes(StandardCharsets.UTF_8));
    if (r == null) throw new IllegalStateException("آوانگاری espeak ناموفق بود (صدا: " + voice + ")");
    return r;
  }

  public static synchronized String version() {
    try { return load() ? nativeVersion() : ""; } catch (Throwable t) { return ""; }
  }

  private static native int nativeInit(byte[] dirUtf8Z);
  private static native int[][] nativePhonemize(byte[] textUtf8Z, byte[] voiceUtf8Z);
  private static native String nativeVersion();
}
