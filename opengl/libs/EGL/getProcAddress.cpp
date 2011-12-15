/*
 ** Copyright 2009, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#include <ctype.h>
#include <stdlib.h>
#include <errno.h>

#include <cutils/log.h>

#include "egldefs.h"
#include "hooks.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

#undef API_ENTRY
#undef CALL_GL_EXTENSION_API
#undef GL_EXTENSION
#undef GL_EXTENSION_NAME
#undef GL_EXTENSION_ARRAY
#undef GL_EXTENSION_LIST
#undef GET_TLS

#if USE_FAST_TLS_KEY

    #if defined(__arm__)
        #ifdef HAVE_ARM_TLS_REGISTER
            #define GET_TLS(reg) \
                "mrc p15, 0, " #reg ", c13, c0, 3 \n"
	#else
            #define GET_TLS(reg) \
                "mov   " #reg ", #0xFFFF0FFF      \n"  \
                "ldr   " #reg ", [" #reg ", #-15] \n"
        #endif

        #define API_ENTRY(_api) __attribute__((naked)) _api

        #define CALL_GL_EXTENSION_API(_api)                         \
             asm volatile(                                          \
                GET_TLS(r12)                                        \
                "ldr   r12, [r12, %[tls]] \n"                       \
                "cmp   r12, #0            \n"                       \
                "ldrne r12, [r12, %[api]] \n"                       \
                "cmpne r12, #0            \n"                       \
                "bxne  r12                \n"                       \
                "bx    lr                 \n"                       \
                :                                                   \
                : [tls] "J"(TLS_SLOT_OPENGL_API*4),                 \
                  [api] "J"(__builtin_offsetof(gl_hooks_t,          \
                                          ext.extensions[_api]))    \
                :                                                   \
                );
    #elif defined(__mips__)
        #define API_ENTRY(_api) __attribute__((noinline)) _api

        #define CALL_GL_EXTENSION_API(_api, ...)                    \
            register unsigned int t0 asm("t0");                     \
            register unsigned int fn asm("t1");                     \
            register unsigned int tls asm("v1");                    \
            asm volatile(                                           \
                ".set  push\n\t"                                    \
                ".set  noreorder\n\t"                               \
                ".set  mips32r2\n\t"                                \
                "rdhwr %[tls], $29\n\t"                             \
                "lw    %[t0], %[OPENGL_API](%[tls])\n\t"            \
                "beqz  %[t0], 1f\n\t"                               \
                " move %[fn], $ra\n\t"                              \
                "lw    %[fn], %[API](%[t0])\n\t"                    \
                "movz  %[fn], $ra, %[fn]\n\t"                       \
                "1:\n\t"                                            \
                "j     %[fn]\n\t"                                   \
                " nop\n\t"                                          \
                ".set  pop\n\t"                                     \
                : [fn] "=c"(fn),                                    \
                  [tls] "=&r"(tls),                                 \
                  [t0] "=&r"(t0)                                    \
                : [OPENGL_API] "I"(TLS_SLOT_OPENGL_API*4),          \
                  [API] "I"(__builtin_offsetof(gl_hooks_t,          \
                                          ext.extensions[_api]))    \
                :                                                   \
            );

    #else
        #error Unsupported architecture
    #endif

    #define GL_EXTENSION_NAME(_n)   __glExtFwd##_n

    #define GL_EXTENSION(_n)                         \
        void API_ENTRY(GL_EXTENSION_NAME(_n))() {    \
            CALL_GL_EXTENSION_API(_n);               \
        }

#else

    #define GL_EXTENSION_NAME(_n) NULL

    #define GL_EXTENSION(_n)

    #warning "eglGetProcAddress() partially supported"

#endif

#define GL_EXTENSION_LIST(name) \
        name(0)   name(1)   name(2)   name(3)   \
        name(4)   name(5)   name(6)   name(7)   \
        name(8)   name(9)   name(10)  name(11)  \
        name(12)  name(13)  name(14)  name(15)  \
        name(16)  name(17)  name(18)  name(19)  \
        name(20)  name(21)  name(22)  name(23)  \
        name(24)  name(25)  name(26)  name(27)  \
        name(28)  name(29)  name(30)  name(31)  \
        name(32)  name(33)  name(34)  name(35)  \
        name(36)  name(37)  name(38)  name(39)  \
        name(40)  name(41)  name(42)  name(43)  \
        name(44)  name(45)  name(46)  name(47)  \
        name(48)  name(49)  name(50)  name(51)  \
        name(52)  name(53)  name(54)  name(55)  \
        name(56)  name(57)  name(58)  name(59)  \
        name(60)  name(61)  name(62)  name(63)


GL_EXTENSION_LIST( GL_EXTENSION )

#define GL_EXTENSION_ARRAY(_n)  GL_EXTENSION_NAME(_n),

extern const __eglMustCastToProperFunctionPointerType gExtensionForwarders[MAX_NUMBER_OF_GL_EXTENSIONS] = {
        GL_EXTENSION_LIST( GL_EXTENSION_ARRAY )
 };

#undef GET_TLS
#undef GL_EXTENSION_LIST
#undef GL_EXTENSION_ARRAY
#undef GL_EXTENSION_NAME
#undef GL_EXTENSION
#undef API_ENTRY
#undef CALL_GL_EXTENSION_API

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

