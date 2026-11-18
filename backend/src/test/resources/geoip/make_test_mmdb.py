"""JM-86 테스트용으로 DB-IP 무료판(IP to City Lite)과 같은 모양의 아주 작은 MaxMind DB(2판, IPv4, 한 칸 24비트)를 만든다.
대역: 1.0.0.0/8 한국 Seoul, 2.0.0.0/8 한국 Gyeonggi-do, 3.0.0.0/8 한국(시/도 없음), 4.0.0.0/8 미국 California.
쓰는 법: python -I make_test_mmdb.py 만들_파일_경로"""
import struct
import sys


def enc_size(type_id, size, extended):
    # 제어 바이트: 위 3비트는 종류(확장 종류는 0), 아래 5비트는 크기 (여기서 쓰는 크기는 모두 29보다 작다)
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

# 데이터 영역: 대역마다 기록 하나
data = b""
offsets = []
for _, record in NETWORKS:
    offsets.append(len(data))
    data += enc(record)

# 앞 8비트로 가르는 이진 트리. 자식은 None(빈 곳), ("node", 번호), ("data", 데이터 위치) 중 하나
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
