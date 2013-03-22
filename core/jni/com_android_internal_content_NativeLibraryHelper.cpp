/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "NativeLibraryHelper"
//#define LOG_NDEBUG 0

#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <utils/ZipFileRO.h>
#include <ScopedUtfChars.h>

#include <zlib.h>

#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/types.h>


#define APK_LIB "lib/"
#define APK_LIB_LEN (sizeof(APK_LIB) - 1)

#define LIB_PREFIX "/lib"
#define LIB_PREFIX_LEN (sizeof(LIB_PREFIX) - 1)

#define LIB_SUFFIX ".so"
#define LIB_SUFFIX_LEN (sizeof(LIB_SUFFIX) - 1)

#define GDBSERVER "gdbserver"
#define GDBSERVER_LEN (sizeof(GDBSERVER) - 1)

#define TMP_FILE_PATTERN "/tmp.XXXXXX"
#define TMP_FILE_PATTERN_LEN (sizeof(TMP_FILE_PATTERN) - 1)

namespace android {

// These match PackageManager.java install codes
typedef enum {
    INSTALL_SUCCEEDED = 1,
    INSTALL_SUCCEEDED_EXIST = 2,
    INSTALL_FAILED_INVALID_APK = -2,
    INSTALL_FAILED_MIS_ABI = -3,
    INSTALL_FAILED_INSUFFICIENT_STORAGE = -4,
    INSTALL_FAILED_CONTAINER_ERROR = -18,
    INSTALL_FAILED_CHECK_UNSUPPORTED_APK = -50,//abandoned,designed to make certain apk install failed
    INSTALL_FAILED_INTERNAL_ERROR = -110,
} install_status_t;

typedef install_status_t (*iterFunc)(JNIEnv*, void*, ZipFileRO*, ZipEntryRO, const char*);

 /*
   The following flags determines the abi order when certain apk is
   installed and what kind of cpuinfo we should show to certain apk.
   By default, we choose the abi which contains native libs more than others.
   */
static int armv7 = 0;
static int armv5 = 0;
static int summed = 0;
static bool neon = false;

// Equivalent to isFilenameSafe
static bool
isFilenameSafe(const char* filename)
{
    off_t offset = 0;
    for (;;) {
        switch (*(filename + offset)) {
        case 0:
            // Null.
            // If we've reached the end, all the other characters are good.
            return true;

        case 'A' ... 'Z':
        case 'a' ... 'z':
        case '0' ... '9':
        case '+':
        case ',':
        case '-':
        case '.':
        case '/':
        case '=':
        case '_':
            offset++;
            break;

        default:
            // We found something that is not good.
            return false;
        }
    }
    // Should not reach here.
}

static bool
isFileDifferent(char* filePath, size_t fileSize, struct stat64* st)
{
    if (lstat64(filePath, st) < 0) {
        // File is not found or cannot be read.
        ALOGV("Couldn't stat %s, copying: %s\n", filePath, strerror(errno));
        return true;
    }

    if (!S_ISREG(st->st_mode)) {
        return true;
    }

    if (st->st_size != fileSize) {
        return true;
    }

    return false;
}

static install_status_t
sumFiles(JNIEnv* env, void* arg, ZipFileRO* zipFile, ZipEntryRO zipEntry, const char* fileName)
{
    size_t* total = (size_t*) arg;
    size_t uncompLen;

    if (!zipFile->getEntryInfo(zipEntry, NULL, &uncompLen, NULL, NULL, NULL, NULL)) {
        return INSTALL_FAILED_INVALID_APK;
    }

    *total += uncompLen;

    return INSTALL_SUCCEEDED;
}

/*
 * Copy the native library if needed.
 *
 * This function assumes the library and path names passed in are considered safe.
 */
static install_status_t
copyFileIfChanged(JNIEnv *env, void* arg, ZipFileRO* zipFile, ZipEntryRO zipEntry, const char* fileName)
{
    jstring* javaNativeLibPath = (jstring*) arg;
    ScopedUtfChars nativeLibPath(env, *javaNativeLibPath);

    size_t uncompLen;
    long when;
    time_t modTime;

    if (!zipFile->getEntryInfo(zipEntry, NULL, &uncompLen, NULL, NULL, &when, NULL)) {
        ALOGD("Couldn't read zip entry info\n");
        return INSTALL_FAILED_INVALID_APK;
    } else {
        struct tm t;
        ZipFileRO::zipTimeToTimespec(when, &t);
        modTime = mktime(&t);
    }

    // Build local file path
    const size_t fileNameLen = strlen(fileName);
    char localFileName[nativeLibPath.size() + fileNameLen + 10]; // some lib are *-arm.so

    if (strlcpy(localFileName, nativeLibPath.c_str(), sizeof(localFileName)) != nativeLibPath.size()) {
        ALOGD("Couldn't allocate local file name for library");
        return INSTALL_FAILED_INTERNAL_ERROR;
    }

    *(localFileName + nativeLibPath.size()) = '/';

    if (strlcpy(localFileName + nativeLibPath.size() + 1, fileName, sizeof(localFileName)
                    - nativeLibPath.size() - 1) != fileNameLen) {
        ALOGD("Couldn't allocate local file name for library");
        return INSTALL_FAILED_INTERNAL_ERROR;
    }

    // Only copy out the native file if it's different.
    struct stat st;
    if (!isFileDifferent(localFileName, uncompLen, &st)) {
        return INSTALL_SUCCEEDED_EXIST;
    }

    char localTmpFileName[nativeLibPath.size() + TMP_FILE_PATTERN_LEN + 2];
    if (strlcpy(localTmpFileName, nativeLibPath.c_str(), sizeof(localTmpFileName))
            != nativeLibPath.size()) {
        ALOGD("Couldn't allocate local file name for library");
        return INSTALL_FAILED_INTERNAL_ERROR;
    }

    *(localFileName + nativeLibPath.size()) = '/';

    if (strlcpy(localTmpFileName + nativeLibPath.size(), TMP_FILE_PATTERN,
                    TMP_FILE_PATTERN_LEN - nativeLibPath.size()) != TMP_FILE_PATTERN_LEN) {
        ALOGI("Couldn't allocate temporary file name for library");
        return INSTALL_FAILED_INTERNAL_ERROR;
    }

    int fd = mkstemp(localTmpFileName);
    if (fd < 0) {
        ALOGI("Couldn't open temporary file name: %s: %s\n", localTmpFileName, strerror(errno));
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

    if (!zipFile->uncompressEntry(zipEntry, fd)) {
        ALOGI("Failed uncompressing %s to %s\n", fileName, localTmpFileName);
        close(fd);
        unlink(localTmpFileName);
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

    close(fd);

    // Set the modification time for this file to the ZIP's mod time.
    struct timeval times[2];
    times[0].tv_sec = st.st_atime;
    times[1].tv_sec = modTime;
    times[0].tv_usec = times[1].tv_usec = 0;
    if (utimes(localTmpFileName, times) < 0) {
        ALOGI("Couldn't change modification time on %s: %s\n", localTmpFileName, strerror(errno));
        unlink(localTmpFileName);
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

    // Set the mode to 755
    static const mode_t mode = S_IRUSR | S_IWUSR | S_IXUSR | S_IRGRP |  S_IXGRP | S_IROTH | S_IXOTH;
    if (chmod(localTmpFileName, mode) < 0) {
        ALOGI("Couldn't change permissions on %s: %s\n", localTmpFileName, strerror(errno));
        unlink(localTmpFileName);
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

    // Finally, rename it to the final name.
    if (rename(localTmpFileName, localFileName) < 0) {
        ALOGI("Couldn't rename %s to %s: %s\n", localTmpFileName, localFileName, strerror(errno));
        unlink(localTmpFileName);
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

    ALOGV("Successfully moved %s to %s\n", localTmpFileName, localFileName);

    return INSTALL_SUCCEEDED;
}

static install_status_t
iterateOverNativeFiles(JNIEnv *env, jstring javaFilePath, jstring javaCpuAbi, bool flagExecCommand,
                       iterFunc callFunc, void* callArg)
{
    ScopedUtfChars filePath(env, javaFilePath);
    ScopedUtfChars cpuAbi(env, javaCpuAbi);

    bool flagAbiFilter = false;
    bool haveLibso = false;
    char MC_arm_cpufileName[128];
    char Neon[128];

    ZipFileRO zipFile;

    char sdFilePath[filePath.size() + 2];
    strcpy(sdFilePath,filePath.c_str());
    char midChar[] = ";!";
    char *filePathName = NULL , *pkgName = NULL;
    if ((pkgName = strstr(sdFilePath, midChar)) != NULL) {
        *pkgName = '\0';
        pkgName = pkgName + 2;
    }

    if (zipFile.open(sdFilePath) != NO_ERROR) {
        ALOGI("Couldn't open APK %s", sdFilePath);
        return INSTALL_FAILED_INVALID_APK;
    }

    const int N = zipFile.getNumEntries();

    char fileName[PATH_MAX];

    for (int i = 0; i < N; i++) {
        const ZipEntryRO entry = zipFile.findEntryByIndex(i);
        if (entry == NULL) {
            continue;
        }

        // Make sure this entry has a filename.
        if (zipFile.getEntryFileName(entry, fileName, sizeof(fileName))) {
            continue;
        }

        // Make sure we're in the lib directory of the ZIP.
        if (strncmp(fileName, APK_LIB, APK_LIB_LEN)) {
            continue;
        }

        // Make sure the filename is at least to the minimum library name size.
        const size_t fileNameLen = strlen(fileName);
        static const size_t minLength = APK_LIB_LEN + 2 + LIB_PREFIX_LEN + 1 + LIB_SUFFIX_LEN;
        if (fileNameLen < minLength) {
            continue;
        }

        const char* lastSlash = strrchr(fileName, '/');
        // ALOG_ASSERT(lastSlash != NULL, "last slash was null somehow for %s\n", fileName);

        // Check to make sure the CPU ABI of this file is one we support.
        const char* cpuAbiOffset = fileName + APK_LIB_LEN;
        const size_t cpuAbiRegionSize = lastSlash - cpuAbiOffset;
        haveLibso = true;

        ALOGD("Comparing ABIs %s  versus %s", cpuAbi.c_str(),  cpuAbiOffset);
        if (cpuAbi.size() == cpuAbiRegionSize
                && *(cpuAbiOffset + cpuAbi.size()) == '/'
                && !strncmp(cpuAbiOffset, cpuAbi.c_str(), cpuAbiRegionSize)) {
            ALOGD("Using ABI %s", cpuAbi.c_str());
        /* cpuAbi2 case omitted for MagicCode */
        } else {
            ALOGD("abi didn't match anything: %s (end at %zd)", cpuAbiOffset, cpuAbiRegionSize);
            continue;
        }

        flagAbiFilter = true;

        /*
         * if abi is not ("armeabi" && "armeabi-v7a"); we will not exec command.
         */
        if (flagExecCommand && (strcmp(cpuAbi.c_str(), "armeabi"))
                            && (strcmp(cpuAbi.c_str(), "armeabi-v7a"))) {
            flagExecCommand = false;
        }

        // If this is a .so file, check to see if we need to copy it.
        if ((!strncmp(fileName + fileNameLen - LIB_SUFFIX_LEN, LIB_SUFFIX, LIB_SUFFIX_LEN)
                    && !strncmp(lastSlash, LIB_PREFIX, LIB_PREFIX_LEN)
                    && isFilenameSafe(lastSlash + 1))
                || !strncmp(lastSlash + 1, GDBSERVER, GDBSERVER_LEN)) {

            install_status_t ret = callFunc(env, callArg, &zipFile, entry, lastSlash + 1);

            if (ret != INSTALL_SUCCEEDED && ret != INSTALL_SUCCEEDED_EXIST) {
                ALOGV("Failure for entry %s", lastSlash + 1);
                return ret;
            }
            if (flagExecCommand && ret != INSTALL_SUCCEEDED_EXIST) {
                jstring* javaNativeLibPath = (jstring*) callArg;
                ScopedUtfChars nativeLibPath(env, *javaNativeLibPath);
                snprintf(MC_arm_cpufileName, 128, "%s/.MC_arm", nativeLibPath.c_str());
                snprintf(Neon, 128, "%s/.Neon", nativeLibPath.c_str());
            }
        }
    }

    if ((!flagAbiFilter) && haveLibso)
        return INSTALL_FAILED_MIS_ABI;
    if (flagExecCommand && haveLibso) {
        int MC_arm_fd = -1;
        if ((MC_arm_fd = open(MC_arm_cpufileName, O_CREAT|O_RDONLY, 0755)) < 0)
            ALOGD("%s create error errno=%d", MC_arm_cpufileName, errno);
        else
            close(MC_arm_fd);
        if (neon) {
            neon = false;
            int Neon_fd = -1;
            if ((Neon_fd = open(Neon, O_CREAT|O_RDONLY, 0755)) < 0)
                ALOGD("%s create error errno=%d", Neon, errno);
            else
                close(Neon_fd);
        }
    }

    return INSTALL_SUCCEEDED;
}

static jint
com_android_internal_content_NativeLibraryHelper_copyArm(JNIEnv *env, jclass clazz, jstring javaFilePath,
                                                         jstring javaNativeLibPath, jstring javaCpuAbi,
                                                         jstring javaCpuAbi2)
{
    int i;
    int ret = 0;
    const char *arme = "armeabi";
    const char *armev7 = "armeabi-v7a";
    ScopedUtfChars abi(env, javaCpuAbi);
    ScopedUtfChars abi2(env, javaCpuAbi2);
    jstring  CpuAbi;

    if (!strcmp(abi2.c_str(), "NEON"))
        neon = true;
    if (!strcmp(abi.c_str(), "armeabi"))
        CpuAbi = env->NewStringUTF("armeabi");
    else if (!strcmp(abi.c_str(), "armeabi-v7a"))
        CpuAbi = env->NewStringUTF("armeabi");
    else
        CpuAbi = env->NewStringUTF("mips");

    ret = iterateOverNativeFiles(env, javaFilePath, CpuAbi, true, copyFileIfChanged, &javaNativeLibPath);

    if (ret == INSTALL_FAILED_MIS_ABI)
        ret = INSTALL_SUCCEEDED;

    return (jint)ret;
}

static jint
com_android_internal_content_NativeLibraryHelper_copyNativeBinaries(JNIEnv *env, jclass clazz, jstring javaFilePath,
                                                                    jstring javaNativeLibPath, jstring javaCpuAbi,
                                                                    jstring javaCpuAbi2)
{
    int i;
    int ret = 0;
    const char *arme = "armeabi";
    const char *armev7 = "armeabi-v7a";
    armv5 = armv7 = 0;
    ScopedUtfChars abi2(env, javaCpuAbi2);
    jstring  CpuAbi;
    /*
     * Decide the abi order by the list
     */
    if (!strcmp(abi2.c_str(), "NEON"))
        neon = true;
    if (!summed) {
        size_t totalSize = 0;
        static int a[4];
        for (i = 0; i < 4; i++) {
            if (i == 0) {
                CpuAbi = javaCpuAbi;
            } else if (i == 1) {
                CpuAbi = env->NewStringUTF(armev7);
            } else if (i == 2) {
                CpuAbi = env->NewStringUTF(arme);
            } else {
                CpuAbi = env->NewStringUTF("mips-r2");
            }

            ret = iterateOverNativeFiles(env, javaFilePath, CpuAbi, false, sumFiles, &totalSize);
            a[i] = totalSize;
            ALOGD("totalSize = %d,      i = %d",a[i],i);
            if (ret == INSTALL_FAILED_MIS_ABI)
                continue;
        }
        if (a[0] >= 0) {
            if (a[1] - a[0] > 0 || a[2] - a[1] -a[0] > 0) {
                if (a[2] - a[1] <= a[1] - a[0]) {
                    armv7 = 1;
                } else {
                    armv5 = 1;
                }
            }
        }
    }
    if (armv7 || armv5) {
        if (armv7) {
            CpuAbi = env->NewStringUTF(armev7);
        } else if (armv5)
            CpuAbi = env->NewStringUTF(arme);
        ret = iterateOverNativeFiles(env, javaFilePath, CpuAbi, true, copyFileIfChanged, &javaNativeLibPath);
        if (ret == INSTALL_FAILED_MIS_ABI)
            ret = INSTALL_SUCCEEDED;
        return (jint)ret;
    }
    for (i = 0; i < 4; i++) {
        if (i == 0) {
            CpuAbi = javaCpuAbi;
        } else if (i == 1) {
            CpuAbi = env->NewStringUTF(armev7);
        } else if (i == 2) {
            CpuAbi = env->NewStringUTF(arme);
        } else {
            CpuAbi = env->NewStringUTF("mips-r2");
        }

        ret = iterateOverNativeFiles(env, javaFilePath, CpuAbi, true, copyFileIfChanged, &javaNativeLibPath);
        if (ret == INSTALL_FAILED_MIS_ABI) {
            continue;
        } else {
            break;
        }
    }

    if (ret == INSTALL_FAILED_MIS_ABI) {
        ret = INSTALL_SUCCEEDED;
    }
    summed = 0;
    return (jint)ret;

}

static jlong
com_android_internal_content_NativeLibraryHelper_sumNativeBinaries(JNIEnv *env, jclass clazz,
        jstring javaFilePath, jstring javaCpuAbi, jstring javaCpuAbi2)
{

    int i;
    int ret = 0;
    size_t totalSize = 0;
    const char *arme = "armeabi";
    const char *armev7 = "armeabi-v7a";
    jstring  CpuAbi;
    static int a[4];
    summed = 1;
    for (i=0; i < 4; i++){
        if (i == 0) {
            CpuAbi = javaCpuAbi;
        } else if (i == 1) {
            CpuAbi = env->NewStringUTF(armev7);
        } else if (i == 2) {
            CpuAbi = env->NewStringUTF(arme);
        } else {
            CpuAbi = env->NewStringUTF("mips-r2");
        }

        ret = iterateOverNativeFiles(env, javaFilePath, CpuAbi, false, sumFiles, &totalSize);
        a[i] = totalSize;
        if (ret == INSTALL_FAILED_MIS_ABI) {
            continue;
        } else {
            //break;
        }
    }

    /*
     * change to choose abi order
     */
    if (a[0] >= 0) {
        if (a[1] - a[0] > 0 || a[2] - a[1] -a[0] > 0) {
            if (a[2] - a[1] <= a[1] - a[0]) {
                armv7 = 1;
            } else {
                armv5 = 1;
            }
        }
    }
    return totalSize;
}

static JNINativeMethod gMethods[] = {
    {"nativeCopyNativeBinaries",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
            (void *)com_android_internal_content_NativeLibraryHelper_copyNativeBinaries},
    {"nativeCopyArm",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
            (void *)com_android_internal_content_NativeLibraryHelper_copyArm},
    {"nativeSumNativeBinaries",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J",
            (void *)com_android_internal_content_NativeLibraryHelper_sumNativeBinaries},
};


int register_com_android_internal_content_NativeLibraryHelper(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env,
                "com/android/internal/content/NativeLibraryHelper", gMethods, NELEM(gMethods));
}

};
