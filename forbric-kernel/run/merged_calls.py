#!/usr/bin/env python3
"""merged_calls.py [--every] <jar> <class> <method> <ref>... — read the merged base the way a gate's expectation needs it.

Exit 0 when <method> of <class> (every overload of that name; "<init>" for a constructor), or a lambda it captures,
makes one of the references; 1 when none does; 2 when the class cannot be read. With --every, 0 only when EACH
overload does, leaving out a constructor that hands over to another one of its class (this(...)).

A reference is one of
  owner.name            any call of that method (owner as an internal name, net/minecraftforge/...)
  owner.name:(desc      a call whose descriptor starts with that text
  new:owner             construction of that type
  put:owner.field       a store into that field of anything but the null constant

Captured lambdas, and only those: a merge that keeps both families' compiles of a lambda can leave the losing body in
the class with nothing capturing it, and a gate asking whether the base delivers an event must not read that orphan
as the base's own call. The capture is followed through the class's BootstrapMethods table (javap -v), transitively,
for lambdas declared in the same class.
"""
import re
import subprocess
import sys


def javap(jar, cls):
    run = subprocess.run(["javap", "-p", "-v", "-cp", jar, cls], capture_output=True, text=True, errors="replace")
    return run.stdout if run.returncode == 0 and "Code:" in run.stdout else None


def methods(text, simple):
    """name -> list of instruction-line blocks, every overload."""
    out = {}
    current = None
    inside = False  # the members sit between javap's "{" and "}"; the attribute tables after them are indented too
    for line in text.splitlines():
        if line == "{":
            inside = True
            continue
        if line == "}":
            inside = False
            current = None
            continue
        if not inside:
            continue
        if re.match(r"^  \S", line) and not line.startswith("  #"):
            decl = line.strip()
            current = None
            if decl.endswith(";") and "(" in decl:
                name = re.search(r"([\w$<>]+)\(", decl).group(1)
                if name == simple or name.endswith("." + simple):
                    name = "<init>"
                current = out.setdefault(name, [])
                current.append([])
            elif decl == "static {};":
                current = out.setdefault("<clinit>", [])
                current.append([])
            continue
        if current is not None and re.match(r"^\s+\d+: ", line):
            current[-1].append(line)
    return out


def bootstrap_lambdas(text, internal):
    """bootstrap index -> lambda names in this class its handles name."""
    table = {}
    section = re.search(r"\nBootstrapMethods:\n(.*?)(?:\n\S|\Z)", text, re.S)
    if not section:
        return table
    current = None
    for line in section.group(1).splitlines():
        head = re.match(r"\s+(\d+): #\d+ ", line)
        if head:
            current = int(head.group(1))
            table[current] = []
            continue
        handle = re.search(r"REF_\w+ ([\w/$]+)\.([\w$<>]+):", line)
        if handle and current is not None and handle.group(1) == internal:
            table[current].append(handle.group(2))
    return table


def references(line, previous, refs, internal):
    call = re.search(r"// (?:Interface)?Method ([\w/$]+)\.([\w$<>]+):(\S+)", line)
    made = re.search(r"\bnew\s+#\d+\s+// class ([\w/$]+)", line)
    # javap leaves the owner out of a field of the class itself
    put = re.search(r"\bputfield\s+#\d+\s+// Field (?:([\w/$]+)\.)?([\w$]+):", line)
    for ref in refs:
        if ref.startswith("new:"):
            if made and made.group(1) == ref[4:]:
                return True
            continue
        if ref.startswith("put:"):
            owner, _, field = ref[4:].rpartition(".")
            if put and (put.group(1) or internal) == owner and put.group(2) == field \
                    and not re.search(r":\s+aconst_null\s*$", previous or ""):
                return True
            continue
        target, _, desc = ref.partition(":")
        owner, _, name = target.rpartition(".")
        if call and call.group(1) == owner and call.group(2) == name and call.group(3).startswith(desc):
            return True
    return False


def reaches(start, bodies, lambdas, refs, internal):
    """Whether the instruction blocks in start, or the lambdas they capture (transitively), make a reference."""
    pending, seen = list(start), set()
    while pending:
        body = pending.pop()
        previous = None
        for line in body:
            if references(line, previous, refs, internal):
                return True
            previous = line
            indy = re.search(r"InvokeDynamic #(\d+):", line)
            if indy:
                for name in lambdas.get(int(indy.group(1)), []):
                    if name not in seen:
                        seen.add(name)
                        pending.extend(bodies.get(name, []))
    return False


def delegates(body, internal):
    return any(re.search(r"\binvokespecial\s+#\d+\s+// Method (?:" + re.escape(internal) + r"\.)?\"<init>\"", line)
               for line in body)


def main(argv):
    every = len(argv) > 1 and argv[1] == "--every"
    if every:
        argv = argv[:1] + argv[2:]
    if len(argv) < 5:
        print(__doc__, file=sys.stderr)
        return 2
    jar, cls, method, refs = argv[1], argv[2], argv[3], argv[4:]
    text = javap(jar, cls)
    if text is None:
        print(f"cannot read {cls} from {jar}", file=sys.stderr)
        return 2
    internal = cls.replace(".", "/")
    simple = cls.rsplit(".", 1)[-1]
    bodies = methods(text, simple)
    lambdas = bootstrap_lambdas(text, internal)
    if method not in bodies:
        print(f"{cls} has no method {method}", file=sys.stderr)
        return 2
    if every:
        judged = [body for body in bodies[method] if not (method == "<init>" and delegates(body, internal))]
        return 0 if judged and all(reaches([body], bodies, lambdas, refs, internal) for body in judged) else 1
    return 0 if reaches(bodies[method], bodies, lambdas, refs, internal) else 1


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as failure:  # an unreadable answer is 2, never the 1 that means "the base does not call it"
        print(f"merged_calls.py: {failure!r}", file=sys.stderr)
        sys.exit(2)
