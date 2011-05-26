#/***************************************************************************
#*
#*  File: pvmp3_mdct_18_asm.S
#*
#*  Description:
#*
#***************************************************************************/

#/***************************************************************************
#*
#*  Function: pvmp3_mdct_18
#*
#*  Description:
#*
#*           Returns the mdct of length 18 of the input vector, as well
#*           as the overlap vector for next iteration ( on history[])
#*
#*  Parameters:
#*
#*       a0: input vector of length 18
#*       a1: input for overlap and add, vector updated with
#*           next overlap and add values
#*       a2: sine window used in the mdct, three types are allowed
#*           noraml, start and stop
#*
#*
#*  Reference: pvmp3_mdct_18.cpp
#*
#*
#*  Notes:
#*
#***************************************************************************/
#define cos_pi_9     (0x7847d900)
#define cos_2pi_9    (0x620dbe80)
#define cos_4pi_9    (0x163a1a80)
#define cos_5pi_9    (0xe9c5e580)
#define cos_7pi_9    (0x9df24180)
#define cos_8pi_9    (0x87b82700)
#define cos_pi_6     (0x6ed9eb80)
#define cos_5pi_6    (0x91261480)
#define cos_5pi_18   (0x5246dd80)
#define cos_7pi_18   (0x2bc75100)
#define cos_11pi_18  (0xd438af00)
#define cos_13pi_18  (0xadb92280)
#define cos_17pi_18  (0x81f1d200)
   .text
   .align   2
   .globl  pvmp3_mdct_18
   .set  nomips16
   .set  nomicromips
   .ent  pvmp3_mdct_18
   .type pvmp3_mdct_18, @function
   #### void pvmp3_mdct_18(int32 vec[], int32 *history, const int32 *window)
   # a0: int32 vec[]
   # a1: int32 *history
   # a2: const int32 *window
pvmp3_mdct_18:
   .frame $sp, 40, $ra
   .set  noreorder
   .cpload $t9
   .set  reorder
   addiu $sp, -40
   sw    $a1, 44($sp)
   sw    $a2, 48($sp)
   sw    $s0, 0($sp)
   sw    $s1, 4($sp)
   sw    $s2, 8($sp)
   sw    $s3, 12($sp)
   sw    $s4, 16($sp)
   sw    $s5, 20($sp)
   sw    $s6, 24($sp)
   sw    $s7, 28($sp)
   sw    $s8, 32($sp)
   sw    $ra, 36($sp)
   ############################################################################
   # for (i = 9; i != 0; i--)
   ############################################################################
   # loop unrolled
   la    $s8, cosTerms_1_ov_cos_phi
   lw    $s4, 0($a0)          # tmp = vec[ 0]
   lw    $s0, 0($s8)          # s0: cosTerms_1_ov_cos_phi[0]
   lw    $s5, 17*4($a0)       # tmp1 = vec[17]
   lw    $s1, 17*4($s8)       # s1: cosTerms_1_ov_cos_phi[17]
   la    $s7, cosTerms_dct18
   mult  $ac0, $s4, $s0       # fxp_mul32(tmp,  cosTerms_1_ov_cos_phi[0])
   mult  $ac1, $s5, $s1       # fxp_mul32(tmp1, cosTerms_1_ov_cos_phi[17])
   #2
   lw    $s6, 4($a0)          # tmp = vec[ 1]
   lw    $s2, 4($s8)          # s2: cosTerms_1_ov_cos_phi[1]
   lw    $a3, 16*4($a0)       # tmp1 = vec[16]
   extr.w   $s4, $ac0, 31     # tmp= fxp_mul32_Q32(tmp << 1,
                              #                     cosTerms_1_ov_cos_phi[0])
   extr.w   $s5, $ac1, 27     # tmp1= fxp_mul32_Q27(tmp1,
                              #                      cosTerms_1_ov_cos_phi[17])
   mult  $ac3, $s6, $s2       # fxp_mul32(tmp,  cosTerms_1_ov_cos_phi[1])
   lw    $s3, 16*4($s8)       # s3: cosTerms_1_ov_cos_phi[16]
   #3
   lw    $s0, 2*4($s8)        # s0: cosTerms_1_ov_cos_phi[2]
   lw    $s1, 15*4($s8)       # s1: cosTerms_1_ov_cos_phi[15]
   lw    $v0, 0($s7)          # v0: pt_cos_split[0]
   extr.w   $s6, $ac3, 31     # tmp= fxp_mul32_Q32(tmp << 1,
                              #                     cosTerms_1_ov_cos_phi[1])
   add   $t0, $s4, $s5        # tmp + tmp1
   sub   $t8, $s4, $s5        # tmp - tmp1
   sw    $t0, 0($a0)          # vec[0] = tmp + tmp1
   mult  $ac0, $a3, $s3       # fxp_mul32(tmp1, cosTerms_1_ov_cos_phi[16])
   mult  $ac2, $t8, $v0       # fxp_mul32((tmp - tmp1), pt_cos_split[0])
   lw    $s4, 2*4($a0)        # tmp = vec[ 2]
   lw    $s5, 15*4($a0)       # tmp1 = vec[15]
   #4
   lw    $s2, 3*4($s8)        # s2: cosTerms_1_ov_cos_phi[3]
   extr.w   $a3, $ac0, 27     # tmp1= fxp_mul32_Q27(tmp1,
                              #                      cosTerms_1_ov_cos_phi[16])
   extr.w   $t8, $ac2, 28     # fxp_mul32_Q28((tmp - tmp1), pt_cos_split[0])
   mult  $ac2, $s4, $s0       # fxp_mul32(tmp,  cosTerms_1_ov_cos_phi[2])
   mult  $ac3, $s5, $s1       # fxp_mul32(tmp1, cosTerms_1_ov_cos_phi[15])
   lw    $v0, 2*4($s7)        # v0: pt_cos_split[2]
   lw    $s3, 14*4($s8)       # s3: cosTerms_1_ov_cos_phi[14]
   lw    $v1, 4($s7)          # v1: pt_cos_split[1]
   add   $t9, $s6, $a3        # tmp + tmp1
   sub   $t7, $s6, $a3        # tmp - tmp1
   extr.w   $s5, $ac3, 27     # tmp1= fxp_mul32_Q27(tmp1,
                              #                      cosTerms_1_ov_cos_phi[15])
   mult  $ac1, $t7, $v1       # fxp_mul32((tmp - tmp1), pt_cos_split[1])
   extr.w   $s4, $ac2, 31     # tmp= fxp_mul32_Q32(tmp << 1,
                              #                     cosTerms_1_ov_cos_phi[2])
   sw    $t9, 4($a0)          # vec[1] = tmp + tmp1
   lw    $s6, 3*4($a0)        # tmp = vec[ 3]
   lw    $a3, 14*4($a0)       # tmp1 = vec[14]
   extr.w   $t7, $ac1, 28     # fxp_mul32_Q28((tmp - tmp1), pt_cos_split[1])
   mult  $ac1, $s6, $s2       # fxp_mul32(tmp,  cosTerms_1_ov_cos_phi[3])
   mult  $ac2, $a3, $s3       # fxp_mul32(tmp1, cosTerms_1_ov_cos_phi[14])
   add   $t0, $s4, $s5        # tmp + tmp1
   sub   $t6, $s4, $s5        # tmp - tmp1
   sw    $t0, 2*4($a0)        # vec[2] = tmp + tmp1
   mult  $ac0, $t6, $v0       # fxp_mul32((tmp - tmp1), pt_cos_split[2])
   extr.w   $s6, $ac1, 31     # tmp= fxp_mul32_Q32(tmp << 1,
                              #                     cosTerms_1_ov_cos_phi[3])
   extr.w   $a3, $ac2, 27     # tmp1= fxp_mul32_Q27(tmp1,
                              #                      cosTerms_1_ov_cos_phi[14])
   #5
   lw    $s4, 4*4($a0)        # tmp = vec[ 4]
   lw    $s0, 4*4($s8)        # s0: cosTerms_1_ov_cos_phi[4]
   extr.w   $t6, $ac0, 28     # fxp_mul32_Q28((tmp - tmp1), pt_cos_split[2])
   lw    $s5, 13*4($a0)       # tmp1 = vec[13]
   lw    $s1, 13*4($s8)       # s1: cosTerms_1_ov_cos_phi[13]
   mult  $ac0, $s4, $s0       # fxp_mul32(tmp,  cosTerms_1_ov_cos_phi[4])
   lw    $v1, 3*4($s7)        # v1: pt_cos_split[3]
   mult  $ac1, $s5, $s1       # fxp_mul32(tmp1, cosTerms_1_ov_cos_phi[13])
   sub   $t5, $s6, $a3        # tmp - tmp1
   add   $t9, $s6, $a3        # tmp + tmp1
   mult  $ac3, $t5, $v1       # fxp_mul32((tmp - tmp1), pt_cos_split[3])
   extr.w   $s4, $ac0, 31     # tmp= fxp_mul32_Q32(tmp << 1,
                              #                     cosTerms_1_ov_cos_phi[4])
   sw    $t9, 3*4($a0)        # vec[3] = tmp + tmp1
   lw    $v0, 4*4($s7)        # v0: pt_cos_split[4]
   extr.w   $s5, $ac1, 27     # tmp1= fxp_mul32_Q27(tmp1,
                              #                      cosTerms_1_ov_cos_phi[13])
   #6
   lw    $s6, 5*4($a0)        # tmp = vec[ 5]
   lw    $s2, 5*4($s8)        # s2: cosTerms_1_ov_cos_phi[5]
   extr.w   $t5, $ac3, 28     # fxp_mul32_Q28((tmp - tmp1), pt_cos_split[3])
   lw    $s3, 12*4($s8)       # s3: cosTerms_1_ov_cos_phi[12]
   lw    $a3, 12*4($a0)       # tmp1 = vec[12]
   mult  $ac3, $s6, $s2       # fxp_mul32(tmp,  cosTerms_1_ov_cos_phi[5])
   #7
   lw    $s0, 6*4($s8)        # s0: cosTerms_1_ov_cos_phi[6]
   mult  $ac0, $a3, $s3       # fxp_mul32(tmp1, cosTerms_1_ov_cos_phi[12])
   add   $t0, $s4, $s5        # tmp + tmp1
   sub   $t4, $s4, $s5        # tmp - tmp1
   extr.w   $s6, $ac3, 31     # tmp= fxp_mul32_Q32(tmp << 1,
                              #                     cosTerms_1_ov_cos_phi[5])
   mult  $ac2, $t4, $v0       # fxp_mul32((tmp - tmp1), pt_cos_split[4])
   sw    $t0, 4*4($a0)        # vec[4] = tmp + tmp1
   lw    $v1, 5*4($s7)        # v1: pt_cos_split[5]
   extr.w   $a3, $ac0, 27     # tmp1= fxp_mul32_Q27(tmp1,
                              #                      cosTerms_1_ov_cos_phi[12])
   lw    $s4, 6*4($a0)        # tmp = vec[ 6]
   lw    $v0, 6*4($s7)        # v0: pt_cos_split[6]
   extr.w   $t4, $ac2, 28     # fxp_mul32_Q28((tmp - tmp1), pt_cos_split[4])
   mult  $ac2, $s4, $s0       # fxp_mul32(tmp,  cosTerms_1_ov_cos_phi[6])
   lw    $s5, 11*4($a0)       # tmp1 = vec[11]
   lw    $s1, 11*4($s8)       # s1: cosTerms_1_ov_cos_phi[11]
   add   $t9, $s6, $a3        # tmp + tmp1
   sub   $t3, $s6, $a3        # tmp - tmp1
   mult  $ac3, $s5, $s1       # fxp_mul32(tmp1, cosTerms_1_ov_cos_phi[11])
   extr.w   $s4, $ac2, 31     # tmp= fxp_mul32_Q32(tmp << 1,
                              #                     cosTerms_1_ov_cos_phi[6])
   mult  $ac1, $t3, $v1       # fxp_mul32((tmp - tmp1), pt_cos_split[5])
   sw    $t9, 5*4($a0)        # vec[5] = tmp + tmp1
   #8
   lw    $s6, 7*4($a0)        # tmp = vec[ 7]
   extr.w   $s5, $ac3, 27     # tmp1= fxp_mul32_Q27(tmp1,
                              #                      cosTerms_1_ov_cos_phi[11])
   lw    $s2, 7*4($s8)        # s2: cosTerms_1_ov_cos_phi[7]
   lw    $a3, 10*4($a0)       # tmp1 = vec[10]
   extr.w   $t3, $ac1, 28     # fxp_mul32_Q28((tmp - tmp1), pt_cos_split[5])
   mult  $ac1, $s6, $s2       # fxp_mul32(tmp,  cosTerms_1_ov_cos_phi[7])
   lw    $s3, 10*4($s8)       # s3: cosTerms_1_ov_cos_phi[10]
   lw    $v1, 7*4($s7)        # v1: pt_cos_split[7]
   add   $t0, $s4, $s5        # tmp + tmp1
   mult  $ac2, $a3, $s3       # fxp_mul32(tmp1, cosTerms_1_ov_cos_phi[10])
   sub   $t2, $s4, $s5        # tmp - tmp1
   extr.w   $s6, $ac1, 31     # tmp= fxp_mul32_Q32(tmp << 1,
                              #                     cosTerms_1_ov_cos_phi[7])
   mult  $ac0, $t2, $v0       # fxp_mul32((tmp - tmp1), pt_cos_split[6])
   sw    $t0, 6*4($a0)        # vec[6] = tmp + tmp1
   extr.w   $a3, $ac2, 27     # tmp1= fxp_mul32_Q27(tmp1,
                              #                     cosTerms_1_ov_cos_phi[10])
   #9
   lw    $s4, 8*4($a0)        # tmp = vec[ 8]
   lw    $s0, 8*4($s8)        # s0: cosTerms_1_ov_cos_phi[8]
   extr.w   $t2, $ac0, 28     # fxp_mul32_Q28((tmp - tmp1), pt_cos_split[6])
   lw    $s5, 9*4($a0)        # tmp1 = vec[ 9]
   mult  $ac2, $s4, $s0       # fxp_mul32(tmp,  cosTerms_1_ov_cos_phi[8])
   lw    $s1, 9*4($s8)        # s1: cosTerms_1_ov_cos_phi[9]
   sub   $t1, $s6, $a3        # tmp - tmp1
   add   $t9, $s6, $a3        # tmp + tmp1
   extr.w   $s4, $ac2, 31     # tmp= fxp_mul32_Q32(tmp << 1,
                              #                     cosTerms_1_ov_cos_phi[8])
   mult  $ac1, $s5, $s1       # fxp_mul32(tmp1, cosTerms_1_ov_cos_phi[9])
   mult  $ac3, $t1, $v1       # fxp_mul32((tmp - tmp1), pt_cos_split[7])
   sw    $t9, 7*4($a0)        # vec[7] = tmp + tmp1
   lw    $v0, 8*4($s7)        # v0: pt_cos_split[8]
   ############################################################################
   # pvmp3_dct_9(&vec[9]);     // Odd  terms
   # these are done first in order to minimize memory manipulation;
   # after both dct_9 functions are done, odd terms are in memory on locations
   # vec[9] to vec[17] and even are in registers $t0 to $t8;
   ############################################################################
   li    $a1, cos_pi_9        # a1 = cos_pi_9
   extr.w   $s5, $ac1, 27     # tmp1= fxp_mul32_Q27(tmp1,
                              #                      cosTerms_1_ov_cos_phi[9])
   extr.w   $t1, $ac3, 28     # fxp_mul32_Q28((tmp - tmp1), pt_cos_split[7])
   add   $s2, $t6, $t2        # s2 = tmp2 =  vec[15] + vec[11]
   sub   $s6, $t6, $t2        # s6 = tmp6 =  vec[15] - vec[11]
   li    $a2, cos_5pi_9       # a2 = cos_5pi_9
   add   $s3, $t5, $t3        # s3 = tmp3 =  vec[14] + vec[12]
   li    $a3, cos_7pi_9       # a3 = cos_7pi_9
   sub   $t0, $s4, $s5        # tmp - tmp1
   add   $t9, $s4, $s5        # tmp + tmp1
   mult  $ac2, $t0, $v0       # fxp_mul32((tmp - tmp1), pt_cos_split[8])
   sw    $t9, 8*4($a0)        # vec[8] = tmp + tmp1
   sub   $s5, $t5, $t3        # s5 = tmp5 =  vec[14] - vec[12]
   sub   $s7, $t7, $t1        # s7 = tmp7 =  vec[16] - vec[10]
   add   $s1, $t7, $t1        # s1 = tmp1 =  vec[16] + vec[10]
   extr.w   $t0, $ac2, 28     # fxp_mul32_Q28((tmp - tmp1), pt_cos_split[8])
   add   $v1, $s1, $t4        # v1 = tmp1 + vec[13]
   sra   $s1, $s1, 1          # tmp1 >> 1
   sub   $t2, $s1, $t4        # vec[11]  = (tmp1 >> 1) - vec[13]
   negu  $t4, $t2             # vec[13]  =  -vec[11]
   add   $t9, $s5, $s6        # tmp5 + tmp6
   mthi  $t2, $ac0            # hi = vec[11]
   mtlo  $zero, $ac0          # lo = 0
   add   $s0, $t8, $t0        # s0 = tmp0 =  vec[17] + vec[9]
   sub   $s8, $t8, $t0        # s8 = tmp8 =  vec[17] - vec[9]
   add   $v0, $s0, $s2        # v0 = tmp0 + tmp2
   add   $v0, $v0, $s3        # v0 = tmp0 + tmp2 + tmp3
   add   $t0, $v0, $v1        # vec[9]=(tmp0+tmp2+tmp3)+(tmp1+vec[13])
   sra   $v0, $v0, 1          # (tmp0 + tmp2 + tmp3) >> 1
   negu  $t8, $t2             # vec[17]  =  -vec[11]
   sub   $t6, $v0, $v1        # vec[15]=((tmp0+tmp2+tmp3)>>1)-(tmp1+vec[13])
   sub   $t9, $t9, $s8        # (tmp5 + tmp6 - tmp8)
   sll   $t9, 1               # (tmp5 + tmp6 - tmp8) << 1
   sll   $s0, 1               # tmp0<<1
   madd  $ac0, $s0, $a1       # vec[11]=fxp_mac32(vec[11],tmp0<<1,cos_pi_9)
   mtlo  $zero, $ac0          # lo = 0
   sll   $s2, 1               # tmp2<<1
   madd  $ac0, $s2, $a2       # vec[11]=fxp_mac32(vec[11],tmp2<<1,cos_5pi_9)
   mtlo  $zero, $ac0          # lo = 0
   sll   $s3, 1               # tmp3<<1
   madd  $ac0, $s3, $a3       # vec[11]=fxp_mac32(vec[11],tmp3<<1,cos_7pi_9)
   li    $s1, cos_2pi_9       # s1 = cos_2pi_9
   li    $s4, cos_4pi_9       # s4 = cos_4pi_9
   li    $v1, cos_8pi_9       # v1 = cos_8pi_9
   mthi  $t4, $ac1            # hi = vec[13]
   mfhi  $t2, $ac0            # t2 = vec[11] = fxp_mac32_Q32(vec[11],
                              #                              tmp3<<1,cos_7pi_9)
   mtlo  $zero, $ac1          # lo = 0
   mthi  $t8, $ac2            # hi = vec[17]
   madd  $ac1, $s0, $s1       # vec[13]=fxp_mac32(vec[13],tmp0<<1,cos_2pi_9)
   mtlo  $zero, $ac1          # lo = 0
   mtlo  $zero, $ac2          # lo = 0
   madd  $ac1, $s2, $v1       # vec[13]=fxp_mac322(vec[13],tmp2<<1,cos_8pi_9)
   mtlo  $zero, $ac1          # lo = 0
   madd  $ac2, $s0, $s4       # vec[17]=fxp_mac32(vec[17],tmp0<<1,cos_4pi_9)
   madd  $ac1, $s3, $s4       # vec[13]=fxp_mac32(vec[13],tmp3<<1,cos_4pi_9)
   mtlo  $zero, $ac2          # lo = 0
   sll   $s5, 1               # tmp5<<1
   madd  $ac2, $s2, $s1       # fxp_mac32(vec[17], tmp2 << 1, cos_2pi_9)
   mtlo  $zero, $ac2          # lo = 0
   mfhi  $t4, $ac1            # t4 = vec[13]= fxp_mac32_Q32(vec[13],
                              #                         tmp3<<1,cos_4pi_9)
   madd  $ac2, $s3, $v1       # fxp_mac32(vec[17],tmp3<<1,cos_8pi_9)
   li    $a1, cos_pi_6        # cos_pi_6
   li    $a2, cos_11pi_18     # a2 = cos_11pi_18
   li    $a3, cos_13pi_18     # a3 = cos_13pi_18
   mult  $ac0, $t9, $a1       # fxp_mul32((tmp5+tmp6-tmp8)<<1,cos_pi_6)
   mfhi  $t8, $ac2            # t8 = vec[17] = fxp_mac32_Q32(vec[17],tmp3<<1,
                              #                              cos_8pi_9)
   li    $s1, cos_5pi_6       # s1 = cos_5pi_6
   li    $s4, cos_17pi_18     # s4 = cos_17pi_18
   sll   $s6, 1               # tmp6<<1
   mfhi  $t3, $ac0            # t3 = vec[12] = fxp_mul32_Q32((tmp5+tmp6-tmp8)<<1,
                              #                           cos_pi_6)
   mult  $ac1, $s5, $a2       # vec[10]=fxp_mul32(tmp5<<1,cos_11pi_18)
   mtlo  $zero, $ac1          # lo = 0
   sll   $s7, 1               # tmp7<<1
   madd  $ac1, $s6, $a3       # vec[10]=fxp_mac32(vec[10],tmp6<<1,
                              #                               cos_13pi_18)
   mtlo  $zero, $ac1          # lo = 0
   sll   $s8, 1               # tmp8<<1
   madd  $ac1, $s7, $s1       # vec[10]=fxp_mac32(vec[10],tmp7<<1,
                              #                               cos_5pi_6)
   mtlo  $zero, $ac1          # lo = 0
   li    $v1, cos_7pi_18      # v1 = cos_7pi_18
   madd  $ac1, $s8, $s4       # vec[10]=fxp_mac32(vec[10],tmp8<<1,
                              #                               cos_17pi_18)
   mult  $ac2, $s5, $s4       # vec[14]  = fxp_mul32(tmp5 << 1,
                              #                               cos_17pi_18)
   mtlo  $zero, $ac2          # lo = 0
   madd  $ac2, $s6, $v1       # vec[14]=fxp_mac32(vec[14],tmp6<<1,
                              #                               cos_7pi_18)
   mfhi  $t1, $ac1            # t1 = vec[10] = fxp_mac32_Q32(vec[10],tmp8<<1,
                              #                               cos_17pi_18)
   mtlo  $zero, $ac2          # lo = 0
   li    $ra, cos_5pi_18      # ra = cos_5pi_18
   madd  $ac2, $s7, $a1       # vec[14]= fxp_mac32(vec[14],tmp7<<1,
                              #                                cos_pi_6)
   mtlo  $zero, $ac2          # lo = 0
   mult  $ac3, $s5, $ra       # vec[16]  = fxp_mul32(tmp5 << 1,
                              #                           cos_5pi_18)
   madd  $ac2, $s8, $a3       # vec[14]=fxp_mac32(vec[14],tmp8<<1,
                              #                                cos_13pi_18)
   mtlo  $zero, $ac3          # lo = 0
   sub   $t0, $t0, $t1        # vec[1]  = vec[ 9] - tmp2
   madd  $ac3, $s6, $s4       # vec[16]=fxp_mac32(vec[16],tmp6<<1,
                              #                                cos_17pi_18)
   mtlo  $zero, $ac3          # lo = 0
   mfhi  $t5, $ac2            # tmp4 = vec[14] = fxp_mac32_Q32(vec[14],
                              #                          tmp8<<1,cos_13pi_18)
   madd  $ac3, $s7, $a1       # vec[16]=fxp_mac32(vec[16],tmp7<<1,
                              #                                cos_pi_6)
   mtlo  $zero, $ac3          # lo = 0
   sub   $t1, $t2, $t1        # vec[3]  = vec[11] - tmp2
   madd  $ac3, $s8, $a2       # vec[16]=fxp_mac32(vec[16],tmp8<<1,
                              #                                cos_11pi_18)
   sub   $t2, $t2, $t3        # vec[ 5]  = vec[11] - tmp
   sub   $t3, $t4, $t3        # vec[ 7]  = vec[13] - tmp
   sub   $t4, $t4, $t5        # vec[ 9]  = vec[13] - tmp4
   sub   $t5, $t6, $t5        # vec[11]  = vec[15] - tmp4
   mfhi  $t7, $ac3            # t7 = vec[16] = fxp_mac32_Q32(vec[16],tmp8<<1,
                              #                                cos_11pi_18)
   sw    $t0, 9*4($a0)        # vec[1]
   sw    $t1, 10*4($a0)       # vec[3]
   sw    $t2, 11*4($a0)       # vec[5]
   sw    $t3, 12*4($a0)       # vec[7]
   sw    $t4, 13*4($a0)       # vec[9]
   sw    $t5, 14*4($a0)       # vec[11]
   sub   $t6, $t6, $t7        # vec[13]  = vec[15] - tmp3
   sub   $t7, $t8, $t7        # vec[15]  = vec[17] - tmp3
   sw    $t6, 15*4($a0)       # vec[13]
   sw    $t7, 16*4($a0)       # vec[15]
   sw    $t8, 17*4($a0)       # vec[17]
   lw    $t0, 0($a0)          # vec[0]
   lw    $t8, 8*4($a0)        # vec[8]
   lw    $t1, 4($a0)          # vec[1]
   lw    $t2, 2*4($a0)        # vec[2]
   lw    $t3, 3*4($a0)        # vec[3]
   lw    $t4, 4*4($a0)        # vec[4]
   lw    $t5, 5*4($a0)        # vec[5]
   lw    $t6, 6*4($a0)        # vec[6]
   lw    $t7, 7*4($a0)        # vec[7]
   ############################################################################
   # pvmp3_dct_9(vec);         // Even terms
   ############################################################################
   add   $s0, $t8, $t0        # s0 = tmp0 =  vec[8] + vec[0]
   sub   $s8, $t8, $t0        # s8 = tmp8 =  vec[8] - vec[0]
   add   $s1, $t7, $t1        # s1 = tmp1 =  vec[7] + vec[1]
   sub   $s7, $t7, $t1        # s7 = tmp7 =  vec[7] - vec[1]
   add   $s2, $t6, $t2        # s2 = tmp2 =  vec[6] + vec[2]
   sub   $s6, $t6, $t2        # s6 = tmp6 =  vec[6] - vec[2]
   add   $s3, $t5, $t3        # s3 = tmp3 =  vec[5] + vec[3]
   sub   $s5, $t5, $t3        # s5 = tmp5 =  vec[5] - vec[3]
   add   $ra, $s0, $s2        # ra = tmp0 + tmp2
   add   $ra, $ra, $s3        # ra = tmp0 + tmp2 + tmp3
   add   $v1, $s1, $t4        # v1 = tmp1 + vec[4]
   add   $t0, $ra, $v1        # vec[0] = (tmp0 + tmp2 + tmp3) + (tmp1 + vec[4])
   sra   $s1, $s1, 1          # tmp1 >> 1
   sra   $ra, $ra, 1          # (tmp0 + tmp2 + tmp3) >> 1
   sub   $t2, $s1, $t4        # vec[2]  = (tmp1 >> 1) - vec[4]
   sub   $t6, $ra, $v1        # vec[6] ((tmp0 + tmp2 + tmp3) >> 1)
                              #         - (tmp1 + vec[4])  // later vec[12]
   negu  $t4, $t2             # vec[4]  =  -vec[2]
   negu  $t8, $t2             # vec[8]  =  -vec[2]
   add   $t9, $s5, $s6        # t9 = tmp5 + tmp6
   sub   $t9, $t9, $s8        # t9 = tmp5 + tmp6  - tmp8
   li    $a1, cos_pi_9        # a1 = cos_pi_9
   sll   $t9, 1               # (tmp5 + tmp6  - tmp8) << 1
   li    $a2, cos_5pi_9       # a2 = cos_5pi_9
   li    $a3, cos_7pi_9       # a3 = cos_7pi_9
   mthi  $t2, $ac0            # hi = vec[2]
   mtlo  $zero, $ac0          # lo = 0
   sll   $s0, 1               # tmp0<<1
   madd  $ac0, $s0, $a1       # fxp_mac32(vec[2], tmp0 << 1, cos_pi_9)
   mtlo  $zero, $ac0          # lo = 0
   sll   $s2, 1               # tmp2<<1
   madd  $ac0, $s2, $a2       # fxp_mac32(vec[2], tmp2 << 1, cos_5pi_9)
   mtlo  $zero, $ac0          # lo = 0
   sll   $s3, 1               # tmp3<<1
   madd  $ac0, $s3, $a3       # fxp_mac32(vec[2], tmp3 << 1, cos_7pi_9)
   li    $s1, cos_2pi_9       # s1 = cos_2pi_9
   li    $s4, cos_4pi_9       # s4 = cos_4pi_9
   li    $v1, cos_8pi_9       # v1 = cos_8pi_9
   mthi  $t4, $ac1            # hi = vec[4]
   mfhi  $t2, $ac0            # vec[2] = fxp_mac32_Q32(vec[2],
                              #          tmp3 << 1, cos_7pi_9) // later vec[4]
   mtlo  $zero, $ac1          # lo = 0
   mthi  $t8, $ac2            # hi = vec[8]
   madd  $ac1, $s0, $s1       # fxp_mac32(vec[4], tmp0 << 1, cos_2pi_9)
   mtlo  $zero, $ac1          # lo = 0
   mtlo  $zero, $ac2          # lo = 0
   madd  $ac1, $s2, $v1       # fxp_mac32(vec[4], tmp2 << 1, cos_8pi_9)
   mtlo  $zero, $ac1          # lo = 0
   madd  $ac2, $s0, $s4       # fxp_mac32(vec[8], tmp0 << 1, cos_4pi_9)
   madd  $ac1, $s3, $s4       # fxp_mac32(vec[4], tmp3 << 1, cos_4pi_9)
   mtlo  $zero, $ac2          # lo = 0
   sll   $s5, 1               # tmp5<<1
   madd  $ac2, $s2, $s1       # fxp_mac32(vec[8], tmp2 << 1, cos_2pi_9)
   mtlo  $zero, $ac2          # lo = 0
   mfhi  $t4, $ac1            # vec[4] = fxp_mac32_Q32(vec[4],
                              #          tmp3 << 1, cos_4pi_9) //later vec[8]
   madd  $ac2, $s3, $v1       # fxp_mac32(vec[8], tmp3 << 1, cos_8pi_9)
   li    $a1, cos_pi_6        # cos_pi_6
   li    $a2, cos_11pi_18     # a2 = cos_11pi_18
   li    $a3, cos_13pi_18     # a3 = cos_13pi_18
   mult  $ac0, $t9, $a1       # fxp_mul32((tmp5+tmp6-tmp8)<<1,cos_pi_6)
   mfhi  $t8, $ac2            # vec[8] = fxp_mac32_Q32(vec[8],
                              #          tmp3 << 1, cos_8pi_9) // later vec[16]
   li    $s1, cos_5pi_6       # s1 = cos_5pi_6
   li    $s4, cos_17pi_18     # s4 = cos_17pi_18
   sll   $s6, 1               # tmp6<<1
   mfhi  $t3, $ac0            # vec[3] = fxp_mul32_Q32((tmp5+tmp6-tmp8)<<1,
                              #                         cos_pi_6) //later vec[6]
   mult  $ac1, $s5, $a2       # vec[1]  = fxp_mul32(tmp5 << 1, cos_11pi_18)
   mtlo  $zero, $ac1          # lo = 0
   sll   $s7, 1               # tmp7<<1
   madd  $ac1, $s6, $a3       # fxp_mac32(vec[1], tmp6 << 1, cos_13pi_18)
   mtlo  $zero, $ac1          # lo = 0
   sll   $s8, 1               # tmp8<<1
   madd  $ac1, $s7, $s1       # fxp_mac32(vec[1], tmp7 << 1,   cos_5pi_6)
   mtlo  $zero, $ac1          # lo = 0
   li    $v1, cos_7pi_18      # v1 = cos_7pi_18
   madd  $ac1, $s8, $s4       # fxp_mac32(vec[1], tmp8 << 1, cos_17pi_18)
   mult  $ac2, $s5, $s4       # vec[5]  = fxp_mul32(tmp5 << 1, cos_17pi_18)
   mtlo  $zero, $ac2          # lo = 0
   ############################################################################
   #  for (i = 0; i < 6; i++)
   ############################################################################
   # loop unrolled
   # 0 and finish of dct_9
   lw    $s0, 9*4($a0)        # tmp1 = vec[1]
   madd  $ac2, $s6, $v1       # fxp_mac32(vec[5], tmp6 << 1,  cos_7pi_18)
   mfhi  $t1, $ac1            # vec[1] = fxp_mac32_Q32(vec[1],
                              #         tmp8 << 1, cos_17pi_18) //later vec[2]
   mtlo  $zero, $ac2          # lo = 0
   li    $v0, cos_5pi_18      # v0 = cos_5pi_18
   madd  $ac2, $s7, $a1       # fxp_mac32(vec[5], tmp7 << 1,    cos_pi_6)
   mtlo  $zero, $ac2          # lo = 0
   mult  $ac3, $s5, $v0       # vec[7]  = fxp_mul32(tmp5 << 1, cos_5pi_18)
   madd  $ac2, $s8, $a3       # fxp_mac32(vec[5], tmp8 << 1, cos_13pi_18)
   mtlo  $zero, $ac3          # lo = 0
   add   $t9, $t0, $s0        # vec[0] + vec[1]  (tmp2 + tmp1)
   madd  $ac3, $s6, $s4       # fxp_mac32(vec[7], tmp6 << 1, cos_17pi_18)
   mtlo  $zero, $ac3          # lo = 0
   mfhi  $t5, $ac2            # vec[5] = fxp_mac32_Q32(vec[5],
                              #         tmp8 << 1, cos_13pi_18) //later vec[10]
   madd  $ac3, $s7, $a1       # fxp_mac32(vec[7], tmp7 << 1,    cos_pi_6)
   mtlo  $zero, $ac3          # lo = 0
   lw    $a1, 44($sp)         # ptr to history
   madd  $ac3, $s8, $a2       # fxp_mac32(vec[7], tmp8 << 1, cos_11pi_18)
   negu  $t9, $t9             # -(vec[0] + vec[1]) = -(tmp2 + tmp1)
   lw    $v0, 0($a1)          # history[ 0]
   lw    $a2, 48($sp)         # ptr to window
   lw    $s4, 13*4($a0)       # vec[9] (tmp3)
   mfhi  $t7, $ac3            # vec[7] = fxp_mac32_Q32(vec[7],
                              #         tmp8 << 1, cos_11pi_18) //later vec[14]
   lw    $a3, 0($a2)          # window[0]
   sw    $t9, 0($a1)          # history[ 0]= -(vec[0] + vec[1]) = -(tmp2 + tmp1)
   move  $s8, $t5             # tmp3 = tmp4 = vec[10]
   add   $t5, $s4, $t5        # vec[10] = vec[9] + vec[10]
   mult  $ac0, $t5, $a3       # fxp_mul32((vec[10]), window[ 0])
   # 1
   lw    $s5, 14*4($a0)       # tmp4 = vec[11]
   lw    $v1, 4($a1)          # tmp = history[ 1]
   add   $t9, $s0, $t1        # vec[1] + vec[2] (tmp2 + tmp1)
   negu  $t9, $t9             # -(vec[1] + vec[2])  ( - (tmp2 + tmp1) )
   mfhi  $t0, $ac0            # fxp_mul32_Q32((vec[10]), window[ 0]);
   lw    $a3, 4($a2)          # window[ 1]
   sw    $t9, 4($a1)          # history[ 1] = -(vec[1] + vec[2])
   move  $s3, $s5             # tmp3 = tmp4 = vec[11]
   add   $s5, $s8, $s5        # vec[11] = vec[10] + vec[11]
   mult  $ac1, $s5, $a3       # fxp_mul32((vec[11]),window[ 1])
   # 2
   lw    $s1, 10*4($a0)       # tmp1 = vec[3]
   add   $t0, $t0, $v0        # vec[0] = fxp_mac32_Q32(history[0],
                              #                        (vec[10]), window[ 0])
   sw    $t0, 0($a0)          # vec[0]
   lw    $v0, 2*4($a1)        # tmp = history[ 2]
   add   $t9, $t1, $s1        # vec[2] + vec[3] (tmp2 + tmp1)
   negu  $t9, $t9             # -(vec[2] + vec[3])  ( - (tmp2 + tmp1) )
   mfhi  $s0, $ac1            # fxp_mul32_Q32((vec[11]),window[ 1])
   lw    $a3, 2*4($a2)        # window[ 2]
   sw    $t9, 2*4($a1)        # history[ 2] = -(vec[2] + vec[3])
   move  $s8, $t6             # tmp3 = tmp4 = vec[12]
   add   $t6, $s3, $t6        # vec[12] = vec[11] + vec[12]
   mult  $ac2, $t6, $a3       # fxp_mul32((vec[12]),window[ 2])
   # 3
   lw    $s6, 15*4($a0)       # tmp4 = vec[13]
   add   $s0, $s0, $v1        # fxp_mac32_Q32(history[1], (vec[11]), window[ 1])
   sw    $s0, 4($a0)          # vec[1]
   lw    $v1, 3*4($a1)        # tmp = history[ 3]
   add   $t9, $s1, $t2        # vec[3] + vec[4]  (tmp2 + tmp1)
   negu  $s0, $t9             # s0: history[3] = - (vec[3] + vec[4])
                              #                  - (tmp2 + tmp1)
   mfhi  $t1, $ac2            # fxp_mul32_Q32((vec[12]),window[ 2])
   lw    $a3, 3*4($a2)        # window[ 3]
   move  $s3, $s6             # tmp3 = tmp4 = vec[13]
   add   $s6, $s8, $s6        # vec[13] = vec[12] + vec[13]
   mult  $ac3, $s6, $a3       # fxp_mul32((vec[13]),window[ 3])
   # 4
   lw    $s2, 11*4($a0)       # tmp1 = vec[5]
   add   $t1, $t1, $v0        # fxp_mac32_Q32(history[2], (vec[12]), window[ 2])
   sw    $t1, 2*4($a0)        # vec[2]
   lw    $v0, 4*4($a1)        # tmp = history[ 4]
   add   $t9, $t2, $s2        # vec[4] + vec[5]  (tmp2 + tmp1)
   negu  $t9, $t9             # -(vec[4] + vec[5])  ( - (tmp2 + tmp1) )
   mfhi  $s1, $ac3            # fxp_mul32_Q32((vec[13]),window[ 3])
   lw    $a3, 4*4($a2)        # window[ 4]
   sw    $t9, 4*4($a1)        # history[ 4] = -(vec[4] + vec[5])
   move  $s8, $t7             # tmp3 = tmp4 = vec[14]
   add   $t7, $s3, $t7        # vec[14] = vec[13] + vec[14]
   mult  $ac0, $t7, $a3       # fxp_mul32((vec[14]),window[ 4])
   # 5
   lw    $s7, 16*4($a0)       # tmp4 = vec[15]
   add   $s1, $s1, $v1        # fxp_mac32_Q32(history[3], (vec[13]), window[ 3])
   sw    $s1, 3*4($a0)        # vec[3]
   lw    $v1, 5*4($a1)        # tmp = history[ 5]
   add   $t9, $s2, $t3        # vec[5] + vec[6]   (tmp2 + tmp1)
   negu  $s1, $t9             # -(vec[5] + vec[6])   ( - (tmp2 + tmp1) )
   mfhi  $t2, $ac0            # fxp_mul32_Q32((vec[14]),window[ 4])
   lw    $a3, 5*4($a2)        # window[ 5]
   move  $ra, $s7             # tmp3 = tmp4 = vec[15]
   add   $s7, $s8, $s7        # vec[15] = vec[14] + vec[15]
   mult  $ac1, $s7, $a3       # fxp_mul32((vec[15]),window[ 5])
   ############################################################################
   #  after  for (i = 0; i < 6; i++)
   ############################################################################
   lw    $s3, 12*4($a0)       # tmp1 = vec[7]
   add   $t2, $t2, $v0        # fxp_mac32_Q32(history[4], (vec[14]), window[ 4])
   sw    $t2, 4*4($a0)        # vec[4]
   lw    $v0, 6*4($a1)        # tmp = history[ 6]
   add   $t9, $t3, $s3        # vec[6] + vec[7]   (tmp2 + tmp1)
   negu  $t9, $t9             # -(vec[6] + vec[7])   ( - (tmp2 + tmp1) )
   mfhi  $s2, $ac1            # fxp_mul32_Q32((vec[15]),window[ 5])
   lw    $a3, 6*4($a2)        # window[6]
   sw    $t9, 6*4($a1)        # history[ 6] = -(vec[6] + vec[7])
   move  $t0, $t8             # tmp4 = vec[16]
   add   $t8, $ra, $t8        # vec[16] = vec[16] +  vec[15]
   mult  $ac2, $t8, $a3       # fxp_mul32(vec[16], window[ 6])
   lw    $s8, 17*4($a0)       # vec[17]
   add   $s2, $s2, $v1        # fxp_mac32_Q32(history[5],vec[15],window[ 5])
   sw    $s2, 5*4($a0)        # vec[5]
   lw    $v1, 7*4($a1)        # tmp = history[ 7]
   lw    $a3, 7*4($a2)        # window[7]
   add   $t9, $s3, $t4        # vec[7] + vec[8]
   extr.w  $t3, $ac2, 31      # fxp_mul32(vec[16] << 1, window[ 6])
   negu  $s2, $t9             # history[ 7] = -( vec[7] + vec[8])
   add   $ra, $s8, $t0        # tmp4 = vec[17] + vec[16] = vec[17] + tmp4
   mult  $ac3, $ra, $a3       # fxp_mul32( tmp4, window[ 7])
   lw    $a3, 8*4($a2)        # window[8]
   add   $t3, $t3, $v0        # fxp_mac32_Q32(history[6],vec[16]<<1,window[6])
   sw    $t3, 6*4($a0)        # vec[6]
   lw    $v0, 8*4($a1)        # tmp1 = history[ 8]
   add   $t9, $t4, $s4        # vec[8] + vec[9]
   negu  $t2, $t9             # history[ 8] = -(vec[8] + vec[9])
   extr.w  $s3, $ac3, 31      # fxp_mul32( tmp4 << 1, window[ 7])
   mult  $ac0, $s8, $a3       # fxp_mul32( vec[17], window[ 8])
   lw    $a3, 9*4($a2)        # window[9]
   add   $s3, $s3, $v1        # fxp_mac32_Q32(history[7],tmp4<<1,window[7])
   sw    $s3, 7*4($a0)        # vec[7]
   lw    $v1, 9*4($a1)        # tmp = history[ 9]
   extr.w  $t4, $ac0, 31      # fxp_mul32( vec[17] << 1, window[ 8])
   mult  $ac1, $s8, $a3       # fxp_mul32(  vec[17], window[ 9])
   lw    $a3, 17*4($a2)       # window[17]
   mult  $ac2, $t5, $a3       # fxp_mul32( vec[10], window[17])
   extr.w  $s4, $ac1, 31      # fxp_mul32(  vec[17] << 1, window[ 9])
   add   $t4, $t4, $v0        # fxp_mac32_Q32(tmp1, vec[17] << 1, window[ 8])
   sw    $t4, 8*4($a0)        # vec[8]
   lw    $v0, 17*4($a1)       # tmp1 = history[17]
   extr.w  $s8, $ac2, 31      # fxp_mul32( vec[10] << 1, window[17])
   lw    $a3, 16*4($a2)       # window[16]
   negu  $t5, $t8             # vec[10] = - vec[16]
   add   $s4, $s4, $v1        # fxp_mac32_Q32(tmp,  vec[17] << 1, window[ 9])
   mult  $ac3, $s5, $a3       # fxp_mul32(vec[11], window[16])
   sw    $s4, 9*4($a0)        # vec[9]
   lw    $v1, 16*4($a1)       # tmp2 = history[16]
   negu  $s5, $s7             # vec[11] = - vec[15]
   add   $s8, $s8, $v0        # fxp_mac32_Q32(tmp1, vec[10] << 1, window[17])
   sw    $s8, 17*4($a0)       # vec[17]
   extr.w  $t8, $ac3, 31      # fxp_mul32(vec[11] << 1, window[16])
   lw    $a3, 15*4($a2)       # window[15]
   lw    $v0, 15*4($a1)       # tmp1 = history[15]
   mult  $ac0, $t6, $a3       # fxp_mul32( vec[12], window[15])
   negu  $t6, $t7             # vec[12] = - vec[ 14]
   lw    $a3, 14*4($a2)       # window[14]
   mult  $ac1, $s6, $a3       # fxp_mul32( vec[13], window[14])
   add   $t8, $t8, $v1        # fxp_mac32_Q32(tmp2, vec[11] << 1, window[16])
   sw    $t8, 16*4($a0)       # vec[16]
   extr.w  $s7, $ac0, 31      # fxp_mul32( vec[12] << 1, window[15])
   lw    $v1, 14*4($a1)       # tmp2 = history[14]
   extr.w  $t7, $ac1, 31      # fxp_mul32( vec[13] << 1, window[14])
   lw    $a3, 13*4($a2)       # window[13]
   lw    $s4, 12*4($a2)       # window[12]
   mult  $ac2, $t6, $a3       # fxp_mul32( vec[12], window[13])
   add   $s7, $s7, $v0        # fxp_mac32_Q32(tmp1, vec[12] << 1, window[15])
   sw    $s7, 15*4($a0)       # vec[16]
   lw    $v0, 13*4($a1)       # tmp = history[13]
   add   $t7, $t7, $v1        # fxp_mac32_Q32(tmp2, vec[13] << 1, window[14])
   extr.w  $s6, $ac2, 31      # fxp_mul32( vec[12] << 1, window[13])
   sw    $t7, 14*4($a0)       # vec[14]
   lw    $v1, 12*4($a1)       # tmp1 = history[12]
   mult  $ac3, $s5, $s4       # fxp_mul32( vec[11], window[12])
   lw    $a3, 11*4($a2)       # window[11]
   add   $s6, $s6, $v0        # fxp_mac32_Q32(tmp,  vec[12] << 1, window[13])
   sw    $s6, 13*4($a0)       # vec[13]
   extr.w  $t6, $ac3, 31      # fxp_mul32( vec[11] << 1, window[12])
   lw    $v0, 11*4($a1)       # tmp2 = history[11]
   mult  $ac0, $t5, $a3       # fxp_mul32( vec[10], window[11])
   lw    $a3, 10*4($a2)       # window[10]
   #############################################################################
   #  /* next iteration overlap */
   #############################################################################
   lw    $t0, 0($a1)          # history[0]
   mult  $ac1, $ra, $a3       # fxp_mul32(tmp4, window[10]);
   extr.w  $s5, $ac0, 31      # fxp_mul32( vec[10]<<1, window[11])
   add   $t6, $t6, $v1        # fxp_mac32_Q32(tmp1, vec[11] << 1, window[12])
   sw    $t6, 12*4($a0)       # vec[12]
   lw    $v1, 10*4($a1)       # tmp3 = history[10]
   extr.w  $t5, $ac1, 31      # fxp_mul32(tmp4 << 1, window[10]);
   lw    $t1, 4($a1)          # history[1]
   lw    $a3, 34*4($a2)       # window[34]
   add   $s5, $s5, $v0        # fxp_mac32_Q32(tmp2, vec[10] << 1, window[11])
   sw    $s5, 11*4($a0)       # vec[11]
   lw    $t8, 2*4($a1)        # history[2]
   lw    $t4, 4*4($a1)        # history[4]
   add   $t5, $t5, $v1        # fxp_mac32_Q32(tmp3, tmp4 << 1, window[10]);
   sw    $t5, 10*4($a0)       # vec[10]
   lw    $t6, 6*4($a1)        # history[ 6]
   lw    $v0, 18*4($a2)       # window[18]
   lw    $v1, 19*4($a2)       # window[19]
   lw    $t9, 35*4($a2)       # window[35]
   mult  $ac2, $t2, $v0       # fxp_mul32(history[ 8], window[18])
   mult  $ac3, $t2, $t9       # fxp_mul32(history[ 8], window[35])
   mult  $ac0, $s2, $v1       # fxp_mul32(history[ 7], window[19])
   mult  $ac1, $s2, $a3       # fxp_mul32(history[ 7], window[34])
   lw    $v0, 25*4($a2)       # window[25]
   extr.w  $t3, $ac2, 31      # fxp_mul32_Q32(history[ 8]<<1, window[18])
   extr.w  $t5, $ac3, 31      # fxp_mul32_Q32(history[ 8]<<1, window[35])
   extr.w  $s2, $ac0, 31      # fxp_mul32_Q32(history[ 7]<<1, window[19])
   extr.w  $s3, $ac1, 31      # fxp_mul32_Q32(history[ 7]<<1, window[34])
   lw    $v1, 28*4($a2)       # window[28]
   lw    $a3, 26*4($a2)       # window[26]
   lw    $t9, 27*4($a2)       # window[27]
   sw    $t3,0($a1)           # history[ 0]
   sw    $t5, 17*4($a1)       # history[17]
   sw    $s2, 4($a1)          # history[ 1]
   sw    $s3, 16*4($a1)       # history[16]
   mult  $ac2, $t1, $v0       # fxp_mul32(history[ 1], window[25])
   mult  $ac3, $t1, $v1       # fxp_mul32(history[ 1], window[28])
   mult  $ac0, $t0, $a3       # fxp_mul32(history[ 0], window[26])
   mult  $ac1, $t0, $t9       # fxp_mul32(history[ 0], window[27])
   lw    $v0, 20*4($a2)       # window[20]
   extr.w  $t3, $ac2, 31      # fxp_mul32_Q32(history[ 1]<<1, window[25])
   extr.w  $t5, $ac3, 31      # fxp_mul32_Q32(history[ 1]<<1, window[28])
   extr.w  $s2, $ac0, 31      # fxp_mul32_Q32(history[ 0]<<1, window[26])
   extr.w  $s3, $ac1, 31      # fxp_mul32_Q32(history[ 0]<<1, window[27])
   lw    $v1, 33*4($a2)       # window[33]
   lw    $a3, 21*4($a2)       # window[21]
   lw    $t9, 32*4($a2)       # window[32]
   sw    $t3, 7*4($a1)        # history[ 7]
   sw    $t5, 10*4($a1)       # history[10]
   sw    $s2, 8*4($a1)        # history[ 8]
   sw    $s3, 9*4($a1)        # history[ 9]
   mult  $ac2, $t6, $v0       # fxp_mul32(history[ 6], window[20])
   mult  $ac3, $t6, $v1       # fxp_mul32(history[ 6], window[33])
   mult  $ac0, $s1, $a3       # fxp_mul32(history[ 5], window[21])
   mult  $ac1, $s1, $t9       # fxp_mul32(history[ 5], window[32])
   lw    $v0, 22*4($a2)       # window[22]
   extr.w  $t3, $ac2, 31      # fxp_mul32_Q32(history[ 6]<<1, window[20])
   extr.w  $t5, $ac3, 31      # fxp_mul32_Q32(history[ 6]<<1, window[33])
   extr.w  $s2, $ac0, 31      # fxp_mul32_Q32(history[ 5]<<1, window[21])
   extr.w  $s3, $ac1, 31      # fxp_mul32_Q32(history[ 5]<<1, window[32])
   lw    $v1, 31*4($a2)       # window[31]
   lw    $a3, 23*4($a2)       # window[23]
   mult  $ac2, $t4, $v0       # fxp_mul32(history[ 4], window[22])
   mult  $ac3, $t4, $v1       # fxp_mul32(history[ 4], window[31])
   mult  $ac0, $s0, $a3       # fxp_mul32(history[ 3], window[23])
   sw    $t3, 2*4($a1)        # history[ 2]
   sw    $t5, 15*4($a1)       # history[15]
   sw    $s2, 3*4($a1)        # history[ 3]
   sw    $s3, 14*4($a1)       # history[14]
   extr.w  $t3, $ac2, 31      # fxp_mul32_Q32(history[ 4]<<1, window[22])
   extr.w  $t5, $ac3, 31      # fxp_mul32_Q32(history[ 4]<<1, window[31])
   extr.w  $s2, $ac0, 31      # fxp_mul32_Q32(history[ 3]<<1, window[23])
   lw    $v0, 30*4($a2)       # window[30]
   lw    $v1, 24*4($a2)       # window[24]
   lw    $a3, 29*4($a2)       # window[29]
   mult  $ac1, $s0, $v0       # fxp_mul32(history[3], window[30])
   mult  $ac2, $t8, $v1       # fxp_mul32(history[2], window[24])
   mult  $ac3, $t8, $a3       # fxp_mul32(history[2], window[29])
   sw    $t3, 4*4($a1)        # history[ 4]
   sw    $t5, 13*4($a1)       # history[13]
   sw    $s2, 5*4($a1)        # history[ 5]
   extr.w  $s6, $ac1, 31      # fxp_mul32_Q32(history[3]<<1, window[30])
   extr.w  $s7, $ac2, 31      # fxp_mul32_Q32(history[2]<<1, window[24])
   extr.w  $s8, $ac3, 31      # fxp_mul32_Q32(history[2]<<1, window[29])
   lw    $s0, 0($sp)
   lw    $s1, 4($sp)
   lw    $s2, 8($sp)
   lw    $s3, 12($sp)
   sw    $s6, 12*4($a1)       # history[12]
   sw    $s7, 6*4($a1)        # history[ 6]
   sw    $s8, 11*4($a1)       # history[11]
   lw    $s4, 16($sp)
   lw    $s5, 20($sp)
   lw    $s6, 24($sp)
   lw    $s7, 28($sp)
   lw    $s8, 32($sp)
   lw    $ra, 36($sp)
   addiu $sp, 40
   jr    $ra
   .end  pvmp3_mdct_18

   .data
cosTerms_1_ov_cos_phi:
   .word 0x400f9c00           # Qfmt1(0.50047634258166f)
   .word 0x408d6080           # Qfmt1(0.50431448029008f)
   .word 0x418dcb80           # Qfmt1(0.51213975715725f)
   .word 0x431b1a00           # Qfmt1(0.52426456257041f)
   .word 0x4545ea00           # Qfmt1(0.54119610014620f)
   .word 0x48270680           # Qfmt1(0.56369097343317f)
   .word 0x4be25480           # Qfmt1(0.59284452371708f)
   .word 0x50ab9480           # Qfmt1(0.63023620700513f)
   .word 0x56ce4d80           # Qfmt1(0.67817085245463f)
   .word 0x05ebb630           # Qfmt2(0.74009361646113f)
   .word 0x06921a98           # Qfmt2(0.82133981585229f)
   .word 0x0771d3a8           # Qfmt2(0.93057949835179f)
   .word 0x08a9a830           # Qfmt2(1.08284028510010f)
   .word 0x0a73d750           # Qfmt2(1.30656296487638f)
   .word 0x0d4d5260           # Qfmt2(1.66275476171152f)
   .word 0x127b1ca0           # Qfmt2(2.31011315767265f)
   .word 0x1ea52b40           # Qfmt2(3.83064878777019f)
   .word 0x5bb3cc80           # Qfmt2(11.46279281302667f)

cosTerms_dct18:
   .word 0x0807d2b0           # Qfmt(0.50190991877167f)
   .word 0x08483ee0           # Qfmt(0.51763809020504f)
   .word 0x08d3b7d0           # Qfmt(0.55168895948125f)
   .word 0x09c42570           # Qfmt(0.61038729438073f)
   .word 0x0b504f30           # Qfmt(0.70710678118655f)
   .word 0x0df29440           # Qfmt(0.87172339781055f)
   .word 0x12edfb20           # Qfmt(1.18310079157625f)
   .word 0x1ee8dd40           # Qfmt(1.93185165257814f)
   .word 0x5bca2a00           # Qfmt(5.73685662283493f)