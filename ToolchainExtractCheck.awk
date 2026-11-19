function decide(srcLen, dEx, dLen,   x) {
  if (srcLen >= 0 && dEx) { if (dLen == srcLen) return 1; return 0 }
  if (srcLen < 0 && dEx && dLen > 0) return 1
  return 0
}
BEGIN {
  fail=0
  if (decide(-1,1,27768026) != 1) { print "FAIL: kompresi+ada isi harus skip"; fail=1 }
  if (decide(-1,1,0) != 0) { print "FAIL: dest 0B harus extract ulang"; fail=1 }
  if (decide(-1,0,0) != 0) { print "FAIL: belum ada harus extract"; fail=1 }
  if (decide(2521,1,2521) != 1) { print "FAIL: panjang sama harus skip"; fail=1 }
  if (decide(2521,1,100) != 0) { print "FAIL: terpotong harus extract"; fail=1 }
  if (fail) exit 1
  print "TOOLCHAIN-EXTRACT SELF-CHECK PASS"
}
