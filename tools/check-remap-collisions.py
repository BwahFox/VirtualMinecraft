#!/usr/bin/env python3
"""Check a remapped (1.20.1) release jar for interface methods that nothing implements any more.

The bug this catches (v1.0.1, 2026-08-30): one of our interfaces declared `BlockPos getBlockPos()` and the block
entities implementing it relied on `BlockEntity.getBlockPos()` to satisfy it. Loom remaps Minecraft's method to its
intermediary name (`method_11016`) in the shipped jar but leaves the interface's own `getBlockPos` alone, so in
production the class implements neither and the first call is an AbstractMethodError. It cannot show on 26.2
(unobfuscated) or in the 1.20.1 dev client (named mappings) — only in the built 1.20.1 jar.

    tools/check-remap-collisions.py mc1.20.1/build/libs/virtualminecraft-<ver>+mc1.20.1.jar

For every class in the jar, for every abstract method of every dev.virtualminecraft interface it implements, the
method must be declared by the class or by a dev.virtualminecraft superclass. Exit 1 with a list otherwise.
"""
import pathlib, re, subprocess, sys, tempfile, zipfile

JAVAP = "javap"
OURS = "dev.virtualminecraft"


def main(jar: str) -> int:
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(jar) as z:
            names = [n for n in z.namelist() if n.startswith("dev/") and n.endswith(".class")]
            z.extractall(tmp, names)
        classes = sorted(n[:-6] for n in names)
        info = {}
        for i in range(0, len(classes), 60):
            out = subprocess.run([JAVAP, "-p"] + [c + ".class" for c in classes[i:i + 60]],
                                 capture_output=True, text=True, cwd=tmp).stdout
            cur = None
            for line in out.splitlines():
                s = line.strip()
                if line and not line[0].isspace() and "{" in line and re.search(r"\b(class|interface|enum|record)\b", line):
                    name = re.search(r"(?:class|interface|enum|record) ([\w.$]+)", s).group(1)
                    ext = re.search(r"extends ([\w.$]+)", s)
                    imp = re.search(r"implements (.+?) \{", s)
                    cur = {"iface": bool(re.search(r"\binterface\b", s)),
                           "super": ext.group(1) if ext else None,
                           "ifaces": [x.strip().split("<")[0] for x in imp.group(1).split(",")] if imp else [],
                           "methods": set(), "abstract": set()}
                    info[name] = cur
                elif cur is not None and "(" in s and s.endswith(";"):
                    m = re.search(r"([\w$]+)\(", s)
                    if m and m.group(1) not in ("<init>", "<clinit>"):
                        cur["methods"].add(m.group(1))
                        if cur["iface"] and "abstract" in s:
                            cur["abstract"].add(m.group(1))

    def declares(cls, m):
        while cls and cls.startswith(OURS) and cls in info:
            if m in info[cls]["methods"]:
                return True
            cls = info[cls]["super"]
        return False

    def all_ifaces(cls):
        seen, stack = set(), [cls]
        while stack:
            c = stack.pop()
            if c not in info:
                continue
            for i in info[c]["ifaces"]:
                if i not in seen:
                    seen.add(i)
                    stack.append(i)
            if info[c]["super"]:
                stack.append(info[c]["super"])
        return seen

    bad = set()
    for cls, ci in info.items():
        if ci["iface"]:
            continue
        for i in all_ifaces(cls):
            if i.startswith(OURS) and i in info:
                for m in info[i]["abstract"]:
                    if not declares(cls, m):
                        bad.add((cls, i, m, ci["super"]))
    for b in sorted(bad):
        print("MISSING %s : %s.%s  (inherits from %s, which the remap renames)" % b)
    print("%d classes checked, %d unimplemented interface methods" % (len(info), len(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
