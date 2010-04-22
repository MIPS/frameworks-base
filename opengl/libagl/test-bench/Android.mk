LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= libagl_bench_test.c

ifeq ($(TARGET_CPU_ENDIAN),EL)
	ifeq ($(TARGET_CPU_FLOAT),soft-float)
		LOCAL_SRC_FILES += ../arch-mips/fixed_asm.S
	endif
endif

ifeq ($(TARGET_CPU_FLOAT),soft-float)
	LOCAL_CFLAGS += -D__SOFT_FLOAT__
endif

LOCAL_MODULE:= libagl_bench_test

LOCAL_FORCE_STATIC_EXECUTABLE := true
LOCAL_MODULE_PATH := $(TARGET_ROOT_OUT)
LOCAL_UNSTRIPPED_PATH := $(TARGET_ROOT_OUT_UNSTRIPPED)

LOCAL_STATIC_LIBRARIES := libcutils libc libm


include $(BUILD_EXECUTABLE)

