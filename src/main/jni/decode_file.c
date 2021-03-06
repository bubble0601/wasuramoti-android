#include <stdio.h>
#include <stdlib.h>
#define STB_VORBIS_HEADER_ONLY
#include "stb_vorbis.c"

#ifdef __ANDROID__
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <jni.h>
#define close_jni() { \
  if(curThread != NULL){(*env)->DeleteLocalRef(env,curThread);}; \
  if(thread != NULL){(*env)->DeleteLocalRef(env,thread);}; \
}
#define abort_if_interrupted() { \
  if(curThread != NULL && mIsInterrupted != NULL){ \
    jboolean res = (jboolean)(*env)->CallBooleanMethod(env, curThread, mIsInterrupted); \
    if(res == JNI_TRUE){ \
      abort_task("Vorbis decoding interrupted.\n"); \
    } \
  } \
}
#else // __ANDROID__
#define ANDROID_LOG_INFO 0
#define __android_log_print(level,tag,...) { \
  printf(__VA_ARGS__); \
}
#define JNIEnv void
#define close_jni() {}
#define abort_if_interrupted() {}
#endif // __ANDROID__

#define abort_task(...) { \
  __android_log_print(ANDROID_LOG_INFO,APP_NAME, __VA_ARGS__); \
  if(data != NULL){free(data);};\
  close_all() \
  return(1); \
}

#define close_all() { \
  if(vin != NULL){stb_vorbis_close(vin);}; \
  close_jni(); \
}

#define APP_NAME "wasuramoti"

// Returns zero for success, non-zero for failure
int decode_asset_or_file(JNIEnv *env, AAssetManager *mgr, const char* fin_path, short ** out_data, int * out_data_len, stb_vorbis_info * return_info){

  // we have to define all the variable used in abort_task() before calling it.

#ifdef __ANDROID__
  jclass thread = NULL;
  jmethodID mCurThread = NULL;
  jmethodID mIsInterrupted = NULL;
  jobject curThread = NULL;
  /* calling AsyncTask.cancel(true) in java code will set isInterrupted() == true */
  thread = (*env)->FindClass(env, "java/lang/Thread");
  if(thread != NULL){
    mCurThread = (*env)->GetStaticMethodID(env, thread, "currentThread", "()Ljava/lang/Thread;");
    mIsInterrupted = (*env)->GetMethodID(env, thread, "isInterrupted", "()Z");
    if(mCurThread != NULL){
      curThread = (jobject)(*env)->CallStaticObjectMethod(env, thread, mCurThread);
    }
  }
#endif

  stb_vorbis *vin = NULL;
  short *data = NULL;
  int error = 0;

  if((vin = stb_vorbis_open_asset_or_file(mgr, fin_path, &error, NULL)) == NULL) {
    abort_task("cannot open read file: %s\n",fin_path)
  }

  *return_info = stb_vorbis_get_info(vin);
  int channels = return_info->channels;

  // copied and altered from stb_vorbis_decode_filename()
  int limit = channels * 4096;
  int offset = 0;
  int data_len = 0;
  int total = limit;
  data = (short *) malloc(total * sizeof(*data));
  if (data == NULL) {
    abort_task("data malloc failed.\n")
  }
  int i;
  for (i=0;;i++) {
    int n = stb_vorbis_get_frame_short_interleaved(vin, channels, data+offset, total-offset);
    if (n == 0) break;
    data_len += n;
    offset += n * channels;
    if (offset + limit > total) {
      short *data2;
      total *= 2;
      data2 = (short *) realloc(data, total * sizeof(*data));
      if (data2 == NULL) {
        abort_task("data realloc failed.\n")
      }
      data = data2;
    }
    if(i%16 == 0){
      abort_if_interrupted(); 
    }
  }
  *out_data = data;  
  *out_data_len = offset;
  // __android_log_print(ANDROID_LOG_INFO,APP_NAME,"Ogg Decode Done.\n");
  close_all();
  return(0);
}

static void writeBytes(JNIEnv *env,jobject output_stream, const char * str){
  int len = strlen(str);
  jbyteArray data = (*env)->NewByteArray(env,len);
  if(data != NULL){
    (*env)->SetByteArrayRegion(env,data,0,len,str);
    jclass outClass = (*env)->FindClass(env, "java/io/OutputStream");
    if(outClass != NULL){
      jmethodID mWriteStream = (*env)->GetMethodID(env,outClass,"write","([B)V");
      if(mWriteStream != NULL){
        (*env)->CallVoidMethod(env,output_stream,mWriteStream,data);
      }
    }
  }
  (*env)->DeleteLocalRef(env,data);
}


int testApi(JNIEnv *env, jobject ostream, AAssetManager * mgr, const char * fin_path){
  char buf[64];
  AAssetOrFILE * af = afopen(mgr,fin_path);
  if(af == NULL){
    return 1;
  }
  long len = aflen(af);
  int i = 0;
  sprintf(buf,"aflen: %ld\nafgetc:",len);
  writeBytes(env,ostream,buf);
  for(i = 0; i < len; i++){
    int c = afgetc(af);
    sprintf(buf," %d",c);
    writeBytes(env,ostream,buf);
  }
  sprintf(buf,"\naftell: %ld\n", aftell(af));
  writeBytes(env,ostream,buf);
  sprintf(buf,"afseek: %d\n", afseek(af,0,SEEK_SET));
  writeBytes(env,ostream,buf);
  unsigned char * rb = malloc(len);
  sprintf(buf,"afread: %d ->", afread(rb,len,1,af));
  writeBytes(env,ostream,buf);
  for(i = 0; i < len; i++){
    sprintf(buf," %u",rb[i]);
    writeBytes(env,ostream,buf);
  }
  free(rb);
  afclose(af);
  return 0;
}
