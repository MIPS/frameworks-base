/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>

#include <cutils/properties.h>

#include <utils/RefBase.h>
#include <utils/Log.h>

#include <ui/PixelFormat.h>
#include <ui/FramebufferNativeWindow.h>
#include <ui/EGLUtils.h>

#include <GLES/gl.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <pixelflinger/pixelflinger.h>

#include "DisplayHardware/DisplayHardware.h"

#include <hardware/copybit.h>
#include <hardware/overlay.h>
#include <hardware/gralloc.h>

using namespace android;


static __attribute__((noinline))
void checkGLErrors()
{
    do {
        // there could be more than one error flag
        GLenum error = glGetError();
        if (error == GL_NO_ERROR)
            break;
        LOGE("GL error 0x%04x", int(error));
    } while(true);
}

static __attribute__((noinline))
void checkEGLErrors(const char* token)
{
    EGLint error = eglGetError();
    if (error && error != EGL_SUCCESS) {
        LOGE("%s: EGL error 0x%04x (%s)",
                token, int(error), EGLUtils::strerror(error));
    }
}

/*
 * Initialize the display to the specified values.
 *
 */

DisplayHardware::DisplayHardware(
        const sp<SurfaceFlinger>& flinger,
        uint32_t dpy)
    : DisplayHardwareBase(flinger, dpy)
{
    init(dpy);
}

DisplayHardware::~DisplayHardware()
{
    fini();
}

float DisplayHardware::getDpiX() const          { return mDpiX; }
float DisplayHardware::getDpiY() const          { return mDpiY; }
float DisplayHardware::getDensity() const       { return mDensity; }
float DisplayHardware::getRefreshRate() const   { return mRefreshRate; }
int DisplayHardware::getWidth() const           { return mWidth; }
int DisplayHardware::getHeight() const          { return mHeight; }
PixelFormat DisplayHardware::getFormat() const  { return mFormat; }

void DisplayHardware::init(uint32_t dpy)
{
    mNativeWindow = new FramebufferNativeWindow();
    framebuffer_device_t const * fbDev = mNativeWindow->getDevice();

    mOverlayEngine = NULL;
    hw_module_t const* module;
    if (hw_get_module(OVERLAY_HARDWARE_MODULE_ID, &module) == 0) {
        overlay_control_open(module, &mOverlayEngine);
    }

    // initialize EGL
    EGLint attribs[] = {
            EGL_SURFACE_TYPE,   EGL_WINDOW_BIT,
            EGL_NONE,           0,
            EGL_NONE
    };

    // debug: disable h/w rendering
    char property[PROPERTY_VALUE_MAX];
    if (property_get("debug.sf.hw", property, NULL) > 0) {
        if (atoi(property) == 0) {
            LOGW("H/W composition disabled");
            attribs[2] = EGL_CONFIG_CAVEAT;
            attribs[3] = EGL_SLOW_CONFIG;
        }
    }

    EGLint w, h, dummy;
    EGLint numConfigs=0;
    EGLSurface surface;
    EGLContext context;
    mFlags = CACHED_BUFFERS;

    // TODO: all the extensions below should be queried through
    // eglGetProcAddress().

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, NULL, NULL);
    eglGetConfigs(display, NULL, 0, &numConfigs);

    EGLConfig config;
    status_t err = EGLUtils::selectConfigForNativeWindow(
            display, attribs, mNativeWindow.get(), &config);
    LOGE_IF(err, "couldn't find an EGLConfig matching the screen format");
    
    EGLint r,g,b,a;
    eglGetConfigAttrib(display, config, EGL_RED_SIZE,   &r);
    eglGetConfigAttrib(display, config, EGL_GREEN_SIZE, &g);
    eglGetConfigAttrib(display, config, EGL_BLUE_SIZE,  &b);
    eglGetConfigAttrib(display, config, EGL_ALPHA_SIZE, &a);

    /*
     * Gather EGL extensions
     */

    const char* const egl_extensions = eglQueryString(
            display, EGL_EXTENSIONS);
    
    LOGI("EGL informations:");
    LOGI("# of configs : %d", numConfigs);
    LOGI("vendor    : %s", eglQueryString(display, EGL_VENDOR));
    LOGI("version   : %s", eglQueryString(display, EGL_VERSION));
    LOGI("extensions: %s", egl_extensions);
    LOGI("Client API: %s", eglQueryString(display, EGL_CLIENT_APIS)?:"Not Supported");
    LOGI("EGLSurface: %d-%d-%d-%d, config=%p", r, g, b, a, config);
    

    if (mNativeWindow->isUpdateOnDemand()) {
        mFlags |= PARTIAL_UPDATES;
    }
    
    if (eglGetConfigAttrib(display, config, EGL_CONFIG_CAVEAT, &dummy) == EGL_TRUE) {
        if (dummy == EGL_SLOW_CONFIG)
            mFlags |= SLOW_CONFIG;
    }

    /*
     * Create our main surface
     */

    surface = eglCreateWindowSurface(display, config, mNativeWindow.get(), NULL);

    if (mFlags & PARTIAL_UPDATES) {
        // if we have partial updates, we definitely don't need to
        // preserve the backbuffer, which may be costly.
        eglSurfaceAttrib(display, surface,
                EGL_SWAP_BEHAVIOR, EGL_BUFFER_DESTROYED);
    }

    if (eglQuerySurface(display, surface, EGL_SWAP_BEHAVIOR, &dummy) == EGL_TRUE) {
        if (dummy == EGL_BUFFER_PRESERVED) {
            mFlags |= BUFFER_PRESERVED;
        }
    }

    eglQuerySurface(display, surface, EGL_WIDTH,  &mWidth);
    eglQuerySurface(display, surface, EGL_HEIGHT, &mHeight);

#ifdef EGL_ANDROID_swap_rectangle    
    if (strstr(egl_extensions, "EGL_ANDROID_swap_rectangle")) {
        if (eglSetSwapRectangleANDROID(display, surface,
                0, 0, mWidth, mHeight) == EGL_TRUE) {
            // This could fail if this extension is not supported by this
            // specific surface (of config)
            mFlags |= SWAP_RECTANGLE;
        }
    }
    // when we have the choice between PARTIAL_UPDATES and SWAP_RECTANGLE
    // choose PARTIAL_UPDATES, which should be more efficient
    if (mFlags & PARTIAL_UPDATES)
        mFlags &= ~SWAP_RECTANGLE;
#endif
    

    LOGI("flags     : %08x", mFlags);
    
    mDpiX = mNativeWindow->xdpi;
    mDpiY = mNativeWindow->ydpi;
    mRefreshRate = fbDev->fps; 
    
    /* Read density from build-specific ro.sf.lcd_density property
     * except if it is overridden by qemu.sf.lcd_density.
     */
    if (property_get("qemu.sf.lcd_density", property, NULL) <= 0) {
        if (property_get("ro.sf.lcd_density", property, NULL) <= 0) {
            LOGW("ro.sf.lcd_density not defined, using 160 dpi by default.");
            strcpy(property, "160");
        }
    } else {
        /* for the emulator case, reset the dpi values too */
        mDpiX = mDpiY = atoi(property);
    }
    mDensity = atoi(property) * (1.0f/160.0f);


    /*
     * Create our OpenGL ES context
     */
    
    context = eglCreateContext(display, config, NULL, NULL);
    
    /*
     * Gather OpenGL ES extensions
     */

    eglMakeCurrent(display, surface, surface, context);
    const char* const  gl_extensions = (const char*)glGetString(GL_EXTENSIONS);
    const char* const  gl_renderer = (const char*)glGetString(GL_RENDERER);
    LOGI("OpenGL informations:");
    LOGI("vendor    : %s", glGetString(GL_VENDOR));
    LOGI("renderer  : %s", gl_renderer);
    LOGI("version   : %s", glGetString(GL_VERSION));
    LOGI("extensions: %s", gl_extensions);

    if (strstr(gl_renderer, "Adreno")) {
        LOGD("Assuming uncached graphics buffers.");
        mFlags &= ~CACHED_BUFFERS;
    }

    if (strstr(gl_extensions, "GL_ARB_texture_non_power_of_two")) {
        mFlags |= NPOT_EXTENSION;
    }
    if (strstr(gl_extensions, "GL_OES_draw_texture")) {
        mFlags |= DRAW_TEXTURE_EXTENSION;
    }
#ifdef EGL_ANDROID_image_native_buffer
    if (strstr( gl_extensions, "GL_OES_EGL_image") &&
        (strstr(egl_extensions, "EGL_KHR_image_base") || 
                strstr(egl_extensions, "EGL_KHR_image")) &&
        strstr(egl_extensions, "EGL_ANDROID_image_native_buffer")) {
        mFlags |= DIRECT_TEXTURE;
    }
#else
#warning "EGL_ANDROID_image_native_buffer not supported"
#endif

    // Unbind the context from this thread
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    mDisplay = display;
    mConfig  = config;
    mSurface = surface;
    mContext = context;
    mFormat  = fbDev->format;
    mPageFlipCount = 0;
}

/*
 * Clean up.  Throw out our local state.
 *
 * (It's entirely possible we'll never get here, since this is meant
 * for real hardware, which doesn't restart.)
 */

void DisplayHardware::fini()
{
    eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglTerminate(mDisplay);
    overlay_control_close(mOverlayEngine);
}

void DisplayHardware::releaseScreen() const
{
    DisplayHardwareBase::releaseScreen();
}

void DisplayHardware::acquireScreen() const
{
    DisplayHardwareBase::acquireScreen();
}

uint32_t DisplayHardware::getPageFlipCount() const {
    return mPageFlipCount;
}

status_t DisplayHardware::compositionComplete() const {
    return mNativeWindow->compositionComplete();
}

void DisplayHardware::flip(const Region& dirty) const
{
    checkGLErrors();

    EGLDisplay dpy = mDisplay;
    EGLSurface surface = mSurface;

#ifdef EGL_ANDROID_swap_rectangle    
    if (mFlags & SWAP_RECTANGLE) {
        const Region newDirty(dirty.intersect(bounds()));
        const Rect b(newDirty.getBounds());
        eglSetSwapRectangleANDROID(dpy, surface,
                b.left, b.top, b.width(), b.height());
    } 
#endif
    
    if (mFlags & PARTIAL_UPDATES) {
        mNativeWindow->setUpdateRectangle(dirty.getBounds());
    }
    
    mPageFlipCount++;
    eglSwapBuffers(dpy, surface);
    checkEGLErrors("eglSwapBuffers");

    // for debugging
    //glClearColor(1,0,0,0);
    //glClear(GL_COLOR_BUFFER_BIT);
}

uint32_t DisplayHardware::getFlags() const
{
    return mFlags;
}

void DisplayHardware::makeCurrent() const
{
    eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
}
