import ipaddress
import random
import asyncio
import aiohttp
import socket
import json
import ping3
from typing import Optional, List, Set, Dict

# Global references to the Java callback objects (set from Kotlin)
progress_callback = None
result_callback = None

class SingleHostResolver:
    def __init__(self, ip: str, host: str = 'speed.cloudflare.com'):
        self.ip = ip
        self.host = host

    async def resolve(self, host: str, port: int = 0, family: int = 0):
        if host == self.host:
            return [{
                'hostname': host,
                'host': self.ip,
                'port': port,
                'family': family or socket.AF_INET,
                'proto': socket.IPPROTO_TCP,
                'flags': socket.AI_NUMERICHOST,
            }]
        from aiohttp.resolver import AsyncResolver
        resolver = AsyncResolver()
        return await resolver.resolve(host, port, family)

async def async_ping_loss(ip: str, timeout: float, count: int = 5) -> (float, float):
    results = []
    for _ in range(count):
        try:
            resp = ping3.ping(ip, timeout=timeout)
            if resp is not None and resp > 0:
                results.append(resp * 1000)
        except:
            pass
    if not results:
        return -1, 1.0
    avg_ping = sum(results) / len(results)
    loss = 1.0 - (len(results) / count)
    return avg_ping, loss

async def async_latency_jitter(ip: str, acceptable_latency: int) -> (int, int):
    url = f"https://speed.cloudflare.com/__down?bytes=1000"
    headers = {'Host': 'speed.cloudflare.com'}
    timeout = aiohttp.ClientTimeout(total=(acceptable_latency / 1000) * 1.5)
    resolver = SingleHostResolver(ip)
    latencies = []
    try:
        async with aiohttp.ClientSession(
            connector=aiohttp.TCPConnector(resolver=resolver, ssl=False),
            timeout=timeout
        ) as session:
            for _ in range(4):
                start = asyncio.get_event_loop().time()
                async with session.get(url, headers=headers) as resp:
                    await resp.read()
                latencies.append((asyncio.get_event_loop().time() - start) * 1000)
    except Exception:
        return 99999, -1
    if not latencies:
        return 99999, -1
    avg_latency = int(sum(latencies) / len(latencies))
    jitter = int(sum(abs(latencies[i] - latencies[i-1]) for i in range(1, len(latencies))) / (len(latencies)-1)) if len(latencies) > 1 else 0
    return avg_latency, jitter

# Synchronous speed tests (using requests)
def getDownloadSpeed(ip, size, min_speed):
    import requests
    download_size = size * 1024
    min_speed_bytes = min_speed * 125000
    timeout = download_size / min_speed_bytes
    url = f"https://speed.cloudflare.com/__down?bytes={download_size}"
    headers = {'Host': 'speed.cloudflare.com'}
    params = {'resolve': f"speed.cloudflare.com:443:{ip}"}
    try:
        import time
        start = time.time()
        response = requests.get(url, headers=headers, params=params, timeout=timeout)
        download_time = time.time() - start
        speed = round(download_size / download_time * 8 / 1000000, 2)
        return speed
    except:
        return 0

def getUploadSpeed(ip, size, min_speed):
    import requests
    upload_size = int(size * 1024 / 4)
    min_speed_bytes = min_speed * 125000
    timeout = upload_size / min_speed_bytes
    url = 'https://speed.cloudflare.com/__up'
    headers = {'Content-Type': 'multipart/form-data', 'Host': 'speed.cloudflare.com'}
    params = {'resolve': f"speed.cloudflare.com:443:{ip}"}
    files = {'file': ('sample.bin', b"\x55" * upload_size)}
    try:
        import time
        start = time.time()
        response = requests.post(url, headers=headers, params=params, files=files, timeout=timeout)
        upload_time = time.time() - start
        speed = round(upload_size / upload_time * 8 / 1000000, 2)
        return speed
    except:
        return 0

def random_ip_generator(ranges, tested_ips, total_possible):
    while len(tested_ips) < total_possible:
        net = random.choice(ranges)
        ip_int = random.randint(int(net.network_address) + 1, int(net.broadcast_address) - 1)
        ip = str(ipaddress.IPv4Address(ip_int))
        if ip not in tested_ips:
            tested_ips.add(ip)
            yield ip

class IPInfo:
    def __init__(self, ip, ping, jitter, latency, packet_loss, upload, download):
        self.ip = ip
        self.ping = ping
        self.jitter = jitter
        self.latency = latency
        self.packet_loss = packet_loss
        self.upload = upload
        self.download = download

    def to_dict(self):
        return {
            'ip': self.ip,
            'ping': self.ping,
            'jitter': self.jitter,
            'latency': self.latency,
            'packet_loss': self.packet_loss,
            'upload': self.upload,
            'download': self.download
        }

# ================== Main scanning function called from Kotlin ==================
def run_scan(config_dict, progress_cb, result_cb):
    global progress_callback, result_callback
    progress_callback = progress_cb
    result_callback = result_cb

    # Extract config
    max_ip = int(config_dict.get('max_ip', 10))
    max_ping = int(config_dict.get('max_ping', 500))
    max_jitter = int(config_dict.get('max_jitter', 100))
    max_latency = int(config_dict.get('max_latency', 1000))
    max_packet_loss = float(config_dict.get('max_packet_loss', 0.5))
    test_size = int(config_dict.get('test_size', 1024))
    min_dl = float(config_dict.get('min_download_speed', 3.0))
    min_ul = float(config_dict.get('min_upload_speed', 0.2))

    # Load IP ranges from a file inside the app (assets or raw)
    # For simplicity, we'll pass the file path as an argument or use a default
    # We'll use the same ipv4.txt from raw resource – but Python can't read Android resources directly.
    # We'll provide the file as a temporary extracted file from the assets.
    # For now, assume the caller provides the file path.
    ranges = []
    with open(config_dict['ip_list_path'], 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if '/' in line:
                try:
                    net = ipaddress.ip_network(line, strict=False)
                    ranges.append(net)
                except:
                    pass
            else:
                try:
                    ip_obj = ipaddress.ip_address(line)
                    ranges.append(ipaddress.ip_network(f"{ip_obj}/32"))
                except:
                    pass

    total_possible = sum(len(list(net.hosts())) for net in ranges)
    tested_ips = set()
    all_selected = []
    target_valid = max_ip * 4   # as in original

    def save_checkpoint():
        pass  # not needed for Android version, but we could implement

    # Normal scan
    sem = asyncio.Semaphore(100)
    async def check_ip(ip):
        async with sem:
            ping_timeout = max_ping / 1000
            avg_ping, loss = await async_ping_loss(ip, ping_timeout)
            if avg_ping < 0 or avg_ping > max_ping or loss > max_packet_loss:
                return None
            latency, jitter = await async_latency_jitter(ip, max_latency)
            if jitter > max_jitter or latency > max_latency:
                return None
            return {
                'ip': ip,
                'ping': int(avg_ping),
                'jitter': jitter,
                'latency': latency,
                'packet_loss': round(loss, 4)
            }

    ip_gen = random_ip_generator(ranges, tested_ips, total_possible)
    satisfied = []
    checked = 0
    batch_size = 100

    async def normal_scan():
        nonlocal checked
        while len(satisfied) < target_valid:
            tasks = []
            for _ in range(batch_size):
                try:
                    ip = next(ip_gen)
                    checked += 1
                    tasks.append(asyncio.create_task(check_ip(ip)))
                except StopIteration:
                    break
            if not tasks:
                break
            for t in asyncio.as_completed(tasks):
                res = await t
                if res is not None:
                    satisfied.append(res)
                    if len(satisfied) >= target_valid:
                        break
            # Report progress
            progress_callback.report(checked, total_possible, len(satisfied), None)
        return satisfied

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    valid_candidates = loop.run_until_complete(normal_scan())

    # Speed test batch
    for entry in valid_candidates:
        ip = entry['ip']
        if len(all_selected) >= max_ip:
            break
        progress_callback.report(checked, total_possible, len(all_selected), ip)
        upload = getUploadSpeed(ip, test_size, min_ul)
        if upload < min_ul:
            continue
        download = getDownloadSpeed(ip, test_size, min_dl)
        if download < min_dl:
            continue
        new_ip = IPInfo(ip, entry['ping'], entry['jitter'], entry['latency'],
                        entry['packet_loss'], upload, download)
        all_selected.append(new_ip)
        result_callback.addResult(new_ip.to_dict())

    progress_callback.report(checked, total_possible, len(all_selected), None)
    loop.close()