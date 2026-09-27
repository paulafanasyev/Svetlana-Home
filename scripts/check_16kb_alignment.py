#!/usr/bin/env python3
"""Проверка 16 KB-совместимости нативных библиотек Android.

Аудит выявил, что прежняя inline-проверка в CI читала e_phoff (смещение
таблицы program headers) как будто это p_align, и поэтому сообщала
"(unknown)" для всех библиотек.

Настоящая проверка: ELF-заголовок → таблица program headers → для каждого
PT_LOAD-сегмента берём p_align. Условие 16 KB-совместимости — все PT_LOAD
сегменты выровнены не менее чем на 0x4000 (16384).

Источники:
  https://developer.android.com/guide/practices/page-sizes

Выход: код 0 — все библиотеки 16 KB-совместимы, 1 — есть 4 KB-RISK.
"""
import glob
import os
import struct
import sys
from typing import List

PT_LOAD = 1
MIN_16KB = 0x4000


def load_alignments(path: str) -> List[int]:
    """Возвращает p_align всех PT_LOAD-сегментов ELF-файла."""
    with open(path, "rb") as f:
        head = f.read(64)
        if head[:4] != b"\x7fELF":
            return []
        ei_class = head[4]  # 1 = ELF32, 2 = ELF64
        ei_data = head[5]   # 1 = little, 2 = big
        endian = "<" if ei_data == 1 else ">"

        if ei_class == 2:  # Elf64_Ehdr
            e_phoff = struct.unpack_from(endian + "Q", head, 0x20)[0]
            e_phentsize = struct.unpack_from(endian + "H", head, 0x36)[0]
            e_phnum = struct.unpack_from(endian + "H", head, 0x38)[0]
            align_off = 0x30  # p_align внутри Elf64_Phdr
            align_fmt = endian + "Q"
        elif ei_class == 1:  # Elf32_Ehdr
            e_phoff = struct.unpack_from(endian + "I", head, 0x1C)[0]
            e_phentsize = struct.unpack_from(endian + "H", head, 0x2A)[0]
            e_phnum = struct.unpack_from(endian + "H", head, 0x2C)[0]
            align_off = 0x1C  # p_align внутри Elf32_Phdr
            align_fmt = endian + "I"
        else:
            return []

        f.seek(e_phoff)
        phdrs = f.read(e_phentsize * e_phnum)

    aligns = []
    for i in range(e_phnum):
        p = phdrs[i * e_phentsize:(i + 1) * e_phentsize]
        if len(p) < e_phentsize:
            break
        p_type = struct.unpack_from(endian + "I", p, 0)[0]
        if p_type == PT_LOAD:
            aligns.append(struct.unpack_from(align_fmt, p, align_off)[0])
    return aligns


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <native-lib-dir|apk>", file=sys.stderr)
        return 2

    target = sys.argv[1]
    sos = sorted(glob.glob(os.path.join(target, "**", "*.so"), recursive=True))
    if not sos:
        sos = sorted(glob.glob(target + "*.so"))
    if not sos:
        print(f"NO_LIBS_FOUND in {target}", file=sys.stderr)
        return 2

    print("=== ELF LOAD alignment нативных библиотек ===")
    misaligned = []
    for so in sos:
        name = os.path.basename(so)
        aligns = load_alignments(so)
        if not aligns:
            print(f"  SKIP      {name} (no PT_LOAD / not ELF)")
            continue
        # Лимитирующий фактор — МИНИМАЛЬНОЕ выравнивание среди сегментов.
        worst = min(aligns)
        status = "16KB-OK" if worst >= MIN_16KB else "4KB-RISK"
        print(f"  {status:9s} {name:40s} p_align=0x{worst:x}")
        if worst < MIN_16KB:
            misaligned.append((name, worst))

    print()
    print(f"MISALIGNED_LIBS={len(misaligned)}")
    if misaligned:
        print("4KB-RISK библиотеки (несовместимы с 16 KB page-size):")
        for name, align in misaligned:
            print(f"  - {name} (p_align=0x{align:x})")
        return 1
    print("Все нативные библиотеки 16 KB-совместимы.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
