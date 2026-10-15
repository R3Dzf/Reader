#!/usr/bin/env python3
"""Package an already signed APK and a signed update feed. Private key stays outside source."""
import argparse,base64,hashlib,json,os,re,shutil,subprocess,tempfile
from pathlib import Path
from rafolio_delta import make_patch,apply_patch
PIN='f81569437e31be6e14b0cdd3ce91b0e03c28684418e0a9b9598d13eb30d1d7f3'
def run(args):
    r=subprocess.run(args,capture_output=True)
    if r.returncode: raise RuntimeError('Release signing/verification command failed: '+args[0])
    return r.stdout

def package(apk,code,name,output,keystore,password_file,apksigner,previous=()):
    assert re.fullmatch(r'[0-9A-Za-z.+_-]{1,60}',name) and code>0
    assert len(previous)<=10
    output.mkdir(parents=True,exist_ok=True)
    def verify(path):
        report=run(['java','-jar',str(apksigner),'verify','--verbose','--print-certs',str(path)]).decode()
        assert 'Signer #1 certificate SHA-256 digest: '+PIN in report
        assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in report
    verify(apk)
    name_file='Rafolio-'+name+'.apk';target=output/name_file
    if apk.resolve()!=target.resolve():shutil.copyfile(apk,target)
    def asset(path):return dict(url='https://github.com/R3Dzf/Reader/releases/download/v'+name+'/'+path.name,bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    data=target.read_bytes();deltas=[]
    for old in previous:
        verify(old);base=old.read_bytes();base_hash=hashlib.sha256(base).hexdigest()
        if base_hash==hashlib.sha256(data).hexdigest():continue
        patch=make_patch(base,data);assert apply_patch(base,patch)==data
        if len(patch)>=len(data)*.9:continue
        path=output/('Rafolio-'+name+'-from-'+base_hash[:16]+'.rfd');path.write_bytes(patch)
        deltas.append(dict(baseSha256=base_hash,asset=asset(path)))
    payload=json.dumps(dict(schema=1,package='com.r3dzf.reader',versionCode=code,versionName=name,minSdk=26,certificateSha256=PIN,notes='In-app updates with smaller delta downloads and permanent release signing.',full=asset(target),deltas=deltas),sort_keys=True,separators=(',',':')).encode()
    with tempfile.TemporaryDirectory(prefix='rafolio-sign-') as tmp:
        private=Path(tmp)/'key.pem';public=Path(tmp)/'public.pem';cert=Path(tmp)/'certificate.pem';message=Path(tmp)/'payload';sig=Path(tmp)/'signature'
        run(['openssl','pkcs12','-in',str(keystore),'-passin','file:'+str(password_file),'-nocerts','-nodes','-out',str(private)]);private.chmod(0o600)
        run(['openssl','pkcs12','-in',str(keystore),'-passin','file:'+str(password_file),'-clcerts','-nokeys','-out',str(cert)])
        der=run(['openssl','x509','-in',str(cert),'-outform','DER']);assert hashlib.sha256(der).hexdigest()==PIN
        message.write_bytes(payload)
        run(['openssl','dgst','-sha256','-sign',str(private),'-out',str(sig),str(message)])
        public.write_bytes(run(['openssl','x509','-in',str(cert),'-pubkey','-noout']))
        run(['openssl','dgst','-sha256','-verify',str(public),'-signature',str(sig),str(message)])
        envelope=dict(payload=base64.b64encode(payload).decode(),signature=base64.b64encode(sig.read_bytes()).decode())
    (output/'rafolio-update.json').write_text(json.dumps(envelope,separators=(',',':'))+'\n')
    return dict(versionCode=code,versionName=name,full=asset(target),deltas=deltas)
def main():
    p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True);p.add_argument('--version-code',type=int,required=True);p.add_argument('--version-name',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--keystore',type=Path,required=True);p.add_argument('--password-file',type=Path,required=True);p.add_argument('--apksigner',type=Path,required=True);p.add_argument('--previous',type=Path,action='append',default=[]);a=p.parse_args()
    print(json.dumps(package(a.apk,a.version_code,a.version_name,a.output,a.keystore,a.password_file,a.apksigner,a.previous)))
if __name__=='__main__':main()
