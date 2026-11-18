"""Writes a tiny MaxMind DB (MMDB v2, IPv4, 24-bit records) shaped like DB-IP City Lite, for JM-86 tests.
Networks: 1.0.0.0/8 KR Seoul, 2.0.0.0/8 KR Gyeonggi-do, 3.0.0.0/8 KR without subdivision, 4.0.0.0/8 US California.
Usage: python -I make_test_mmdb.py OUT_PATH"""
import struct
import sys


def enc_size(type_id, size, extended):
    # control byte: type in top 3 bits (0 for extended types), size in low 5 bits (sizes here are all < 29)
    assert size < 29
    if extended:
        return bytes([size, type_id - 7])
    return bytes([(type_id << 5) | size])


def enc(value, kind=None):
    if kind == "uint16":
        payload = value.to_bytes((value.bit_length() + 7) // 8, "big") if value else b""
        return enc_size(5, len(payload), False) + payload
    if kind == "uint32":
        payload = value.to_bytes((value.bit_length() + 7) // 8, "big") if value else b""
        return enc_size(6, len(payload), False) + payload
    if kind == "uint64":
        payload = value.to_bytes((value.bit_length() + 7) // 8, "big") if value else b""
        return enc_size(9, len(payload), True) + payload
    if isinstance(value, str):
        raw = value.encode("utf-8")
        return enc_size(2, len(raw), False) + raw
    if isinstance(value, dict):
        out = enc_size(7, len(value), False)
        for k, v in value.items():
            out += enc(k) + (enc(*v) if isinstance(v, tuple) else enc(v))
        return out
    if isinstance(value, list):
        out = enc_size(11, len(value), True)
        for v in value:
            out += enc(v)
        return out
    raise TypeError(value)


def city(iso, subdivision):
    record = {"country": {"iso_code": iso, "names": {"en": iso}}}
    if subdivision:
        record["subdivisions"] = [{"names": {"en": subdivision}}]
    return record


NETWORKS = [
    (1, city("KR", "Seoul")),
    (2, city("KR", "Gyeonggi-do")),
    (3, city("KR", None)),
    (4, city("US", "California")),
]

# data section: one record per network
data = b""
offsets = []
for _, record in NETWORKS:
    offsets.append(len(data))
    data += enc(record)

# binary trie over the first 8 bits; children: None (empty), ("node", i) or ("data", offset)
nodes = [[None, None]]
for (first_octet, _), offset in zip(NETWORKS, offsets):
    node = 0
    for i in range(8):
        bit = (first_octet >> (7 - i)) & 1
        if i == 7:
            nodes[node][bit] = ("data", offset)
        else:
            if nodes[node][bit] is None:
                nodes.append([None, None])
                nodes[node][bit] = ("node", len(nodes) - 1)
            node = nodes[node][bit][1]

node_count = len(nodes)


def record_value(child):
    if child is None:
        return node_count
    if child[0] == "node":
        return child[1]
    return node_count + 16 + child[1]


tree = b""
for left, right in nodes:
    tree += record_value(left).to_bytes(3, "big") + record_value(right).to_bytes(3, "big")

metadata = {
    "binary_format_major_version": (2, "uint16"),
    "binary_format_minor_version": (0, "uint16"),
    "build_epoch": (1763337600, "uint64"),
    "database_type": "DBIP-City-Lite-Test",
    "description": {"en": "JM-86 test file"},
    "ip_version": (4, "uint16"),
    "languages": ["en"],
    "node_count": (node_count, "uint32"),
    "record_size": (24, "uint16"),
}
out = tree + b"\x00" * 16 + data + b"\xab\xcd\xefMaxMind.com" + enc(metadata)
open(sys.argv[1], "wb").write(out)
print("wrote", sys.argv[1], len(out), "bytes,", node_count, "nodes")
