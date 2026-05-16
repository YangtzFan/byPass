#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对 iEDA iPA 输出做后处理：

iEDA 当前版本（2026-05）在大型设计上做组合逻辑 internal-power 时存在 bug：
约 9% 的 cell 因为上游 slew 未传播（"rise slew is not exist"）会查到
未初始化的 NLPM 内部能量表项，给出 1e+150 数量级的 garbage power，
进而把 MyCPU.pwr 的 combinational total 推高到 3e+153 W。

本脚本读取 iPA 的 MyCPU_instance.csv（每个 cell 一行），
按以下规则区分：
  - GARBAGE  : Internal Power > GARBAGE_THRESH   （默认 1e-3 W = 1mW/cell，
               55nm 工艺单 cell 一般在 nW~µW 量级，>1mW 的全是查表 NaN）
  - VALID    : 其余视为有效

输出：
  <result_dir>/MyCPU.pwr.clean    -- 过滤后总览（可信下界）
  <result_dir>/MyCPU.pwr.broken   -- 列出所有 garbage cell（前 200）+ 总数

用法：
  python3 clean_power.py <result_dir>
"""
import sys, os, csv

GARBAGE_THRESH = 1e-3  # W / cell

def main():
    if len(sys.argv) != 2:
        print(__doc__); sys.exit(1)
    rd = sys.argv[1]
    csv_path = os.path.join(rd, "MyCPU_instance.csv")
    if not os.path.isfile(csv_path):
        print(f"[clean_power] {csv_path} not found, skip.")
        return

    n_total = n_valid = n_garbage = 0
    sum_int = sum_sw = sum_lk = 0.0
    sum_int_garbage_lk = 0.0  # garbage cell 的 leakage 仍可信，单独累加
    broken_samples = []

    with open(csv_path) as f:
        rdr = csv.reader(f)
        header = next(rdr)
        # header: Instance, Vnom, Internal, Switch, Leakage, Total
        for row in rdr:
            if len(row) < 6: continue
            try:
                ip = float(row[2]); sp = float(row[3]); lk = float(row[4])
            except ValueError:
                continue
            n_total += 1
            if abs(ip) > GARBAGE_THRESH or ip != ip:  # NaN / >1mW
                n_garbage += 1
                sum_int_garbage_lk += lk
                if len(broken_samples) < 200:
                    broken_samples.append((row[0], ip))
            else:
                n_valid += 1
                sum_int += ip
                sum_sw  += sp
                sum_lk  += lk

    sum_lk_all = sum_lk + sum_int_garbage_lk
    out_clean = os.path.join(rd, "MyCPU.pwr.clean")
    with open(out_clean, "w") as f:
        f.write("# Cleaned power report (iEDA garbage cells filtered)\n")
        f.write(f"# Source       : {csv_path}\n")
        f.write(f"# Threshold    : per-cell internal power > {GARBAGE_THRESH} W treated as garbage\n")
        f.write(f"# Total cells  : {n_total}\n")
        f.write(f"# Valid cells  : {n_valid}  ({100.0*n_valid/n_total:.2f} %)\n")
        f.write(f"# Garbage cells: {n_garbage}  ({100.0*n_garbage/n_total:.2f} %)\n")
        f.write("\n")
        f.write("Power summary (lower-bound, valid cells only for internal/switch)\n")
        f.write(f"  Internal Power (valid cells)  = {sum_int:.6e} W   ({sum_int*1000:.3f} mW)\n")
        f.write(f"  Switch   Power (valid cells)  = {sum_sw:.6e}  W   ({sum_sw*1000:.3f}  mW)\n")
        f.write(f"  Leakage  Power (all cells)    = {sum_lk_all:.6e}  W   ({sum_lk_all*1000:.3f}  mW)\n")
        total = sum_int + sum_sw + sum_lk_all
        f.write(f"  ------------------------------------------------------------\n")
        f.write(f"  TOTAL (lower bound, missing {n_garbage} cells internal) = {total:.6e} W ({total*1000:.3f} mW)\n")
        f.write("\n")
        f.write("Note: True total is higher than the lower bound, because the\n")
        f.write(f"      {n_garbage} 'garbage' cells (uIssRRDff buffer chains etc.)\n")
        f.write("      have unknown but bounded internal power. A reasonable\n")
        f.write("      estimate is to extrapolate from the median valid cell\n")
        f.write("      internal power and multiply by the broken-cell count.\n")

    out_broken = os.path.join(rd, "MyCPU.pwr.broken")
    with open(out_broken, "w") as f:
        f.write(f"# {n_garbage} cells with garbage internal-power (top 200 listed)\n")
        for name, ip in broken_samples:
            f.write(f"{ip:>12.3e}   {name}\n")

    print(f"[clean_power] wrote {out_clean}")
    print(f"[clean_power] wrote {out_broken}")
    print(f"[clean_power] valid={n_valid}  garbage={n_garbage}  "
          f"valid_total={(sum_int+sum_sw+sum_lk_all)*1000:.3f} mW (lower bound)")

if __name__ == "__main__":
    main()
