#!/usr/bin/env python3
"""Every vanilla FIELD whose descriptor the merged base no longer offers.

The merged base is vanilla+MinecraftForge+NeoForge byte-merged. When one ecosystem RETYPES a vanilla field
(Forge's ClearableLazy featuresPerStep, NeoForge's Map builder), the merge can keep only the winner's
descriptor -- and then every consumer compiled against the vanilla descriptor gets NoSuchFieldError at the
getfield/putfield, or an unbindable @Accessor. Two mods died on exactly that in this run, so this asks the
question for all 11k classes at once: which vanilla (name, descriptor) pairs are simply gone?

Reports two kinds:
  GONE     -- the name exists in the merged class but NO variant carries the vanilla descriptor (fatal shape)
  SHADOWED -- the vanilla descriptor survives alongside another variant of the same name (the ParticleResources
              shape: legal in the JVM, but one of the two is written and the other is not)
"""
import argparse
import struct
import zipfile

def parse_fields(data):
    """Minimal class-file reader: constant pool -> field (name, descriptor) pairs. No ASM here on purpose."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    off = 10
    count = struct.unpack_from(">H", data, 8)[0]
    pool, i = {}, 1
    while i < count:
        tag = data[off]; off += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, off)[0]; off += 2
            pool[i] = data[off:off+ln].decode("utf-8", "replace"); off += ln
        elif tag in (7, 8, 16, 19, 20):  off += 2
        elif tag == 15:                   off += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): off += 4
        elif tag in (5, 6):               off += 8; i += 1
        else: return None
        i += 1
    off += 6                                        # access, this, super
    ifc = struct.unpack_from(">H", data, off)[0]; off += 2 + 2 * ifc
    nfields = struct.unpack_from(">H", data, off)[0]; off += 2
    out = set()
    for _ in range(nfields):
        _acc, nidx, didx, nattr = struct.unpack_from(">HHHH", data, off); off += 8
        out.add((pool.get(nidx, "?"), pool.get(didx, "?")))
        for _ in range(nattr):
            alen = struct.unpack_from(">I", data, off + 2)[0]; off += 6 + alen
    return out

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("vanilla", help="vanilla game jar")
    parser.add_argument("merged", help="merged game jar")
    args = parser.parse_args()
    van, mer = args.vanilla, args.merged
    vz, mz = zipfile.ZipFile(van), zipfile.ZipFile(mer)
    mnames = set(mz.namelist())
    gone, shadowed, missing_class = [], [], 0
    for n in vz.namelist():
        if not n.endswith(".class") or not n.startswith("net/minecraft/"):
            continue
        if n not in mnames:
            missing_class += 1
            continue
        vf = parse_fields(vz.read(n))
        mf = parse_fields(mz.read(n))
        if vf is None or mf is None:
            continue
        mbyname = {}
        for name, desc in mf:
            mbyname.setdefault(name, set()).add(desc)
        for name, desc in sorted(vf):
            have = mbyname.get(name)
            if have is None:
                continue                                 # field removed outright -- a different question
            if desc not in have:
                gone.append((n[:-6], name, desc, sorted(have)))
            elif len(have) > 1:
                shadowed.append((n[:-6], name, desc, sorted(have)))

    print("vanilla classes absent from merged base: %d" % missing_class)
    print("\n=== GONE: vanilla descriptor no longer exists (%d) ===" % len(gone))
    for c, n, d, have in gone:
        print("%-70s %-28s %s   ->  %s" % (c, n, d, ", ".join(have)))
    print("\n=== SHADOWED: vanilla descriptor survives beside another (%d) ===" % len(shadowed))
    for c, n, d, have in shadowed:
        print("%-70s %-28s %s   beside  %s" % (c, n, d, ", ".join(x for x in have if x != d)))


if __name__ == "__main__":
    main()
