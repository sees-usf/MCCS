/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class platu_NativeLib_NativeBDDWrapper */

#ifndef _Included_platu_NativeLib_NativeBDDWrapper
#define _Included_platu_NativeLib_NativeBDDWrapper
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     platu_NativeLib_NativeBDDWrapper
 * Method:    add
 * Signature: ([I)I
 */
JNIEXPORT jint JNICALL Java_platu_NativeLib_NativeBDDWrapper_add
  (JNIEnv *, jobject, jintArray);

/*
 * Class:     platu_NativeLib_NativeBDDWrapper
 * Method:    contains
 * Signature: ([I)Z
 */
JNIEXPORT jboolean JNICALL Java_platu_NativeLib_NativeBDDWrapper_contains
  (JNIEnv *, jobject, jintArray);

/*
 * Class:     platu_NativeLib_NativeBDDWrapper
 * Method:    size
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_platu_NativeLib_NativeBDDWrapper_size
  (JNIEnv *, jobject);

/*
 * Class:     platu_NativeLib_NativeBDDWrapper
 * Method:    stats
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_platu_NativeLib_NativeBDDWrapper_stats
  (JNIEnv *, jobject);

/*
 * Class:     platu_NativeLib_NativeBDDWrapper
 * Method:    initBDDSet
 * Signature: (I[I)V
 */
JNIEXPORT void JNICALL Java_platu_NativeLib_NativeBDDWrapper_initBDDSet
  (JNIEnv *, jobject, jint, jintArray);

#ifdef __cplusplus
}
#endif
#endif
