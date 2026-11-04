/* پل JNI بین Phonemizer.java و C API کتابخانهٔ piper-phonemize (c-api.h).
 * بدون STL و بدون وابستگی دیگر؛ با C ساخته می‌شود (اگر هدر C++ لازم داشت، build-phonemizer.sh با C++ دوباره تلاش می‌کند). */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "c-api.h"

#ifdef __cplusplus
#define JC(env, fn, ...) ((env)->fn(__VA_ARGS__))
#else
#define JC(env, fn, ...) ((*(env))->fn((env), __VA_ARGS__))
#endif

/* byte[] (UTF-8 و پایان‌دهندهٔ صفر از سمت جاوا) → رشتهٔ C؛ همیشه با صفر تمام می‌شود */
static char *to_cstr(JNIEnv *env, jbyteArray a) {
  if (!a) return NULL;
  jsize n = JC(env, GetArrayLength, a);
  char *s = (char *)malloc((size_t)n + 1);
  if (!s) return NULL;
  if (n > 0) JC(env, GetByteArrayRegion, a, 0, n, (jbyte *)s);
  s[n] = 0;
  return s;
}

JNIEXPORT jint JNICALL Java_com_pdfvoice_phon_Phonemizer_nativeInit(JNIEnv *env, jclass cls, jbyteArray dir) {
  (void)cls;
  char *d = to_cstr(env, dir);
  if (!d) return -2;
  int32_t rc = PiperPhonemizeInitialize(d);
  free(d);
  return (jint)rc;
}

JNIEXPORT jobjectArray JNICALL Java_com_pdfvoice_phon_Phonemizer_nativePhonemize(JNIEnv *env, jclass cls, jbyteArray text, jbyteArray voice) {
  (void)cls;
  char *t = to_cstr(env, text);
  char *v = to_cstr(env, voice);
  if (!t || !v) { free(t); free(v); return NULL; }
  PiperPhonemizeResult *r = PiperPhonemizeText(t, v);
  free(t); free(v);
  if (!r) return NULL;

  int32_t ns = PiperPhonemizeResultGetNumSentences(r);
  if (ns < 0) ns = 0;
  jclass ic = JC(env, FindClass, "[I");
  if (!ic) { PiperPhonemizeDestroyResult(r); return NULL; }
  jobjectArray out = JC(env, NewObjectArray, (jsize)ns, ic, NULL);
  if (!out) { PiperPhonemizeDestroyResult(r); return NULL; }
  for (int32_t i = 0; i < ns; i++) {
    int32_t n = PiperPhonemizeResultGetNumPhonemes(r, i);
    if (n < 0) n = 0;
    const void *p = (const void *)PiperPhonemizeResultGetPhonemes(r, i);   /* uint32 کدپوینت‌های یونیکد */
    jintArray a = JC(env, NewIntArray, (jsize)n);
    if (!a) { PiperPhonemizeDestroyResult(r); return NULL; }
    if (n > 0 && p) JC(env, SetIntArrayRegion, a, 0, (jsize)n, (const jint *)p);
    JC(env, SetObjectArrayElement, out, (jsize)i, a);
    JC(env, DeleteLocalRef, a);
  }
  PiperPhonemizeDestroyResult(r);
  return out;
}

JNIEXPORT jstring JNICALL Java_com_pdfvoice_phon_Phonemizer_nativeVersion(JNIEnv *env, jclass cls) {
  (void)cls;
  const char *v = PiperPhonemizeGetVersionStr();
  return JC(env, NewStringUTF, v ? v : "");
}
