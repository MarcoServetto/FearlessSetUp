package setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BatchingProgressMonitor;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

final class Repos{
  record Repo(String owner, String name){
    String url(){ return "https://github.com/"+owner+"/"+name; }
  }
  static final List<Repo> all= List.of(
    new Repo("FearlessLang", "Commons"),
    new Repo("FearlessLang", "Frontend"),
    new Repo("FearlessLang", "Coordinator"),
    new Repo("FearlessLang", "StandardLibrary"),
    new Repo("FearlessLang", "Controllers"),
    new Repo("MarcoServetto", "FearlessTour"),
    new Repo("MarcoServetto", "ZeroToHero"));
  //JGit reads the git configurations of the user and of the system, and saves its measures of each file system in user.home/.config/jgit/config: empty configurations in memory keep JGit inside the checkouts.
  static{
    SystemReader.setInstance(new SystemReader.Delegate(SystemReader.getInstance()){
      @Override public FileBasedConfig openUserConfig(Config parent, FS fs){ return empty(parent, fs); }
      @Override public FileBasedConfig openSystemConfig(Config parent, FS fs){ return empty(parent, fs); }
      @Override public FileBasedConfig openJGitConfig(Config parent, FS fs){ return empty(parent, fs); }
    });
  }
  static FileBasedConfig empty(Config parent, FS fs){
    return new FileBasedConfig(parent, null, fs){
      @Override public void load(){}
      @Override public void save(){}
      @Override public boolean isOutdated(){ return false; }
    };
  }

  static void cloneAll(Path repos) throws IOException{
    Files.createDirectories(repos);
    for (var r : all){
      var to= repos.resolve(r.name());
      if (Files.isDirectory(to)){ official(to, r); continue; }
      var part= repos.resolve("_part_"+r.name());
      Fetch.delete(part);
      Main.log("Cloning "+r.url());
      try{ Git.cloneRepository().setURI(r.url()+".git").setBranch("main").setDirectory(part.toFile()).setDepth(1).setTimeout(60).setProgressMonitor(new Progress()).call().close(); }
      catch (GitAPIException e){ throw new Problem("The clone of "+r.url()+" failed: "+e.getMessage()+".\n"+Problem.network); }
      Files.move(part, to);
    }
  }
  /// A folder already in repos is used only when it is exactly the main branch the official repository has now.
  static void official(Path dir, Repo r) throws IOException{
    Main.log("Checking "+dir+" against "+r.url());
    var redo= "delete the folder \""+dir+"\", then press \"Try again\": the setup clones it again.";
    var origin= origin(dir);
    if (origin==null){ throw new Problem("The folder \""+dir+"\" is not a git checkout: it has no \".git\" folder with an \"origin\" remote; "+redo); }
    if (!origin.equals(r.url()+".git")){ throw new Problem("The folder \""+dir+"\" is a checkout of \""+origin+"\", not of "+r.url()+".git: "+redo); }
    try (var g= Git.open(dir.toFile())){
      var s= g.status().call();
      if (!s.isClean()){ throw new Problem("The folder \""+dir+"\" differs from its last commit, for example in \""+Stream.concat(s.getUncommittedChanges().stream(), s.getUntracked().stream()).findFirst().orElseThrow()+"\": "+redo); }
      var main= Git.lsRemoteRepository().setRemote(origin).setHeads(true).setTimeout(60).callAsMap().get("refs/heads/main").getObjectId();
      if (!main.equals(g.getRepository().resolve("HEAD"))){ throw new Problem("The folder \""+dir+"\" is not on the commit "+main.name()+", the commit the main branch of "+r.url()+" is on now: "+redo); }
    }
    catch (GitAPIException e){ throw new Problem("Checking the folder \""+dir+"\" against "+r.url()+" failed: "+e.getMessage()+".\n"+Problem.network); }
  }
  static final class Progress extends BatchingProgressMonitor{
    @Override protected void onUpdate(String task, int done, Duration d){}
    @Override protected void onEndTask(String task, int done, Duration d){}
    @Override protected void onUpdate(String task, int done, int total, int percent, Duration d){ if (percent%10==0){ Main.log("  "+task+" "+percent+"%"); } }
    @Override protected void onEndTask(String task, int done, int total, int percent, Duration d){}
  }
  static List<String> check(Path repos){
    if (!Files.isDirectory(repos)){ return List.of("There is no folder \""+repos+"\": the seven repositories should be folders in it."); }
    if (Files.exists(repos.resolve(".git"))){ return List.of("The folder \""+repos+"\" is itself a git checkout: the seven repositories should be folders in it, each one a checkout."); }
    var errors= new ArrayList<String>();
    for (var r : all){
      var dir= repos.resolve(r.name());
      if (!Files.isDirectory(dir)){ errors.add("The folder \""+dir+"\" is missing: it should hold a checkout of the "+r.name()+" repository."); continue; }
      var url= origin(dir);
      if (url==null){ errors.add("The folder \""+dir+"\" is not a git checkout: it has no \".git\" folder with an \"origin\" remote."); continue; }
      if (!url.replaceAll("\\.git$", "").endsWith("/"+r.name())){ errors.add("The folder \""+dir+"\" is a checkout of \""+url+"\", not of the "+r.name()+" repository."); }
    }
    if (!errors.isEmpty()){ return errors; }
    Stream.concat(Workspace.projects.stream()
      .flatMap(p -> p.links().stream().map(Workspace.Link::path)), Stream.of(Workspace.tourJars))
      .map(repos::resolve)
      .distinct()
      .filter(p -> !Files.isDirectory(p))
      .forEach(p -> errors.add("The folder \""+p+"\" is missing: the checkout holding it is not on a recent version of its main branch."));
    return errors;
  }
  static String origin(Path dir){
    try (var g= Git.open(dir.toFile())){ return g.getRepository().getConfig().getString("remote", "origin", "url"); }
    catch (IOException e){ return null; }
  }
}
