package setup;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

final class Fetch{
  record Download(String url, String sha256){
    String fileName(){ return url.substring(url.lastIndexOf('/')+1); }
  }
  static final boolean windows= System.getProperty("os.name").startsWith("Windows");
  static final Download jdk= windows
    ? new Download("https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.2%2B10/OpenJDK26U-jdk_x64_windows_hotspot_26.0.2_10.zip",
      "0b532502e29a5d2babe608069f6df2423ae5e1aa714b715b560217cf8c6ce8ff")
    : new Download("https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.2%2B10/OpenJDK26U-jdk_x64_linux_hotspot_26.0.2_10.tar.gz",
      "56f768372f6ca1e2eb4c5f46b78f627949e8dcfe9c9723926cf45a45faf35802");
  static final Download eclipse= windows
    ? new Download("https://download.eclipse.org/technology/epp/downloads/release/2026-06/R/eclipse-java-2026-06-R-win32-x86_64.zip",
      "aa03a8488b1a4abc441611e52984269f6db23969dc5c29c057092fe19c8b83b2")
    : new Download("https://download.eclipse.org/technology/epp/downloads/release/2026-06/R/eclipse-java-2026-06-R-linux-gtk-x86_64.tar.gz",
      "5e0f6c241fd04530618df526d847150163c2a639f2bd6aa570ca608ff878f1ee");
  static final String maven= "https://repo1.maven.org/maven2/";
  static final List<Download> flexmark= List.of(
    new Download(maven+"com/vladsch/flexmark/flexmark/0.64.8/flexmark-0.64.8.jar", "3133878d108f0e1964d7d427f379a7433e1b71a45f9f3c1fab7ed0ac0301d3b0"),
    new Download(maven+"com/vladsch/flexmark/flexmark-ext-tables/0.64.8/flexmark-ext-tables-0.64.8.jar", "cdf82d26e112e96ade7e370138aeb16920b7e2d411d20c6c15cb1acfacd1ef1f"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-ast/0.64.8/flexmark-util-ast-0.64.8.jar", "a5342f644c6a5f37d502f225c3a0cb699c263621c82e720d9c08eba5a9f3b8d3"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-builder/0.64.8/flexmark-util-builder-0.64.8.jar", "afb9c88d9f652451e75f1ab069ad7ddd791d712eb211c51d8d2a83494240875d"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-collection/0.64.8/flexmark-util-collection-0.64.8.jar", "031ff01408d0f54c6235db18d451dc6e6b15b859b0ec1e40ff3429a82375082a"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-data/0.64.8/flexmark-util-data-0.64.8.jar", "83ceb5989ab0205054bf1b42446f55cb6da14c10aa7c2bda05d6a2504670f353"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-dependency/0.64.8/flexmark-util-dependency-0.64.8.jar", "e41adb5e8144699b61b78d50eb004eeb7de84f90c4c63626609e6cee5ea28b42"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-format/0.64.8/flexmark-util-format-0.64.8.jar", "6c9ac87686474edf0117b3f838d9dcb3b965fa72be4998d2177b598013679f73"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-html/0.64.8/flexmark-util-html-0.64.8.jar", "5b19e6506aff7913c74ad725af7c2b8a9f23958e6324bf0710c5a645a4663017"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-misc/0.64.8/flexmark-util-misc-0.64.8.jar", "74472ca81a9080e51ea1a4f11c270898e3d6c242c92275fab47eb993806c8f2b"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-options/0.64.8/flexmark-util-options-0.64.8.jar", "2489f41e9caf1d9f8061833b311b25403925cbb631fff2b8c069192804d6fc88"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-sequence/0.64.8/flexmark-util-sequence-0.64.8.jar", "6e22976fe4e9b8de94fd529991ecb2d79cc482aad94985367470a93be1d82631"),
    new Download(maven+"com/vladsch/flexmark/flexmark-util-visitor/0.64.8/flexmark-util-visitor-0.64.8.jar", "d9b3002a833a078c45e139568b306dab0ac117ff9046c6a1f9e180c0154563fc"),
    new Download(maven+"org/jetbrains/annotations/24.0.1/annotations-24.0.1.jar", "61666dbce7e42e6c85b43c04fcfb8293a21dcb55b3c80e869270ce42c01a6b35"));

  static void file(Download d, Path to) throws IOException{
    if (Files.exists(to)){ return; }
    var part= to.resolveSibling("_part_"+to.getFileName());
    Main.log("Downloading "+d.url());
    try{ download(d, part); }
    catch (IOException e){ throw new Problem("The download of "+d.url()+" failed: "+e+".\n"+Problem.network); }
    Files.move(part, to);
  }
  static void download(Download d, Path to) throws IOException{
    var c= (HttpURLConnection)URI.create(d.url()).toURL().openConnection();
    c.setConnectTimeout(60_000);
    c.setReadTimeout(60_000);
    try{
      var status= c.getResponseCode();
      if (status!=200){ throw new Problem("The server answered the download of "+d.url()+" with the HTTP status "+status+" instead of the file.\n"+Problem.report); }
      var size= c.getContentLengthLong();
      var md= sha256();
      try (var in= new DigestInputStream(c.getInputStream(), md); var out= Files.newOutputStream(to)){
        var buf= new byte[1<<16];
        long done= 0;
        for (int n= in.read(buf); n>=0; done += n, n= in.read(buf)){
          out.write(buf, 0, n);
          if ((done+n)*10/size>done*10/size){ Main.log("  "+(done+n)*10/size*10+"%"); }
        }
      }
      var got= HexFormat.of().formatHex(md.digest());
      if (!got.equals(d.sha256())){ throw new Problem("The file downloaded from "+d.url()+" has the SHA-256 "+got+" instead of "+d.sha256()+": it is not the file this setup program expects.\n"+Problem.report); }
    }
    finally{ c.disconnect(); }
  }
  static MessageDigest sha256(){
    try{ return MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException e){ throw new Error("Broken Environment", e); }
  }
  static void folder(Download d, Path to, Configure configure) throws IOException, InterruptedException{
    var archive= to.resolveSibling("_archive_"+to.getFileName());
    if (Files.isDirectory(to)){ Files.deleteIfExists(archive); return; }
    file(d, archive);
    var part= to.resolveSibling("_part_"+to.getFileName());
    delete(part);
    Main.log("Extracting "+d.fileName());
    if (d.fileName().endsWith(".zip")){ unzip(archive, part); } else { untar(archive, part); }
    configure.accept(part);
    Files.move(part, to);
    Files.delete(archive);
  }
  interface Configure{ void accept(Path p) throws IOException, InterruptedException; }

  static void delete(Path p) throws IOException{
    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)){ return; }
    try (var s= Files.walk(p)){
      for (var f : s.sorted(Comparator.reverseOrder()).toList()){ f.toFile().setWritable(true); Files.delete(f); }
    }
  }
  static Path strip(Path to, String name){ return to.resolve(name.substring(name.indexOf('/')+1)); }
  static void unzip(Path archive, Path to) throws IOException{
    try (var z= new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))){
      for (var e= z.getNextEntry(); e!=null; e= z.getNextEntry()){
        var p= strip(to, e.getName());
        if (e.isDirectory()){ Files.createDirectories(p); continue; }
        Files.createDirectories(p.getParent());
        Files.copy(z, p);
      }
    }
  }
  static void untar(Path archive, Path to) throws IOException{
    try (var in= new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive))))){
      var h= new byte[512];
      String longName= null;
      String longLink= null;
      for (in.readFully(h); h[0]!=0; in.readFully(h)){
        var size= Long.parseLong(text(h, 124, 12).trim(), 8);
        var type= (char)h[156];
        if ("LKxg".indexOf(type)>=0){
          var data= new String(in.readNBytes((int)size), StandardCharsets.UTF_8);
          in.skipNBytes(padding(size));
          if (type=='L'){ longName= data.split("\0")[0]; }
          if (type=='K'){ longLink= data.split("\0")[0]; }
          if (type=='x'){ longName= pax(data, "path", longName); longLink= pax(data, "linkpath", longLink); }
          continue;
        }
        var name= longName!=null ? longName : ustarName(h);
        var link= longLink!=null ? longLink : text(h, 157, 100);
        longName= null;
        longLink= null;
        var p= strip(to, name);
        if (type=='5'){ Files.createDirectories(p); continue; }
        Files.createDirectories(p.getParent());
        if (type=='2'){ Files.createSymbolicLink(p, Path.of(link)); continue; }
        if (type=='1'){ Files.createLink(p, strip(to, link)); continue; }
        assert type=='0' || type==0;
        Files.write(p, in.readNBytes((int)size));
        in.skipNBytes(padding(size));
        var mode= Integer.parseInt(text(h, 100, 8).trim(), 8);
        Files.setPosixFilePermissions(p, Arrays.stream(PosixFilePermission.values()).filter(x -> (mode & (0400>>x.ordinal()))!=0).collect(Collectors.toSet()));
      }
    }
  }
  static long padding(long size){ return (512-size%512)%512; }
  static String text(byte[] h, int off, int len){ return new String(h, off, len, StandardCharsets.UTF_8).split("\0", 2)[0]; }
  static String ustarName(byte[] h){
    var prefix= text(h, 345, 155);
    var name= text(h, 0, 100);
    return prefix.isEmpty() ? name : prefix+"/"+name;
  }
  static String pax(String data, String key, String old){
    return data.lines()
      .map(l -> l.substring(l.indexOf(' ')+1))
      .filter(l -> l.startsWith(key+"="))
      .map(l -> l.substring(key.length()+1))
      .reduce(old, (a, b) -> b);
  }
}
