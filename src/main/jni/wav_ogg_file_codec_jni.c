#include <jni.h>
#include "wav_ogg_file_codec_jni.h"

// Returns zero for success, non-zero for failure
jint
Java_karuta_hpnpwd_audio_OggVorbisDecoder_decodeFile( JNIEnv* env,
                                                  jobject thiz, jstring fin_path, jstring fout_path)
{
  //Get the native string from javaString
  const char *native_fin_path = (*env)->GetStringUTFChars(env, fin_path, 0);
  const char *native_fout_path = (*env)->GetStringUTFChars(env, fout_path, 0);
  struct wav_ogg_file_codec_info wi;
  int r = decode_file(env, native_fin_path, native_fout_path, &wi);
  if(r == 0){
    jclass cls = (*env)->GetObjectClass(env, thiz);
    jfieldID fid = (*env)->GetFieldID(env, cls, "channels","I");
    (*env)->SetIntField(env, thiz, fid, wi.channels);
    fid = (*env)->GetFieldID(env, cls, "rate","J");
    (*env)->SetLongField(env, thiz, fid, wi.rate);
    fid = (*env)->GetFieldID(env, cls, "bit_depth","I");
    (*env)->SetIntField(env, thiz, fid, wi.bit_depth);
    (*env)->DeleteLocalRef(env,cls);
  }
  //Don't forget to release strings
  (*env)->ReleaseStringUTFChars(env, fin_path, native_fin_path);
  (*env)->ReleaseStringUTFChars(env, fout_path, native_fout_path); 
  return r;
} 
