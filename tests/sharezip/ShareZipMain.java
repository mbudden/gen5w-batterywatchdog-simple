package com.nemesis.batterywatchdog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host driver for tests/test_sharezip.py: ShareZipMain <raw dir> <out.zip> <shift seconds | random> */
public class ShareZipMain {
    public static void main(String[] a) throws Exception {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        for (String n : ShareZip.SHARE_FILES) {
            File f = new File(a[0], n);
            if (f.exists()) raw.put(n, new String(Files.readAllBytes(f.toPath()), "UTF-8"));
        }
        long shift = "random".equals(a[2]) ? ShareZip.randomShift() : Long.parseLong(a[2]);
        FileOutputStream o = new FileOutputStream(a[1]);
        o.write(ShareZip.share(raw, "app: test\nfirmware: test\n", shift));
        o.close();
        System.out.println(ShareZip.randomId());
    }
}
