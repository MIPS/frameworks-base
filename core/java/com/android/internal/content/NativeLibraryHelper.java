/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.content;

import static android.content.pm.PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.content.pm.PackageManager.NO_NATIVE_LIBRARIES;
import static android.system.OsConstants.S_IRGRP;
import static android.system.OsConstants.S_IROTH;
import static android.system.OsConstants.S_IRWXU;
import static android.system.OsConstants.S_IXGRP;
import static android.system.OsConstants.S_IXOTH;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Package;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.os.Build;
import android.os.SELinux;
import android.os.SystemProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Slog;

import dalvik.system.CloseGuard;
import dalvik.system.VMRuntime;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Arrays;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * Native libraries helper.
 *
 * @hide
 */
public class NativeLibraryHelper {
    private static final String TAG = "NativeHelper";
    private static final boolean DEBUG_NATIVE = false;

    public static final String LIB_DIR_NAME = "lib";
    public static final String LIB64_DIR_NAME = "lib64";

    // Special value for {@code PackageParser.Package#cpuAbiOverride} to indicate
    // that the cpuAbiOverride must be clear.
    public static final String CLEAR_ABI_OVERRIDE = "-";

    /**
     * A handle to an opened package, consisting of one or more APKs. Used as
     * input to the various NativeLibraryHelper methods. Allows us to scan and
     * parse the APKs exactly once instead of doing it multiple times.
     *
     * @hide
     */
    public static class Handle implements Closeable {
        private final CloseGuard mGuard = CloseGuard.get();
        private volatile boolean mClosed;

        final String packageName;
        final long[] apkHandles;
        final boolean multiArch;
        final boolean extractNativeLibs;

        public static Handle create(File packageFile) throws IOException {
            try {
                final PackageLite lite = PackageParser.parsePackageLite(packageFile, 0);
                return create(lite);
            } catch (PackageParserException e) {
                throw new IOException("Failed to parse package: " + packageFile, e);
            }
        }

        public static Handle create(Package pkg) throws IOException {
            return create(pkg.getAllCodePaths(),
                    (pkg.applicationInfo.flags & ApplicationInfo.FLAG_MULTIARCH) != 0,
                    (pkg.applicationInfo.flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0,
                    pkg.packageName);
        }

        public static Handle create(PackageLite lite) throws IOException {
            return create(lite.getAllCodePaths(), lite.multiArch, lite.extractNativeLibs,
                    lite.packageName);
        }

        private static Handle create(List<String> codePaths, boolean multiArch,
                boolean extractNativeLibs, String packageName) throws IOException {
            final int size = codePaths.size();
            final long[] apkHandles = new long[size];
            for (int i = 0; i < size; i++) {
                final String path = codePaths.get(i);
                apkHandles[i] = nativeOpenApk(path);
                if (apkHandles[i] == 0) {
                    // Unwind everything we've opened so far
                    for (int j = 0; j < i; j++) {
                        nativeClose(apkHandles[j]);
                    }
                    throw new IOException("Unable to open APK: " + path);
                }
            }

            return new Handle(apkHandles, multiArch, extractNativeLibs, packageName);
        }

        Handle(long[] apkHandles, boolean multiArch, boolean extractNativeLibs,
                String packageName) {
            this.apkHandles = apkHandles;
            this.multiArch = multiArch;
            this.extractNativeLibs = extractNativeLibs;
            this.packageName = packageName;
            mGuard.open("close");
        }

        @Override
        public void close() {
            for (long apkHandle : apkHandles) {
                nativeClose(apkHandle);
            }
            mGuard.close();
            mClosed = true;
        }

        @Override
        protected void finalize() throws Throwable {
            if (mGuard != null) {
                mGuard.warnIfOpen();
            }
            try {
                if (!mClosed) {
                    close();
                }
            } finally {
                super.finalize();
            }
        }
    }

    private static native long nativeOpenApk(String path);
    private static native void nativeClose(long handle);

    private static native long nativeSumNativeBinaries(long handle, String cpuAbi);

    private native static int nativeCopyNativeBinaries(long handle, String sharedLibraryPath,
            String abiToCopy, boolean extractNativeLibs, boolean hasNativeBridge);


    /** Per-application MagicCode hints for emulating Arm code on MIPS: */

    /** These are static global vars rather than passed-through args, because
        - Most of the direct callers deal only with filenames, not package class objects.
        - The filenames are sometimes random temp names without package names.
        - The upper layer knowing about package names is sometimes in some asynchronous thread.
     */

    /** Per-app tuning of Build.CPU_ABI, CPU_ABI2, ... */
    private static String[] MCCpuAbi;

    /** Default for Arm apps, whether /proc/cpuinfo should encourage optional use of Neon instrs */
    public static final boolean MCShowNeonDefault = true;

    /** Per-app tuning of whether /proc/cpuinfo should encourage optional use of Neon instrs */
    private static boolean MCShowNeon;



    /** Lookup per-application hints for how to emulate Arm native code on MIPS */
    private static void lookupCustomizedAbi(String packageName, String[] abiList) {

        /* Defaults for when app is not named in the magiccode_prefs.xml file */
        MCCpuAbi = abiList;
        MCShowNeon = MCShowNeonDefault;

        if (!Build.CPU_ABI.equals("mips"))
            return;

        if (packageName.isEmpty())
            return;

        // do not interfere if abi is already overridden
        if(abiList != Build.SUPPORTED_ABIS &&
           abiList != Build.SUPPORTED_32_BIT_ABIS)
            return;

        try {
            InputStream in = new FileInputStream("/data/system/magiccode_prefs.xml");
            try {
                Slog.i(TAG, "Customizing CpuAbi for " + packageName);
                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                factory.setNamespaceAware(true);
                XmlPullParser xpp = factory.newPullParser();
                xpp.setInput(in, "UTF-8");
                int eventType = xpp.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG &&
                        xpp.getName().equals("apk")            ) {
                        Slog.d(TAG, "prefs for "+xpp.getAttributeValue(0));
                        /* TODO: check field names instead of assuming positions */
                        if (xpp.getAttributeValue(0).equals(packageName) ||
                            xpp.getAttributeValue(0).equals("*")             ) {
                            MCCpuAbi = Arrays.copyOf(abiList, 3);
                            /* AttributeValue(1) version= is now ignored */
                            MCCpuAbi[0] = xpp.getAttributeValue(2);  /* first=  */
                            MCCpuAbi[1] = xpp.getAttributeValue(3);  /* second= */
                            MCCpuAbi[2] = xpp.getAttributeValue(4);  /* third=  */
                            /* features= */
                            MCShowNeon = xpp.getAttributeValue(5).indexOf("neon") != -1;
                            Slog.i(TAG, "Customizing CpuAbi to " +
                                         MCCpuAbi[0] + ", " +
                                         MCCpuAbi[1] + ", " +
                                         MCCpuAbi[2] + "; " +
                                        "neon " + MCShowNeon +
                                        " for package " + packageName);
                            break;
                        }
                    }
                    eventType = xpp.next();
                }
            } catch (IOException e) {
                Slog.e(TAG, "Mangled magiccode_prefs.xml file", e);
            } catch (XmlPullParserException e) {
                Slog.e(TAG, "Mangled magiccode_prefs.xml file", e);
            }
            in.close();
        } catch (IOException e) {
            // magiccode prefs file was not found, using original abiList
        }
    }


    private static long sumNativeBinaries(Handle handle, String abi) {
        long sum = 0;
        for (long apkHandle : handle.apkHandles) {
            sum += nativeSumNativeBinaries(apkHandle, abi);
        }
        return sum;
    }

    /**
     * Copies native binaries to a shared library directory.
     *
     * @param handle APK file to scan for native libraries
     * @param sharedLibraryDir directory for libraries to be copied to
     * @return {@link PackageManager#INSTALL_SUCCEEDED} if successful or another
     *         error code from that class if not
     */
    public static int copyNativeBinaries(Handle handle, File sharedLibraryDir, String abi) {
        for (long apkHandle : handle.apkHandles) {
            int res = nativeCopyNativeBinaries(apkHandle, sharedLibraryDir.getPath(), abi,
                    handle.extractNativeLibs, HAS_NATIVE_BRIDGE);
            if (res != INSTALL_SUCCEEDED) {
                return res;
            }
        }
        return INSTALL_SUCCEEDED;
    }

    /**
     * Checks if a given APK contains native code for any of the provided
     * {@code supportedAbis}. Returns an index into {@code supportedAbis} if a matching
     * ABI is found, {@link PackageManager#NO_NATIVE_LIBRARIES} if the
     * APK doesn't contain any native code, and
     * {@link PackageManager#INSTALL_FAILED_NO_MATCHING_ABIS} if none of the ABIs match.
     */
    public static int findSupportedAbi(Handle handle, String[] supportedAbis) {
        int finalRes = NO_NATIVE_LIBRARIES;
        // Override ABI if needed
        lookupCustomizedAbi(handle.packageName, supportedAbis);
        for (long apkHandle : handle.apkHandles) {
            final int res = nativeFindSupportedAbi(apkHandle, MCCpuAbi);
            if (res == NO_NATIVE_LIBRARIES) {
                // No native code, keep looking through all APKs.
            } else if (res == INSTALL_FAILED_NO_MATCHING_ABIS) {
                // Found some native code, but no ABI match; update our final
                // result if we haven't found other valid code.
                if (finalRes < 0) {
                    finalRes = INSTALL_FAILED_NO_MATCHING_ABIS;
                }
            } else if (res >= 0) {
                // Found valid native code, track the best ABI match

                // Convert result from MCCpuAbi to supportedAbis index
                int actualRes = -1;
                for(int i = 0; i < supportedAbis.length; i++) {
                    if(supportedAbis[i].equals(MCCpuAbi[res])) {
                        actualRes = i;
                        break;
                    }
                }

                if(actualRes == -1) {
                    Slog.e(TAG, "Invalid ABI from magiccode_prefs.xml: " + MCCpuAbi[res] + "!");
                    return INSTALL_FAILED_NO_MATCHING_ABIS;
                }
                if (finalRes < 0 || actualRes < finalRes) {
                    finalRes = actualRes;
                }
            } else {
                // Unexpected error; bail
                return res;
            }
        }

        return finalRes;
    }

    private native static int nativeFindSupportedAbi(long handle, String[] supportedAbis);

    // Convenience method to call removeNativeBinariesFromDirLI(File)
    public static void removeNativeBinariesLI(String nativeLibraryPath) {
        if (nativeLibraryPath == null) return;
        removeNativeBinariesFromDirLI(new File(nativeLibraryPath), false /* delete root dir */);
    }

    /**
     * Remove the native binaries of a given package. This deletes the files
     */
    public static void removeNativeBinariesFromDirLI(File nativeLibraryRoot,
            boolean deleteRootDir) {
        if (DEBUG_NATIVE) {
            Slog.w(TAG, "Deleting native binaries from: " + nativeLibraryRoot.getPath());
        }

        /*
         * Just remove any file in the directory. Since the directory is owned
         * by the 'system' UID, the application is not supposed to have written
         * anything there.
         */
        if (nativeLibraryRoot.exists()) {
            final File[] files = nativeLibraryRoot.listFiles();
            if (files != null) {
                for (int nn = 0; nn < files.length; nn++) {
                    if (DEBUG_NATIVE) {
                        Slog.d(TAG, "    Deleting " + files[nn].getName());
                    }

                    if (files[nn].isDirectory()) {
                        removeNativeBinariesFromDirLI(files[nn], true /* delete root dir */);
                    } else if (!files[nn].delete()) {
                        Slog.w(TAG, "Could not delete native binary: " + files[nn].getPath());
                    }
                }
            }
            // Do not delete 'lib' directory itself, unless we're specifically
            // asked to or this will prevent installation of future updates.
            if (deleteRootDir) {
                if (!nativeLibraryRoot.delete()) {
                    Slog.w(TAG, "Could not delete native binary directory: " +
                            nativeLibraryRoot.getPath());
                }
            }
        }
    }

    private static void createNativeLibrarySubdir(File path) throws IOException {
        if (!path.isDirectory()) {
            path.delete();

            if (!path.mkdir()) {
                throw new IOException("Cannot create " + path.getPath());
            }

            try {
                Os.chmod(path.getPath(), S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
            } catch (ErrnoException e) {
                throw new IOException("Cannot chmod native library directory "
                        + path.getPath(), e);
            }
        } else if (!SELinux.restorecon(path)) {
            throw new IOException("Cannot set SELinux context for " + path.getPath());
        }
    }

    private static long sumNativeBinariesForSupportedAbi(Handle handle, String[] abiList) {
        int abi = findSupportedAbi(handle, abiList);
        if (abi >= 0) {
            return sumNativeBinaries(handle, abiList[abi]);
        } else {
            return 0;
        }
    }

    private static void createFlagFile(File libraryRoot, String filename) throws IOException {
        Slog.i(TAG, "Creating flag file = " + libraryRoot.getPath() + "/arm/" + filename);
        File flagFile = new File(libraryRoot, filename);
        if(!flagFile.exists()) {
            flagFile.createNewFile();
            try {
                Os.chmod(flagFile.getPath(), S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
            } catch (ErrnoException e) {
                        Slog.e(TAG, "Error creating flag file = " + libraryRoot.getPath() + "/" + filename);

                throw new IOException("Cannot chmod flag file " + flagFile.getPath(), e);
            }
        }
    }

    public static int copyNativeBinariesForSupportedAbi(Handle handle, File libraryRoot,
            String[] abiList, boolean useIsaSubdir) throws IOException {
        createNativeLibrarySubdir(libraryRoot);
        /*
         * If this is an internal application or our nativeLibraryPath points to
         * the app-lib directory, unpack the libraries if necessary.
         */
        int abi = findSupportedAbi(handle, abiList);
        if (abi >= 0) {
            if (abiList[abi].startsWith("arm") && Build.CPU_ABI.equals("mips")) {
                /* We are copying files, and there are native libs for goal armv5/armv7 abi */
                /* Create the installed program's marker files that guide whether /proc/cpuinfo
                   will show {mips, armv5, armv7 w/o Neon, or armv7 with Neon} for that program. */
                /* When program is launched, these marker files set corresponding bits in
                   thread_struct, which then cause writing of /proc/magic files,
                   which then cause kernel to modify its response to reads of /proc/cpuinfo. */
                createFlagFile(libraryRoot, ".MC_arm");
                if(MCShowNeon) {
                    createFlagFile(libraryRoot, ".Neon");
                }
            }

            /*
             * If we have a matching instruction set, construct a subdir under the native
             * library root that corresponds to this instruction set.
             */
            final String instructionSet = VMRuntime.getInstructionSet(abiList[abi]);
            final File subDir;
            if (useIsaSubdir) {
                final File isaSubdir = new File(libraryRoot, instructionSet);
                createNativeLibrarySubdir(isaSubdir);
                subDir = isaSubdir;
            } else {
                subDir = libraryRoot;
            }

            int copyRet = copyNativeBinaries(handle, subDir, abiList[abi]);
            if (copyRet != PackageManager.INSTALL_SUCCEEDED) {
                return copyRet;
            }
        }

        return abi;
    }

    public static int copyNativeBinariesWithOverride(Handle handle, File libraryRoot,
            String abiOverride) {
        try {
            if (handle.multiArch) {
                // Warn if we've set an abiOverride for multi-lib packages..
                // By definition, we need to copy both 32 and 64 bit libraries for
                // such packages.
                if (abiOverride != null && !CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
                    Slog.w(TAG, "Ignoring abiOverride for multi arch application.");
                }

                int copyRet = PackageManager.NO_NATIVE_LIBRARIES;
                if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                    copyRet = copyNativeBinariesForSupportedAbi(handle, libraryRoot,
                            Build.SUPPORTED_32_BIT_ABIS, true /* use isa specific subdirs */);
                    if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES &&
                            copyRet != PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS) {
                        Slog.w(TAG, "Failure copying 32 bit native libraries; copyRet=" +copyRet);
                        return copyRet;
                    }
                }

                if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
                    copyRet = copyNativeBinariesForSupportedAbi(handle, libraryRoot,
                            Build.SUPPORTED_64_BIT_ABIS, true /* use isa specific subdirs */);
                    if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES &&
                            copyRet != PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS) {
                        Slog.w(TAG, "Failure copying 64 bit native libraries; copyRet=" +copyRet);
                        return copyRet;
                    }
                }
            } else {
                String cpuAbiOverride = null;
                if (CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
                    cpuAbiOverride = null;
                } else if (abiOverride != null) {
                    cpuAbiOverride = abiOverride;
                }

                String[] abiList = (cpuAbiOverride != null) ?
                        new String[] { cpuAbiOverride } : Build.SUPPORTED_ABIS;
                if (Build.SUPPORTED_64_BIT_ABIS.length > 0 && cpuAbiOverride == null &&
                        hasRenderscriptBitcode(handle)) {
                    abiList = Build.SUPPORTED_32_BIT_ABIS;
                }

                int copyRet = copyNativeBinariesForSupportedAbi(handle, libraryRoot, abiList,
                        true /* use isa specific subdirs */);
                if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES) {
                    Slog.w(TAG, "Failure copying native libraries [errorCode=" + copyRet + "]");
                    return copyRet;
                }
            }

            return PackageManager.INSTALL_SUCCEEDED;
        } catch (IOException e) {
            Slog.e(TAG, "Copying native libraries failed", e);
            return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }
    }

    public static long sumNativeBinariesWithOverride(Handle handle, String abiOverride)
            throws IOException {
        long sum = 0;
        if (handle.multiArch) {
            // Warn if we've set an abiOverride for multi-lib packages..
            // By definition, we need to copy both 32 and 64 bit libraries for
            // such packages.
            if (abiOverride != null && !CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
                Slog.w(TAG, "Ignoring abiOverride for multi arch application.");
            }

            if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                sum += sumNativeBinariesForSupportedAbi(handle, Build.SUPPORTED_32_BIT_ABIS);
            }

            if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
                sum += sumNativeBinariesForSupportedAbi(handle, Build.SUPPORTED_64_BIT_ABIS);
            }
        } else {
            String cpuAbiOverride = null;
            if (CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
                cpuAbiOverride = null;
            } else if (abiOverride != null) {
                cpuAbiOverride = abiOverride;
            }

            String[] abiList = (cpuAbiOverride != null) ?
                    new String[] { cpuAbiOverride } : Build.SUPPORTED_ABIS;
            if (Build.SUPPORTED_64_BIT_ABIS.length > 0 && cpuAbiOverride == null &&
                    hasRenderscriptBitcode(handle)) {
                abiList = Build.SUPPORTED_32_BIT_ABIS;
            }

            sum += sumNativeBinariesForSupportedAbi(handle, abiList);
        }
        return sum;
    }

    // We don't care about the other return values for now.
    private static final int BITCODE_PRESENT = 1;

    private static final boolean HAS_NATIVE_BRIDGE =
            !"0".equals(SystemProperties.get("ro.dalvik.vm.native.bridge", "0"));

    private static native int hasRenderscriptBitcode(long apkHandle);

    public static boolean hasRenderscriptBitcode(Handle handle) throws IOException {
        for (long apkHandle : handle.apkHandles) {
            final int res = hasRenderscriptBitcode(apkHandle);
            if (res < 0) {
                throw new IOException("Error scanning APK, code: " + res);
            } else if (res == BITCODE_PRESENT) {
                return true;
            }
        }
        return false;
    }
}
