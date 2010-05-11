/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.hardware;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;
import java.io.IOException;

import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * The Camera class is used to connect/disconnect with the camera service,
 * set capture settings, start/stop preview, snap a picture, and retrieve
 * frames for encoding for video.
 * <p>There is no default constructor for this class. Use {@link #open()} to
 * get a Camera object.</p>
 *
 * <p>In order to use the device camera, you must declare the
 * {@link android.Manifest.permission#CAMERA} permission in your Android
 * Manifest. Also be sure to include the
 * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
 * manifest element in order to declare camera features used by your application.
 * For example, if you use the camera and auto-focus feature, your Manifest
 * should include the following:</p>
 * <pre> &lt;uses-permission android:name="android.permission.CAMERA" />
 * &lt;uses-feature android:name="android.hardware.camera" />
 * &lt;uses-feature android:name="android.hardware.camera.autofocus" /></pre>
 *
 * <p class="caution"><strong>Caution:</strong> Different Android-powered devices
 * may have different hardware specifications, such as megapixel ratings and
 * auto-focus capabilities. In order for your application to be compatible with
 * more devices, you should not make assumptions about the device camera
 * specifications.</p>
 */
public class Camera {
    private static final String TAG = "Camera";

    // These match the enums in frameworks/base/include/ui/Camera.h
    private static final int CAMERA_MSG_ERROR            = 0x001;
    private static final int CAMERA_MSG_SHUTTER          = 0x002;
    private static final int CAMERA_MSG_FOCUS            = 0x004;
    private static final int CAMERA_MSG_ZOOM             = 0x008;
    private static final int CAMERA_MSG_PREVIEW_FRAME    = 0x010;
    private static final int CAMERA_MSG_VIDEO_FRAME      = 0x020;
    private static final int CAMERA_MSG_POSTVIEW_FRAME   = 0x040;
    private static final int CAMERA_MSG_RAW_IMAGE        = 0x080;
    private static final int CAMERA_MSG_COMPRESSED_IMAGE = 0x100;
    private static final int CAMERA_MSG_ALL_MSGS         = 0x1FF;

    private int mNativeContext; // accessed by native methods
    private EventHandler mEventHandler;
    private ShutterCallback mShutterCallback;
    private PictureCallback mRawImageCallback;
    private PictureCallback mJpegCallback;
    private PreviewCallback mPreviewCallback;
    private PictureCallback mPostviewCallback;
    private AutoFocusCallback mAutoFocusCallback;
    private ZoomCallback mZoomCallback;
    private ErrorCallback mErrorCallback;
    private boolean mOneShot;
    private boolean mWithBuffer;

    /**
     * Returns a new Camera object.
     */
    public static Camera open() {
        return new Camera();
    }

    Camera() {
        mShutterCallback = null;
        mRawImageCallback = null;
        mJpegCallback = null;
        mPreviewCallback = null;
        mPostviewCallback = null;
        mZoomCallback = null;

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        native_setup(new WeakReference<Camera>(this));
    }

    protected void finalize() {
        native_release();
    }

    private native final void native_setup(Object camera_this);
    private native final void native_release();


    /**
     * Disconnects and releases the Camera object resources.
     * <p>It is recommended that you call this as soon as you're done with the
     * Camera object.</p>
     */
    public final void release() {
        native_release();
    }

    /**
     * Reconnect to the camera after passing it to MediaRecorder. To save
     * setup/teardown time, a client of Camera can pass an initialized Camera
     * object to a MediaRecorder to use for video recording. Once the
     * MediaRecorder is done with the Camera, this method can be used to
     * re-establish a connection with the camera hardware. NOTE: The Camera
     * object must first be unlocked by the process that owns it before it
     * can be connected to another process.
     *
     * @throws IOException if the method fails.
     *
     * @hide
     */
    public native final void reconnect() throws IOException;

    /**
     * Lock the camera to prevent other processes from accessing it. To save
     * setup/teardown time, a client of Camera can pass an initialized Camera
     * object to another process. This method is used to re-lock the Camera
     * object prevent other processes from accessing it. By default, the
     * Camera object is locked. Locking it again from the same process will
     * have no effect. Attempting to lock it from another process if it has
     * not been unlocked will fail.
     *
     * @throws RuntimeException if the method fails.
     */
    public native final void lock();

    /**
     * Unlock the camera to allow another process to access it. To save
     * setup/teardown time, a client of Camera can pass an initialized Camera
     * object to another process. This method is used to unlock the Camera
     * object before handing off the Camera object to the other process.
     *
     * @throws RuntimeException if the method fails.
     */
    public native final void unlock();

    /**
     * Sets the SurfaceHolder to be used for a picture preview. If the surface
     * changed since the last call, the screen will blank. Nothing happens
     * if the same surface is re-set.
     *
     * @param holder the SurfaceHolder upon which to place the picture preview
     * @throws IOException if the method fails.
     */
    public final void setPreviewDisplay(SurfaceHolder holder) throws IOException {
        if (holder != null) {
            setPreviewDisplay(holder.getSurface());
        } else {
            setPreviewDisplay((Surface)null);
        }
    }

    private native final void setPreviewDisplay(Surface surface);

    /**
     * Used to get a copy of each preview frame.
     */
    public interface PreviewCallback
    {
        /**
         * The callback that delivers the preview frames.
         *
         * @param data The contents of the preview frame in the format defined
         *  by {@link android.graphics.PixelFormat}, which can be queried
         *  with {@link android.hardware.Camera.Parameters#getPreviewFormat()}.
         *  If {@link android.hardware.Camera.Parameters#setPreviewFormat(int)}
         *             is never called, the default will be the YCbCr_420_SP
         *             (NV21) format.
         * @param camera The Camera service object.
         */
        void onPreviewFrame(byte[] data, Camera camera);
    };

    /**
     * Start drawing preview frames to the surface.
     */
    public native final void startPreview();

    /**
     * Stop drawing preview frames to the surface.
     */
    public native final void stopPreview();

    /**
     * Return current preview state.
     *
     * FIXME: Unhide before release
     * @hide
     */
    public native final boolean previewEnabled();

    /**
     * Can be called at any time to instruct the camera to use a callback for
     * each preview frame in addition to displaying it.
     *
     * @param cb A callback object that receives a copy of each preview frame.
     *           Pass null to stop receiving callbacks at any time.
     */
    public final void setPreviewCallback(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = false;
        mWithBuffer = false;
        // Always use one-shot mode. We fake camera preview mode by
        // doing one-shot preview continuously.
        setHasPreviewCallback(cb != null, false);
    }

    /**
     * Installs a callback to retrieve a single preview frame, after which the
     * callback is cleared.
     *
     * @param cb A callback object that receives a copy of the preview frame.
     */
    public final void setOneShotPreviewCallback(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = true;
        mWithBuffer = false;
        setHasPreviewCallback(cb != null, false);
    }

    private native final void setHasPreviewCallback(boolean installed, boolean manualBuffer);

    /**
     * Installs a callback which will get called as long as there are buffers in the
     * preview buffer queue, which minimizes dynamic allocation of preview buffers.
     *
     * Apps must call addCallbackBuffer to explicitly register the buffers to use, or no callbacks
     * will be received. addCallbackBuffer may be safely called before or after
     * a call to setPreviewCallbackWithBuffer with a non-null callback parameter.
     *
     * The buffer queue will be cleared upon any calls to setOneShotPreviewCallback,
     * setPreviewCallback, or to this method with a null callback parameter.
     *
     * @param cb A callback object that receives a copy of the preview frame.  A null value will clear the queue.
     * @hide
     */
    public final void setPreviewCallbackWithBuffer(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = false;
        mWithBuffer = true;
        setHasPreviewCallback(cb != null, true);
    }

    /**
     * Adds a pre-allocated buffer to the callback buffer queue.
     * Preview width and height can be determined from getPreviewSize, and bitsPerPixel can be
     * found from from  {@link android.hardware.Camera.Parameters#getPreviewFormat()} and
     * {@link android.graphics.PixelFormat#getPixelFormatInfo(int, PixelFormat)}
     *
     * Alternatively, a buffer from a previous callback may be passed in or used
     * to determine the size of new preview frame buffers.
     *
     * @param callbackBuffer The buffer to register. Size should be width * height * bitsPerPixel / 8.
     * @hide
     */
    public native final void addCallbackBuffer(byte[] callbackBuffer);

    private class EventHandler extends Handler
    {
        private Camera mCamera;

        public EventHandler(Camera c, Looper looper) {
            super(looper);
            mCamera = c;
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
            case CAMERA_MSG_SHUTTER:
                if (mShutterCallback != null) {
                    mShutterCallback.onShutter();
                }
                return;

            case CAMERA_MSG_RAW_IMAGE:
                if (mRawImageCallback != null) {
                    mRawImageCallback.onPictureTaken((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_COMPRESSED_IMAGE:
                if (mJpegCallback != null) {
                    mJpegCallback.onPictureTaken((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_PREVIEW_FRAME:
                if (mPreviewCallback != null) {
                    PreviewCallback cb = mPreviewCallback;
                    if (mOneShot) {
                        // Clear the callback variable before the callback
                        // in case the app calls setPreviewCallback from
                        // the callback function
                        mPreviewCallback = null;
                    } else if (!mWithBuffer) {
                        // We're faking the camera preview mode to prevent
                        // the app from being flooded with preview frames.
                        // Set to oneshot mode again.
                        setHasPreviewCallback(true, false);
                    }
                    cb.onPreviewFrame((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_POSTVIEW_FRAME:
                if (mPostviewCallback != null) {
                    mPostviewCallback.onPictureTaken((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_FOCUS:
                if (mAutoFocusCallback != null) {
                    mAutoFocusCallback.onAutoFocus(msg.arg1 == 0 ? false : true, mCamera);
                }
                return;

            case CAMERA_MSG_ZOOM:
                if (mZoomCallback != null) {
                    mZoomCallback.onZoomUpdate(msg.arg1, msg.arg2 != 0, mCamera);
                }
                return;

            case CAMERA_MSG_ERROR :
                Log.e(TAG, "Error " + msg.arg1);
                if (mErrorCallback != null) {
                    mErrorCallback.onError(msg.arg1, mCamera);
                }
                return;

            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    }

    private static void postEventFromNative(Object camera_ref,
                                            int what, int arg1, int arg2, Object obj)
    {
        Camera c = (Camera)((WeakReference)camera_ref).get();
        if (c == null)
            return;

        if (c.mEventHandler != null) {
            Message m = c.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            c.mEventHandler.sendMessage(m);
        }
    }

    /**
     * Handles the callback for the camera auto focus.
     * <p>Devices that do not support auto-focus will receive a "fake"
     * callback to this interface. If your application needs auto-focus and
     * should not be installed on devices <em>without</em> auto-focus, you must
     * declare that your app uses the
     * {@code android.hardware.camera.autofocus} feature, in the
     * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
     * manifest element.</p>
     */
    public interface AutoFocusCallback
    {
        /**
         * Callback for the camera auto focus. If the camera does not support
         * auto-focus and autoFocus is called, onAutoFocus will be called
         * immediately with success.
         *
         * @param success true if focus was successful, false if otherwise
         * @param camera  the Camera service object
         */
        void onAutoFocus(boolean success, Camera camera);
    };

    /**
     * Starts auto-focus function and registers a callback function to run when
     * camera is focused. Only valid after startPreview() has been called.
     * Applications should call {@link
     * android.hardware.Camera.Parameters#getFocusMode()} to determine if this
     * method should be called. If the camera does not support auto-focus, it is
     * a no-op and {@link AutoFocusCallback#onAutoFocus(boolean, Camera)}
     * callback will be called immediately.
     * <p>If your application should not be installed
     * on devices without auto-focus, you must declare that your application
     * uses auto-focus with the
     * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
     * manifest element.</p>
     * <p>If the current flash mode is not
     * {@link android.hardware.Camera.Parameters#FLASH_MODE_OFF}, flash may be
     * fired during auto-focus depending on the driver.<p>
     *
     * @param cb the callback to run
     */
    public final void autoFocus(AutoFocusCallback cb)
    {
        mAutoFocusCallback = cb;
        native_autoFocus();
    }
    private native final void native_autoFocus();

    /**
     * Cancels auto-focus function. If the auto-focus is still in progress,
     * this function will cancel it. Whether the auto-focus is in progress
     * or not, this function will return the focus position to the default.
     * If the camera does not support auto-focus, this is a no-op.
     */
    public final void cancelAutoFocus()
    {
        mAutoFocusCallback = null;
        native_cancelAutoFocus();
    }
    private native final void native_cancelAutoFocus();

    /**
     * An interface which contains a callback for the shutter closing after taking a picture.
     */
    public interface ShutterCallback
    {
        /**
         * Can be used to play a shutter sound as soon as the image has been captured, but before
         * the data is available.
         */
        void onShutter();
    }

    /**
     * Handles the callback for when a picture is taken.
     */
    public interface PictureCallback {
        /**
         * Callback for when a picture is taken.
         *
         * @param data   a byte array of the picture data
         * @param camera the Camera service object
         */
        void onPictureTaken(byte[] data, Camera camera);
    };

    /**
     * Triggers an asynchronous image capture. The camera service will initiate
     * a series of callbacks to the application as the image capture progresses.
     * The shutter callback occurs after the image is captured. This can be used
     * to trigger a sound to let the user know that image has been captured. The
     * raw callback occurs when the raw image data is available (NOTE: the data
     * may be null if the hardware does not have enough memory to make a copy).
     * The jpeg callback occurs when the compressed image is available. If the
     * application does not need a particular callback, a null can be passed
     * instead of a callback method.
     *
     * This method will stop the preview. Applications should not call {@link
     * #stopPreview()} before this. After jpeg callback is received,
     * applications can call {@link #startPreview()} to restart the preview.
     *
     * @param shutter   callback after the image is captured, may be null
     * @param raw       callback with raw image data, may be null
     * @param jpeg      callback with jpeg image data, may be null
     */
    public final void takePicture(ShutterCallback shutter, PictureCallback raw,
            PictureCallback jpeg) {
        takePicture(shutter, raw, null, jpeg);
    }
    private native final void native_takePicture();

    /**
     * Triggers an asynchronous image capture. The camera service will initiate
     * a series of callbacks to the application as the image capture progresses.
     * The shutter callback occurs after the image is captured. This can be used
     * to trigger a sound to let the user know that image has been captured. The
     * raw callback occurs when the raw image data is available (NOTE: the data
     * may be null if the hardware does not have enough memory to make a copy).
     * The postview callback occurs when a scaled, fully processed postview
     * image is available (NOTE: not all hardware supports this). The jpeg
     * callback occurs when the compressed image is available. If the
     * application does not need a particular callback, a null can be passed
     * instead of a callback method.
     *
     * This method will stop the preview. Applications should not call {@link
     * #stopPreview()} before this. After jpeg callback is received,
     * applications can call {@link #startPreview()} to restart the preview.
     *
     * @param shutter   callback after the image is captured, may be null
     * @param raw       callback with raw image data, may be null
     * @param postview  callback with postview image data, may be null
     * @param jpeg      callback with jpeg image data, may be null
     */
    public final void takePicture(ShutterCallback shutter, PictureCallback raw,
            PictureCallback postview, PictureCallback jpeg) {
        mShutterCallback = shutter;
        mRawImageCallback = raw;
        mPostviewCallback = postview;
        mJpegCallback = jpeg;
        native_takePicture();
    }

    /**
     * Zooms to the requested value smoothly. Driver will generate {@link
     * #ZoomCallback} for the current zoom value and whether zoom is stopped.
     * The applications can call {@link #stopSmoothZoom} to stop the zoom
     * earlier. The applications should not call startSmoothZoom again or {@link
     * android.hardware.Camera.Parameters#setZoom(int)} before the zoom stops.
     *
     * @param value zoom value. The valid range is 0 to {@link
     *              android.hardware.Camera.Parameters#getMaxZoom}.
     * @hide
     */
    public native final void startSmoothZoom(int value);

    /**
     * Stops the smooth zoom. The applications should wait for the {@link
     * #ZoomCallback} to know when the zoom is actually stopped.
     * @hide
     */
    public native final void stopSmoothZoom();

    /**
     * Handles the zoom callback.
     *
     * @hide
     */
    public interface ZoomCallback
    {
        /**
         * Callback for zoom updates
         *
         * @param zoomValue the current zoom value. In smooth zoom mode, camera
         *                  generates this callback for every new zoom value.
         * @param stopped whether smooth zoom is stopped. If the value is true,
         *                this is the last zoom update for the application.
         *
         * @param camera  the Camera service object
         * @see android.hardware.Camera.Parameters#startSmoothZoom
         */
        void onZoomUpdate(int zoomValue, boolean stopped, Camera camera);
    };

    /**
     * Registers a callback to be invoked when the zoom value is updated by the
     * camera driver during smooth zoom.
     * @param cb the callback to run
     * @hide
     */
    public final void setZoomCallback(ZoomCallback cb)
    {
        mZoomCallback = cb;
    }

    // These match the enum in include/ui/Camera.h
    /** Unspecified camerar error.  @see #ErrorCallback */
    public static final int CAMERA_ERROR_UNKNOWN = 1;
    /** Media server died. In this case, the application must release the
     * Camera object and instantiate a new one. @see #ErrorCallback */
    public static final int CAMERA_ERROR_SERVER_DIED = 100;

    /**
     * Handles the camera error callback.
     */
    public interface ErrorCallback
    {
        /**
         * Callback for camera errors.
         * @param error   error code:
         * <ul>
         * <li>{@link #CAMERA_ERROR_UNKNOWN}
         * <li>{@link #CAMERA_ERROR_SERVER_DIED}
         * </ul>
         * @param camera  the Camera service object
         */
        void onError(int error, Camera camera);
    };

    /**
     * Registers a callback to be invoked when an error occurs.
     * @param cb the callback to run
     */
    public final void setErrorCallback(ErrorCallback cb)
    {
        mErrorCallback = cb;
    }

    private native final void native_setParameters(String params);
    private native final String native_getParameters();

    /**
     * Sets the Parameters for pictures from this Camera service.
     *
     * @param params the Parameters to use for this Camera service
     */
    public void setParameters(Parameters params) {
        native_setParameters(params.flatten());
    }

    /**
     * Returns the picture Parameters for this Camera service.
     */
    public Parameters getParameters() {
        Parameters p = new Parameters();
        String s = native_getParameters();
        p.unflatten(s);
        return p;
    }

    /**
     * Handles the picture size (dimensions).
     */
    public class Size {
        /**
         * Sets the dimensions for pictures.
         *
         * @param w the photo width (pixels)
         * @param h the photo height (pixels)
         */
        public Size(int w, int h) {
            width = w;
            height = h;
        }
        /** width of the picture */
        public int width;
        /** height of the picture */
        public int height;
    };

    /**
     * Handles the parameters for pictures created by a Camera service.
     *
     * <p>To make camera parameters take effect, applications have to call
     * Camera.setParameters. For example, after setWhiteBalance is called, white
     * balance is not changed until Camera.setParameters() is called.
     *
     * <p>Different devices may have different camera capabilities, such as
     * picture size or flash modes. The application should query the camera
     * capabilities before setting parameters. For example, the application
     * should call getSupportedColorEffects before calling setEffect. If the
     * camera does not support color effects, getSupportedColorEffects will
     * return null.
     */
    public class Parameters {
        // Parameter keys to communicate with the camera driver.
        private static final String KEY_PREVIEW_SIZE = "preview-size";
        private static final String KEY_PREVIEW_FORMAT = "preview-format";
        private static final String KEY_PREVIEW_FRAME_RATE = "preview-frame-rate";
        private static final String KEY_PICTURE_SIZE = "picture-size";
        private static final String KEY_PICTURE_FORMAT = "picture-format";
        private static final String KEY_JPEG_THUMBNAIL_WIDTH = "jpeg-thumbnail-width";
        private static final String KEY_JPEG_THUMBNAIL_HEIGHT = "jpeg-thumbnail-height";
        private static final String KEY_JPEG_THUMBNAIL_QUALITY = "jpeg-thumbnail-quality";
        private static final String KEY_JPEG_QUALITY = "jpeg-quality";
        private static final String KEY_ROTATION = "rotation";
        private static final String KEY_GPS_LATITUDE = "gps-latitude";
        private static final String KEY_GPS_LONGITUDE = "gps-longitude";
        private static final String KEY_GPS_ALTITUDE = "gps-altitude";
        private static final String KEY_GPS_TIMESTAMP = "gps-timestamp";
        private static final String KEY_WHITE_BALANCE = "whitebalance";
        private static final String KEY_EFFECT = "effect";
        private static final String KEY_ANTIBANDING = "antibanding";
        private static final String KEY_SCENE_MODE = "scene-mode";
        private static final String KEY_FLASH_MODE = "flash-mode";
        private static final String KEY_FOCUS_MODE = "focus-mode";
        // Parameter key suffix for supported values.
        private static final String SUPPORTED_VALUES_SUFFIX = "-values";

        // Values for white balance settings.
        public static final String WHITE_BALANCE_AUTO = "auto";
        public static final String WHITE_BALANCE_INCANDESCENT = "incandescent";
        public static final String WHITE_BALANCE_FLUORESCENT = "fluorescent";
        public static final String WHITE_BALANCE_WARM_FLUORESCENT = "warm-fluorescent";
        public static final String WHITE_BALANCE_DAYLIGHT = "daylight";
        public static final String WHITE_BALANCE_CLOUDY_DAYLIGHT = "cloudy-daylight";
        public static final String WHITE_BALANCE_TWILIGHT = "twilight";
        public static final String WHITE_BALANCE_SHADE = "shade";

        // Values for color effect settings.
        public static final String EFFECT_NONE = "none";
        public static final String EFFECT_MONO = "mono";
        public static final String EFFECT_NEGATIVE = "negative";
        public static final String EFFECT_SOLARIZE = "solarize";
        public static final String EFFECT_SEPIA = "sepia";
        public static final String EFFECT_POSTERIZE = "posterize";
        public static final String EFFECT_WHITEBOARD = "whiteboard";
        public static final String EFFECT_BLACKBOARD = "blackboard";
        public static final String EFFECT_AQUA = "aqua";

        // Values for antibanding settings.
        public static final String ANTIBANDING_AUTO = "auto";
        public static final String ANTIBANDING_50HZ = "50hz";
        public static final String ANTIBANDING_60HZ = "60hz";
        public static final String ANTIBANDING_OFF = "off";

        // Values for flash mode settings.
        /**
         * Flash will not be fired.
         */
        public static final String FLASH_MODE_OFF = "off";

        /**
         * Flash will be fired automatically when required. The flash may be fired
         * during preview, auto-focus, or snapshot depending on the driver.
         */
        public static final String FLASH_MODE_AUTO = "auto";

        /**
         * Flash will always be fired during snapshot. The flash may also be
         * fired during preview or auto-focus depending on the driver.
         */
        public static final String FLASH_MODE_ON = "on";

        /**
         * Flash will be fired in red-eye reduction mode.
         */
        public static final String FLASH_MODE_RED_EYE = "red-eye";

        /**
         * Constant emission of light during preview, auto-focus and snapshot.
         * This can also be used for video recording.
         */
        public static final String FLASH_MODE_TORCH = "torch";

        // Values for scene mode settings.
        public static final String SCENE_MODE_AUTO = "auto";
        public static final String SCENE_MODE_ACTION = "action";
        public static final String SCENE_MODE_PORTRAIT = "portrait";
        public static final String SCENE_MODE_LANDSCAPE = "landscape";
        public static final String SCENE_MODE_NIGHT = "night";
        public static final String SCENE_MODE_NIGHT_PORTRAIT = "night-portrait";
        public static final String SCENE_MODE_THEATRE = "theatre";
        public static final String SCENE_MODE_BEACH = "beach";
        public static final String SCENE_MODE_SNOW = "snow";
        public static final String SCENE_MODE_SUNSET = "sunset";
        public static final String SCENE_MODE_STEADYPHOTO = "steadyphoto";
        public static final String SCENE_MODE_FIREWORKS = "fireworks";
        public static final String SCENE_MODE_SPORTS = "sports";
        public static final String SCENE_MODE_PARTY = "party";
        public static final String SCENE_MODE_CANDLELIGHT = "candlelight";

        // Values for focus mode settings.
        /**
         * Auto-focus mode.
         */
        public static final String FOCUS_MODE_AUTO = "auto";

        /**
         * Focus is set at infinity. Applications should not call
         * {@link #autoFocus(AutoFocusCallback)} in this mode.
         */
        public static final String FOCUS_MODE_INFINITY = "infinity";
        public static final String FOCUS_MODE_MACRO = "macro";

        /**
         * Focus is fixed. The camera is always in this mode if the focus is not
         * adjustable. If the camera has auto-focus, this mode can fix the
         * focus, which is usually at hyperfocal distance. Applications should
         * not call {@link #autoFocus(AutoFocusCallback)} in this mode.
         */
        public static final String FOCUS_MODE_FIXED = "fixed";

        // Formats for setPreviewFormat and setPictureFormat.
        private static final String PIXEL_FORMAT_YUV422SP = "yuv422sp";
        private static final String PIXEL_FORMAT_YUV420SP = "yuv420sp";
        private static final String PIXEL_FORMAT_YUV422I = "yuv422i-yuyv";
        private static final String PIXEL_FORMAT_RGB565 = "rgb565";
        private static final String PIXEL_FORMAT_JPEG = "jpeg";

        private HashMap<String, String> mMap;

        private Parameters() {
            mMap = new HashMap<String, String>();
        }

        /**
         * Writes the current Parameters to the log.
         * @hide
         * @deprecated
         */
        public void dump() {
            Log.e(TAG, "dump: size=" + mMap.size());
            for (String k : mMap.keySet()) {
                Log.e(TAG, "dump: " + k + "=" + mMap.get(k));
            }
        }

        /**
         * Creates a single string with all the parameters set in
         * this Parameters object.
         * <p>The {@link #unflatten(String)} method does the reverse.</p>
         *
         * @return a String with all values from this Parameters object, in
         *         semi-colon delimited key-value pairs
         */
        public String flatten() {
            StringBuilder flattened = new StringBuilder();
            for (String k : mMap.keySet()) {
                flattened.append(k);
                flattened.append("=");
                flattened.append(mMap.get(k));
                flattened.append(";");
            }
            // chop off the extra semicolon at the end
            flattened.deleteCharAt(flattened.length()-1);
            return flattened.toString();
        }

        /**
         * Takes a flattened string of parameters and adds each one to
         * this Parameters object.
         * <p>The {@link #flatten()} method does the reverse.</p>
         *
         * @param flattened a String of parameters (key-value paired) that
         *                  are semi-colon delimited
         */
        public void unflatten(String flattened) {
            mMap.clear();

            StringTokenizer tokenizer = new StringTokenizer(flattened, ";");
            while (tokenizer.hasMoreElements()) {
                String kv = tokenizer.nextToken();
                int pos = kv.indexOf('=');
                if (pos == -1) {
                    continue;
                }
                String k = kv.substring(0, pos);
                String v = kv.substring(pos + 1);
                mMap.put(k, v);
            }
        }

        public void remove(String key) {
            mMap.remove(key);
        }

        /**
         * Sets a String parameter.
         *
         * @param key   the key name for the parameter
         * @param value the String value of the parameter
         */
        public void set(String key, String value) {
            if (key.indexOf('=') != -1 || key.indexOf(';') != -1) {
                Log.e(TAG, "Key \"" + key + "\" contains invalid character (= or ;)");
                return;
            }
            if (value.indexOf('=') != -1 || value.indexOf(';') != -1) {
                Log.e(TAG, "Value \"" + value + "\" contains invalid character (= or ;)");
                return;
            }

            mMap.put(key, value);
        }

        /**
         * Sets an integer parameter.
         *
         * @param key   the key name for the parameter
         * @param value the int value of the parameter
         */
        public void set(String key, int value) {
            mMap.put(key, Integer.toString(value));
        }

        /**
         * Returns the value of a String parameter.
         *
         * @param key the key name for the parameter
         * @return the String value of the parameter
         */
        public String get(String key) {
            return mMap.get(key);
        }

        /**
         * Returns the value of an integer parameter.
         *
         * @param key the key name for the parameter
         * @return the int value of the parameter
         */
        public int getInt(String key) {
            return Integer.parseInt(mMap.get(key));
        }

        /**
         * Sets the dimensions for preview pictures.
         *
         * @param width  the width of the pictures, in pixels
         * @param height the height of the pictures, in pixels
         */
        public void setPreviewSize(int width, int height) {
            String v = Integer.toString(width) + "x" + Integer.toString(height);
            set(KEY_PREVIEW_SIZE, v);
        }

        /**
         * Returns the dimensions setting for preview pictures.
         *
         * @return a Size object with the height and width setting
         *          for the preview picture
         */
        public Size getPreviewSize() {
            String pair = get(KEY_PREVIEW_SIZE);
            return strToSize(pair);
        }

        /**
         * Gets the supported preview sizes.
         *
         * @return a List of Size object. This method will always return a list
         *         with at least one element.
         */
        public List<Size> getSupportedPreviewSizes() {
            String str = get(KEY_PREVIEW_SIZE + SUPPORTED_VALUES_SUFFIX);
            return splitSize(str);
        }

        /**
         * Sets the dimensions for EXIF thumbnail in Jpeg picture.
         *
         * @param width  the width of the thumbnail, in pixels
         * @param height the height of the thumbnail, in pixels
         */
        public void setJpegThumbnailSize(int width, int height) {
            set(KEY_JPEG_THUMBNAIL_WIDTH, width);
            set(KEY_JPEG_THUMBNAIL_HEIGHT, height);
        }

        /**
         * Returns the dimensions for EXIF thumbnail in Jpeg picture.
         *
         * @return a Size object with the height and width setting for the EXIF
         *         thumbnails
         */
        public Size getJpegThumbnailSize() {
            return new Size(getInt(KEY_JPEG_THUMBNAIL_WIDTH),
                            getInt(KEY_JPEG_THUMBNAIL_HEIGHT));
        }

        /**
         * Sets the quality of the EXIF thumbnail in Jpeg picture.
         *
         * @param quality the JPEG quality of the EXIF thumbnail. The range is 1
         *                to 100, with 100 being the best.
         */
        public void setJpegThumbnailQuality(int quality) {
            set(KEY_JPEG_THUMBNAIL_QUALITY, quality);
        }

        /**
         * Returns the quality setting for the EXIF thumbnail in Jpeg picture.
         *
         * @return the JPEG quality setting of the EXIF thumbnail.
         */
        public int getJpegThumbnailQuality() {
            return getInt(KEY_JPEG_THUMBNAIL_QUALITY);
        }

        /**
         * Sets Jpeg quality of captured picture.
         *
         * @param quality the JPEG quality of captured picture. The range is 1
         *                to 100, with 100 being the best.
         */
        public void setJpegQuality(int quality) {
            set(KEY_JPEG_QUALITY, quality);
        }

        /**
         * Returns the quality setting for the JPEG picture.
         *
         * @return the JPEG picture quality setting.
         */
        public int getJpegQuality() {
            return getInt(KEY_JPEG_QUALITY);
        }

        /**
         * Sets the rate at which preview frames are received.
         *
         * @param fps the frame rate (frames per second)
         */
        public void setPreviewFrameRate(int fps) {
            set(KEY_PREVIEW_FRAME_RATE, fps);
        }

        /**
         * Returns the setting for the rate at which preview frames
         * are received.
         *
         * @return the frame rate setting (frames per second)
         */
        public int getPreviewFrameRate() {
            return getInt(KEY_PREVIEW_FRAME_RATE);
        }

        /**
         * Gets the supported preview frame rates.
         *
         * @return a List of Integer objects (preview frame rates). null if
         *         preview frame rate setting is not supported.
         */
        public List<Integer> getSupportedPreviewFrameRates() {
            String str = get(KEY_PREVIEW_FRAME_RATE + SUPPORTED_VALUES_SUFFIX);
            return splitInt(str);
        }

        /**
         * Sets the image format for preview pictures.
         * <p>If this is never called, the default format will be
         * {@link android.graphics.PixelFormat#YCbCr_420_SP}, which
         * uses the NV21 encoding format.</p>
         *
         * @param pixel_format the desired preview picture format, defined
         *   by one of the {@link android.graphics.PixelFormat} constants.
         *   (E.g., <var>PixelFormat.YCbCr_420_SP</var> (default),
         *                      <var>PixelFormat.RGB_565</var>, or
         *                      <var>PixelFormat.JPEG</var>)
         * @see android.graphics.PixelFormat
         */
        public void setPreviewFormat(int pixel_format) {
            String s = cameraFormatForPixelFormat(pixel_format);
            if (s == null) {
                throw new IllegalArgumentException(
                        "Invalid pixel_format=" + pixel_format);
            }

            set(KEY_PREVIEW_FORMAT, s);
        }

        /**
         * Returns the image format for preview pictures got from
         * {@link PreviewCallback}.
         *
         * @return the {@link android.graphics.PixelFormat} int representing
         *         the preview picture format.
         */
        public int getPreviewFormat() {
            return pixelFormatForCameraFormat(get(KEY_PREVIEW_FORMAT));
        }

        /**
         * Gets the supported preview formats.
         *
         * @return a List of Integer objects. This method will always return a
         *         list with at least one element.
         */
        public List<Integer> getSupportedPreviewFormats() {
            String str = get(KEY_PREVIEW_FORMAT + SUPPORTED_VALUES_SUFFIX);
            ArrayList<Integer> formats = new ArrayList<Integer>();
            for (String s : split(str)) {
                int f = pixelFormatForCameraFormat(s);
                if (f == PixelFormat.UNKNOWN) continue;
                formats.add(f);
            }
            return formats;
        }

        /**
         * Sets the dimensions for pictures.
         *
         * @param width  the width for pictures, in pixels
         * @param height the height for pictures, in pixels
         */
        public void setPictureSize(int width, int height) {
            String v = Integer.toString(width) + "x" + Integer.toString(height);
            set(KEY_PICTURE_SIZE, v);
        }

        /**
         * Returns the dimension setting for pictures.
         *
         * @return a Size object with the height and width setting
         *          for pictures
         */
        public Size getPictureSize() {
            String pair = get(KEY_PICTURE_SIZE);
            return strToSize(pair);
        }

        /**
         * Gets the supported picture sizes.
         *
         * @return a List of Size objects. This method will always return a list
         *         with at least one element.
         */
        public List<Size> getSupportedPictureSizes() {
            String str = get(KEY_PICTURE_SIZE + SUPPORTED_VALUES_SUFFIX);
            return splitSize(str);
        }

        /**
         * Sets the image format for pictures.
         *
         * @param pixel_format the desired picture format
         *                     (<var>PixelFormat.YCbCr_420_SP (NV21)</var>,
         *                      <var>PixelFormat.RGB_565</var>, or
         *                      <var>PixelFormat.JPEG</var>)
         * @see android.graphics.PixelFormat
         */
        public void setPictureFormat(int pixel_format) {
            String s = cameraFormatForPixelFormat(pixel_format);
            if (s == null) {
                throw new IllegalArgumentException(
                        "Invalid pixel_format=" + pixel_format);
            }

            set(KEY_PICTURE_FORMAT, s);
        }

        /**
         * Returns the image format for pictures.
         *
         * @return the PixelFormat int representing the picture format
         */
        public int getPictureFormat() {
            return pixelFormatForCameraFormat(get(KEY_PICTURE_FORMAT));
        }

        /**
         * Gets the supported picture formats.
         *
         * @return a List of Integer objects (values are PixelFormat.XXX). This
         *         method will always return a list with at least one element.
         */
        public List<Integer> getSupportedPictureFormats() {
            String str = get(KEY_PICTURE_FORMAT + SUPPORTED_VALUES_SUFFIX);
            ArrayList<Integer> formats = new ArrayList<Integer>();
            for (String s : split(str)) {
                int f = pixelFormatForCameraFormat(s);
                if (f == PixelFormat.UNKNOWN) continue;
                formats.add(f);
            }
            return formats;
        }

        private String cameraFormatForPixelFormat(int pixel_format) {
            switch(pixel_format) {
            case PixelFormat.YCbCr_422_SP: return PIXEL_FORMAT_YUV422SP;
            case PixelFormat.YCbCr_420_SP: return PIXEL_FORMAT_YUV420SP;
            case PixelFormat.YCbCr_422_I:  return PIXEL_FORMAT_YUV422I;
            case PixelFormat.RGB_565:      return PIXEL_FORMAT_RGB565;
            case PixelFormat.JPEG:         return PIXEL_FORMAT_JPEG;
            default:                       return null;
            }
        }

        private int pixelFormatForCameraFormat(String format) {
            if (format == null)
                return PixelFormat.UNKNOWN;

            if (format.equals(PIXEL_FORMAT_YUV422SP))
                return PixelFormat.YCbCr_422_SP;

            if (format.equals(PIXEL_FORMAT_YUV420SP))
                return PixelFormat.YCbCr_420_SP;

            if (format.equals(PIXEL_FORMAT_YUV422I))
                return PixelFormat.YCbCr_422_I;

            if (format.equals(PIXEL_FORMAT_RGB565))
                return PixelFormat.RGB_565;

            if (format.equals(PIXEL_FORMAT_JPEG))
                return PixelFormat.JPEG;

            return PixelFormat.UNKNOWN;
        }

        /**
         * Sets the orientation of the device in degrees. For example, suppose
         * the natural position of the device is landscape. If the user takes a
         * picture in landscape mode in 2048x1536 resolution, the rotation
         * should be set to 0. If the user rotates the phone 90 degrees
         * clockwise, the rotation should be set to 90. Applications can use
         * {@link android.view.OrientationEventListener} to set this parameter.
         *
         * The camera driver may set orientation in the EXIF header without
         * rotating the picture. Or the driver may rotate the picture and
         * the EXIF thumbnail. If the Jpeg picture is rotated, the orientation
         * in the EXIF header will be missing or 1 (row #0 is top and column #0
         * is left side).
         *
         * @param rotation The orientation of the device in degrees. Rotation
         *                 can only be 0, 90, 180 or 270.
         * @throws IllegalArgumentException if rotation value is invalid.
         * @see android.view.OrientationEventListener
         */
        public void setRotation(int rotation) {
            if (rotation == 0 || rotation == 90 || rotation == 180
                    || rotation == 270) {
                set(KEY_ROTATION, Integer.toString(rotation));
            } else {
                throw new IllegalArgumentException(
                        "Invalid rotation=" + rotation);
            }
        }

        /**
         * Sets GPS latitude coordinate. This will be stored in JPEG EXIF
         * header.
         *
         * @param latitude GPS latitude coordinate.
         */
        public void setGpsLatitude(double latitude) {
            set(KEY_GPS_LATITUDE, Double.toString(latitude));
        }

        /**
         * Sets GPS longitude coordinate. This will be stored in JPEG EXIF
         * header.
         *
         * @param longitude GPS longitude coordinate.
         */
        public void setGpsLongitude(double longitude) {
            set(KEY_GPS_LONGITUDE, Double.toString(longitude));
        }

        /**
         * Sets GPS altitude. This will be stored in JPEG EXIF header.
         *
         * @param altitude GPS altitude in meters.
         */
        public void setGpsAltitude(double altitude) {
            set(KEY_GPS_ALTITUDE, Double.toString(altitude));
        }

        /**
         * Sets GPS timestamp. This will be stored in JPEG EXIF header.
         *
         * @param timestamp GPS timestamp (UTC in seconds since January 1,
         *                  1970).
         */
        public void setGpsTimestamp(long timestamp) {
            set(KEY_GPS_TIMESTAMP, Long.toString(timestamp));
        }

        /**
         * Removes GPS latitude, longitude, altitude, and timestamp from the
         * parameters.
         */
        public void removeGpsData() {
            remove(KEY_GPS_LATITUDE);
            remove(KEY_GPS_LONGITUDE);
            remove(KEY_GPS_ALTITUDE);
            remove(KEY_GPS_TIMESTAMP);
        }

        /**
         * Gets the current white balance setting.
         *
         * @return one of WHITE_BALANCE_XXX string constant. null if white
         *         balance setting is not supported.
         */
        public String getWhiteBalance() {
            return get(KEY_WHITE_BALANCE);
        }

        /**
         * Sets the white balance.
         *
         * @param value WHITE_BALANCE_XXX string constant.
         */
        public void setWhiteBalance(String value) {
            set(KEY_WHITE_BALANCE, value);
        }

        /**
         * Gets the supported white balance.
         *
         * @return a List of WHITE_BALANCE_XXX string constants. null if white
         *         balance setting is not supported.
         */
        public List<String> getSupportedWhiteBalance() {
            String str = get(KEY_WHITE_BALANCE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the current color effect setting.
         *
         * @return one of EFFECT_XXX string constant. null if color effect
         *         setting is not supported.
         */
        public String getColorEffect() {
            return get(KEY_EFFECT);
        }

        /**
         * Sets the current color effect setting.
         *
         * @param value EFFECT_XXX string constants.
         */
        public void setColorEffect(String value) {
            set(KEY_EFFECT, value);
        }

        /**
         * Gets the supported color effects.
         *
         * @return a List of EFFECT_XXX string constants. null if color effect
         *         setting is not supported.
         */
        public List<String> getSupportedColorEffects() {
            String str = get(KEY_EFFECT + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }


        /**
         * Gets the current antibanding setting.
         *
         * @return one of ANTIBANDING_XXX string constant. null if antibanding
         *         setting is not supported.
         */
        public String getAntibanding() {
            return get(KEY_ANTIBANDING);
        }

        /**
         * Sets the antibanding.
         *
         * @param antibanding ANTIBANDING_XXX string constant.
         */
        public void setAntibanding(String antibanding) {
            set(KEY_ANTIBANDING, antibanding);
        }

        /**
         * Gets the supported antibanding values.
         *
         * @return a List of ANTIBANDING_XXX string constants. null if
         *         antibanding setting is not supported.
         */
        public List<String> getSupportedAntibanding() {
            String str = get(KEY_ANTIBANDING + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the current scene mode setting.
         *
         * @return one of SCENE_MODE_XXX string constant. null if scene mode
         *         setting is not supported.
         */
        public String getSceneMode() {
            return get(KEY_SCENE_MODE);
        }

        /**
         * Sets the scene mode. Other parameters may be changed after changing
         * scene mode. For example, flash and supported flash mode may be
         * changed to "off" in night scene mode. After setting scene mode,
         * applications should call getParameters to know if some parameters are
         * changed.
         *
         * @param value SCENE_MODE_XXX string constants.
         */
        public void setSceneMode(String value) {
            set(KEY_SCENE_MODE, value);
        }

        /**
         * Gets the supported scene modes.
         *
         * @return a List of SCENE_MODE_XXX string constant. null if scene mode
         *         setting is not supported.
         */
        public List<String> getSupportedSceneModes() {
            String str = get(KEY_SCENE_MODE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the current flash mode setting.
         *
         * @return one of FLASH_MODE_XXX string constant. null if flash mode
         *         setting is not supported.
         */
        public String getFlashMode() {
            return get(KEY_FLASH_MODE);
        }

        /**
         * Sets the flash mode.
         *
         * @param value FLASH_MODE_XXX string constants.
         */
        public void setFlashMode(String value) {
            set(KEY_FLASH_MODE, value);
        }

        /**
         * Gets the supported flash modes.
         *
         * @return a List of FLASH_MODE_XXX string constants. null if flash mode
         *         setting is not supported.
         */
        public List<String> getSupportedFlashModes() {
            String str = get(KEY_FLASH_MODE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the current focus mode setting.
         *
         * @return one of FOCUS_MODE_XXX string constant. If the camera does not
         *         support auto-focus, this should return {@link
         *         #FOCUS_MODE_FIXED}. If the focus mode is not FOCUS_MODE_FIXED
         *         or {@link #FOCUS_MODE_INFINITY}, applications should call
         *         {@link #autoFocus(AutoFocusCallback)} to start the focus.
         */
        public String getFocusMode() {
            return get(KEY_FOCUS_MODE);
        }

        /**
         * Sets the focus mode.
         *
         * @param value FOCUS_MODE_XXX string constants.
         */
        public void setFocusMode(String value) {
            set(KEY_FOCUS_MODE, value);
        }

        /**
         * Gets the supported focus modes.
         *
         * @return a List of FOCUS_MODE_XXX string constants. This method will
         *         always return a list with at least one element.
         */
        public List<String> getSupportedFocusModes() {
            String str = get(KEY_FOCUS_MODE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets current zoom value. This also works when smooth zoom is in
         * progress.
         *
         * @return the current zoom value. The range is 0 to {@link
         *          #getMaxZoom}.
         * @hide
         */
        public int getZoom() {
            return getInt("zoom");
        }

        /**
         * Sets current zoom value. If {@link #startSmoothZoom(int)} has been
         * called and zoom is not stopped yet, applications should not call this
         * method.
         *
         * @param value zoom value. The valid range is 0 to {@link #getMaxZoom}.
         * @hide
         */
        public void setZoom(int value) {
            set("zoom", value);
        }

        /**
         * Returns true if zoom is supported. Applications should call this
         * before using other zoom methods.
         *
         * @return true if zoom is supported.
         * @hide
         */
        public boolean isZoomSupported() {
            String str = get("zoom-supported");
            return "true".equals(str);
        }

        /**
         * Gets the maximum zoom value allowed for snapshot. This is the maximum
         * value that applications can set to {@link #setZoom(int)}.
         *
         * @return the maximum zoom value supported by the camera.
         * @hide
         */
        public int getMaxZoom() {
            return getInt("max-zoom");
        }

        /**
         * Gets the zoom factors of all zoom values.
         *
         * @return the zoom factors in 1/100 increments. Ex: a zoom of 3.2x is
         *         returned as 320. Accuracy of the value is dependent on the
         *         hardware implementation. The first element of the list is the
         *         zoom factor of first zoom value. If the first zoom value is
         *         0, the zoom factor should be 100. The last element is the
         *         zoom factor of zoom value {@link #getMaxZoom}.
         * @hide
         */
        public List<Integer> getZoomFactors() {
            return splitInt(get("zoom-factors"));
        }

        /**
         * Returns true if smooth zoom is supported. Applications should call
         * this before using other smooth zoom methods.
         *
         * @return true if smooth zoom is supported.
         * @hide
         */
        public boolean isSmoothZoomSupported() {
            String str = get("smooth-zoom-supported");
            return "true".equals(str);
        }

        // Splits a comma delimited string to an ArrayList of String.
        // Return null if the passing string is null or the size is 0.
        private ArrayList<String> split(String str) {
            if (str == null) return null;

            // Use StringTokenizer because it is faster than split.
            StringTokenizer tokenizer = new StringTokenizer(str, ",");
            ArrayList<String> substrings = new ArrayList<String>();
            while (tokenizer.hasMoreElements()) {
                substrings.add(tokenizer.nextToken());
            }
            return substrings;
        }

        // Splits a comma delimited string to an ArrayList of Integer.
        // Return null if the passing string is null or the size is 0.
        private ArrayList<Integer> splitInt(String str) {
            if (str == null) return null;

            StringTokenizer tokenizer = new StringTokenizer(str, ",");
            ArrayList<Integer> substrings = new ArrayList<Integer>();
            while (tokenizer.hasMoreElements()) {
                String token = tokenizer.nextToken();
                substrings.add(Integer.parseInt(token));
            }
            if (substrings.size() == 0) return null;
            return substrings;
        }

        // Splits a comma delimited string to an ArrayList of Size.
        // Return null if the passing string is null or the size is 0.
        private ArrayList<Size> splitSize(String str) {
            if (str == null) return null;

            StringTokenizer tokenizer = new StringTokenizer(str, ",");
            ArrayList<Size> sizeList = new ArrayList<Size>();
            while (tokenizer.hasMoreElements()) {
                Size size = strToSize(tokenizer.nextToken());
                if (size != null) sizeList.add(size);
            }
            if (sizeList.size() == 0) return null;
            return sizeList;
        }

        // Parses a string (ex: "480x320") to Size object.
        // Return null if the passing string is null.
        private Size strToSize(String str) {
            if (str == null) return null;

            int pos = str.indexOf('x');
            if (pos != -1) {
                String width = str.substring(0, pos);
                String height = str.substring(pos + 1);
                return new Size(Integer.parseInt(width),
                                Integer.parseInt(height));
            }
            Log.e(TAG, "Invalid size parameter string=" + str);
            return null;
        }
    };
}
