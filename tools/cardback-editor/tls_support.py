#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""卡背编辑器的局域网 TLS 支持（1.4.7.2 W4）。

这个模块只做三件事，HTTP 业务一行不碰：

1. 生成/复用自签证书，落盘到 ``<BASE_DIR>/.cardback_tls/{cert.pem,key.pem}``；
2. 算证书 DER SPKI 的 SHA-256（App 用它做证书固定，别拿整张证书的哈希）；
3. 把 ``CardbackServer`` 已经 bind 好的监听 socket 包成 TLS。

优先级：cryptography（本机 48.x）→ openssl 命令行（3.5 可作 fallback）。
两条路都失败时抛 TlsError，调用方只打印 warning，HTTP 照常起。
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import ipaddress
import os
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass

CERT_FILENAME = "cert.pem"
KEY_FILENAME = "key.pem"
DEFAULT_VALID_DAYS = 3650  # 10 年：自签局域网证书，别让用户每年重配一次
RENEW_BEFORE = _dt.timedelta(days=7)  # 快过期才换，平时保持 SPKI 稳定
COMMON_NAME = "cardback-editor"


class TlsError(RuntimeError):
    """证书或 TLS 环境不可用；调用方应当降级成「只跑 HTTP」。"""


@dataclass(frozen=True)
class CertInfo:
    cert_path: str
    key_path: str
    spki_sha256: str
    not_after: _dt.datetime

    @property
    def not_after_iso(self) -> str:
        return self.not_after.astimezone(_dt.timezone.utc).isoformat()


# ---------------------------------------------------------------- 路径 / SAN


def certificate_paths(directory):
    return (
        os.path.join(directory, CERT_FILENAME),
        os.path.join(directory, KEY_FILENAME),
    )


def _normalise_ips(lan_ips):
    """去重、只留可放进 SAN 的地址；回环/链路本地/未指定不算「局域网 IPv4」。"""
    result = []
    seen = set()
    for raw in lan_ips or ():
        text = str(raw or "").strip()
        if not text:
            continue
        try:
            addr = ipaddress.ip_address(text)
        except ValueError:
            continue
        if addr.is_loopback or addr.is_link_local or addr.is_unspecified:
            continue
        if addr.is_global:  # 证书只给局域网用；公网地址不进 SAN
            continue
        if addr in seen:
            continue
        seen.add(addr)
        result.append(addr)
    return result


def san_entries(lan_ips):
    """返回 [(kind, value)]，kind 是 "DNS" 或 "IP"；本地三件套永远在最前。"""
    entries = [
        ("DNS", "localhost"),
        ("IP", "127.0.0.1"),
        ("IP", "::1"),
    ]
    seen = {value for _kind, value in entries}
    for addr in _normalise_ips(lan_ips):
        value = str(addr)
        if value in seen:
            continue
        seen.add(value)
        entries.append(("IP", value))
    return entries


# ---------------------------------------------------------------- 证书生成


def ensure_certificate(directory, lan_ips=(), valid_days=DEFAULT_VALID_DAYS):
    """确保目录里有可用的 cert/key，返回 CertInfo。

    - 已有且未过期（>7 天）、私钥与证书匹配：直接复用，SPKI 不变；
    - 缺失/损坏/过期：重新生成一对，覆盖旧文件。
    """
    cert_path, key_path = certificate_paths(directory)
    os.makedirs(directory, exist_ok=True)

    if _certificate_usable(cert_path, key_path):
        try:
            spki, not_after = _certificate_info(cert_path, key_path)
            if not_after > _dt.datetime.now(_dt.timezone.utc) + RENEW_BEFORE:
                return CertInfo(cert_path, key_path, spki, not_after)
        except Exception:  # noqa: BLE001 - 坏证书就走重新生成
            pass

    _generate_certificate(cert_path, key_path, lan_ips, valid_days)
    spki, not_after = _certificate_info(cert_path, key_path)
    return CertInfo(cert_path, key_path, spki, not_after)


def _certificate_usable(cert_path, key_path):
    try:
        return (
            os.path.isfile(cert_path)
            and os.path.isfile(key_path)
            and os.path.getsize(cert_path) > 0
            and os.path.getsize(key_path) > 0
        )
    except OSError:
        return False


def _generate_certificate(cert_path, key_path, lan_ips, valid_days):
    try:
        _generate_with_cryptography(cert_path, key_path, lan_ips, valid_days)
        return
    except Exception as exc:  # noqa: BLE001 - cryptography 不可用/出错时降级
        crypto_error = exc

    try:
        _generate_with_openssl(cert_path, key_path, lan_ips, valid_days)
    except Exception as exc:  # noqa: BLE001
        raise TlsError(
            "生成自签证书失败（cryptography: %s；openssl: %s）" % (crypto_error, exc)
        ) from exc


def _generate_with_cryptography(cert_path, key_path, lan_ips, valid_days):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, COMMON_NAME)])
    now = _dt.datetime.now(_dt.timezone.utc)

    alt_names = []
    for kind, value in san_entries(lan_ips):
        if kind == "DNS":
            alt_names.append(x509.DNSName(value))
        else:
            alt_names.append(x509.IPAddress(ipaddress.ip_address(value)))

    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - _dt.timedelta(days=1))  # 容忍手机/电脑时钟小偏差
        .not_valid_after(now + _dt.timedelta(days=valid_days))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=True,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=False,
                crl_sign=False,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(
            x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False
        )
        .add_extension(x509.SubjectAlternativeName(alt_names), critical=False)
        .sign(key, hashes.SHA256())
    )

    cert_pem = certificate.public_bytes(serialization.Encoding.PEM)
    key_pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    _atomic_write(cert_path, cert_pem, 0o644)
    _atomic_write(key_path, key_pem, 0o600)


def _generate_with_openssl(cert_path, key_path, lan_ips, valid_days):
    openssl = shutil.which("openssl")
    if not openssl:
        raise TlsError("找不到 openssl 命令")

    lines = [
        "[req]",
        "distinguished_name = dn",
        "x509_extensions = v3_req",
        "prompt = no",
        "[dn]",
        "CN = %s" % COMMON_NAME,
        "[v3_req]",
        "basicConstraints = critical,CA:FALSE",
        "keyUsage = critical,digitalSignature,keyEncipherment",
        "extendedKeyUsage = serverAuth",
        "subjectAltName = @alt_names",
        "[alt_names]",
    ]
    dns_index = 0
    ip_index = 0
    for kind, value in san_entries(lan_ips):
        if kind == "DNS":
            dns_index += 1
            lines.append("DNS.%d = %s" % (dns_index, value))
        else:
            ip_index += 1
            lines.append("IP.%d = %s" % (ip_index, value))

    directory = os.path.dirname(os.path.abspath(cert_path)) or "."
    os.makedirs(directory, exist_ok=True)
    fd, config_path = tempfile.mkstemp(prefix=".openssl-", suffix=".cnf", dir=directory)
    cert_tmp = key_tmp = None
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")
        cert_tmp = _temp_path(directory, ".cert-", ".pem")
        key_tmp = _temp_path(directory, ".key-", ".pem")
        proc = subprocess.run(
            [
                openssl,
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-sha256",
                "-days",
                str(valid_days),
                "-nodes",
                "-keyout",
                key_tmp,
                "-out",
                cert_tmp,
                "-config",
                config_path,
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=120,
        )
        if proc.returncode != 0:
            detail = (proc.stderr or b"").decode("utf-8", "replace").strip()
            raise TlsError("openssl req 失败：%s" % (detail or "exit %d" % proc.returncode))
        os.replace(cert_tmp, cert_path)
        cert_tmp = None
        os.replace(key_tmp, key_path)
        key_tmp = None
        _chmod(key_path, 0o600)
    finally:
        for path in (config_path, cert_tmp, key_tmp):
            if path:
                try:
                    os.unlink(path)
                except OSError:
                    pass


# ---------------------------------------------------------------- 证书读取


def certificate_spki_sha256(cert_path):
    """证书 DER SPKI 的 SHA-256（小写 hex）——App 固定的就是这个值。"""
    return _certificate_info(cert_path, None)[0]


def _certificate_info(cert_path, key_path):
    try:
        return _certificate_info_with_cryptography(cert_path, key_path)
    except ImportError:
        return _certificate_info_with_openssl(cert_path, key_path)
    except Exception:
        # cryptography 能 import 但读不了（比如文件损坏）就别再拿 openssl 猜一遍，
        # 让上层按「证书不可用」处理；只有完全没装 cryptography 才走 openssl。
        raise


def _certificate_info_with_cryptography(cert_path, key_path):
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    with open(cert_path, "rb") as fh:
        certificate = x509.load_pem_x509_certificate(fh.read())
    spki = certificate.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    not_after = getattr(certificate, "not_valid_after_utc", None)
    if not_after is None:  # cryptography < 42
        not_after = certificate.not_valid_after.replace(tzinfo=_dt.timezone.utc)

    if key_path:
        with open(key_path, "rb") as fh:
            private_key = serialization.load_pem_private_key(fh.read(), password=None)
        key_public = private_key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        if key_public != spki:
            raise TlsError("cert.pem 与 key.pem 不是一对")
    return hashlib.sha256(spki).hexdigest(), not_after


def _certificate_info_with_openssl(cert_path, key_path):
    der = _openssl_spki_der(cert_path)
    not_after = _openssl_not_after(cert_path)
    if key_path:
        key_der = _run_openssl(
            ["pkey", "-in", key_path, "-pubout", "-outform", "DER"]
        )
        if key_der != der:
            raise TlsError("cert.pem 与 key.pem 不是一对")
    return hashlib.sha256(der).hexdigest(), not_after


def _openssl_spki_der(cert_path):
    pub_pem = _run_openssl(["x509", "-in", cert_path, "-pubkey", "-noout"])
    return _run_openssl(["pkey", "-pubin", "-outform", "DER"], input_bytes=pub_pem)


def _openssl_not_after(cert_path):
    text = _run_openssl(["x509", "-in", cert_path, "-noout", "-enddate"]).decode(
        "utf-8", "replace"
    )
    value = text.strip().split("=", 1)[-1]
    value = re.sub(r"\s+", " ", value).strip()
    try:
        return _dt.datetime.strptime(value, "%b %d %H:%M:%S %Y GMT").replace(
            tzinfo=_dt.timezone.utc
        )
    except ValueError as exc:
        raise TlsError("看不懂 openssl 的 notAfter：%r" % text) from exc


def _run_openssl(args, input_bytes=None):
    openssl = shutil.which("openssl")
    if not openssl:
        raise TlsError("找不到 openssl 命令")
    env = dict(os.environ)
    env.update({"LC_ALL": "C", "LANG": "C"})  # notAfter 的月份名别被本地化
    proc = subprocess.run(
        [openssl] + list(args),
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=30,
        env=env,
    )
    if proc.returncode != 0:
        detail = (proc.stderr or b"").decode("utf-8", "replace").strip()
        raise TlsError("openssl %s 失败：%s" % (" ".join(args[:2]), detail))
    return proc.stdout


# ---------------------------------------------------------------- TLS 上下文


def make_server_context(cert_path, key_path):
    import ssl

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    try:
        context.minimum_version = ssl.TLSVersion.TLSv1_2
    except (AttributeError, ValueError):
        pass
    context.load_cert_chain(certfile=cert_path, keyfile=key_path)
    return context


def wrap_listening_socket(context, sock):
    """把已 bind/listen 的 socket 包成 TLS；accept 时完成握手。"""
    return context.wrap_socket(sock, server_side=True)


# ---------------------------------------------------------------- 小工具


def _atomic_write(path, data, mode):
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".tmp-", dir=directory)
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
        _chmod(tmp, mode)
        os.replace(tmp, path)
    finally:
        try:
            os.unlink(tmp)
        except OSError:
            pass


def _temp_path(directory, prefix, suffix):
    fd, path = tempfile.mkstemp(prefix=prefix, suffix=suffix, dir=directory)
    os.close(fd)
    os.unlink(path)  # 让 openssl 自己创建，免得它嫌文件已存在
    return path


def _chmod(path, mode):
    try:
        os.chmod(path, mode)
    except OSError:
        pass  # Windows 上没有 POSIX 权限位，忽略
