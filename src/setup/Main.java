package setup;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;

public final class Main{
  static JTextArea log;
  static JPanel question;
  static JFrame frame;

  public static void main(String[] args){
    SwingUtilities.invokeLater(Main::open);
    var setup= askFolder("The setup folder holds the JDK, Eclipse, the Eclipse workspace and the Fearless repositories.\n"
      +"Choose a new folder, or the folder of an earlier setup to continue it or to start its Eclipse.\n"
      +"Deleting the setup folder removes everything the setup did.", Main::checkSetup);
    var choice= Files.isDirectory(setup.resolve("workspace")) ? -1 : askChoice();
    var repos= choice==1 ? askFolder("The folder holding the seven repositories (Commons, Frontend, Coordinator, StandardLibrary, Controllers, FearlessTour, ZeroToHero).", Repos::check) : choice==2 ? askForks(setup.resolve("repos")) : setup.resolve("repos");
    while (true){
      try{ run(setup, repos, choice); System.exit(0); }
      catch (Throwable e){
        var message= e instanceof Problem ? e.getMessage() : e+".\n"+Problem.report;
        log(e instanceof Problem ? message : trace(e));
        ask(problem("The setup stopped: "+message), JPanel::new, "Try again");
      }
    }
  }
  /// Every Swing component is made and used on the event thread, and open runs there before any other use.
  static void open(){
    log= new JTextArea();
    log.setEditable(false);
    log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    question= new JPanel(new BorderLayout());
    question.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    frame= new JFrame("Fearless setup");
    frame.add(question, BorderLayout.NORTH);
    frame.add(new JScrollPane(log), BorderLayout.CENTER);
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    var screen= GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    frame.setSize(Math.min(1100, screen.width), Math.min(800, screen.height));
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
  }
  static void run(Path setup, Path repos, int choice) throws IOException, InterruptedException{
    Fetch.folder(Fetch.jdk, setup.resolve("jdk"), p -> {});
    Fetch.folder(Fetch.eclipse, setup.resolve("eclipse"), p -> Workspace.configureEclipse(p, setup));
    if (choice==0){ Repos.cloneAll(repos); }
    if (choice>=0){ Workspace.write(setup, repos); }
    log("Starting Eclipse");
    var eclipse= setup.resolve("eclipse");
    //Secure storage takes its file only from the command line, from the ECLIPSE_KEYRING variable, or from user.home, which every Eclipse shares.
    new ProcessBuilder(eclipse.resolve(Fetch.windows ? "eclipse.exe" : "eclipse").toString(), "-eclipse.keyring", eclipse.resolve("configuration", "secure_storage").toString())
      .directory(eclipse.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  }
  static <T> T edt(Supplier<T> s){
    var result= new CompletableFuture<T>();
    SwingUtilities.invokeLater(() -> result.complete(s.get()));
    return result.join();
  }
  /// Shows the html, the component below it and the buttons above the log, and waits for a button: the index of the pressed button.
  static int ask(String html, Supplier<JComponent> below, String... buttons){
    var answer= new CompletableFuture<Integer>();
    SwingUtilities.invokeLater(() -> show(panel(html, below.get(), buttons, answer)));
    var result= answer.join();
    SwingUtilities.invokeLater(Main::show);
    return result;
  }
  static JPanel panel(String html, JComponent below, String[] buttons, CompletableFuture<Integer> answer){
    var pane= new JEditorPane("text/html", "<html>"+html+"</html>");
    pane.setEditable(false);
    pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
    pane.setCaretPosition(0);
    pane.addHyperlinkListener(e -> { if (e.getEventType()==HyperlinkEvent.EventType.ACTIVATED){ browse(e.getURL().toString()); } });
    var scroll= new JScrollPane(pane){
      @Override public Dimension getPreferredSize(){
        pane.setSize(frame.getContentPane().getWidth()-24, Short.MAX_VALUE);
        var d= super.getPreferredSize();
        return new Dimension(d.width, Math.min(d.height, frame.getContentPane().getHeight()*2/3));
      }
    };
    var row= new JPanel();
    for (int i= 0; i<buttons.length; i += 1){
      var b= new JButton(buttons[i]);
      var index= i;
      b.addActionListener(e -> answer.complete(index));
      row.add(b);
    }
    var south= new JPanel(new BorderLayout());
    south.add(below, BorderLayout.CENTER);
    south.add(row, BorderLayout.SOUTH);
    var panel= new JPanel(new BorderLayout());
    panel.add(scroll, BorderLayout.CENTER);
    panel.add(south, BorderLayout.SOUTH);
    return panel;
  }
  static void show(JComponent... parts){
    question.removeAll();
    for (var p : parts){ question.add(p); }
    frame.validate();
    frame.repaint();
  }
  static String html(String text){ return Workspace.xml(text).replace("\n", "<br>"); }
  static String problem(String text){ return "<font color=\"red\">"+html(text)+"</font>"; }
  static void askUntil(String html, Supplier<JComponent> below, String button, Supplier<List<String>> check){
    var error= "";
    while (true){
      ask(error+html, below, button);
      var errors= check.get();
      if (errors.isEmpty()){ return; }
      errors.forEach(Main::log);
      error= problem(String.join("\n", errors))+"<br><br>";
    }
  }
  static Path askFolder(String text, Function<Path, List<String>> check){
    var field= edt(JTextField::new);
    askUntil(html(text), () -> folderRow(field), "Continue", () -> checkFolder(edt(field::getText).strip(), check));
    return Path.of(edt(field::getText).strip());
  }
  static JPanel folderRow(JTextField field){
    var browse= new JButton("Browse...");
    browse.addActionListener(e -> choose(field));
    var row= new JPanel(new BorderLayout());
    row.add(field, BorderLayout.CENTER);
    row.add(browse, BorderLayout.EAST);
    return row;
  }
  static void choose(JTextField field){
    var chooser= new JFileChooser(field.getText().isBlank() ? File.listRoots()[0] : new File(field.getText()));
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION){ field.setText(chooser.getSelectedFile().toString()); }
  }
  static List<String> checkFolder(String text, Function<Path, List<String>> check){
    try{
      var folder= Path.of(text);
      return folder.isAbsolute() ? check.apply(folder) : List.of("\""+text+"\" is not a full folder path: choose the folder with \"Browse...\".");
    }
    catch (InvalidPathException e){ return List.of("\""+text+"\" is not a valid folder path: "+e.getReason()+"."); }
  }
  static List<String> checkSetup(Path setup){
    if (Files.isRegularFile(setup)){ return List.of("\""+setup+"\" is a file, not a folder: choose a new folder."); }
    try (var s= Files.list(Files.createDirectories(setup))){
      var others= s.map(p -> p.getFileName().toString()).filter(n -> !List.of("jdk", "eclipse", "workspace", "repos").contains(n) && !n.startsWith("_")).sorted().map(n -> "\""+n+"\"").toList();
      return others.isEmpty() ? List.of() : List.of("The folder \""+setup+"\" holds "+String.join(", ", others)+", so it is not a setup folder: choose a new folder, or an empty one.");
    }
    catch (IOException e){ return List.of("The folder \""+setup+"\" can not be created or read ("+e+"): choose another folder."); }
  }
  static int askChoice(){
    var text= "Where do the Fearless repositories come from?\n\n"
      +"Official: the setup program downloads the official repositories.\n"
      +"You can build, run and change the code, but you can not push your changes to GitHub.\n\n"
      +"Existing folder: a folder that already holds the seven repositories, official or forks.\n\n"
      +"My forks: you fork the repositories on GitHub and clone your forks, guided step by step.\n"
      +"You can push your changes to your forks and open pull requests.";
    return ask(html(text), JPanel::new, "Official", "Existing folder", "My forks");
  }
  static Path askForks(Path repos){
    var forks= Repos.all.stream().map(r -> "<a href=\""+r.url()+"/fork\">"+r.url()+"/fork</a>").collect(Collectors.joining("<br>"));
    var clones= Repos.all.stream().map(r -> Fetch.windows
        ? "Repository <b>"+r.name()+"</b>, Local path <b>"+Workspace.xml(repos.resolve(r.name()))+"</b>"
        : "gh repo clone "+r.name())
      .collect(Collectors.joining("<br>"));
    var tools= Fetch.windows
      ? "3. Install GitHub Desktop from <a href=\"https://desktop.github.com\">https://desktop.github.com</a>, start it and sign in with your GitHub account.<br><br>"
        +"4. In GitHub Desktop, clone each fork with File, Clone repository, GitHub.com.<br>"
        +"Choose the fork and write its Local path exactly as below.<br>"
        +"When GitHub Desktop asks how you plan to use the fork, answer \"To contribute to the parent project\".<br>"
      : "3. Install git and the GitHub command line tool gh with the package manager of your system,<br>"
        +"for example with <b>sudo apt install git gh</b>.<br>"
        +"In a terminal, run <b>gh auth login</b> and answer its questions, then run <b>gh auth setup-git</b>.<br><br>"
        +"4. In a terminal, clone each fork with these commands:<br>"
        +"mkdir -p \""+Workspace.xml(repos)+"\"<br>cd \""+Workspace.xml(repos)+"\"<br>";
    var page= "1. Sign in on <a href=\"https://github.com\">https://github.com</a>; create an account first if you have none.<br><br>"
      +"2. Fork each repository: open each link below and press \"Create fork\", keeping the repository name.<br>"+forks+"<br><br>"
      +tools+clones+"<br><br>5. When all seven clones are done, press \"The clones are done\".";
    askUntil(page, JPanel::new, "The clones are done", () -> Repos.check(repos));
    return repos;
  }
  static void browse(String url){
    try{ Desktop.getDesktop().browse(URI.create(url)); }
    catch (IOException | UnsupportedOperationException e){ log("This system can not open the link "+url+": copy it into a web browser."); }
  }
  static void log(String line){
    SwingUtilities.invokeLater(() -> append(line));
  }
  static void append(String line){
    log.append(line+"\n");
    log.setCaretPosition(log.getDocument().getLength());
  }
  static String trace(Throwable e){
    var w= new StringWriter();
    e.printStackTrace(new PrintWriter(w));
    return w.toString();
  }
}
