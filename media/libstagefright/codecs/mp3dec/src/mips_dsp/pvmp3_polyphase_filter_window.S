#/***************************************************************************
#*
#*  File: pvmp3_polyphase_filter_window.S
#*
#***************************************************************************/

#/***************************************************************************
#*
#*  Function: pvmp3_polyphase_filter_window
#*
#*  Description:
#*           apply polyphase filter window
#*           Input 32 subband samples
#*           Calculate 64 values
#*
#*  Parameters:
#*
#*       a0: synthesis input buffer
#*       a1: generated output ( 32 values)
#*       a2: number of channels
#*
#*
#*  Reference: see pvmp3_polyphase_filter_window.cpp
#*
#*
#*  Notes:
#*
#***************************************************************************/
   .text
   .align   2
   .globl  pvmp3_polyphase_filter_window
   .set  nomips16
   .set  nomicromips
   .ent  pvmp3_polyphase_filter_window
   .type pvmp3_polyphase_filter_window, @function
   #### int pvmp3_polyphase_filter_window( int32 *synth_buffer, int16 *outPcm,
   ####                                    int32 numChannels)
   # a0: int32 *synth_buffer
   # a1: int16 *outPcm
   # a2: int32 numChannels
pvmp3_polyphase_filter_window:
   .frame $sp, 16, $ra
   .set  noreorder
   .cpload $t9
   .set  reorder
   addiu $sp, -16
   sw    $s0, 0($sp)
   sw    $s1, 4($sp)
   la    $s0, pqmfSynthWin    # s0: const int32 *winPtr = pqmfSynthWin
   addiu $t7, $a0, 15*4       # t7: int32 *pt_2 = &synth_buffer[ i-j];
   addiu $t9, $a0, 17*4       # t9: int32 *pt_1 = &synth_buffer[ i+j];
   li    $t8, 15              # t8: j counter
   li    $v1, 0xFFFFFFFF
   li    $t6, 1
   sllv  $v0, $t6, $a2        # v0: (1 << numChannels)
                              #     <=> ((1 << (numChannels - 1)) * 2)
   move  $s1, $a1             # s1 -> outPcm[0]
   sll   $t6, $a2, 6          # t6: (numChannels << 5)*2
   addu  $t6, $a1, $t6        # t6 -> outPcm[ numChannels << 5 ]

   # pipeline warmup
   lw    $t1, 0($t9)          # t1: int32 temp1 = pt_1[ 0];
   lw    $t3, 32*15*4($t7)    # t3: int32 temp3 = pt_2[ SUBBANDS_NUMBER*15 ];
   ############################################################################
   #  for (int16 j = 1; j < SUBBANDS_NUMBER / 2; j++)
   ############################################################################
pvmp3_polyphase_filter_window_sb_loop:
   # inner loop has only one lap
   lw    $t0, 0($s0)          # t0: winPtr[ 0]
   lw    $t5, 4($s0)          # t5: winPtr[ 1]
   lw    $t2, 32*4($t7)       # t2: int32 temp2 = pt_2[ SUBBANDS_NUMBER* 1 ];
   mult  $t1, $t0             # sum1 = MULT32 (temp1, winPtr[ 0]);
   mult  $ac1, $t3, $t0       # sum2 = MULT32 (temp3, winPtr[ 0]);
   mtlo  $zero, $ac1
   mtlo  $v1
   madd  $ac1, $t1, $t5       # sum2 = fxp_mac32_Q32(sum2, temp1, winPtr[ 1]);
   msub  $t3, $t5             # sum1 = fxp_msb32_Q32(sum1, temp3, winPtr[ 1]);
   lw    $t0, 2*4($s0)        # t0: winPtr[ 2]
   lw    $t4, 32*14*4($t9)    # t4: int32 temp4 = pt_1[ SUBBANDS_NUMBER*14 ];
   mtlo  $zero
   mtlo  $v1, $ac1
   madd  $t2, $t0             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[ 2]);
   msub  $ac1, $t4, $t0       # sum2 = fxp_msb32_Q32(sum2, temp4, winPtr[ 2]);
   lw    $t5, 3*4($s0)        # t5: winPtr[ 3]
   mtlo  $zero, $ac1
   mtlo  $zero
   madd  $ac1, $t2, $t5       # sum2 = fxp_mac32_Q32(sum2, temp2, winPtr[ 3]);
   madd  $t4, $t5             # sum1 = fxp_mac32_Q32(sum1, temp4, winPtr[ 3]);

   lw    $t0, 4*4($s0)        # t0: winPtr[ 4]
   lw    $t1, 32*2*4($t9)     # t1: int32 temp1 = pt_1[ SUBBANDS_NUMBER* 2];
   lw    $t3, 32*13*4($t7)    # t3: int32 temp3 = pt_2[ SUBBANDS_NUMBER*13 ];
   mtlo  $zero
   mtlo  $zero, $ac1
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[ 4]);
   madd  $ac1, $t3, $t0       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[ 4]);
   lw    $t5, 5*4($s0)        # t5: winPtr[ 5]
   mtlo  $zero, $ac1
   mtlo  $v1
   madd  $ac1, $t1, $t5       # sum2 = fxp_mac32_Q32(sum2, temp1, winPtr[ 5]);
   msub  $t3, $t5             # sum1 = fxp_msb32_Q32(sum1, temp3, winPtr[ 5]);
   lw    $t0, 6*4($s0)        # t0: winPtr[ 6]
   lw    $t2, 32*3*4($t7)     # t2: int32 temp2 = pt_2[ SUBBANDS_NUMBER* 3 ];
   lw    $t4, 32*12*4($t9)    # t4: int32 temp4 = pt_1[ SUBBANDS_NUMBER*12 ];
   mtlo  $zero
   mtlo  $v1, $ac1
   madd  $t2, $t0             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[ 6]);
   msub  $ac1, $t4, $t0       # sum2 = fxp_msb32_Q32(sum2, temp4, winPtr[ 6]);
   lw    $t5, 7*4($s0)        # t5: winPtr[ 7]
   mtlo  $zero, $ac1
   mtlo  $zero
   madd  $ac1, $t2, $t5       # sum2 = fxp_mac32_Q32(sum2, temp2, winPtr[ 7]);
   madd  $t4, $t5             # sum1 = fxp_mac32_Q32(sum1, temp4, winPtr[ 7]);

   lw    $t0, 8*4($s0)        # t0: winPtr[ 8]
   lw    $t1, 32*4*4($t9)     # t1: int32 temp1 = pt_1[ SUBBANDS_NUMBER* 4];
   lw    $t3, 32*11*4($t7)    # t3: int32 temp3 = pt_2[ SUBBANDS_NUMBER*11 ];
   mtlo  $zero
   mtlo  $zero, $ac1
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[ 8]);
   madd  $ac1, $t3, $t0       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[ 8]);
   lw    $t5, 9*4($s0)        # t5: winPtr[ 9]
   mtlo  $zero, $ac1
   mtlo  $v1
   madd  $ac1, $t1, $t5       # sum2 = fxp_mac32_Q32(sum2, temp1, winPtr[ 9]);
   msub  $t3, $t5             # sum1 = fxp_msb32_Q32(sum1, temp3, winPtr[ 9]);
   lw    $t0, 10*4($s0)       # t0: winPtr[ 10]
   lw    $t2, 32*5*4($t7)     # t2: int32 temp2 = pt_2[ SUBBANDS_NUMBER* 5 ];
   lw    $t4, 32*10*4($t9)    # t4: int32 temp4 = pt_1[ SUBBANDS_NUMBER*10 ];
   mtlo  $zero
   mtlo  $v1, $ac1
   madd  $t2, $t0             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[10]);
   msub  $ac1, $t4, $t0       # sum2 = fxp_msb32_Q32(sum2, temp4, winPtr[10]);
   lw    $t5, 11*4($s0)       # t5: winPtr[ 11]
   mtlo  $zero, $ac1
   mtlo  $zero
   madd  $ac1, $t2, $t5       # sum2 = fxp_mac32_Q32(sum2, temp2, winPtr[11]);
   madd  $t4, $t5             # sum1 = fxp_mac32_Q32(sum1, temp4, winPtr[11]);

   lw    $t0, 12*4($s0)       # t0: winPtr[ 12]
   lw    $t1, 32*6*4($t9)     # t1: int32 temp1 = pt_1[ SUBBANDS_NUMBER* 6];
   lw    $t3, 32*9*4($t7)     # t3: int32 temp3 = pt_2[ SUBBANDS_NUMBER* 9];
   mtlo  $zero
   mtlo  $zero, $ac1
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[12]);
   madd  $ac1, $t3, $t0       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[12]);
   lw    $t5, 13*4($s0)       # t5: winPtr[ 13]
   mtlo  $zero, $ac1
   mtlo  $v1
   madd  $ac1, $t1, $t5       # sum2 = fxp_mac32_Q32(sum2, temp1, winPtr[13]);
   msub  $t3, $t5             # sum1 = fxp_msb32_Q32(sum1, temp3, winPtr[13]);
   lw    $t0, 14*4($s0)       # t0: winPtr[14]
   lw    $t2, 32*7*4($t7)     # t2: int32 temp2 = pt_2[ SUBBANDS_NUMBER* 7];
   lw    $t4, 32*8*4($t9)     # t4: int32 temp4 = pt_1[ SUBBANDS_NUMBER* 8];
   mtlo  $zero
   lw    $t5, 15*4($s0)       # t5: winPtr[15]
   madd  $t2, $t0             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[14]);
   mtlo  $zero
   madd  $t4, $t5             # sum1 = fxp_mac32_Q32(sum1, temp4, winPtr[15]);
   mtlo  $v1, $ac1
   msub  $ac1, $t4, $t0       # sum2 = fxp_msb32_Q32(sum2, temp4, winPtr[14]);
   mtlo  $zero, $ac1
   madd  $ac1, $t2, $t5       # sum2 = fxp_mac32_Q32(sum2, temp2, winPtr[15]);
   mfhi  $t4                  # t4: sum1
   addiu $t8, -1
   addiu $s0, 16*4            # winPtr += 16;
   addu  $s1, $v0             # s1 -> outPcm[j << (numChannels - 1)]
   mfhi  $t2, $ac1            # t2: sum2
   subu  $t6, $v0             # t6 -> outPcm[(numChannels<<5) - k]
   addiu $t9, 4               # pt_1++
   shra_r.w $t4, $t4, 6       # t4: (sum1 + 0x00000020) >> 6
   addiu $t7, -4              # pt_2--
   shll_s.w $t4, $t4, 16      # t4: SAT32((sum1 + 0x00000020) >> 6)
   lw    $t1, 0($t9)          # t1: int32 temp1 = pt_1[ 0];
   shra_r.w $t2, $t2, 6       # t2: (sum2 + 0x00000020) >> 6
   sra   $t4, 16              # t4: saturate16(sum1 >> 6)
   shll_s.w $t2, $t2, 16      # t4: SAT32((sum2 + 0x00000020) >> 6)
   sh    $t4, 0($s1)          # outPcm[k] = saturate16(sum1 >> 6);
   lw    $t3, 32*15*4($t7)    # t3: int32 temp3 = pt_2[ SUBBANDS_NUMBER*15 ];
   sra   $t2, 16              # t4: saturate16(sum2 >> 6)
   .set  noreorder
   .set  nomacro
   bnez  $t8, pvmp3_polyphase_filter_window_sb_loop
    sh   $t2, 0($t6)          # outPcm[(numChannels<<5) - k] =
                              #         saturate16(sum2 >> 6);
   .set  macro
   .set  reorder
   ############################################################################
   #  for (i = 16; i < HAN_SIZE + 16; i += (SUBBANDS_NUMBER << 2))
   ############################################################################
   # int32 *pt_synth = &synth_buffer[i];
   # in each lap pt_synth is calculated again offsetting synth_buffer by i
   # Since loop is unrolled, address of array value is calculated by adding
   # value of i for respectable lap and calculated index of pt_synth array.
   # Multiplication by 4 is done because data is 32-bit
   # i = 16
   lw    $t0, 0($s0)          # t0: winPtr[0]
   lw    $t1, 16*4($a0)       # t1: int32 temp1 = pt_synth[ 0                ];
   lw    $t3, 16*4+16*4($a0)  # t3: int32 temp3 = pt_synth[ SUBBANDS_NUMBER/2];
   lw    $t5, 2*4($s0)        # t5: winPtr[2]
   mult  $t1, $t0             # sum1 = MULT32 (temp1, winPtr[0]) ;
   lw    $t2, 32*4+16*4($a0)  # t2: int32 temp2 = pt_synth[ SUBBANDS_NUMBER  ];
   lw    $t4, 4($s0)          # t4: winPtr[1]
   mtlo  $zero
   mult  $ac1, $t3, $t5       # sum2 = MULT32 (temp3, winPtr[2]) ;
   madd  $t2, $t4             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[1]);
   mtlo  $zero, $ac1
   mtlo  $zero
   lw    $t0, 3*4($s0)        # t0: winPtr[3]
   lw    $t1, 64*4+16*4($a0)  # t1: temp1 = pt_synth[ SUBBANDS_NUMBER<<1 ];
   lw    $t3, 80*4+16*4($a0)  # t3: temp3 = pt_synth[ SUBBANDS_NUMBER*5/2];
   lw    $t5, 5*4($s0)        # t5: winPtr[5]
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[3]);
   lw    $t2, 96*4+16*4($a0)  # t2: temp2 = pt_synth[ 3*SUBBANDS_NUMBER  ];
   lw    $t4, 4*4($s0)        # t4: winPtr[4]
   mtlo  $zero
   madd  $ac1, $t3, $t5       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[5]);
   madd  $t2, $t4             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[4]);
   mtlo  $zero
   mtlo  $zero, $ac1
   # i = 144
   lw    $t0, 6*4($s0)        # t0: winPtr[6]
   lw    $t1, 144*4($a0)      # t1: int32 temp1 = pt_synth[
                              #                    (SUBBANDS_NUMBER << 2)];
   lw    $t3, 16*4+144*4($a0) # t3: int32 temp3 = pt_synth[ SUBBANDS_NUMBER/2
                              #              + (SUBBANDS_NUMBER << 2)];
   lw    $t5, 8*4($s0)        # t5: winPtr[8]
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[6]);
   lw    $t2, 32*4+144*4($a0) # t2: int32 temp2 = pt_synth[ SUBBANDS_NUMBER
                              #              + (SUBBANDS_NUMBER << 2)];
   lw    $t4, 7*4($s0)        # t4: winPtr[7]
   mtlo  $zero
   madd  $ac1, $t3, $t5       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[8]);
   madd  $t2, $t4             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[7]);
   mtlo  $zero, $ac1
   mtlo  $zero
   lw    $t0, 9*4($s0)        # t0: winPtr[9]
   lw    $t1, 64*4+144*4($a0) # t1: temp1 = pt_synth[ SUBBANDS_NUMBER<<1
                              #              + (SUBBANDS_NUMBER << 2)];
   lw    $t3, 80*4+144*4($a0) # t3: temp3 = pt_synth[ SUBBANDS_NUMBER*5/2
                              #              + (SUBBANDS_NUMBER << 2)];
   lw    $t5, 11*4($s0)       # t5: winPtr[11]
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[9]);
   lw    $t2, 96*4+144*4($a0) # t2: temp2 = pt_synth[ 3*SUBBANDS_NUMBER
                              #              + (SUBBANDS_NUMBER << 2)];
   lw    $t4, 10*4($s0)       # t4: winPtr[10]
   mtlo  $zero
   madd  $ac1, $t3, $t5       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[11]);
   madd  $t2, $t4             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[10]);
   mtlo  $zero
   mtlo  $zero, $ac1
   # i = 272
   lw    $t0, 12*4($s0)       # t0: winPtr[12]
   lw    $t1, 272*4($a0)      # t1: int32 temp1 = pt_synth[2 *
                              #                    (SUBBANDS_NUMBER << 2)];
   lw    $t3, 16*4+272*4($a0) # t3: int32 temp3 = pt_synth[ SUBBANDS_NUMBER/2
                              #              + 2 * (SUBBANDS_NUMBER << 2)];
   lw    $t5, 14*4($s0)       # t5: winPtr[14]
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[12]);
   lw    $t2, 32*4+272*4($a0) # t2: int32 temp2 = pt_synth[ SUBBANDS_NUMBER
                              #              + 2 * (SUBBANDS_NUMBER << 2)];
   lw    $t4, 13*4($s0)       # t4: winPtr[13]
   mtlo  $zero
   madd  $ac1, $t3, $t5       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[14]);
   madd  $t2, $t4             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[13]);
   mtlo  $zero, $ac1
   mtlo  $zero
   lw    $t0, 15*4($s0)       # t0: winPtr[15]
   lw    $t1, 64*4+272*4($a0) # t1: temp1 = pt_synth[ SUBBANDS_NUMBER<<1
                              #              + 2 * (SUBBANDS_NUMBER << 2)];
   lw    $t3, 80*4+272*4($a0) # t3: temp3 = pt_synth[ SUBBANDS_NUMBER*5/2
                              #              + 2 * (SUBBANDS_NUMBER << 2)];
   lw    $t5, 17*4($s0)       # t5: winPtr[17]
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[15]);
   lw    $t2, 96*4+272*4($a0) # t2: temp2 = pt_synth[ 3*SUBBANDS_NUMBER
                              #              + 2 * (SUBBANDS_NUMBER << 2)];
   lw    $t4, 16*4($s0)       # t4: winPtr[16]
   mtlo  $zero
   madd  $ac1, $t3, $t5       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[17]);
   madd  $t2, $t4             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[16]);
   mtlo  $zero
   mtlo  $zero, $ac1
   # i = 400
   lw    $t0, 18*4($s0)       # t0: winPtr[18]
   lw    $t1, 400*4($a0)      # t1: temp1 = pt_synth[3 * (SUBBANDS_NUMBER << 2)];
   lw    $t3, 16*4+400*4($a0) # t3: temp3 = pt_synth[ SUBBANDS_NUMBER/2
                              #              + 3 * (SUBBANDS_NUMBER << 2)];
   lw    $t5, 20*4($s0)       # t5: winPtr[20]
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[18]) ;
   lw    $t2, 32*4+400*4($a0) # t2: temp2 = pt_synth[ SUBBANDS_NUMBER
                              #              + 3 * (SUBBANDS_NUMBER << 2)];
   lw    $t4, 19*4($s0)       # t4: winPtr[19]
   mtlo  $zero
   madd  $ac1, $t3, $t5       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[20]);
   madd  $t2, $t4             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[19]);
   mtlo  $zero, $ac1
   mtlo  $zero
   lw    $t3, 80*4+400*4($a0) # t3: temp3 = pt_synth[ SUBBANDS_NUMBER*5/2
                              #              + 3 * (SUBBANDS_NUMBER << 2)];
   lw    $t5, 23*4($s0)       # t5: winPtr[23]
   lw    $t0, 21*4($s0)       # t0: winPtr[21]
   lw    $t1, 64*4+400*4($a0) # t1: temp1 = pt_synth[ SUBBANDS_NUMBER<<1
                              #              + 3 * (SUBBANDS_NUMBER << 2)];
   madd  $ac1, $t3, $t5       # sum2 = fxp_mac32_Q32(sum2, temp3, winPtr[23]);
   lw    $t2, 96*4+400*4($a0) # t2: temp2 = pt_synth[ 3*SUBBANDS_NUMBER
                              #              + 3 * (SUBBANDS_NUMBER << 2)];
   lw    $t4, 22*4($s0)       # t4: winPtr[22]
   madd  $t1, $t0             # sum1 = fxp_mac32_Q32(sum1, temp1, winPtr[21]);
   mtlo  $zero
   madd  $t2, $t4             # sum1 = fxp_mac32_Q32(sum1, temp2, winPtr[22]);
   mfhi  $t0, $ac1            # sum2
   mfhi  $t1                  # sum1
   addu  $s1, $v0             # s1 -> outPcm[(SUBBANDS_NUMBER/2)
                              #                << (numChannels - 1)]
   shra_r.w $t0, $t0, 6       # t0: (sum2 + 0x00000020) >> 6
   shra_r.w $t1, $t1, 6       # t1: (sum1 + 0x00000020) >> 6
   shll_s.w $t0, $t0, 16      # t0: SAT32((sum2 + 0x00000020) >> 6)
   shll_s.w $t1, $t1, 16      # t1: SAT32((sum1 + 0x00000020) >> 6)
   sra   $t0, 16              # t0: saturate16(sum2 >> 6)
   sra   $t1, 16              # t1: saturate16(sum1 >> 6)
   sh    $t0, 0($s1)          # outPcm[(SUBBANDS_NUMBER/2)<<(numChannels-1)] =
                              #          saturate16(sum2 >> 6);
   sh    $t1, 0($a1)          # outPcm[0] = saturate16(sum1 >> 6);

   lw    $s0, 0($sp)
   lw    $s1, 4($sp)
   addiu $sp, 16
   jr    $ra
   .end pvmp3_polyphase_filter_window