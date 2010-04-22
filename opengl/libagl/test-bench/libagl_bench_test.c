#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <ctype.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/poll.h>
#include <time.h>
#include <errno.h>
#include <stdarg.h>
#include <sys/types.h>
#include <math.h>


typedef int32_t khronos_int32_t;
typedef khronos_int32_t GLfixed;
typedef int              GLint;
typedef float GLfloat;
typedef int32_t GGLfixed;

/* MIPS assembly version of function validation and benchmark */

//#define __QEMU__

#ifdef __QEMU__  

/* QEMU runs slow,so you can set this value less to save time. */
#define VALIDATE_TIMES 40000000
#define BENCHMARK_TIMES  400000000

#else /*real board*/

#define VALIDATE_TIMES 40000000
#define BENCHMARK_TIMES 400000000

#endif

#define gglFloatToFixed_VALIDATE_TIMES    VALIDATE_TIMES
#define vsquare3_VALIDATE_TIMES    VALIDATE_TIMES
#define mla3a_VALIDATE_TIMES       VALIDATE_TIMES
#define mla3a16_VALIDATE_TIMES     VALIDATE_TIMES
#define mla3a16_btb_VALIDATE_TIMES     VALIDATE_TIMES
#define mla3a16_btt_VALIDATE_TIMES     VALIDATE_TIMES
#define mla3_VALIDATE_TIMES     VALIDATE_TIMES
#define mla4_VALIDATE_TIMES     VALIDATE_TIMES

#define vsquare3_BENCHMARK_TIMES     BENCHMARK_TIMES
#define mla3a_BENCHMARK_TIMES     BENCHMARK_TIMES
#define mla3a16_BENCHMARK_TIMES     BENCHMARK_TIMES
#define mla3a16_btb_BENCHMARK_TIMES     BENCHMARK_TIMES
#define mla3a16_btt_BENCHMARK_TIMES     BENCHMARK_TIMES
#define mla3_BENCHMARK_TIMES     BENCHMARK_TIMES
#define mla4_BENCHMARK_TIMES     BENCHMARK_TIMES


static inline
GLfixed vsquare3_mips(GLfixed a, GLfixed b, GLfixed c) 
{
	GLfixed res;
	int32_t t1,t2,t3;
	asm(
    	"mult  %[a], %[a]       \r\n"
    	"li    %[res],0x8000 \r\n"
    	"madd   %[b],%[b] \r\n"
    	"move   %[t3],$zero \r\n"
    	"madd   %[c],%[c] \r\n"
    	"mflo	%[t1]\r\n"
    	"mfhi	%[t2]\r\n"
    	"addu   %[t1],%[res],%[t1]\r\n"          /*add 0x8000*/
    	"sltu   %[t3],%[t1],%[res]\r\n"
        "addu   %[t2],%[t2],%[t3]\r\n"
    	"srl    %[res],%[t1],16\r\n"
        "sll    %[t2],%[t2],16\r\n"
    	"or     %[res],%[res],%[t2]\r\n"
        :   [res]"=&r"(res),[t1]"=&r"(t1),[t2]"=&r"(t2),[t3]"=&r"(t3)
        :   [a] "r" (a),[b] "r" (b),[c] "r" (c)
        : "%hi","%lo"
        );
    return res;
}

static inline
GLfixed vsquare3(GLfixed a, GLfixed b, GLfixed c) 
{
    return ((   (int64_t)(a)*a +
                (int64_t)(b)*b +
                (int64_t)(c)*c + 0x8000)>>16);
}

static inline
GLfixed mla3a_mips( GLfixed a0, GLfixed b0,
                             GLfixed a1, GLfixed b1,
                             GLfixed a2, GLfixed b2,
                             GLfixed c)
{
	GLfixed res;
	int32_t t1,t2;
	asm(
    	"mult  %[a0],%[b0]       \r\n"
    	"madd  %[a1],%[b1]       \r\n"
    	"madd  %[a2],%[b2]       \r\n"
    	"mflo  %[t2]\r\n"
    	"mfhi  %[t1]\r\n"
    	"srl    %[t2],%[t2],16\r\n"
        "sll    %[t1],%[t1],16\r\n"
        "or     %[t2],%[t2],%[t1]\r\n"
        "addu   %[res],%[t2],%[c]"
        :   [res]"=&r"(res),[t1]"=&r"(t1),[t2]"=&r"(t2)
        :   [a0] "r" (a0),[b0] "r" (b0),[a1] "r" (a1),[b1] "r" (b1),[a2] "r" (a2),[b2] "r" (b2),[c] "r" (c)
        : "%hi","%lo"
        );
    return res;
}

static inline
GLfixed mla3a( GLfixed a0, GLfixed b0,
                             GLfixed a1, GLfixed b1,
                             GLfixed a2, GLfixed b2,
                             GLfixed c)
{

    return ((   (int64_t)(a0)*b0 +
                (int64_t)(a1)*b1 +
                (int64_t)(a2)*b2)>>16) + c;

}

static inline GLfixed mla3a16_mips( GLfixed a0, int32_t b1b0,
                               GLfixed a1,
                               GLfixed a2, int32_t b2,
                               GLint shift,
                               GLfixed c)
{
    int32_t b0,b1,res;
    asm(
        #ifdef _MIPS_ARCH_MIPS32R2
        "seh %[b0],%[b1b0] \r\n"
        #else
        "sll %[b0],%[b1b0],16 \r\n"
        "sra %[b0],%[b0],16 \r\n"
        #endif
        "mult %[a0],%[b0]\r\n"           /*[hi,lo] += a0*b0 */
        "sra %[b1],%[b1b0],16 \r\n"
        "lui %[b1b0],0xffff \r\n"
        "mflo %[b0] \r\n"
        "and %[b0],%[b0],%[b1b0] \r\n"  /*mask bit [0:16]*/
        "mtlo %[b0] \r\n"
        "madd %[a1],%[b1]\r\n \r\n"      /*[hi,lo] += a1*b1 */
        #ifdef _MIPS_ARCH_MIPS32R2
        "seh %[b2],%[b2] \r\n"
        #else
        "sll %[b2],%[b2],16 \r\n"
        "sra %[b2],%[b2],16 \r\n"
        #endif
        "mflo %[b0] \r\n"
        "and %[b0],%[b0],%[b1b0] \r\n"  /*mask bit [0:16]*/
        "mtlo %[b0] \r\n"
        "madd %[a2],%[b2]\r\n \r\n"     /*[hi,lo] += a2*b2 */
        "andi %[shift],%[shift],0x1f \r\n" /*only keep bit [0:5] of shift*/
        "subu %[b0],%[shift],1\r\n"     /*b0=shift-1*/
        "mflo %[res] \r\n"              /*lo->res*/
        "srl %[res],%[res],16 \r\n"     /*res = res >>16*/
        "mfhi %[b1] \r\n"               /*hi->b1*/
        "sll %[b1],%[b1],16 \r\n"       /*b1 = b1 <<16*/
        "or %[res],%[res],%[b1] \r\n"   /*res = [hi,lo] >>16 */
        "sll %[b1],%[res],1\r\n"          /*b1= res<<1*/
        "sllv %[b1],%[b1],%[b0]\r\n"       /*b1= b1<<(shift-1)*/
        "movn %[res],%[b1],%[shift] \r\n"   /*if shift!=0, then b1->res*/
        "addu %[res],%[res],%[c]\r\n"       /*res = res + c*/
        : [res]"=&r"(res) ,[b0]"=&r"(b0),[b1]"=&r"(b1)
        : [a0] "r" (a0),[b1b0] "r" (b1b0),[a1] "r" (a1),[a2] "r" (a2),[b2] "r" (b2),[shift] "r" (shift),[c] "r" (c)
        : "%hi","%lo"
        );
    return res;
}

static inline GLfixed mla3a16( GLfixed a0, int32_t b1b0,
                               GLfixed a1,
                               GLfixed a2, int32_t b2,
                               GLint shift,
                               GLfixed c)
{
    int32_t accum;
    int16_t b0 = b1b0 & 0xffff;
    int16_t b1 = (b1b0 >> 16) & 0xffff;
    accum  = (int64_t)(a0)*(int16_t)(b0) >> 16;
    accum += (int64_t)(a1)*(int16_t)(b1) >> 16;
    accum += (int64_t)(a2)*(int16_t)(b2) >> 16;
    accum = (accum << shift) + c;
    return accum;
}


static inline GLfixed mla3a16_btb_mips( GLfixed a0,
                                   GLfixed a1,
                                   GLfixed a2,
                                   int32_t b1b0, int32_t xxb2,
                                   GLint shift,
                                   GLfixed c)
{
    int32_t b0,b1,res;
    asm(
        #ifdef _MIPS_ARCH_MIPS32R2
        "seh %[b0],%[b1b0] \r\n"
        #else
        "sll %[b0],%[b1b0],16 \r\n"
        "sra %[b0],%[b0],16 \r\n"
        #endif
        "mult %[a0],%[b0]\r\n"           /*[hi,lo] += a0*b0 */
        "sra %[b1],%[b1b0],16 \r\n"
        "lui %[b1b0],0xffff \r\n"
        "mflo %[b0] \r\n"
        "and %[b0],%[b0],%[b1b0] \r\n"  /*mask bit [0:16]*/
        "mtlo %[b0] \r\n"
        "madd %[a1],%[b1]\r\n \r\n"      /*[hi,lo] += a1*b1 */
        #ifdef _MIPS_ARCH_MIPS32R2
        "seh %[b2],%[b2] \r\n"
        #else
        "sll %[b2],%[b2],16 \r\n"
        "sra %[b2],%[b2],16 \r\n"
        #endif
        "mflo %[b0] \r\n"
        "and %[b0],%[b0],%[b1b0] \r\n"  /*mask bit [0:16]*/
        "mtlo %[b0] \r\n"
        "madd %[a2],%[b2]\r\n \r\n"     /*[hi,lo] += a2*b2 */
        "andi %[shift],%[shift],0x1f \r\n" /*only keep bit [0:5] of shift*/
        "subu %[b0],%[shift],1\r\n"     /*b0=shift-1*/
        "mflo %[res] \r\n"              /*lo->res*/
        "srl %[res],%[res],16 \r\n"     /*res = res >>16*/
        "mfhi %[b1] \r\n"               /*hi->b1*/
        "sll %[b1],%[b1],16 \r\n"       /*b1 = b1 <<16*/
        "or %[res],%[res],%[b1] \r\n"   /*res = [hi,lo] >>16 */
        "sll %[b1],%[res],1\r\n"          /*b1= res<<1*/
        "sllv %[b1],%[b1],%[b0]\r\n"       /*b1= b1<<(shift-1)*/
        "movn %[res],%[b1],%[shift] \r\n"   /*if shift!=0, then b1->res*/
        "addu %[res],%[res],%[c]\r\n"       /*res = res + c*/
        : [res]"=&r"(res) ,[b0]"=&r"(b0),[b1]"=&r"(b1)
        : [a0] "r" (a0),[b1b0] "r" (b1b0),[a1] "r" (a1),[a2] "r" (a2),[b2] "r" (xxb2),[shift] "r" (shift),[c] "r" (c)
        : "%hi","%lo"
        );
    return res;
}

static inline GLfixed mla3a16_btb( GLfixed a0,
                                   GLfixed a1,
                                   GLfixed a2,
                                   int32_t b1b0, int32_t xxb2,
                                   GLint shift,
                                   GLfixed c)
{
    int32_t accum;
    int16_t b0 =  b1b0        & 0xffff;
    int16_t b1 = (b1b0 >> 16) & 0xffff;
    int16_t b2 =  xxb2        & 0xffff;
    accum  = (int64_t)(a0)*(int16_t)(b0) >> 16;
    accum += (int64_t)(a1)*(int16_t)(b1) >> 16;
    accum += (int64_t)(a2)*(int16_t)(b2) >> 16;
    accum = (accum << shift) + c;
    return accum;

}

static inline GLfixed mla3a16_btt_mips( GLfixed a0,
                                   GLfixed a1,
                                   GLfixed a2,
                                   int32_t b1b0, int32_t b2xx,
                                   GLint shift,
                                   GLfixed c)
{
    int32_t b0,b1,res;
    asm(
        #ifdef _MIPS_ARCH_MIPS32R2
        "seh %[b0],%[b1b0] \r\n"
        #else
        "sll %[b0],%[b1b0],16 \r\n"
        "sra %[b0],%[b0],16 \r\n"
        #endif
        "mult %[a0],%[b0]\r\n"           /*[hi,lo] += a0*b0 */
        "sra %[b1],%[b1b0],16 \r\n"
        "lui %[b1b0],0xffff \r\n"
        "mflo %[b0] \r\n"
        "and %[b0],%[b0],%[b1b0] \r\n"  /*mask bit [0:16]*/
        "mtlo %[b0] \r\n"
        "madd %[a1],%[b1]\r\n \r\n"      /*[hi,lo] += a1*b1 */
        "sra %[b2],%[b2],16 \r\n"
        "mflo %[b0] \r\n"
        "and %[b0],%[b0],%[b1b0] \r\n"  /*mask bit [0:16]*/
        "mtlo %[b0] \r\n"
        "madd %[a2],%[b2]\r\n \r\n"     /*[hi,lo] += a2*b2 */
        "andi %[shift],%[shift],0x1f \r\n" /*only keep bit [0:5] of shift*/
        "subu %[b0],%[shift],1\r\n"     /*b0=shift-1*/
        "mflo %[res] \r\n"              /*lo->res*/
        "srl %[res],%[res],16 \r\n"     /*res = res >>16*/
        "mfhi %[b1] \r\n"               /*hi->b1*/
        "sll %[b1],%[b1],16 \r\n"       /*b1 = b1 <<16*/
        "or %[res],%[res],%[b1] \r\n"   /*res = [hi,lo] >>16 */
        "sll %[b1],%[res],1\r\n"          /*b1= res<<1*/
        "sllv %[b1],%[b1],%[b0]\r\n"       /*b1= b1<<(shift-1)*/
        "movn %[res],%[b1],%[shift] \r\n"   /*if shift!=0, then b1->res*/
        "addu %[res],%[res],%[c]\r\n"       /*res = res + c*/
        : [res]"=&r"(res) ,[b0]"=&r"(b0),[b1]"=&r"(b1)
        : [a0] "r" (a0),[b1b0] "r" (b1b0),[a1] "r" (a1),[a2] "r" (a2),[b2] "r" (b2xx),[shift] "r" (shift),[c] "r" (c)
        : "%hi","%lo"
        );
    return res;
}

static inline GLfixed mla3a16_btt( GLfixed a0,
                                   GLfixed a1,
                                   GLfixed a2,
                                   int32_t b1b0, int32_t b2xx,
                                   GLint shift,
                                   GLfixed c)
{
    int32_t accum;
    int16_t b0 =  b1b0        & 0xffff;
    int16_t b1 = (b1b0 >> 16) & 0xffff;
    int16_t b2 = (b2xx >> 16) & 0xffff;
    accum  = (int64_t)(a0)*(int16_t)(b0) >> 16;
    accum += (int64_t)(a1)*(int16_t)(b1) >> 16;
    accum += (int64_t)(a2)*(int16_t)(b2) >> 16;
    accum = (accum << shift) + c;
    return accum;
}

static inline GLfixed mla3_mips( GLfixed a0, GLfixed b0,
                            GLfixed a1, GLfixed b1,
                            GLfixed a2, GLfixed b2)
{
    GLfixed res;
	int32_t t1,t2;
    asm(
    	"mult  %[a0],%[b0]       \r\n"
    	"madd  %[a1],%[b1]       \r\n"
    	"madd  %[a2],%[b2]       \r\n"
    	"li    %[res],0x8000    \r\n"
    	"mflo  %[t2]\r\n"
        "addu  %[t2],%[res],%[t2] \r\n"
        "sltu  %[t1],%[t2],%[res]  \r\n" /*overflow?*/
    	"mfhi  %[res]\r\n"
        "addu  %[t1],%[res],%[t1] \r\n"
    	"srl    %[t2],%[t2],16\r\n"
        "sll    %[t1],%[t1],16\r\n"
        "or     %[res],%[t2],%[t1]\r\n"
        :   [res]"=&r"(res),[t1]"=&r"(t1),[t2]"=&r"(t2)
        :   [a0] "r" (a0),[b0] "r" (b0),[a1] "r" (a1),[b1] "r" (b1),[a2] "r" (a2),[b2] "r" (b2)
        : "%hi","%lo"
        );
    return res;
}
static inline GLfixed mla3( GLfixed a0, GLfixed b0,
                            GLfixed a1, GLfixed b1,
                            GLfixed a2, GLfixed b2)
{
    return ((   (int64_t)(a0)*b0 +
                (int64_t)(a1)*b1 +
                (int64_t)(a2)*b2 + 0x8000)>>16);
}


static inline GLfixed mla4_mips( GLfixed a0, GLfixed b0,
                            GLfixed a1, GLfixed b1,
                            GLfixed a2, GLfixed b2,
                            GLfixed a3, GLfixed b3)
{

    GLfixed res;
	int32_t t1,t2;
	asm(
    	"mult  %[a0],%[b0]       \r\n"
    	"madd  %[a1],%[b1]       \r\n"
    	"madd  %[a2],%[b2]       \r\n"
    	"madd  %[a3],%[b3]       \r\n"
    	"li    %[res],0x8000    \r\n"
    	"mflo  %[t2]\r\n"
        "addu  %[t2],%[res],%[t2] \r\n"
        "sltu  %[t1],%[t2],%[res]  \r\n" /*overflow?*/
    	"mfhi  %[res]\r\n"
        "addu  %[t1],%[res],%[t1] \r\n"
    	"srl    %[t2],%[t2],16\r\n"
        "sll    %[t1],%[t1],16\r\n"
        "or     %[res],%[t2],%[t1]\r\n"
        :   [res]"=&r"(res),[t1]"=&r"(t1),[t2]"=&r"(t2)
        :   [a0] "r" (a0),[b0] "r" (b0),[a1] "r" (a1),[b1] "r" (b1),[a2] "r" (a2),[b2] "r" (b2),[a3] "r" (a3),[b3] "r" (b3)
        : "%hi","%lo"
        );
    return res;
}

static inline GLfixed mla4( GLfixed a0, GLfixed b0,
                            GLfixed a1, GLfixed b1,
                            GLfixed a2, GLfixed b2,
                            GLfixed a3, GLfixed b3)
{   
    return ((   (int64_t)(a0)*b0 +
                (int64_t)(a1)*b1 +
                (int64_t)(a2)*b2 +
                (int64_t)(a3)*b3 + 0x8000)>>16);
}

GGLfixed gglFloatToFixed(float v);

GGLfixed gglFloatToFixed_c(float v) {   
    return (GGLfixed)(floorf(v * 65536.0f + 0.5f));
}

#define TEST_ASSERT_gglFloatToFixed(resc,resasm,v) \
do { \
if (resc != resasm) \
	{\
	if ((v>=32768.000000) ){ \
	if  (resasm != 0x7fffffff) {\
		printf("resc %x  resasm %x v %f \n",resc,resasm,v);\
		printf("FAILED1 \n"); \
		exit(-1);\
	}\
	} else if (abs(resc-resasm)!=1){\
	 printf("resc %x  resasm %x v %f \n",resc,resasm,v);\
		printf("FAILED \n"); \
		exit(-1);\
	}\
    }\
}\
while (0)

#define TEST_gglFloatToFixed(v) \
do { \
	resc = gglFloatToFixed_c(v); \
	resasm = gglFloatToFixed(v);\
	TEST_ASSERT_gglFloatToFixed(resc,resasm,v);\
}\
while (0)

#define TEST_ASSERT_vsquare3(resc,resasm,a,b,c) \
do { \
if (resc != resasm) \
	{\
		printf("resc %x  resasm %x a %x b %x c %x \n",resc,resasm,a,b,c);\
		printf("FAILED \n"); \
		exit(-1);\
    }\
}\
while (0)

#define TEST_vsquare3(a,b,c) \
do { \
	resc = vsquare3(a,b,c); \
	resasm = vsquare3_mips(a,b,c);\
	TEST_ASSERT_vsquare3(resc,resasm,a,b,c);\
}\
while (0)



#define TEST_ASSERT_mla3a(resc,resasm,a0,b0,a1,b1,a2,b2,c) \
do { \
if (resc != resasm) \
	{\
		printf("resc %x  resasm %x a0 %x b0 %x a1 %x b1 %x a2 %x b2 %x c %x \n",resc,resasm,a0,b0,a1,b1,a2,b2,c);\
		printf("FAILED \n"); \
		exit(-1);\
    }\
}\
while (0)

#define TEST_mla3a(a0,b0,a1,b1,a2,b2,c) \
do { \
	resc = mla3a(a0,b0,a1,b1,a2,b2,c); \
	resasm = mla3a_mips(a0,b0,a1,b1,a2,b2,c);\
	TEST_ASSERT_mla3a(resc,resasm,a0,b0,a1,b1,a2,b2,c);\
}\
while (0)

#if 1
#define TEST_ASSERT_mla3a16(resc,resasm,a0,b1b0,a1,a2,b2,shift,c) \
do { \
if (resc != resasm) \
	{\
		printf("resc 0x%x  resasm 0x%x a0 0x%x b1b0 0x%x a1 0x%x a2 0x%x b2 0x%x shift 0x%x c 0x%x \n",resc,resasm,a0,b1b0,a1,a2,b2,shift,c);\
		printf("FAILED \n"); \
		exit(-1);\
    }\
}\
while (0)

#define TEST_mla3a16(a0,b1b0,a1,a2,b2,shift,c) \
do { \
	resc = mla3a16(a0,b1b0,a1,a2,b2,shift,c); \
	resasm = mla3a16_mips(a0,b1b0,a1,a2,b2,shift,c);\
	TEST_ASSERT_mla3a16(resc,resasm,a0,b1b0,a1,a2,b2,shift,c);\
}\
while (0)



#define TEST_ASSERT_mla3a16_btb(resc,resasm,a0,a1,a2,b1b0,xxb2,shift,c) \
do { \
if (resc != resasm) \
	{\
		printf("resc 0x%x  resasm 0x%x a0 0x%x a1 0x%x a2 0x%x b1b0 0x%x xxb2 0x%x shift 0x%x c 0x%x \n",resc,resasm,a0,a1,a2,b1b0,xxb2,shift,c);\
		printf("FAILED \n"); \
		exit(-1);\
    }\
}\
while (0)

#define TEST_mla3a16_btb(a0,a1,a2,b1b0,xxb2,shift,c) \
do { \
	resc = mla3a16(a0,a1,a2,b1b0,xxb2,shift,c); \
	resasm = mla3a16_mips(a0,a1,a2,b1b0,xxb2,shift,c);\
	TEST_ASSERT_mla3a16_btb(resc,resasm,a0,a1,a2,b1b0,xxb2,shift,c);\
}\
while (0)


#define TEST_ASSERT_mla3a16_btt(resc,resasm,a0,a1,a2,b1b0,b2xx,shift,c) \
do { \
if (resc != resasm) \
	{\
		printf("resc 0x%x  resasm 0x%x a0 0x%x a1 0x%x a2 0x%x b1b0 0x%x b2xx 0x%x shift 0x%x c 0x%x \n",resc,resasm,a0,a1,a2,b1b0,b2xx,shift,c);\
		printf("FAILED \n"); \
		exit(-1);\
    }\
}\
while (0)

#define TEST_mla3a16_btt(a0,a1,a2,b1b0,b2xx,shift,c) \
do { \
	resc = mla3a16(a0,a1,a2,b1b0,b2xx,shift,c); \
	resasm = mla3a16_mips(a0,a1,a2,b1b0,b2xx,shift,c);\
	TEST_ASSERT_mla3a16_btt(resc,resasm,a0,a1,a2,b1b0,b2xx,shift,c);\
}\
while (0)


#define TEST_ASSERT_mla3(resc,resasm,a0,b0,a1,b1,a2,b2) \
do { \
if (resc != resasm) \
	{\
		printf("resc %x  resasm %x a0 %x b0 %x a1 %x b1 %x a2 %x b2 %x  \n",resc,resasm,a0,b0,a1,b1,a2,b2);\
		printf("FAILED \n"); \
		exit(-1);\
    }\
}\
while (0)

#define TEST_mla3(a0,b0,a1,b1,a2,b2) \
do { \
	resc = mla3(a0,b0,a1,b1,a2,b2); \
	resasm = mla3_mips(a0,b0,a1,b1,a2,b2);\
	TEST_ASSERT_mla3(resc,resasm,a0,b0,a1,b1,a2,b2);\
}\
while (0)

#define TEST_ASSERT_mla4(resc,resasm,a0,b0,a1,b1,a2,b2,a3,b3) \
do { \
if (resc != resasm) \
	{\
		printf("resc %x  resasm %x a0 %x b0 %x a1 %x b1 %x a2 %x b2 %x a3 %x b3 %x \n",resc,resasm,a0,b0,a1,b1,a2,b2,a3,b3);\
		printf("FAILED \n"); \
		exit(-1);\
    }\
}\
while (0)

#define TEST_mla4(a0,b0,a1,b1,a2,b2,a3,b3) \
do { \
	resc = mla4(a0,b0,a1,b1,a2,b2,a3,b3); \
	resasm = mla4_mips(a0,b0,a1,b1,a2,b2,a3,b3);\
	TEST_ASSERT_mla4(resc,resasm,a0,b0,a1,b1,a2,b2,a3,b3);\
}\
while (0)

#endif

#define VALIDATION_START do { \
							printf("\n[validation start] %s\n",__FUNCTION__); \
							} while (0)
#define VALIDATION_END do { \
							printf("[validation pass] %s\n",__FUNCTION__); \
							} while (0)

void validate_gglFloatToFixed()
{
	GGLfixed resc,resasm;
    float a;
    int i;
        
    VALIDATION_START;
    
    a = 672768.000000;
    TEST_gglFloatToFixed(a);
    
    a = -672768.000000;
    TEST_gglFloatToFixed(a);
    
    a = 0.00000672768;
    TEST_gglFloatToFixed(a);
    a = -0.0000000672768;
    TEST_gglFloatToFixed(a);
    
    a = 32768.000000;
    TEST_gglFloatToFixed(a);
    
    a = 211.277786;
    TEST_gglFloatToFixed(a);

    a = 0.0;
    TEST_gglFloatToFixed(a);

    a = 32767.0;
    TEST_gglFloatToFixed(a);

    a = -32768.0;
    TEST_gglFloatToFixed(a);

    a = -32767.0;
    TEST_gglFloatToFixed(a);
    
    srand((unsigned) time(NULL));
    
    for (i=0;i<gglFloatToFixed_VALIDATE_TIMES;i++)
	{        
		a=(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
        TEST_gglFloatToFixed(a);

        a=0-(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
        TEST_gglFloatToFixed(a);

        a=(((100+1.0))*rand()/(RAND_MAX+1.0));
        TEST_gglFloatToFixed(a);

	}
	
	VALIDATION_END;
}

void validate_vsquare3()
{
	int i;
	GLfixed a,b,c,resc,resasm;
	
	VALIDATION_START;
	
	srand(time(NULL));
	
	for(i=0;i<=vsquare3_VALIDATE_TIMES;i++)
	{
		a = rand();
        b = rand();
        c = rand();
		TEST_vsquare3(a,b,c);
	}
	VALIDATION_END;
}

void validate_mla3a()
{
	int i;
	GLfixed a0,b0,a1,b1,a2,b2,c,resc,resasm;

	VALIDATION_START;
	
	srand(time(NULL));
	for(i=0;i<=mla3a_VALIDATE_TIMES;i++)
	{
        a0 = rand();
		b0 = rand();
		a1 = rand();
		b1 = rand();
		a2 = rand();
		b2 = rand();
		c = rand();
		TEST_mla3a(a0,b0,a1,b1,a2,b2,c);
	}
	VALIDATION_END;
}


void validate_mla3a16()
{
	int i;
	GLfixed a0,b1b0,a1,a2,b2,shift,c,resc,resasm;

	VALIDATION_START;

	srand(time(NULL));
	
	for(i=0;i<=mla3a16_VALIDATE_TIMES;i++)
	{
        a0 = rand();
		b1b0 = rand();
		a1 = rand();
		a2 = rand();
		shift = rand();
		b2 = rand();
		c = rand();
		TEST_mla3a16(a0,b1b0,a1,a2,b2,shift,c);
	}
	VALIDATION_END;
}

void validate_mla3a16_btb()
{
	int i;
	GLfixed a0,a1,a2,b1b0,xxb2,shift,c,resc,resasm;

	VALIDATION_START;

	srand(time(NULL));
	
	for(i=0;i<=mla3a16_btb_VALIDATE_TIMES;i++)
	{
		a0 = rand();
		b1b0 = rand();
		a1 = rand();
		a2 = rand();
		shift = rand();
		xxb2 = rand();
		c = rand();
		TEST_mla3a16_btb(a0,a1,a2,b1b0,xxb2,shift,c);
				
	}
	VALIDATION_END;
}

void validate_mla3a16_btt()
{
	int i;
	GLfixed a0,a1,a2,b1b0,b2xx,shift,c,resc,resasm;

	VALIDATION_START;
	
	srand(time(NULL));
	
	for(i=0;i<=mla3a16_btb_VALIDATE_TIMES;i++)
	{
		a0 = rand();
		b1b0 = rand();
		a1 = rand();
		a2 = rand();
		shift = rand();
		b2xx = rand();
		c = rand();
		TEST_mla3a16_btt(a0,a1,a2,b1b0,b2xx,shift,c);
		
	}

	VALIDATION_END;
}

void validate_mla3()
{
	int i;
	GLfixed a0,b0,a1,b1,a2,b2,resc,resasm;

	VALIDATION_START;

	srand(time(NULL));
	
	for(i=0;i<=mla3_VALIDATE_TIMES;i++)
	{
        a0 = rand();
		b0 = rand();
		a1 = rand();
		b1 = rand();
		a2 = rand();
		b2 = rand();
		TEST_mla3(a0,b0,a1,b1,a2,b2);
	}
	VALIDATION_END;
}

void validate_mla4()
{
	int i;
	GLfixed a0,b0,a1,b1,a2,b2,a3,b3,resc,resasm;

	VALIDATION_START;

	srand(time(NULL));
	
	for(i=0;i<=mla4_VALIDATE_TIMES;i++)
	{
		a0 = rand();
		b0 = rand();
		a1 = rand();
		b1 = rand();
		a2 = rand();
		b2 = rand();
		a3 = rand();
		b3 = rand();
		TEST_mla4(a0,b0,a1,b1,a2,b2,a3,b3);
	}
	VALIDATION_END;
}


#define BENCH_LOGO do { \
						printf("\n[benchmark] %s start \n",__FUNCTION__); \
					  } while(0);
					  
#define BENCH_ASM do { \
						printf("[ASM version] time used: %d seconds. START:%d END:%d DUMMY:%d \n",time_e-time_s,time_e,time_s,resc); \
					 } while(0);
					 
#define BENCH_C do { \
						printf("[C version:] time used: %d seconds. START:%d END:%d DUMMY:%d \n",time_e-time_s,time_e,time_s,resc); \
				  } while(0);
				  
#define BENCH_END do { \
						printf("[benchmark] %s end \n",__FUNCTION__); \
					} while(0);

/*

You can dump the results using the following command:

mips-linux-gnu-objdump -d -S libagl_bench_test >agl.asm

vsquare3_mips:

   8020c:	00420018 	mult	v0,v0
   80210:	340a8000 	li	t2,0x8000
   80214:	70630000 	madd	v1,v1
   80218:	00004821 	move	t1,zero
   8021c:	70840000 	madd	a0,a0
   80220:	00003812 	mflo	a3
   80224:	00004010 	mfhi	t0
   80228:	01473821 	addu	a3,t2,a3
   8022c:	00ea482b 	sltu	t1,a3,t2
   80230:	01094021 	addu	t0,t0,t1
   80234:	00075402 	srl	t2,a3,0x10
   80238:	00084400 	sll	t0,t0,0x10
   8023c:	01485025 	or	t2,t2,t0

	
resc += vsquare3(a,b,c);
   8031c:	03042021 	addu	a0,t8,a0
   80320:	00630019 	multu	v1,v1
   80324:	01401821 	move	v1,t2
   80328:	00026840 	sll	t5,v0,0x1
   8032c:	0000a812 	mflo	s5
   80330:	0000c810 	mfhi	t9
   80334:	01b97821 	addu	t7,t5,t9
   80338:	02b46821 	addu	t5,s5,s4
   8033c:	716e5002 	mul	t2,t3,t6
   80340:	01b5c02b 	sltu	t8,t5,s5
   80344:	030fc021 	addu	t8,t8,t7
   80348:	01ce0019 	multu	t6,t6
   8034c:	000ac840 	sll	t9,t2,0x1
   80350:	00001012 	mflo	v0
   80354:	0000a810 	mfhi	s5
   80358:	03357821 	addu	t7,t9,s5
   8035c:	01a2a821 	addu	s5,t5,v0
   80360:	73ec5002 	mul	t2,ra,t4
   80364:	030f7021 	addu	t6,t8,t7
   80368:	02ad582b 	sltu	t3,s5,t5
   8036c:	016ec021 	addu	t8,t3,t6
   80370:	018c0019 	multu	t4,t4
   80374:	000ac840 	sll	t9,t2,0x1
   80378:	0000f812 	mflo	ra
   8037c:	02bf7021 	addu	t6,s5,ra
   80380:	01d5602b 	sltu	t4,t6,s5
   80384:	00001010 	mfhi	v0
   80388:	03227821 	addu	t7,t9,v0
   8038c:	030f6821 	addu	t5,t8,t7
   80390:	018d5821 	addu	t3,t4,t5
   80394:	000b5400 	sll	t2,t3,0x10
   80398:	000e1402 	srl	v0,t6,0x10
   8039c:	0142f825 	or	ra,t2,v0
*/

void bench_vsquare3()
{
	int i;
	GLfixed a,b,c,resc = 0;
	uint32_t time_s,time_e;

    
    BENCH_LOGO;
    srand(0);
    a=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    c=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
        
    time_s = time(NULL);
	for (i=0;i<vsquare3_BENCHMARK_TIMES;i++)
	{
		a++;
		b++;
		c++;
		
		/* we use return value res to stop gcc 
		   eliminating this function call
		*/
        resc += vsquare3_mips(a,b,c);
	}
	time_e = time(NULL);
	BENCH_ASM;
	
	time_s = time(NULL);
	for (i=0;i<vsquare3_BENCHMARK_TIMES;i++)
	{
        a++;
		b++;
		c++;
		resc += vsquare3(a,b,c);
	}
	time_e = time(NULL);
    BENCH_C;
    
	BENCH_END;	
}

void bench_mla3a()
{
	int i;
	GLfixed a0,b0,a1,b1,a2,b2,c,resc = 0;
	uint32_t time_s,time_e;

    
    BENCH_LOGO;
    srand(0);
    a0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a1=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b1=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));  
    c=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
        
    time_s = time(NULL);
	for (i=0;i<mla3a_BENCHMARK_TIMES;i++)
	{
		a0++;
		a1++;
		a2++;
		b0++;
		b1++;
		b2++;
		c++;
		
		/* we use return value res to stop gcc 
		   eliminating this function call
		*/
        resc += mla3a_mips(a0,b0,a1,b1,a2,b2,c);
	}
	time_e = time(NULL);
	BENCH_ASM;
	    
	time_s = time(NULL);
	for (i=0;i<mla3a_BENCHMARK_TIMES;i++)
	{
        a0++;
		a1++;
		a2++;
		b0++;
		b1++;
		b2++;
		c++;
		resc += mla3a(a0,b0,a1,b1,a2,b2,c);
	}
	time_e = time(NULL);
    BENCH_C;
    
	BENCH_END;	
}

void bench_mla3a16()
{
	int i;
	GLfixed a0,b1b0,a1,a2,b2,shift,c,resc = 0;
	uint32_t time_s,time_e;

    
    BENCH_LOGO;
    srand(0);
    a0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b1b0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a1=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));  
    shift=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    c=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
        
    time_s = time(NULL);
	for (i=0;i<mla3a16_BENCHMARK_TIMES;i++)
	{
		/*if we do not change the a0/a1.., then gcc will call mla3a_mips only
		  once and then add the return value  mla3a16_BENCHMARK_TIMES times.
		  A smart guy but that's not what we want when do benchmark....
		*/
		a0++;
		a1++;
		a2++;
		b1b0++;
		b2++;
		shift++;
		c++;
		
		/* we use return value res to stop gcc 
		   eliminating this function call
		*/
        resc += mla3a16_mips(a0,b1b0,a1,a2,b2,shift,c);
	}
	time_e = time(NULL);
	BENCH_ASM;
	    
	time_s = time(NULL);
	for (i=0;i<mla3a16_BENCHMARK_TIMES;i++)
	{
        a0++;
		a1++;
		a2++;
		b1b0++;
		b2++;
		shift++;
		c++;
		resc += mla3a16(a0,b1b0,a1,a2,b2,shift,c);
	}
	time_e = time(NULL);
    BENCH_C;
    
	BENCH_END;	
}

void bench_mla3a16_btb()
{
	int i;
	GLfixed a0,b1b0,a1,a2,xxb2,shift,c,resc = 0;
	uint32_t time_s,time_e;

    
    BENCH_LOGO;
    srand(0);
    a0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b1b0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a1=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    xxb2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));  
    shift=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    c=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
        
    time_s = time(NULL);
	for (i=0;i<mla3a16_btb_BENCHMARK_TIMES;i++)
	{
		/*if we do not change the a0/a1.., then gcc will call mla3a_mips only
		  once and then add the return value  mla3a16_BENCHMARK_TIMES times.
		  A smart guy but that's not what we want when do benchmark....
		*/
		a0++;
		a1++;
		a2++;
		b1b0++;
		xxb2++;
		shift++;
		c++;
		
		/* we use return value res to stop gcc 
		   eliminating this function call
		*/
        resc += mla3a16_btb_mips(a0,a1,a2,b1b0,xxb2,shift,c);
	}
	time_e = time(NULL);
	BENCH_ASM;
	    
	time_s = time(NULL);
	for (i=0;i<mla3a16_btb_BENCHMARK_TIMES;i++)
	{
        a0++;
		a1++;
		a2++;
		b1b0++;
		xxb2++;
		shift++;
		c++;
		resc += mla3a16_btb(a0,a1,a2,b1b0,xxb2,shift,c);
	}
	time_e = time(NULL);
    BENCH_C;
    
	BENCH_END;	
}

void bench_mla3a16_btt()
{
	int i;
	GLfixed a0,b1b0,a1,a2,b2xx,shift,c,resc = 0;
	uint32_t time_s,time_e;

    
    BENCH_LOGO;
    srand(0);
    a0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b1b0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a1=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b2xx=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));  
    shift=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    c=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
        
    time_s = time(NULL);
	for (i=0;i<mla3a16_btt_BENCHMARK_TIMES;i++)
	{
		/*if we do not change the a0/a1.., then gcc will call mla3a_mips only
		  once and then add the return value  mla3a16_BENCHMARK_TIMES times.
		  A smart guy but that's not what we want when do benchmark....
		*/
		a0++;
		a1++;
		a2++;
		b1b0++;
		b2xx++;
		shift++;
		c++;
		
		/* we use return value res to stop gcc 
		   eliminating this function call
		*/
        resc += mla3a16_btt_mips(a0,a1,a2,b1b0,b2xx,shift,c);
	}
	time_e = time(NULL);
	BENCH_ASM;
	    
	time_s = time(NULL);
	for (i=0;i<mla3a16_btt_BENCHMARK_TIMES;i++)
	{
        a0++;
		a1++;
		a2++;
		b1b0++;
		b2xx++;
		shift++;
		c++;
		resc += mla3a16_btt(a0,a1,a2,b1b0,b2xx,shift,c);
	}
	time_e = time(NULL);
    BENCH_C;
    
	BENCH_END;	
}

void bench_mla3()
{
	int i;
	GLfixed a0,b0,a1,b1,a2,b2,resc = 0;
	uint32_t time_s,time_e;

    
    BENCH_LOGO;
    srand(0);
    a0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a1=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b1=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));  

    time_s = time(NULL);
	for (i=0;i<mla3_BENCHMARK_TIMES;i++)
	{
		a0++;
		a1++;
		a2++;
		b0++;
		b1++;
		b2++;

		/* we use return value res to stop gcc 
		   eliminating this function call
		*/
        resc += mla3_mips(a0,b0,a1,b1,a2,b2);
	}
	time_e = time(NULL);
	BENCH_ASM;
	    
	time_s = time(NULL);
	for (i=0;i<mla3_BENCHMARK_TIMES;i++)
	{
        a0++;
		a1++;
		a2++;
		b0++;
		b1++;
		b2++;
		resc += mla3(a0,b0,a1,b1,a2,b2);
	}
	time_e = time(NULL);
    BENCH_C;
    
	BENCH_END;	
}

void bench_mla4()
{
	int i;
	GLfixed a0,b0,a1,b1,a2,b2,a3,b3,resc = 0;
	uint32_t time_s,time_e;

    
    BENCH_LOGO;
    srand(0);
    a0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b0=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a1=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b1=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    a2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b2=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));  
    a3=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));
    b3=(int)(((RAND_MAX+1.0))*rand()/(RAND_MAX+1.0));  

    time_s = time(NULL);
	for (i=0;i<mla4_BENCHMARK_TIMES;i++)
	{
		a0++;
		a1++;
		a2++;
		a3++;
		b0++;
		b1++;
		b2++;
		b3++;

		/* we use return value res to stop gcc 
		   eliminating this function call
		*/
        resc += mla4_mips(a0,b0,a1,b1,a2,b2,a3,b3);
	}
	time_e = time(NULL);
	BENCH_ASM;
	    
	time_s = time(NULL);
	for (i=0;i<mla4_BENCHMARK_TIMES;i++)
	{
        a0++;
		a1++;
		a2++;
		b0++;
		b1++;
		b2++;
		resc += mla4(a0,b0,a1,b1,a2,b2,a3,b3);
	}
	time_e = time(NULL);
    BENCH_C;
    
	BENCH_END;	
}

int main()
{
	/* validation */
	/*this macro is passed by cflags*/
#ifdef __AGL_SOFT_FLOAT__
	validate_gglFloatToFixed();
#endif

	validate_vsquare3();
	validate_mla3a();
	validate_mla3a16();
	validate_mla3a16_btb();
	validate_mla3a16_btt();
	validate_mla3();
	validate_mla4();
	/* benchmark */
	bench_vsquare3();
	bench_mla3a();
	bench_mla3a16();
	bench_mla3a16_btb();
	bench_mla3a16_btt();
	bench_mla3();
	bench_mla4();
	
	return 1;
}
