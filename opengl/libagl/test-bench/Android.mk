ifeq ($(TARGET_ARCH),mips)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= libagl_bench_test.c

ifneq ($(ARCH_MIPS_HAVE_FPU),true)
    LOCAL_SRC_FILES += ../arch-mips/fixed_asm.S
endif

LOCAL_MODULE:= libagl_bench_test

LOCAL_FORCE_STATIC_EXECUTABLE := true
LOCAL_MODULE_PATH := $(TARGET_ROOT_OUT)
LOCAL_UNSTRIPPED_PATH := $(TARGET_ROOT_OUT_UNSTRIPPED)

LOCAL_STATIC_LIBRARIES := libcutils libc libm

LOCAL_MODULE_TAGS := optional

include $(BUILD_EXECUTABLE)

endif
