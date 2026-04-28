.text
main:
    addiu $sp, $sp, -16     # reserve stack space

    addiu $t8, $zero, 5
    sw    $t8, 0($sp)       # mem[sp+0] = 5

    addiu $t9, $zero, 7
    sw    $t9, 4($sp)       # mem[sp+4] = 7

    lw    $t0, 0($sp)       # 5
    add   $t1, $t0, $t0     # 10, load-use stall
    addi  $t2, $t1, 3       # 13, forwarding
    lw    $t3, 4($sp)       # 7
    add   $t4, $t2, $t3     # 20, load-use stall
    sw    $t4, 8($sp)       # store 20

    sub   $t5, $t4, $t0     # 15
    and   $t6, $t5, $t1     # 10
    or    $t7, $t6, $t0     # 15
    nor   $s0, $t7, $zero   # bitwise not of 15
    slt   $s1, $t0, $t3     # 1
    sll   $s2, $s1, 2       # 4
    srl   $s3, $s2, 1       # 2
    sw    $s3, 12($sp)      # store 2

    beq   $s1, $zero, skip  # not taken
    addiu $s4, $zero, 42    # executes

    beq   $s1, $s1, taken   # always taken
    addiu $s5, $zero, 99    # should be flushed

skip:
    addiu $s6, $zero, -1

taken:
    j     done
    addiu $s7, $zero, 123   # should be flushed

done:
    nop
