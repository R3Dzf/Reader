import io,os,struct,tempfile,unittest,zipfile
from rafolio_delta import make_patch,apply_patch

def apk(changed):
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w') as z:
        for name,data in [('assets/fonts/large.ttf',SHARED),('classes.dex',changed),('AndroidManifest.xml',b'manifest')]:
            entry=zipfile.ZipInfo(name,date_time=(2026,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;z.writestr(entry,data)
    return out.getvalue()
SHARED=os.urandom(1024*1024)
class DeltaTest(unittest.TestCase):
    def test_byte_exact_and_small(self):
        a,b=apk(b'old code'),apk(b'new code with different offsets');p=make_patch(a,b)
        self.assertEqual(apply_patch(a,p),b);self.assertLess(len(p),len(b)//20)
    def test_rejects_wrong_base(self):
        a,b=apk(b'a'),apk(b'b');p=make_patch(a,b)
        with self.assertRaises(ValueError):apply_patch(b,p)
    def test_rejects_corrupt_target_hash(self):
        a,b=apk(b'a'),apk(b'b');p=bytearray(make_patch(a,b));p[44]^=1
        with self.assertRaises(ValueError):apply_patch(a,p)
    def test_rejects_truncated_stream(self):
        a,b=apk(b'a'),apk(b'b');p=make_patch(a,b)
        with self.assertRaises((ValueError,EOFError)):apply_patch(a,p[:-9])
    def test_rejects_size_limit(self):
        a,b=apk(b'a'),apk(b'b');p=bytearray(make_patch(a,b));struct.pack_into('>Q',p,4,2**63)
        with self.assertRaises(ValueError):apply_patch(a,p)
    def test_identical_apk(self):
        a=apk(b'a');self.assertEqual(apply_patch(a,make_patch(a,a)),a)
if __name__=='__main__':unittest.main()
