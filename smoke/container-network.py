"""Protocol-5 fake backend and client for container-network.ps1. Standard library only."""

import hashlib
import hmac
import json
import os
import socket
import sys
import time
import uuid


def varint(value):
    output = bytearray()
    while True:
        part = value & 0x7F
        value >>= 7
        output.append(part | (0x80 if value else 0))
        if not value:
            return bytes(output)


def read_exact(stream, length):
    output = bytearray()
    while len(output) < length:
        part = stream.recv(length - len(output))
        if not part:
            raise EOFError("socket closed during a frame")
        output.extend(part)
    return bytes(output)


def read_varint(stream):
    value = 0
    for shift in range(0, 35, 7):
        part = read_exact(stream, 1)[0]
        value |= (part & 0x7F) << shift
        if not part & 0x80:
            return value
    raise ValueError("overlong VarInt")


def frame(stream, body):
    stream.sendall(varint(len(body)) + body)


def read_frame(stream):
    length = read_varint(stream)
    if not 1 <= length <= 2_097_152:
        raise ValueError(f"invalid frame length {length}")
    return read_exact(stream, length)


def string(value):
    encoded = value.encode("utf-8")
    return varint(len(encoded)) + encoded


def parse_string(body, offset):
    shift = 0
    length = 0
    while True:
        part = body[offset]
        offset += 1
        length |= (part & 0x7F) << shift
        if not part & 0x80:
            break
        shift += 7
        if shift > 28:
            raise ValueError("overlong string length")
    return body[offset:offset + length].decode("utf-8"), offset + length


def offline_uuid(username):
    digest = hashlib.md5(("OfflinePlayer:" + username).encode("utf-8")).digest()
    return uuid.UUID(bytes=digest, version=3)


def backend(probes):
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("0.0.0.0", 25565))
    listener.listen(1)
    listener.settimeout(30)
    print("BACKEND_READY", flush=True)
    with listener, listener.accept()[0] as peer:
        peer.settimeout(10)
        handshake = read_frame(peer)
        if not handshake or handshake[0] != 0:
            raise ValueError("backend expected protocol handshake")
        login = read_frame(peer)
        if not login or login[0] != 0:
            raise ValueError("backend expected Login Start")
        username, offset = parse_string(login, 1)
        if offset != len(login):
            raise ValueError("trailing Login Start bytes")
        frame(peer, b"\x02" + string(str(offline_uuid(username))) + string(username))
        # Join Game followed by a minimal Position and Look marker. The client probe
        # verifies routing, not the complete gameplay packet schema.
        frame(peer, bytes((1, 0, 0, 0, 42, 0, 0, 0, 20, 7, 100, 101, 102, 97, 117, 108, 116)))
        frame(peer, b"\x08")
        for index in range(probes):
            expected = bytes((3, 42 + index))
            received = read_frame(peer)
            if received != expected:
                raise ValueError(f"backend PLAY mismatch: {received!r} != {expected!r}")
            frame(peer, received)
    print(f"BACKEND_OK probes={probes}", flush=True)


def connect(host, port):
    peer = socket.create_connection((host, port), timeout=5)
    peer.settimeout(5)
    return peer


def status_online(host):
    with connect(host, 25577) as peer:
        frame(peer, b"\x00" + varint(5) + string("smoke") + (25565).to_bytes(2, "big") + varint(1))
        frame(peer, b"\x00")
        response = read_frame(peer)
        if response[0] != 0:
            raise ValueError("invalid status response")
        document, offset = parse_string(response, 1)
        if offset != len(response):
            raise ValueError("trailing status bytes")
        return json.loads(document)["players"]["online"]


def wait_online(host, expected):
    deadline = time.monotonic() + 15
    last = None
    while time.monotonic() < deadline:
        try:
            last = status_online(host)
            if last == expected:
                return
        except (OSError, EOFError, ValueError) as failure:
            last = str(failure)
        time.sleep(0.2)
    raise AssertionError(f"status online did not become {expected}; last={last}")


def login(host):
    peer = connect(host, 25577)
    try:
        frame(peer, b"\x00" + varint(5) + string("smoke") + (25565).to_bytes(2, "big") + varint(2))
        frame(peer, b"\x00" + string("SmokePlayer"))
        for expected in (2, 1, 8):
            packet = read_frame(peer)
            if packet[0] != expected:
                raise AssertionError(f"expected packet {expected}, got {packet!r}")
        return peer
    except BaseException:
        peer.close()
        raise


def wait_login(host):
    deadline = time.monotonic() + 15
    last = None
    while time.monotonic() < deadline:
        try:
            return login(host)
        except (OSError, EOFError, ValueError, AssertionError) as failure:
            last = str(failure)
        time.sleep(0.2)
    raise AssertionError(f"discovered backend did not accept login; last={last}")


def probe(peer, value):
    expected = bytes((3, value))
    frame(peer, expected)
    received = read_frame(peer)
    if received != expected:
        raise AssertionError(f"PLAY echo mismatch: {received!r} != {expected!r}")


def control_frame(peer, body):
    peer.sendall(len(body).to_bytes(4, "big") + body)


def read_control_frame(peer):
    length = int.from_bytes(read_exact(peer, 4), "big")
    if not 1 <= length <= 66 * 1024:
        raise ValueError(f"invalid control frame length {length}")
    return read_exact(peer, length)


def control_string(value):
    encoded = value.encode("utf-8")
    return len(encoded).to_bytes(2, "big") + encoded


def control_register(host, backend_host, secret):
    peer = connect(host, 28081)
    try:
        hello = read_control_frame(peer)
        if len(hello) != 37 or hello[0] != 1 or int.from_bytes(hello[1:5], "big") != 1:
            raise ValueError("invalid control HELLO")
        fields = ("container-network-smoke", "remote", f"tcp://{backend_host}:25565",
                  str(uuid.uuid4()), "primary")
        canonical = b"".join(control_string(value) for value in fields)
        signature = hmac.new(secret.encode("utf-8"), hello[5:] + canonical, hashlib.sha256).digest()
        control_frame(peer, b"\x02" + canonical + signature)
        registered = read_control_frame(peer)
        if len(registered) != 9 or registered[0] != 3 or int.from_bytes(registered[1:], "big") < 1:
            raise ValueError("control registration was not accepted")
        return peer
    except BaseException:
        peer.close()
        raise


def client(mode, host, backend_host):
    wait_online(host, 0)
    control = None
    if mode == "control":
        control = control_register(host, backend_host, os.environ["STRATAPROXY_CHANNEL_SECRET"])
    try:
        with wait_login(host) as peer:
            probe(peer, 42)
            if mode == "control":
                control_frame(control, b"\x06")
                control.close()
                control = None
            if mode == "control":
                wait_online(host, 1)
                probe(peer, 43)
    finally:
        if control is not None:
            control.close()
    wait_online(host, 0)
    print(f"CLIENT_OK mode={mode} backend={backend_host} login=1 play_probes={2 if mode == 'control' else 1}",
          flush=True)


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) == 2 and args[0] == "backend":
        backend(int(args[1]))
    elif len(args) == 4 and args[0] == "client" and args[1] in ("static", "control"):
        client(args[1], args[2], args[3])
    else:
        raise SystemExit("usage: container-network.py backend <probes> | client <static|control> <proxy> <backend>")
