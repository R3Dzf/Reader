#!/usr/bin/env python3
"""RFD1 byte-exact APK deltas. No recompression of APK entries or signing blocks."""
import argparse, gzip, hashlib, io, json, struct, zipfile
from pathlib import Path
MAX_APK = 300 * 1024 * 1024
MAX_OP = 16 * 1024 * 1024
MAGIC = b'RFD1'
def digest(data): return hashlib.sha256(data).digest()
def records(data):
    result = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for info in sorted(archive.infolist(), key=lambda i: i.header_offset):
            start = info.header_offset
            assert data[start:start+4] == b'PK\x03\x04'
            flags = struct.unpack_from('<H', data, start+6)[0]
            names, extra = struct.unpack_from('<HH', data, start+26)
            end = start+30+names+extra+info.compress_size
            if flags & 8:
                end += 16 if data[end:end+4] == b'PK\x07\x08' else 12
            assert start < end <= len(data)
            result.append((start,end))
    assert all(a[1] <= b[0] for a,b in zip(result,result[1:]))
    return result

def make_patch(base, target):
    if not (0 < len(base) <= MAX_APK and 0 < len(target) <= MAX_APK): raise ValueError('APK size')
    old = {}
    for start,end in records(base): old.setdefault((end-start,digest(base[start:end])),start)
    ops = io.BytesIO()
    def add(data):
        for offset in range(0,len(data),MAX_OP):
            part=data[offset:offset+MAX_OP];ops.write(b'\x01'+struct.pack('>I',len(part))+part)
    def copy(start,size):
        while size:
            count=min(size,MAX_OP);ops.write(b'\x00'+struct.pack('>QI',start,count));start+=count;size-=count
    cursor=0
    for start,end in records(target):
        add(target[cursor:start]);part=target[start:end]
        source=old.get((len(part),digest(part)))
        if source is None: add(part)
        else: copy(source,len(part))
        cursor=end
    add(target[cursor:]);ops.write(b'\x7f')
    return MAGIC+struct.pack('>Q',len(target))+digest(base)+digest(target)+gzip.compress(ops.getvalue(),compresslevel=9,mtime=0)

def apply_patch(base, patch):
    if len(patch)<77 or patch[:4]!=MAGIC: raise ValueError('header')
    size=struct.unpack_from('>Q',patch,4)[0]
    if not 0 < size <= MAX_APK or patch[12:44]!=digest(base): raise ValueError('base/size')
    stream=gzip.GzipFile(fileobj=io.BytesIO(patch[76:]));out=bytearray();count=0
    def read(n):
        data=stream.read(n)
        if len(data)!=n: raise ValueError('truncated')
        return data
    while True:
        count+=1
        if count>100000: raise ValueError('operations')
        op=read(1)
        if op==b'\x7f': break
        if op==b'\x00':
            start,length=struct.unpack('>QI',read(12))
            if start>len(base) or length>len(base)-start: raise ValueError('copy')
        elif op==b'\x01': length=struct.unpack('>I',read(4))[0]
        else: raise ValueError('opcode')
        if not 0 < length <= MAX_OP or length > size-len(out): raise ValueError('length')
        out.extend(base[start:start+length] if op==b'\x00' else read(length))
    if stream.read(1) or len(out)!=size or digest(out)!=patch[44:76]: raise ValueError('target/trailing')
    return bytes(out)

def main():
    p=argparse.ArgumentParser();p.add_argument('base',type=Path);p.add_argument('target',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    base=a.base.read_bytes();target=a.target.read_bytes();patch=make_patch(base,target)
    assert apply_patch(base,patch)==target
    a.output.write_bytes(patch)
    print(json.dumps({'fullBytes':len(target),'deltaBytes':len(patch),'savedPercent':round((1-len(patch)/len(target))*100,2),'baseSha256':digest(base).hex(),'targetSha256':digest(target).hex(),'patchSha256':digest(patch).hex()}))
if __name__=='__main__': main()
