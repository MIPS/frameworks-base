/* libs/opengles/arch-mips/fixed_asm.S
**
** Copyright 2006, The Android Open Source Project
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


    .text
    .align
    
    .global gglFloatToFixed
    .global gglFloatToFixedFast


/*
 * Converts a float to a s15.16 fixed-point number.
 * this doesn't handle floats out of the [-32768, +32768[ range
 * and doesn't performs round-to-nearest.
 * however, it's very fast :-)
 */

gglFloatToFixedFast:
		srl     $t0,$a0,31              /* t0 <- sign bit */
		srl     $t1,$a0,23
		andi    $t1,$t1,0xff            /*get the e*/
		li      $t2,0x8e
		subu    $t1,$t2,$t1             /*t1=127+15-e*/	
		sll     $t2,$a0,8               /* mantissa<<8 */
		lui	    $t3,0x8000
        or	    $t2,$t2,$t3             /* add the missing 1 */
		srl     $v0,$t2,$t1            /* scale to 16.16 */
		subu    $t2,$zero,$v0
		movn    $v0,$t2,$t0            /*if negate?*/
		or      $t1,$a0,$zero          /*a0=0?*/
		movz    $v0,$zero,$t1
		jr      $ra

/*
 * this version rounds-to-nearest and saturates numbers
 * outside the range (but not NaNs).
 */

gglFloatToFixed:
		srl     $t0,$a0,31              /* t0 <- sign bit */
		srl     $t1,$a0,23
		andi    $t1,$t1,0xff            /*get the e*/
		li      $t2,0x8e
		subu    $t1,$t2,$t1             /*t1=127+15-e*/	
		blez    $t1,0f                  /*t1<=0?*/
		sll     $t2,$a0,8               /* mantissa<<8 */
		lui	    $t3,0x8000
        or	    $t2,$t2,$t3             /* add the missing 1 */
		subu    $t1,$t1,1
        srl     $v0,$t2,$t1  
        andi    $t4,$v0,0x1
        srl     $v0,$v0,1               /* scale to 16.16 */
        addu    $v0,$v0,$t4             /* round-to-nearest */
        subu    $t2,$zero,$v0
        movn    $v0,$t2,$t0             /*if negate?*/
        or      $t1,$a0,$zero          /*a0=0?*/
		movz    $v0,$zero,$t1
        jr      $ra
0:      
		lui	    $t1,0x8000
		and     $v0,$a0,$t1           /* keep only the sign bit */
		li      $t1,0x7fffffff      
		movz    $v0,$t1,$t0           /* positive, maximum value */
		jr      $ra
