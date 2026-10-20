// java build/Build.java   (from the repository root)
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Build{
  static final String appName= "fearless_setup";
  static final Path root= Path.of("").toAbsolutePath();
  static final Path out= root.resolve("out");
  static final boolean windows= System.getProperty("os.name").startsWith("Windows");

  public static void main(String[] a) throws IOException, InterruptedException{
    var src= root.resolve("src");
    if (!Files.isDirectory(src)){ throw new IllegalStateException("Run from the repository root, not from "+root); }
    rmTree(out);
    var classes= out.resolve("classes");
    var input= out.resolve("input");
    var runtime= out.resolve("runtime");
    var app= out.resolve("app");
    var dist= out.resolve("dist");
    Files.createDirectories(input);
    Files.createDirectories(dist);
    var jars= new ArrayList<String>();
    try (Stream<Path> s= Files.list(root.resolve("jars"))){
      for (var j : s.sorted().toList()){ jars.add(j.toString()); Files.copy(j, input.resolve(j.getFileName())); }
    }
    var args= new ArrayList<>(List.of("-encoding", "UTF-8", "-Xlint:all,-serial", "-Werror", "-d", classes.toString(), "-cp", String.join(java.io.File.pathSeparator, jars)));
    try (Stream<Path> s= Files.walk(src)){ s.map(Path::toString).filter(p -> p.endsWith(".java")).sorted().forEach(args::add); }
    run("javac", args);
    run("jar", List.of("--create", "--file", input.resolve(appName+".jar").toString(), "-C", classes.toString(), ".", "-C", root.toString(), "importer"));
    run("jlink", List.of(
      "--add-modules", "java.desktop,java.management,java.security.jgss,java.sql,java.naming,jdk.charsets",
      "--output", runtime.toString(),
      "--no-header-files", "--no-man-pages", "--strip-debug", "--compress", "zip-6"));
    var icon= root.resolve("packaging").resolve(windows ? "windows/icon.ico" : "linux/icon.png");
    run("jpackage", List.of(
      "--type", "app-image",
      "--dest", app.toString(),
      "--name", appName,
      "--icon", icon.toString(),
      "--input", input.toString(),
      "--main-jar", appName+".jar",
      "--main-class", "setup.Main",
      "--java-options", "-ea",
      //On Windows the Direct3D pipeline can leave the first window unpainted until it is resized.
      "--java-options", "-Dsun.java2d.d3d=false",
      "--java-options", "-Djava.net.useSystemProxies=true",
      "--runtime-image", runtime.toString()));
    if (windows){ zip(app.resolve(appName), dist.resolve(appName+"_windows.zip")); }
    else{ tar(app, dist.resolve(appName+"_linux.tar.gz")); }
    System.out.println("Built "+dist);
  }

  static void run(String tool, List<String> args){
    var t= ToolProvider.findFirst(tool).orElseThrow();
    int code= t.run(System.out, System.err, args.toArray(String[]::new));
    if (code!=0){ throw new IllegalStateException(tool+" failed with code "+code); }
  }
  static void rmTree(Path p) throws IOException{
    if (!Files.exists(p)){ return; }
    try (Stream<Path> s= Files.walk(p)){
      for (var f : s.sorted(Comparator.reverseOrder()).toList()){ Files.delete(f); }
    }
  }
  static void tar(Path parent, Path archive) throws IOException, InterruptedException{
    var pb= new ProcessBuilder("tar", "czf", archive.toString(), "-C", parent.toString(), appName).inheritIO();
    int code= pb.start().waitFor();
    if (code!=0){ throw new IllegalStateException("tar failed with code "+code); }
  }
  static void zip(Path dir, Path archive) throws IOException{
    try (var z= new ZipOutputStream(Files.newOutputStream(archive)); Stream<Path> s= Files.walk(dir)){
      for (var p : s.filter(Files::isRegularFile).sorted().toList()){
        z.putNextEntry(new ZipEntry(dir.getParent().relativize(p).toString().replace('\\', '/')));
        Files.copy(p, z);
        z.closeEntry();
      }
    }
  }
}
