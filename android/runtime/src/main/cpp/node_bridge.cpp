#include <jni.h>
#include <node.h>
#include <unistd.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static int input_read = -1;
static int output_write = -1;
static int error_write = -1;
static bool started = false;

static void throw_io(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_bbui_runtime_NativeNode_createPipes(JNIEnv *env, jobject) {
    if (started || input_read >= 0) {
        throw_io(env, "Node may only start once per agent process");
        return nullptr;
    }
    int in[2] = {-1, -1}, out[2] = {-1, -1}, err[2] = {-1, -1};
    if (pipe(in) || pipe(out) || pipe(err)) {
        for (int fd : {in[0], in[1], out[0], out[1], err[0], err[1]}) if (fd >= 0) close(fd);
        throw_io(env, "Cannot create Node pipes");
        return nullptr;
    }
    input_read = in[0]; output_write = out[1]; error_write = err[1];
    jint ends[] = {in[1], out[0], err[0]};
    auto result = env->NewIntArray(3);
    env->SetIntArrayRegion(result, 0, 3, ends);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_bbui_runtime_NativeNode_run(JNIEnv *env, jobject, jobjectArray arguments,
                                   jstring home, jstring temp, jstring working_dir) {
    if (started || input_read < 0) {
        throw_io(env, "Node pipes must be initialized exactly once");
        return -1;
    }
    started = true;
    const char *home_chars = env->GetStringUTFChars(home, nullptr);
    const char *temp_chars = env->GetStringUTFChars(temp, nullptr);
    const char *cwd_chars = env->GetStringUTFChars(working_dir, nullptr);
    setenv("HOME", home_chars, 1);
    setenv("TMPDIR", temp_chars, 1);
    setenv("PI_CODING_AGENT_DIR", home_chars, 1);
    setenv("NODE_COMPILE_CACHE", temp_chars, 1);
    int cwd_result = chdir(cwd_chars);
    env->ReleaseStringUTFChars(home, home_chars);
    env->ReleaseStringUTFChars(temp, temp_chars);
    env->ReleaseStringUTFChars(working_dir, cwd_chars);
    if (cwd_result || dup2(input_read, STDIN_FILENO) < 0 ||
        dup2(output_write, STDOUT_FILENO) < 0 || dup2(error_write, STDERR_FILENO) < 0) {
        throw_io(env, "Cannot initialize Node environment/pipes");
        return -1;
    }
    close(input_read); close(output_write); close(error_write);
    const int argc = env->GetArrayLength(arguments);
    std::vector<std::string> values;
    size_t total = 0;
    for (int i = 0; i < argc; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char *utf = env->GetStringUTFChars(value, nullptr);
        values.emplace_back(utf); total += values.back().size() + 1;
        env->ReleaseStringUTFChars(value, utf); env->DeleteLocalRef(value);
    }
    // Node may rewrite argv; provide the contiguous writable memory it expects.
    std::vector<char> storage(total);
    std::vector<char *> argv;
    char *next = storage.data();
    for (auto &value : values) {
        argv.push_back(next); memcpy(next, value.c_str(), value.size() + 1);
        next += value.size() + 1;
    }
    argv.push_back(nullptr);
    return node::Start(argc, argv.data());
}
