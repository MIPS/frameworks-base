#ifndef PV_MP3DEC_FXD_OP_MIPS
#define PV_MP3DEC_FXD_OP_MIPS


#ifdef __cplusplus
extern "C"
{
#endif

#include "pvmp3_audio_type_defs.h"


#ifdef MIPS_DSP

#define Qfmt_31(a)   (Int32)((float)a*0x7FFFFFFF)

#define Qfmt15(x)   (Int16)(x*((Int32)1<<15) + (x>=0?0.5F:-0.5F))

    __inline int32 pv_abs(int32 a)
    {
        Int32 abs_res;

        __asm __volatile(
           "absq_s.w    %[abs_res], %[a]  \n\t"
           :[abs_res]"=r"(abs_res)
           :[a]"r"(a)
        );

        return (abs_res);
    }

    __inline Int32 fxp_mul32_Q14(const Int32 a, const Int32 b)
    {
        register Int32 tmp;

        /* tmp = (Int32)(((int64)(a) * b) >> 14) */
        __asm __volatile(
            "mult    %[a], %[b]        \n\t"
            "extr.w  %[tmp], $ac0, 14  \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b)
            :"hi", "lo"
        );

        return (tmp);
    }

    __inline Int32 fxp_mul32_Q30(const Int32 a, const Int32 b)
    {
        register Int32 tmp;

        /* tmp = (Int32)(((int64)(a) * b) >> 30) */
        __asm __volatile(
            "mult    %[a], %[b]        \n\t"
            "extr.w  %[tmp], $ac0, 30  \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b)
            :"hi", "lo"
        );

        return (tmp);
    }

    __inline Int32 fxp_mac32_Q30(const Int32 a, const Int32 b, Int32 L_add)
    {
        register Int32 tmp;

        /* tmp = (L_add + (Int32)(((int64)(a) * b) >> 30)) */
        __asm __volatile(
            "mtlo    %[L_add]          \n\t"
            "shilo   $ac0, -30         \n\t"
            "madd    %[a], %[b]        \n\t"
            "extr.w  %[tmp], $ac0, 30  \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b), [L_add]"r"(L_add)
            :"hi", "lo"
        );
        return (tmp);
    }

    __inline Int32 fxp_mul32_Q32(const Int32 a, const Int32 b)
    {
        register Int32 tmp;

        /* tmp = (Int32)(((int64)(a) * b) >> 32) */
        __asm __volatile(
            "mult    %[a], %[b]  \n\t"
            "mfhi    %[tmp]      \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b)
            :"hi", "lo"
        );
        return (tmp);
    }


    __inline Int32 fxp_mul32_Q28(const Int32 a, const Int32 b)
    {
        register Int32 tmp;

        /* tmp = (Int32)(((int64)(a) * b) >> 28) */
        __asm __volatile(
            "mult    %[a], %[b]        \n\t"
            "extr.w  %[tmp], $ac0, 28  \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b)
            :"hi", "lo"
        );

        return (tmp);
    }

    __inline Int32 fxp_mul32_Q27(const Int32 a, const Int32 b)
    {
        register Int32 tmp;

        /* tmp = (Int32)(((int64)(a) * b) >> 27) */
        __asm __volatile(
            "mult    %[a], %[b]        \n\t"
            "extr.w  %[tmp], $ac0, 27  \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b)
            :"hi", "lo"
        );

        return (tmp);
    }

    __inline Int32 fxp_mul32_Q26(const Int32 a, const Int32 b)
    {
        register Int32 tmp;

        /* tmp = (Int32)(((int64)(a) * b) >> 26) */
        __asm __volatile(
            "mult    %[a], %[b]        \n\t"
            "extr.w  %[tmp], $ac0, 26  \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b)
            :"hi", "lo"
        );

        return (tmp);
    }


    __inline Int32 fxp_mac32_Q32(Int32 L_add, const Int32 a, const Int32 b)
    {
        register Int32 tmp;

        /* tmp = (L_add + (Int32)(((int64)(a) * b) >> 32)) */
        __asm __volatile(
            "mtlo $0          \n\t"
            "mthi %[L_add]    \n\t"
            "madd %[a], %[b]  \n\t"
            "mfhi %[tmp]      \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b), [L_add]"r"(L_add)
            :"hi", "lo"
        );
        return (tmp);
    }

    __inline Int32 fxp_msb32_Q32(Int32 L_sub, const Int32 a, const Int32 b)
    {
        register Int32 tmp;
        register Int32 set_lo=0xFFFFFFFF;

        /* tmp = (L_sub - ((Int32)(((int64)(a) * b) >> 32))) */
        __asm __volatile(
            "mtlo %[set_lo]   \n\t"
            "mthi %[L_sub]    \n\t"
            "msub %[a], %[b]  \n\t"
            "mfhi %[tmp]      \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b), [L_sub]"r"(L_sub), [set_lo]"r"(set_lo)
            :"hi", "lo"
        );

        return (tmp);
    }

    __inline Int32 fxp_mul32_Q29(const Int32 a, const Int32 b)
    {
        register Int32 tmp;

        /* tmp = (Int32)(((int64)(a) * b) >> 29) */
        __asm __volatile(
            "mult    %[a], %[b]        \n\t"
            "extr.w  %[tmp], $ac0, 29  \n\t"
            :[tmp]"=r"(tmp)
            :[a]"r"(a), [b]"r"(b)
            :"hi", "lo"
        );

        return (tmp);
    }

#endif   /*  MIPS_DSP  */

#ifdef __cplusplus
}
#endif


#endif   /*  PV_MP3DEC_FXD_OP_MIPS  */