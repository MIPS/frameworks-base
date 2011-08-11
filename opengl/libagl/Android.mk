LOCAL_PATH:= $(call my-dir)

#
# Build the software OpenGL ES library
#

include $(CLEAR_VARS)

# Set to 1 to use gralloc and copybits
LIBAGL_USE_GRALLOC_COPYBITS := 1

LOCAL_SRC_FILES:= \
	Tokenizer.cpp               \
	TokenManager.cpp            \
	TextureObjectManager.cpp    \
	BufferObjectManager.cpp     \
	array.cpp.arch		    \
	fp.cpp.arch		    \
	light.cpp.arch		    \
	matrix.cpp.arch		    \
	mipmap.cpp.arch		    \
	primitives.cpp.arch	    \
	vertex.cpp.arch

LOCAL_CFLAGS += -DLOG_TAG=\"libagl\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES
LOCAL_CFLAGS += -fvisibility=hidden

LOCAL_SHARED_LIBRARIES := libcutils libhardware libutils libpixelflinger libETC1
LOCAL_LDLIBS := -lpthread -ldl

ifeq ($(TARGET_ARCH),arm)
	LOCAL_SRC_FILES += fixed_asm.S iterators.S
	LOCAL_CFLAGS += -fstrict-aliasing
endif

ifeq ($(ARCH_MIPS_HAS_MIPS16),true)
	# The TLS hardware register is not supported with MIPS16
	LOCAL_SRC_FILES += egl.cpp.arch state.cpp.arch texture.cpp.arch
else
	LOCAL_SRC_FILES += egl.cpp state.cpp texture.cpp
endif

ifeq ($(TARGET_ARCH),mips)
    ifneq ($(ARCH_MIPS_HAS_FPU),true)
	LOCAL_SRC_FILES += arch-$(TARGET_ARCH)/fixed_asm.S
    endif
    LOCAL_CFLAGS += -fstrict-aliasing
    # The graphics code can generate division by zero
    LOCAL_CFLAGS += -mno-check-zero-division
endif

ifneq ($(TARGET_SIMULATOR),true)
    # we need to access the private Bionic header <bionic_tls.h>
    # on ARM platforms, we need to mirror the ARCH_ARM_HAVE_TLS_REGISTER
    # behavior from the bionic Android.mk file
    ifeq ($(TARGET_ARCH)-$(ARCH_ARM_HAVE_TLS_REGISTER),arm-true)
        LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
    endif
    LOCAL_C_INCLUDES += bionic/libc/private
endif

ifeq ($(LIBAGL_USE_GRALLOC_COPYBITS),1)
    LOCAL_CFLAGS += -DLIBAGL_USE_GRALLOC_COPYBITS
    LOCAL_SRC_FILES += copybit.cpp
    LOCAL_SHARED_LIBRARIES += libui
endif


LOCAL_MODULE_PATH := $(TARGET_OUT_SHARED_LIBRARIES)/egl
LOCAL_MODULE:= libGLES_android

include $(BUILD_SHARED_LIBRARY)
