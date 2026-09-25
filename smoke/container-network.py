"""Protocol-5 fake backend and client for container-network.ps1. Standard library only."""

import hashlib
import hmac
import json
import os
import secrets
import socket
import sys
import time
import urllib.parse
import urllib.request
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


def status_max(host):
    with connect(host, 25577) as peer:
        frame(peer, b"\x00" + varint(5) + string("smoke") + (25565).to_bytes(2, "big") + varint(1))
        frame(peer, b"\x00")
        response = read_frame(peer)
        if response[0] != 0:
            raise ValueError("invalid status response")
        document, offset = parse_string(response, 1)
        if offset != len(response):
            raise ValueError("trailing status bytes")
        return json.loads(document)["players"]["max"]


def wait_max(host, expected):
    deadline = time.monotonic() + 15
    last = None
    while time.monotonic() < deadline:
        try:
            last = status_max(host)
            if last == expected:
                return
        except (OSError, EOFError, ValueError) as failure:
            last = str(failure)
        time.sleep(0.2)
    raise AssertionError(f"status max did not become {expected}; last={last}")


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


def probe(peer, value):
    expected = bytes((3, value))
    frame(peer, expected)
    received = read_frame(peer)
    if received != expected:
        raise AssertionError(f"PLAY echo mismatch: {received!r} != {expected!r}")


def agent_request(host, secret, agent_id, fields):
    body = urllib.parse.urlencode(fields).encode("utf-8")
    timestamp = str(int(time.time()))
    nonce = secrets.token_hex(16)
    canonical = b"POST\n/registration\n" + timestamp.encode() + b"\n" + nonce.encode() \
        + b"\n" + agent_id.encode() + b"\n" + body
    signature = hmac.new(secret.encode("utf-8"), canonical, hashlib.sha256).hexdigest()
    request = urllib.request.Request(f"http://{host}:28080/registration", data=body,
                                     headers={"X-Agent-Id": agent_id, "X-Timestamp": timestamp,
                                              "X-Nonce": nonce, "X-Signature": signature}, method="POST")
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.status


def client(mode, host, backend_host):
    wait_max(host, 0 if mode == "agent" else 4)
    if mode == "agent":
        secret = os.environ["STRATAPROXY_AGENT_SECRET"]
        generation = str(uuid.uuid4())
        agent_id = "container-network-smoke"
        status = agent_request(host, secret, agent_id, {
            "action": "register", "generation": generation, "name": "remote",
            "address": f"tcp://{backend_host}:25565", "capacity": "4", "leaseSeconds": "30"})
        if status != 201:
            raise AssertionError(f"agent registration returned {status}")
        wait_max(host, 4)
    with login(host) as peer:
        probe(peer, 42)
        if mode == "agent":
            status = agent_request(host, secret, agent_id,
                                   {"action": "unregister", "generation": generation})
            if status != 204:
                raise AssertionError(f"agent unregister returned {status}")
            wait_max(host, 1)
            probe(peer, 43)
    print(f"CLIENT_OK mode={mode} backend={backend_host} login=1 play_probes={2 if mode == 'agent' else 1}",
          flush=True)


if __name__ == "__main__":
    match sys.argv[1:]:
        case ["backend", probes]:
            backend(int(probes))
        case ["client", mode, host, backend_host]:
            client(mode, host, backend_host)
        case _:
            raise SystemExit("usage: container-network.py backend <probes> | client <static|dns|agent> <proxy> <backend>")
