package setup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

final class Workspace{
  record Link(String name, String path, String including){}
  record Project(String name, boolean java, List<Link> links, List<String> projects, boolean jars, boolean tests){}
  static Link link(String path){ return new Link(Path.of(path).getFileName().toString(), path, null); }
  static final Link resources= new Link("resources", "Coordinator/Build/src", "resources/");
  static final String tourJars= "FearlessTour/externalJars";
  static final List<Project> projects= List.of(
    new Project("Commons", true, List.of(link("Commons/src")), List.of(), false, false),
    new Project("CommonsTests", true, List.of(link("Frontend/commons_tests/test"), link("Frontend/commons_tests/testModule")), List.of("Commons"), false, true),
    new Project("CompactFrontend", true, List.of(link("Frontend/compact_frontend/src"), link("Frontend/compact_frontend/srcModule")), List.of(), false, false),
    new Project("Frontend", true, List.of(link("Frontend/FearlessFrontend/src"), link("Frontend/FearlessFrontend/srcModule")), List.of("Commons"), false, false),
    new Project("FrontendTests", true, List.of(link("Frontend/FearlessFrontend/src"), link("Frontend/FearlessFrontend/test"), link("Frontend/FearlessFrontend/testModule")), List.of("Commons", "CompactFrontend"), false, true),
    new Project("Coordinator", true, List.of(link("Coordinator/src"), link("Coordinator/srcModule")), List.of("Commons", "Frontend"), false, false),
    new Project("CoordinatorTests", true, List.of(link("Coordinator/src"), link("Coordinator/test"), link("Coordinator/testModule"), resources), List.of("Commons", "Frontend"), false, true),
    new Project("Build", true, List.of(link("Coordinator/Build/src")), List.of("Commons"), false, false),
    new Project("FearlessTour", true, List.of(link("FearlessTour/src"), resources), List.of("Commons", "Frontend", "Coordinator"), true, true),
    new Project("ZeroToHero", true, List.of(link("ZeroToHero/src")), List.of("Commons", "Frontend", "Coordinator"), true, false),
    new Project("StandardLibrary", false, List.of(link("StandardLibrary")), List.of(), false, false));

  static Path jdk(Path setup, String tool){ return setup.resolve("jdk", "bin", Fetch.windows ? tool+".exe" : tool); }

  /// Points Eclipse at the JDK and the workspace of the setup folder, and adds the importer application.
  /// The Windows launcher reads eclipse.ini in the ANSI code page, so the setup writes no path of the setup folder in eclipse.ini:
  /// the paths go in config.ini, a properties file read by Java, where unicode escapes carry every character.
  /// eclipse.ini is read and written as ISO-8859-1, which keeps every byte of any code page.
  /// By default the Edge browser, the tips and JNA keep their state in folders every Eclipse of the user shares; config.ini moves them into the setup folder.
  /// The app name is the Windows AppUserModelID, which owns the taskbar group, the jump list and %LOCALAPPDATA%\<app name>; it is "Eclipse" in every Eclipse IDE.
  static void configureEclipse(Path eclipse, Path setup) throws IOException, InterruptedException{
    var ini= eclipse.resolve("eclipse.ini");
    var lines= new ArrayList<>(Files.readAllLines(ini, StandardCharsets.ISO_8859_1));
    lines.set(lines.indexOf("-vm")+1, "../jdk/bin");
    lines.add(lines.indexOf("-vmargs")+1, "-Dheadless.startup=true");
    Files.write(ini, lines, StandardCharsets.ISO_8859_1);
    var config= eclipse.resolve("configuration", "config.ini");
    var properties= new Properties();
    try (var in= Files.newInputStream(config)){ properties.load(in); }
    var area= setup.resolve("eclipse", "configuration");
    properties.setProperty("osgi.instance.area", setup.resolve("workspace").toString());
    properties.setProperty("eclipse.appName", "fearless_eclipse_"+Integer.toHexString(setup.toString().hashCode()));
    properties.setProperty("org.eclipse.swt.browser.EdgeDataDir", area.resolve("edge").toString());
    properties.setProperty("org.eclipse.tips.statelocation", area.resolve("tips").toString());
    properties.setProperty("jna.tmpdir", area.resolve("jna").toString());
    try (var out= Files.newOutputStream(config)){ properties.store(out, null); }
    var settings= eclipse.resolve("configuration", ".settings");
    pref(settings, "org.eclipse.ui", "windows.defender.startup.check.skip", "true");
    pref(settings, "org.eclipse.urischeme", "skipAutoRegistration", "true");
    var plugins= eclipse.resolve("plugins");
    var bundle= plugins.resolve("fearless_setup");
    for (var f : List.of("META-INF/MANIFEST.MF", "plugin.xml", "importer/Importer.java")){
      var to= bundle.resolve(f);
      Files.createDirectories(to.getParent());
      try (var in= Workspace.class.getResourceAsStream("/importer/"+f)){ Files.copy(in, to); }
    }
    run(List.of(jdk(setup, "javac").toString(), "-d", bundle.toString(), "-cp", plugins+File.separator+"*", bundle.resolve("importer", "Importer.java").toString()));
    Files.writeString(eclipse.resolve("configuration", "org.eclipse.equinox.simpleconfigurator", "bundles.info"),
      "fearless_setup,1.0.0,plugins/fearless_setup/,4,false\n", StandardOpenOption.APPEND);
  }
  static void run(List<String> command) throws IOException, InterruptedException{
    var p= new ProcessBuilder(command).redirectErrorStream(true).start();
    var lines= p.inputReader().lines().toList();
    var code= p.waitFor();
    lines.stream().filter(l -> code!=0 || l.startsWith("Error in ")).forEach(Main::log);
    if (code!=0){ throw new Problem("The command \""+String.join(" ", command)+"\" failed with the exit code "+code+": its output is in the log below.\n"+Problem.report); }
  }

  /// Writes the gitignored files the build needs into the checkouts, then the workspace.
  static void write(Path setup, Path repos) throws IOException, InterruptedException{
    for (var d : Fetch.flexmark){ Fetch.file(d, repos.resolve(tourJars, d.fileName())); }
    Files.writeString(repos.resolve("Coordinator/Build/src/resources/LocalResources.java"), localResources(setup, repos));
    var part= setup.resolve("_part_workspace");
    var java= jdk(setup, "java").toRealPath();
    for (var h : ProcessHandle.allProcesses().filter(p -> p.info().command().map(c -> Path.of(c).equals(java)).orElse(false)).toList()){ h.destroyForcibly(); h.onExit().join(); }
    Fetch.delete(part);
    for (var p : projects){ project(part.resolve(p.name()), p, repos); }
    launches(part, repos);
    prefs(part, setup);
    Main.log("Importing the projects into Eclipse and building them");
    var eclipse= setup.resolve("eclipse");
    var ini= Files.readAllLines(eclipse.resolve("eclipse.ini"), StandardCharsets.ISO_8859_1);
    run(List.of(java.toString(), "-jar", eclipse.resolve(ini.get(ini.indexOf("-startup")+1)).toString(), "-application", "fearless_setup.importer", "-data", part.toString()));
    Files.move(part, setup.resolve("workspace"));
  }
  static String localResources(Path setup, Path repos) throws IOException{
    var fields= Files.readAllLines(repos.resolve("Coordinator/Build/src/resources/LocalResourcesTemplate.java")).stream()
      .filter(l -> l.contains("static final Path") && !l.contains("portableEclipse"))
      .map(l -> l+"\n").collect(Collectors.joining());
    return "package resources;\n\nimport java.nio.file.Path;\n\npublic class LocalResources{\n"
      +"  private static Path prefix= Path.of("+literal(repos)+");\n"
      +fields
      +"  public static final Path portableEclipse= Path.of("+literal(setup.resolve("eclipse"))+");\n}\n";
  }
  /// Eclipse reads the paths of .project and .classpath in its portable form, where only "/" separates folders.
  static String portable(Path p){ return p.toString().replace(File.separatorChar, '/'); }
  static String literal(Path p){ return "\""+p.toString().replace("\\", "\\\\").replace("\"", "\\\"")+"\""; }
  static String xml(Object o){
    return o.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
  static void project(Path dir, Project p, Path repos) throws IOException{
    Files.createDirectories(dir);
    var links= p.links().stream()
      .map(l -> "<link><name>"+l.name()+"</name><type>2</type><location>"+xml(portable(repos.resolve(l.path())))+"</location></link>")
      .collect(Collectors.joining("\n"));
    Files.writeString(dir.resolve(".project"), """
      <?xml version="1.0" encoding="UTF-8"?>
      <projectDescription>
      <name>%s</name>
      <buildSpec>%s</buildSpec>
      <natures>%s</natures>
      <linkedResources>
      %s
      </linkedResources>
      </projectDescription>
      """.formatted(p.name(),
        p.java() ? "<buildCommand><name>org.eclipse.jdt.core.javabuilder</name></buildCommand>" : "",
        p.java() ? "<nature>org.eclipse.jdt.core.javanature</nature>" : "",
        links));
    if (!p.java()){ return; }
    var module= "<attributes><attribute name=\"module\" value=\"true\"/></attributes>";
    var entries= new ArrayList<String>();
    p.links().forEach(l -> entries.add("<classpathentry kind=\"src\" path=\""+l.name()+"\""+(l.including()==null ? "" : " including=\""+l.including()+"\"")+"/>"));
    entries.add("<classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER\">"+module+"</classpathentry>");
    p.projects().forEach(d -> entries.add("<classpathentry kind=\"src\" path=\"/"+d+"\" combineaccessrules=\"false\">"+module+"</classpathentry>"));
    if (p.tests()){ entries.add("<classpathentry kind=\"con\" path=\"org.eclipse.jdt.junit.JUNIT_CONTAINER/6\">"+module+"</classpathentry>"); }
    if (p.jars()){ Fetch.flexmark.forEach(d -> entries.add("<classpathentry kind=\"lib\" path=\""+xml(portable(repos.resolve(tourJars, d.fileName())))+"\">"+module+"</classpathentry>")); }
    entries.add("<classpathentry kind=\"output\" path=\"bin\"/>");
    Files.writeString(dir.resolve(".classpath"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<classpath>\n"+String.join("\n", entries)+"\n</classpath>\n");
  }
  static void launches(Path ws, Path repos) throws IOException{
    var favorites= new ArrayList<String>();
    try (var s= Files.list(repos.resolve("Coordinator/Build/src/scripts"))){
      for (var f : s.sorted().toList()){
        if (!Files.readString(f).contains("static void main(")){ continue; }
        var name= f.getFileName().toString().replace(".java", "");
        Files.writeString(ws.resolve("Build", name+".launch"), """
          <?xml version="1.0" encoding="UTF-8" standalone="no"?>
          <launchConfiguration type="org.eclipse.jdt.launching.localJavaApplication">
          <listAttribute key="org.eclipse.debug.ui.favoriteGroups"><listEntry value="org.eclipse.debug.ui.launchGroup.run"/></listAttribute>
          <stringAttribute key="org.eclipse.jdt.launching.MAIN_TYPE" value="scripts.%s"/>
          <stringAttribute key="org.eclipse.jdt.launching.PROJECT_ATTR" value="Build"/>
          <stringAttribute key="org.eclipse.jdt.launching.VM_ARGUMENTS" value="--add-modules Commons"/>
          <stringAttribute key="org.eclipse.jdt.launching.WORKING_DIRECTORY" value="${resource_loc:/Build/src}"/>
          </launchConfiguration>
          """.formatted(name));
        favorites.add("<launch memento=\""+xml("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><launchConfiguration local=\"false\" path=\"/Build/"+name+".launch\"/>")+"\"/>");
      }
    }
    var history= ws.resolve(".metadata/.plugins/org.eclipse.debug.ui/launchConfigurationHistory.xml");
    Files.createDirectories(history.getParent());
    Files.writeString(history, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<launchHistory><launchGroup id=\"org.eclipse.debug.ui.launchGroup.run\"><mruHistory/><favorites>\n"
      +String.join("\n", favorites)+"\n</favorites></launchGroup></launchHistory>\n");
  }
  static void prefs(Path ws, Path setup) throws IOException{
    var settings= ws.resolve(".metadata/.plugins/org.eclipse.core.runtime/.settings");
    var vm= "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>"
      +"<vmSettings defaultVM=\"57,org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType3,jdk\" defaultVMConnector=\"\">"
      +"<vmType id=\"org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType\">"
      +"<vm id=\"jdk\" name=\"jdk\" path=\""+xml(setup.resolve("jdk"))+"\" vmargs=\"-ea\"/>"
      +"</vmType></vmSettings>";
    pref(settings, "org.eclipse.jdt.launching", "org.eclipse.jdt.launching.PREF_VM_XML", vm, "detectVMsAtStartup", "false");
    pref(settings, "org.eclipse.jdt.core",
      "org.eclipse.jdt.core.compiler.compliance", "26",
      "org.eclipse.jdt.core.compiler.source", "26",
      "org.eclipse.jdt.core.compiler.codegen.targetPlatform", "26",
      "org.eclipse.jdt.core.compiler.release", "enabled");
    pref(settings, "org.eclipse.core.resources", "encoding", "UTF-8");
    pref(settings, "org.eclipse.core.runtime", "line.separator", "\n");
    pref(settings, "org.eclipse.ui", "showIntro", "false");
  }
  static void pref(Path settings, String node, String... keyValues) throws IOException{
    var p= new Properties();
    p.setProperty("eclipse.preferences.version", "1");
    for (int i= 0; i<keyValues.length; i += 2){ p.setProperty(keyValues[i], keyValues[i+1]); }
    var file= settings.resolve(node+".prefs");
    Files.createDirectories(file.getParent());
    try (var out= Files.newOutputStream(file)){ p.store(out, null); }
  }
}
