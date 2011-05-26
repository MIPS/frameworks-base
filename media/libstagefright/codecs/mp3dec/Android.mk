LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        MP3Decoder.cpp \
	src/pvmp3_normalize.cpp \
 	src/pvmp3_alias_reduction.cpp \
 	src/pvmp3_crc.cpp \
 	src/pvmp3_decode_header.cpp \
 	src/pvmp3_decode_huff_cw.cpp \
 	src/pvmp3_getbits.cpp \
 	src/pvmp3_dequantize_sample.cpp \
 	src/pvmp3_framedecoder.cpp \
 	src/pvmp3_get_main_data_size.cpp \
 	src/pvmp3_get_side_info.cpp \
 	src/pvmp3_get_scale_factors.cpp \
 	src/pvmp3_mpeg2_get_scale_data.cpp \
 	src/pvmp3_mpeg2_get_scale_factors.cpp \
 	src/pvmp3_mpeg2_stereo_proc.cpp \
 	src/pvmp3_huffman_decoding.cpp \
 	src/pvmp3_huffman_parsing.cpp \
 	src/pvmp3_tables.cpp \
 	src/pvmp3_imdct_synth.cpp \
 	src/pvmp3_mdct_6.cpp \
 	src/pvmp3_dct_6.cpp \
 	src/pvmp3_poly_phase_synthesis.cpp \
 	src/pvmp3_equalizer.cpp \
 	src/pvmp3_seek_synch.cpp \
 	src/pvmp3_stereo_proc.cpp \
 	src/pvmp3_reorder.cpp \

LOCAL_CFLAGS := \
        -DOSCL_UNUSED_ARG=

ifeq ($(TARGET_ARCH),arm)
LOCAL_SRC_FILES += \
	src/asm/pvmp3_polyphase_filter_window_gcc.s \
 	src/asm/pvmp3_mdct_18_gcc.s \
 	src/asm/pvmp3_dct_9_gcc.s \
	src/asm/pvmp3_dct_16_gcc.s
else
LOCAL_SRC_FILES += \
 	src/pvmp3_polyphase_filter_window.cpp \
 	src/pvmp3_mdct_18.cpp \
 	src/pvmp3_dct_9.cpp \
 	src/pvmp3_dct_16.cpp

ifeq ($(TARGET_ARCH),mips)
LOCAL_CFLAGS += -DMIPS_ASM -DMIPS_ARCH

ifeq ($(ARCH_MIPS_HAS_DSP),true)
LOCAL_SRC_FILES += \
    src/mips_dsp/pvmp3_mdct_18.S \
    src/mips_dsp/pvmp3_polyphase_filter_window.S
LOCAL_CFLAGS += -DMIPS_DSP
endif #mips dsp

endif #mips
endif #arm

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        $(LOCAL_PATH)/src \
        $(LOCAL_PATH)/include

LOCAL_MODULE := libstagefright_mp3dec

include $(BUILD_STATIC_LIBRARY)

