#!/usr/bin/env python3
"""Emit explicit native-image reflection metadata for Jackson-mapped types.

Why this exists: `allDeclaredMethods` / `allDeclaredConstructors` register a
member for *lookup*, not for *invocation*. Jackson invokes, so an image built
from those flags fails at RUNTIME with MissingReflectionRegistrationError. The
tracing agent emits explicit {name, parameterTypes} entries — this reproduces
that shape without needing to drive the UI through every screen.

Usage:  python3 tools/register-json-types.py <classpath> <class> [<class> ...]
"""
import json, pathlib, re, subprocess, sys

META = pathlib.Path("wallet-app/src/main/resources/META-INF/native-image/reachability-metadata.json")
JAVAP = (pathlib.Path.home() / ".sdkman/candidates/java/25.0.4.fx-nik/bin/javap")
if not JAVAP.exists():
    JAVAP = pathlib.Path("javap")

SIG = re.compile(r'^(?:public|protected|private)?\s*(?:static\s+|final\s+|abstract\s+|synchronized\s+)*'
                 r'(?:[\w.$\[\]<>,?\s]+\s+)?([\w$<>]+)\((.*)\)')

def members(cp, cn):
    out = subprocess.run([str(JAVAP), "-p", "-cp", cp, cn], capture_output=True, text=True).stdout
    methods, fields = [], []
    simple = cn.split(".")[-1].split("$")[-1]
    for line in out.splitlines():
        line = line.strip().rstrip(";")
        if not line or line.startswith(("Compiled", "public class", "public final class",
                                        "final class", "class", "public record", "record",
                                        "public interface", "interface", "}")):
            continue
        if "(" in line:
            head, _, rest = line.partition("(")
            args = rest.rsplit(")", 1)[0].strip()
            token = head.split()[-1] if head.split() else ""
            # javap prints a constructor with the FULLY-QUALIFIED name, e.g.
            #   private com.…FileWalletSecretStore$Slot(int, java.lang.Integer, …)
            # Matching only the simple name silently registered no <init> at all,
            # which is how the v4 vault stayed unreadable after "registering" Slot.
            bare = token.split(".")[-1].split("$")[-1]
            if bare == simple:
                name = "<init>"
            else:
                name = bare
            if not name or "<" in name and name != "<init>":
                continue
            params = [a.strip().split("<")[0] for a in args.split(",")] if args else []
            methods.append({"name": name, "parameterTypes": [p for p in params if p]})
        else:
            f = line.split()[-1]
            if f.isidentifier():
                fields.append({"name": f})
    return methods, fields

def main():
    cp, classes = sys.argv[1], sys.argv[2:]
    d = json.load(open(META))
    by = {e.get("type"): e for e in d["reflection"] if isinstance(e.get("type"), str)}
    added = 0
    for cn in classes:
        ms, fs = members(cp, cn)
        if not ms and not fs:
            print(f"  !! {cn}: no members found (wrong classpath?)"); continue
        e = by.get(cn) or {"type": cn}
        if cn not in by:
            d["reflection"].append(e); by[cn] = e
        have = {(m["name"], tuple(m.get("parameterTypes", []))) for m in e.get("methods", [])}
        for m in ms:
            if (m["name"], tuple(m["parameterTypes"])) not in have:
                e.setdefault("methods", []).append(m); added += 1
        hf = {f["name"] for f in e.get("fields", [])}
        for f in fs:
            if f["name"] not in hf:
                e.setdefault("fields", []).append(f); added += 1
        print(f"  {cn}: {len(ms)} methods, {len(fs)} fields")
    json.dump(d, open(META, "w"), indent=2)
    print(f"added {added} entries; reflection total {len(d['reflection'])}")

main()
