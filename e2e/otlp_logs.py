"""Reads the OTLP log bodies the stand-in saved (protobuf, ExportLogsServiceRequest) and prints
their records as JSON: time, severity, body and string attributes. Python's standard library
only: just enough of the protobuf wire format, no generated classes."""
import glob
import json
import os
import sys


def fields(buf):
    i = 0
    while i < len(buf):
        key, i = varint(buf, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = varint(buf, i)
        elif wire == 1:
            value, i = int.from_bytes(buf[i:i + 8], "little"), i + 8
        elif wire == 2:
            length, i = varint(buf, i)
            value, i = buf[i:i + length], i + length
        elif wire == 5:
            value, i = int.from_bytes(buf[i:i + 4], "little"), i + 4
        else:
            raise ValueError("wire type %d" % wire)
        yield number, value


def varint(buf, i):
    result = shift = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def any_value(buf):
    for number, value in fields(buf):
        if number == 1:
            return value.decode("utf-8")
        if number in (2, 3):
            return value
    return None


def attributes(items):
    out = {}
    for kv in items:
        key = value = None
        for number, v in fields(kv):
            if number == 1:
                key = v.decode("utf-8")
            elif number == 2:
                value = any_value(v)
        out[key] = value
    return out


def records(path):
    for n1, resource_logs in fields(open(path, "rb").read()):
        if n1 != 1:
            continue
        for n2, scope_logs in fields(resource_logs):
            if n2 != 2:
                continue
            for n3, record in fields(scope_logs):
                if n3 != 2:
                    continue
                out = {"attributes": []}
                for number, value in fields(record):
                    if number == 1:
                        out["time_unix_nano"] = value
                    elif number == 2:
                        out["severity_number"] = value
                    elif number == 5:
                        out["body"] = any_value(value)
                    elif number == 6:
                        out["attributes"].append(value)
                out["attributes"] = attributes(out["attributes"])
                yield out


folder = sys.argv[1]
logs = [r for path in sorted(glob.glob(os.path.join(folder, "*v1_logs*.bin"))) for r in records(path)]
json.dump(logs, sys.stdout, indent=2)
