package importer;

import java.io.File;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.framework.Bundle;

public class Importer implements IApplication{
  public Object start(IApplicationContext context) throws Exception{
    //EGit stores the absolute location of its hidden project, and the rename of this workspace folder breaks it: EGit makes a new one at the next start.
    Platform.getBundle("org.eclipse.egit.core").stop(Bundle.STOP_TRANSIENT);
    var ws= ResourcesPlugin.getWorkspace();
    var root= ws.getRoot();
    root.getProject(".org.eclipse.egit.core.cmp").delete(true, true, null);
    for (var dir : root.getLocation().toFile().listFiles()){
      if (!new File(dir, ".project").isFile()){ continue; }
      var p= root.getProject(dir.getName());
      p.create(null);
      p.open(null);
    }
    JavaCore.initializeAfterLoad(null);
    ws.build(IncrementalProjectBuilder.FULL_BUILD, null);
    for (var m : root.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)){
      if (m.getAttribute(IMarker.SEVERITY, 0)!=IMarker.SEVERITY_ERROR){ continue; }
      System.out.println("Error in "+m.getResource().getFullPath()+" line "+m.getAttribute(IMarker.LINE_NUMBER, 0)+": "+m.getAttribute(IMarker.MESSAGE, ""));
    }
    while (!Job.getJobManager().isIdle()){ Thread.sleep(100); }
    ws.save(true, null);
    return EXIT_OK;
  }
  public void stop(){}
}
