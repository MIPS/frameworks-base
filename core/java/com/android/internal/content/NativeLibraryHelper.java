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

import android.content.pm.PackageParser;
import android.os.Build;
import android.util.Slog;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;


/**
 * Native libraries helper.
 *
 * @hide
 */
public class NativeLibraryHelper {
    private static final String TAG = "NativeHelper";

    private static final boolean DEBUG_NATIVE = false;

    /** Per-application MagicCode hints for emulating Arm code on MIPS: */

    /** These are static global vars rather than passed-through args, because
        - Most of the direct callers deal only with filenames, not package class objects.
        - The filenames are sometimes random temp names without package names.
        - The upper layer knowing about package names is sometimes in some asynchronous thread.
     */

    /** Unique name of application in Google Play market, version independent */
    private static String MCPackageName = "";

    /** Per-app tuning of Build.CPU_ABI, CPU_ABI2, ... */
    private static String[] MCCpuAbi = {"", "", ""};

    /** Default for Arm apps, whether /proc/cpuinfo should encourage optional use of Neon instrs */
    public static final boolean MCShowNeonDefault = true;

    /** Per-app tuning of whether /proc/cpuinfo should encourage optional use of Neon instrs */
    private static boolean MCShowNeon = MCShowNeonDefault;

    /** Save apk package name for per-application tuning of fat apk unpacking */
    public static void customizeAbiByAppname(String packageName) {
        MCPackageName = packageName;
    }

    /** Lookup per-application hints for how to emulate Arm native code on MIPS */
    private static void lookupCustomizedAbi() {

        /* Defaults for when app is not named in the magiccode_prefs.xml file */
        MCCpuAbi[0] = Build.CPU_ABI;
        MCCpuAbi[1] = Build.CPU_ABI2;
        MCCpuAbi[2] = "";
        MCShowNeon = MCShowNeonDefault;
        if (!Build.CPU_ABI.equals("mips"))
            return;

        MCCpuAbi[1] = "armeabi";  /* temp workaround, until libakim works well on more armv7 apps */

        if (MCCpuAbi[1].equals("armeabi-v7a"))
            MCCpuAbi[2] = "armeabi";
        else if (MCCpuAbi[1].equals("armeabi"))
            MCCpuAbi[2] = "armeabi-v7a";

        if (MCPackageName.isEmpty())
            return;

        Slog.d(TAG, "Customizing CpuAbi for " + MCPackageName);
        try {
            InputStream in = new FileInputStream("/data/system/magiccode_prefs.xml");
            try {
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
                        if (xpp.getAttributeValue(0).equals(MCPackageName) ||
                            xpp.getAttributeValue(0).equals("*")             ) {
                            /* AttributeValue(1) version= is now ignored */
                            MCCpuAbi[0] = xpp.getAttributeValue(2);  /* first=  */
                            MCCpuAbi[1] = xpp.getAttributeValue(3);  /* second= */ 
                            MCCpuAbi[2] = xpp.getAttributeValue(4);  /* third=  */
                            /* features= */
                            MCShowNeon = xpp.getAttributeValue(5).indexOf("neon") != -1;
                            Slog.d(TAG, "Customizing CpuAbi to " +
                                         MCCpuAbi[0] + ", " +
                                         MCCpuAbi[1] + ", " +
                                         MCCpuAbi[2] + "; " +
                                        "neon " + MCShowNeon +
                                        " for package " + MCPackageName);
                            break;
                        }
                    }
                    eventType = xpp.next();
                }
            } catch (IOException e) {
                Slog.d(TAG, "Mangled magiccode_prefs.xml file", e);
            } catch (XmlPullParserException e) {
                Slog.d(TAG, "Mangled magiccode_prefs.xml file", e);
            }
            in.close();
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        }
    }

    private static native long nativeSumNativeBinaries(String file, String cpuAbi,
                                                       String cpuAbi2, String cpuAbi3);
    /**
     * Sums the size of native binaries in an APK.
     *
     * @param apkFile APK file to scan for native libraries
     * @return upper bound on size of native binary files for possible abi's, in bytes
     */
    public static long sumNativeBinariesLI(File apkFile) {
        // get default abi1/2/3; don't know packageName for looking up custom abi
        lookupCustomizedAbi();
        return nativeSumNativeBinaries(apkFile.getPath(), MCCpuAbi[0], MCCpuAbi[1], MCCpuAbi[2]);
    }

    private native static int nativeCopyNativeBinaries(String filePath, String sharedLibraryPath,
            String cpuAbi, String cpuAbi2, String cpuAbi3, boolean showNeon);
    /**
     * Copies native binaries to a shared library directory.
     *
     * @param apkFile APK file to scan for native libraries
     * @param sharedLibraryDir directory for libraries to be copied to
     * @return {@link PackageManager#INSTALL_SUCCEEDED} if successful or another
     *         error code from that class if not
     */
    public static int copyNativeBinariesIfNeededLI(File apkFile, File sharedLibraryDir) {
        lookupCustomizedAbi();
        return nativeCopyNativeBinaries(apkFile.getPath(), sharedLibraryDir.getPath(),
                                        MCCpuAbi[0], MCCpuAbi[1], MCCpuAbi[2], MCShowNeon);
    }

    // Convenience method to call removeNativeBinariesFromDirLI(File)
    public static boolean removeNativeBinariesLI(String nativeLibraryPath) {
        return removeNativeBinariesFromDirLI(new File(nativeLibraryPath));
    }

    // Remove the native binaries of a given package. This simply
    // gets rid of the files in the 'lib' sub-directory.
    public static boolean removeNativeBinariesFromDirLI(File nativeLibraryDir) {
        if (DEBUG_NATIVE) {
            Slog.w(TAG, "Deleting native binaries from: " + nativeLibraryDir.getPath());
        }

        boolean deletedFiles = false;

        /*
         * Just remove any file in the directory. Since the directory is owned
         * by the 'system' UID, the application is not supposed to have written
         * anything there.
         */
        if (nativeLibraryDir.exists()) {
            final File[] binaries = nativeLibraryDir.listFiles();
            if (binaries != null) {
                for (int nn = 0; nn < binaries.length; nn++) {
                    if (DEBUG_NATIVE) {
                        Slog.d(TAG, "    Deleting " + binaries[nn].getName());
                    }

                    if (!binaries[nn].delete()) {
                        Slog.w(TAG, "Could not delete native binary: " + binaries[nn].getPath());
                    } else {
                        deletedFiles = true;
                    }
                }
            }
            // Do not delete 'lib' directory itself, or this will prevent
            // installation of future updates.
        }

        return deletedFiles;
    }
}
